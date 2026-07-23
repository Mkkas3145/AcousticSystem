package org.macaroon.acousticsystem.client.simulation;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects.TailFieldRequest;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.macaroon.acousticsystem.client.scene.AcousticScene;
import org.macaroon.acousticsystem.client.scene.AcousticSceneManager;
import org.macaroon.acousticsystem.mixin.client.GameRendererAccessor;
import org.macaroon.acousticsystem.mixin.client.ChannelAccessor;
import org.macaroon.acousticsystem.mixin.client.ChannelHandleAccessor;
import org.macaroon.acousticsystem.mixin.client.CameraAccessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class AcousticRuntime {
    private static final int WORKER_COUNT = Math.max(
            1,
            Math.min(12, (Runtime.getRuntime().availableProcessors() + 1) / 2)
    );
    private static final long ROOM_PROBE_RETENTION_NANOSECONDS = 2_000_000_000L;
    private static final long PREPARED_RESULT_RETENTION_NANOSECONDS = 2_000_000_000L;
    private static final long CACHE_MAINTENANCE_INTERVAL_NANOSECONDS = 1_000_000_000L;
    private static final double ROOM_PROBE_CELL_SCALE = 2.0;
    // Propagation and listener-field work have reserved capacity inside the same total
    // CPU budget. A long room/tail probe can no longer make a new sound wait behind it.
    private static final int ONSET_WORKER_COUNT = Math.max(1, WORKER_COUNT - 1);
    /*
     * Realtime propagation is latency-sensitive, not throughput-only work.  The old
     * fixed four-lane ceiling made the last voices wait in ceil(voices / 4) waves even
     * when the propagation pool had idle processors.  Use every propagation worker
     * except one instead; the reserved worker keeps onset/cache work runnable while
     * continuous movement is producing realtime requests.
     */
    private static final int REALTIME_BATCH_LANES = Math.max(
            1,
            ONSET_WORKER_COUNT - 1
    );
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger TAIL_WORKER_SEQUENCE = new AtomicInteger();
    private static final AtomicLong FIELD_TASK_SEQUENCE = new AtomicLong();
    private static final ForkJoinPool PROPAGATION_WORKERS = new ForkJoinPool(
            ONSET_WORKER_COUNT,
            pool -> {
                ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory
                        .newThread(pool);
                thread.setName("AcousticSystem-Propagation-" + WORKER_SEQUENCE.incrementAndGet());
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            },
            null,
            true
    );
    private static final ThreadPoolExecutor FIELD_WORKER = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(),
            task -> {
                Thread thread = new Thread(task, "AcousticSystem-ListenerField");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
    );
    private static final Executor ROOM_WORKER = command ->
            FIELD_WORKER.execute(new FieldTask(0, command));
    private static final int TAIL_WORKER_COUNT = Math.max(
            1,
            Math.min(4, Runtime.getRuntime().availableProcessors() / 8)
    );
    private static final ExecutorService TAIL_WORKERS = Executors.newFixedThreadPool(
            TAIL_WORKER_COUNT,
            task -> {
                Thread thread = new Thread(
                        task,
                        "AcousticSystem-TailField-"
                                + TAIL_WORKER_SEQUENCE.incrementAndGet()
                );
                thread.setDaemon(true);
                thread.setPriority(Math.max(
                        Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1
                ));
                return thread;
            }
    );

    private static CompletableFuture<BatchResult> pendingBatch;
    private static ClientLevel currentLevel;
    private static volatile PrePlayContext prePlayContext;
    private static volatile ProbeSnapshot latestProbe;
    private static volatile Vec3 latestObservedListener;
    private static boolean processingEnabled = true;
    private static final Map<ProbeCell, CachedRoomProbe> ROOM_PROBE_CACHE = new ConcurrentHashMap<>();
    private static final Map<SourceCell, CachedRoomProbe> SOURCE_ROOM_PROBE_CACHE = new ConcurrentHashMap<>();
    private static final Map<SourceCell, PreparedResult> PREPARED_RESULTS = new ConcurrentHashMap<>();
    // Source-cell caching avoids duplicate traces, but delivery belongs to a playback
    // occurrence. Keeping only the newest cell entry caused rapid repeated sounds to
    // invalidate every earlier completion before it reached its own channel.
    private static final Map<SoundInstance, PreparedResult> PREPARED_SOUNDS = new ConcurrentHashMap<>();
    private static final Set<Integer> DEFERRED_ONSET_SOURCES = ConcurrentHashMap.newKeySet();
    private static final Map<SoundInstance, RealtimeState> REALTIME_STATES = new ConcurrentHashMap<>();
    /*
     * Every voice owns one running calculation and one replaceable newest request.
     * Ready voices share the available latency lanes, but there is no all-voices
     * barrier: one completed trace reaches the sound thread immediately and that
     * voice's newest movement request returns at the tail of the ready queue.
     */
    private static final ConcurrentLinkedQueue<RealtimeState> REALTIME_READY =
            new ConcurrentLinkedQueue<>();
    private static final AtomicInteger REALTIME_DRAINERS = new AtomicInteger();
    private static final AtomicBoolean APPLICATION_DRAIN_QUEUED = new AtomicBoolean();
    private static final LatestPublication<PendingListenerApplication> PENDING_LISTENER_APPLICATION =
            new LatestPublication<>();
    private static volatile Executor soundExecutor;
    private static volatile long lastSlowListenerApplicationLogNanoseconds;
    private static volatile long lastSlowListenerProbeLogNanoseconds;
    // tick() is invoked serially by SoundEngine on the client thread. Reusing these
    // containers prevents high uncapped frame rates from turning stable audio into a
    // stream of short-lived lists and records which periodically trigger GC pauses.
    private static final ArrayList<SoundRequest> POSITIONAL_SOUND_SCRATCH = new ArrayList<>();
    private static final ArrayList<Vec3> SOURCE_POSITION_SCRATCH = new ArrayList<>();
    private static final Map<SoundInstance, SoundRequest> SOUND_REQUEST_CACHE = new IdentityHashMap<>();
    private static volatile long generation;
    private static long lastDebugLogNanoseconds;
    private static long lastCacheMaintenanceNanoseconds;
    private static BatchKey lastSubmittedBatchKey;

    private AcousticRuntime() {
    }

    public static int workerCount() {
        return WORKER_COUNT;
    }

    static int onsetWorkerCount() {
        return ONSET_WORKER_COUNT;
    }

    static int realtimeBatchLaneCount() {
        return REALTIME_BATCH_LANES;
    }

    public static void tick(
            ClientLevel level,
            Vec3 listener,
            Map<SoundInstance, ChannelAccess.ChannelHandle> activeSounds,
            ChannelAccess channelAccess
    ) {
        boolean enabled = AcousticQualityConfig.settings().enabled();
        boolean configurationToggled = enabled != processingEnabled;
        if (configurationToggled) {
            configurationChanged();
            processingEnabled = enabled;
        }
        if (currentLevel != level) {
            switchLevel(level);
        }
        latestObservedListener = listener;
        if (!enabled) {
            if (configurationToggled) {
                channelAccess.executeOnChannels(ignored ->
                        OpenALAcousticEffects.useVanillaProcessing()
                );
            }
            return;
        }
        POSITIONAL_SOUND_SCRATCH.clear();
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : activeSounds.entrySet()) {
            SoundInstance sound = entry.getKey();
            if (!sound.isRelative()) {
                ChannelAccess.ChannelHandle handle = entry.getValue();
                double sourceX = sound.getX();
                double sourceY = sound.getY();
                double sourceZ = sound.getZ();
                SoundRequest request = SOUND_REQUEST_CACHE.get(sound);
                if (request == null
                        || request.handle() != handle
                        || !request.sourceMatches(sourceX, sourceY, sourceZ)) {
                    request = new SoundRequest(
                            sound,
                            handle,
                            new Vec3(sourceX, sourceY, sourceZ),
                            AcousticTracer.TraceQuality.FULL
                    );
                    SOUND_REQUEST_CACHE.put(sound, request);
                }
                POSITIONAL_SOUND_SCRATCH.add(request);
            }
        }

        publishCompletedBatch(level, listener);
        long now = System.nanoTime();
        List<TailFieldRequest> tailFields = OpenALAcousticEffects.tailFieldRequests();
        SOURCE_POSITION_SCRATCH.clear();
        SOURCE_POSITION_SCRATCH.ensureCapacity(
                POSITIONAL_SOUND_SCRATCH.size() + tailFields.size()
        );
        for (SoundRequest sound : POSITIONAL_SOUND_SCRATCH) {
            SOURCE_POSITION_SCRATCH.add(sound.source());
        }
        for (TailFieldRequest tailField : tailFields) {
            SOURCE_POSITION_SCRATCH.add(tailField.position());
        }
        AcousticScene scene = AcousticSceneManager.requestCapture(
                level, listener, SOURCE_POSITION_SCRATCH
        );
        if (scene == null) {
            maintainCaches(now, activeSounds);
            return;
        }
        PrePlayContext context = prePlayContext;
        if (context == null
                || context.level() != level
                || context.scene() != scene
                || context.generation() != generation
                || !context.listener().equals(listener)) {
            prePlayContext = new PrePlayContext(level, scene, listener, generation);
        }
        submitRealtimeUpdates(
                scene, listener, POSITIONAL_SOUND_SCRATCH, generation, now
        );
        maintainCaches(now, activeSounds);
        if (pendingBatch != null) {
            return;
        }

        if (lastSubmittedBatchKey != null
                && lastSubmittedBatchKey.matches(
                scene, listener, tailFields, generation
        )) {
            return;
        }
        BatchKey batchKey = BatchKey.from(scene, listener, tailFields, generation);
        lastSubmittedBatchKey = batchKey;
        submitBatch(
                level, scene, listener, tailFields,
                generation, channelAccess, now
        );
    }

    /** Called by the coalesced vanilla listener task on Minecraft's sound thread. */
    public static void applyListenerPosition(Vec3 listener) {
        OpenALAcousticEffects.updateListenerPosition(listener);
    }

    /** Installs Minecraft's OpenAL-owning executor for latest-state delivery. */
    public static void attachSoundExecutor(Executor executor) {
        soundExecutor = executor;
    }

    /** Releases a drain task that Minecraft discarded while restarting audio. */
    public static void resetSoundThreadTransport() {
        APPLICATION_DRAIN_QUEUED.set(false);
    }

    /**
     * Applies every voice's newest completed snapshot directly on Minecraft's sound
     * thread. One global drain replaces the extra FIFO callback previously queued for
     * every voice and prevents continuous movement from building delivery latency.
     */
    public static void drainCompletedApplicationsOnSoundThread() {
        APPLICATION_DRAIN_QUEUED.set(false);
        PendingListenerApplication listenerApplication =
                PENDING_LISTENER_APPLICATION.take();
        if (listenerApplication != null
                && listenerApplication.generation() == generation) {
            long appliedNanoseconds = System.nanoTime();
            boolean accepted = OpenALAcousticEffects.applyListenerRoomSequenced(
                    listenerApplication.probe(),
                    listenerApplication.listener(),
                    listenerApplication.sequence(),
                    listenerApplication.sceneRevision()
            );
            reportSlowListenerApplication(
                    listenerApplication, appliedNanoseconds, accepted
            );
        }
        boolean remaining = false;
        for (RealtimeState state : REALTIME_STATES.values()) {
            PendingApplication application = state.takeApplication();
            if (application == null) {
                continue;
            }
            ChannelAccess.ChannelHandle handle = application.handle();
            if (application.generation() == generation && !handle.isStopped()) {
                Channel channel = ((ChannelHandleAccessor) (Object) handle)
                        .acousticsystem$getChannel();
                if (channel != null) {
                    apply(
                            channel, application.result(), application.sequence()
                    );
                }
            }
            remaining |= state.hasPendingApplication();
        }
        if (remaining || PENDING_LISTENER_APPLICATION.hasPending()) {
            requestApplicationDrain();
        }
    }

    private static void reportSlowListenerApplication(
            PendingListenerApplication application,
            long appliedNanoseconds,
            boolean accepted
    ) {
        long totalNanoseconds = appliedNanoseconds - application.sequence();
        if (totalNanoseconds < 25_000_000L
                || appliedNanoseconds - lastSlowListenerApplicationLogNanoseconds
                < 1_000_000_000L) {
            return;
        }
        lastSlowListenerApplicationLogNanoseconds = appliedNanoseconds;
        AcousticSystem.LOGGER.warn(
                "Listener acoustic field reached DSP late: calculation={} ms, "
                        + "sound-thread delivery={} ms, accepted={}, total={} ms, "
                        + "listener-drift={} blocks",
                (application.completedNanoseconds() - application.sequence()) / 1_000_000.0,
                (appliedNanoseconds - application.completedNanoseconds()) / 1_000_000.0,
                accepted,
                totalNanoseconds / 1_000_000.0,
                latestObservedListener == null
                        ? 0.0
                        : latestObservedListener.distanceTo(application.listener())
        );
    }

    private static void requestApplicationDrain() {
        Executor executor = soundExecutor;
        if (executor == null || !APPLICATION_DRAIN_QUEUED.compareAndSet(false, true)) {
            return;
        }
        executor.execute(AcousticRuntime::drainCompletedApplicationsOnSoundThread);
    }

    private static void maintainCaches(
            long now,
            Map<SoundInstance, ChannelAccess.ChannelHandle> activeSounds
    ) {
        if (now - lastCacheMaintenanceNanoseconds
                < CACHE_MAINTENANCE_INTERVAL_NANOSECONDS) {
            return;
        }
        lastCacheMaintenanceNanoseconds = now;
        long maintenanceGeneration = generation;
        PROPAGATION_WORKERS.execute(() -> {
            PREPARED_RESULTS.entrySet().removeIf(entry -> {
                PreparedResult prepared = entry.getValue();
                boolean wrongGeneration = prepared.generation() != maintenanceGeneration;
                boolean completedAndExpired = prepared.computation().isDone()
                        && now - prepared.computedNanoseconds()
                        > PREPARED_RESULT_RETENTION_NANOSECONDS;
                if (wrongGeneration) {
                    prepared.computation().cancel(false);
                }
                return wrongGeneration || completedAndExpired;
            });
            PREPARED_SOUNDS.entrySet().removeIf(entry -> {
                PreparedResult prepared = entry.getValue();
                return prepared.generation() != maintenanceGeneration
                        || (prepared.computation().isDone()
                        && now - prepared.computedNanoseconds()
                        > PREPARED_RESULT_RETENTION_NANOSECONDS);
            });
        });
        REALTIME_STATES.entrySet().removeIf(entry ->
                !activeSounds.containsKey(entry.getKey()) && entry.getValue().idle()
        );
        SOUND_REQUEST_CACHE.keySet().removeIf(sound -> !activeSounds.containsKey(sound));
    }

    /** Called on the client thread before Minecraft allocates the OpenAL channel. */
    public static void prepareSound(SoundInstance sound) {
        if (!AcousticQualityConfig.settings().enabled()) {
            return;
        }
        PrePlayContext context = prePlayContext;
        if (sound.isRelative()) {
            return;
        }
        if (context == null || currentLevel != context.level()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!minecraft.isSameThread()
                    || minecraft.level == null) {
                return;
            }
            var mainCamera = ((GameRendererAccessor) minecraft.gameRenderer)
                    .acousticsystem$mainCamera();
            if (!mainCamera.isInitialized()) {
                return;
            }
            if (currentLevel != minecraft.level) {
                switchLevel(minecraft.level);
            }
            Vec3 listener = ((CameraAccessor) (Object) mainCamera).acousticsystem$getPosition();
            Vec3 source = new Vec3(sound.getX(), sound.getY(), sound.getZ());
            AcousticScene initialScene = AcousticSceneManager.requestCapture(
                    minecraft.level,
                    listener,
                    List.of(source)
            );
            if (initialScene == null) {
                return;
            }
            context = new PrePlayContext(minecraft.level, initialScene, listener, generation);
            prePlayContext = context;
        }
        Vec3 source = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        AcousticScene scene = AcousticSceneManager.requestCapture(
                context.level(),
                context.listener(),
                List.of(source)
        );
        if (scene == null) {
            return;
        }
        long now = System.nanoTime();
        PreparedResult existing = PREPARED_RESULTS.get(SourceCell.from(source));
        if (existing != null
                && existing.generation() == context.generation()
                && existing.sceneRevision() == scene.revision()
                && isListenerResultCurrent(existing.listener(), context.listener())
                && !existing.computation().isCancelled()
                && !existing.computation().isCompletedExceptionally()
                && (!existing.computation().isDone()
                || now - existing.computedNanoseconds() <= PREPARED_RESULT_RETENTION_NANOSECONDS)) {
            PREPARED_SOUNDS.put(sound, existing);
            return;
        }
        RoomProbe roomProbe = prePlayRoomProbe(scene, context.listener(), context.generation(), now);
        PrePlayContext preparedContext = context;
        // The former first-frame predictor traced eight incident rays plus a structural
        // path synchronously from SoundEngine.play on the render thread. Leave the
        // initial source untouched until the same full worker result arrives instead of
        // trading a few milliseconds of onset colour for a visible frame-time spike.
        AcousticResult immediate = null;
        CompletableFuture<AcousticResult> computation = submitNonBlockingTrace(
                () -> {
                    RoomProbe sourceRoomProbe = cachedSourceRoomProbe(
                            scene, source, preparedContext.generation(), now
                    );
                    return AcousticTracer.traceNonBlocking(
                            scene,
                            source,
                            preparedContext.listener(),
                            sourceRoomProbe,
                            roomProbe,
                            AcousticTracer.TraceQuality.FULL
                    );
                }
        );
        PreparedResult prepared = new PreparedResult(
                        context.generation(),
                        scene.revision(),
                        context.listener(),
                        source,
                        now,
                        immediate,
                        computation
                );
        PREPARED_RESULTS.put(SourceCell.from(source), prepared);
        PREPARED_SOUNDS.put(sound, prepared);
        prePlayContext = new PrePlayContext(context.level(), scene, context.listener(), context.generation());
    }

    /** Returns false to keep Channel.play at sample zero until its trace completes. */
    public static boolean applyBeforePlay(int source, Vec3 sourcePosition, boolean relative) {
        if (!AcousticQualityConfig.settings().enabled()) {
            return true;
        }
        PrePlayContext context = prePlayContext;
        if (relative || context == null) {
            return true;
        }

        PreparedResult prepared = PREPARED_RESULTS.get(SourceCell.from(sourcePosition));
        if (prepared != null
                && prepared.generation() == context.generation()) {
            try {
                AcousticResult result = prepared.computation().getNow(prepared.immediate());
                if (result != null) {
                    OpenALAcousticEffects.prepareListenerRoomForOnset(result.reverbRoom());
                    OpenALAcousticEffects.applyBeforePlay(source, result);
                } else {
                    DEFERRED_ONSET_SOURCES.add(source);
                    return false;
                }
            } catch (CompletionException | CancellationException exception) {
                AcousticSystem.LOGGER.warn(
                        "Asynchronous first-frame acoustic calculation failed",
                        exception.getCause() == null ? exception : exception.getCause()
                );
            }
            return true;
        }

        // prepareSound normally populated the exact source cell. Never fall back to a
        // ray trace here: Channel.play can run on the sound/OpenAL thread, where world
        // traversal and allocation cause audible stalls and game-thread contention.
        return true;
    }

    public static void forgetDeferredSource(int source) {
        DEFERRED_ONSET_SOURCES.remove(source);
    }

    /**
     * Delivers an onset result that completed just after Channel.play without waiting
     * for the next whole-world propagation batch. Calculation remains entirely on the
     * onset workers; ChannelHandle marshals only the cheap OpenAL parameter update onto
     * Minecraft's sound thread.
     */
    public static void bindPreparedSound(
            SoundInstance sound,
            ChannelAccess.ChannelHandle handle
    ) {
        if (sound.isRelative() || handle == null || handle.isStopped()) {
            PREPARED_SOUNDS.remove(sound);
            return;
        }
        if (!AcousticQualityConfig.settings().enabled()) {
            PREPARED_SOUNDS.remove(sound);
            handle.execute(AcousticRuntime::resumeDeferredWithoutResult);
            return;
        }
        Vec3 source = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        PreparedResult prepared = PREPARED_SOUNDS.get(sound);
        if (prepared == null
                || prepared.generation() != generation
                || !isListenerResultCurrent(
                        prepared.listener(),
                        prePlayContext == null ? prepared.listener() : prePlayContext.listener()
                )) {
            PREPARED_SOUNDS.remove(sound, prepared);
            handle.execute(AcousticRuntime::resumeDeferredWithoutResult);
            return;
        }
        prepared.computation().thenAccept(result -> {
            if (handle.isStopped() || PREPARED_SOUNDS.get(sound) != prepared) {
                PREPARED_SOUNDS.remove(sound, prepared);
                return;
            }
            handle.execute(channel -> {
                if (PREPARED_SOUNDS.remove(sound, prepared)) {
                    resumeDeferredOrCorrect(channel, result, prepared.computedNanoseconds());
                }
            });
        }).exceptionally(exception -> {
            if (PREPARED_SOUNDS.remove(sound, prepared) && !handle.isStopped()) {
                handle.execute(AcousticRuntime::resumeDeferredWithoutResult);
            }
            AcousticSystem.LOGGER.warn(
                    "Asynchronous first-frame acoustic calculation failed; restoring vanilla source",
                    exception
            );
            return null;
        });
    }

    public static void shutdown() {
        if (pendingBatch != null) {
            pendingBatch.cancel(false);
            pendingBatch = null;
        }
        PROPAGATION_WORKERS.shutdownNow();
        FIELD_WORKER.shutdownNow();
        TAIL_WORKERS.shutdownNow();
        currentLevel = null;
        prePlayContext = null;
        latestProbe = null;
        latestObservedListener = null;
        ROOM_PROBE_CACHE.clear();
        SOURCE_ROOM_PROBE_CACHE.clear();
        cancelPreparedComputations();
        REALTIME_STATES.clear();
        REALTIME_READY.clear();
        PENDING_LISTENER_APPLICATION.clear();
        SOUND_REQUEST_CACHE.clear();
        POSITIONAL_SOUND_SCRATCH.clear();
        SOURCE_POSITION_SCRATCH.clear();
        lastSubmittedBatchKey = null;
        AcousticSceneManager.clear();
    }

    /** Drops results measured with a previous GUI quality configuration. */
    public static void configurationChanged() {
        processingEnabled = AcousticQualityConfig.settings().enabled();
        generation++;
        if (pendingBatch != null) {
            pendingBatch.cancel(false);
            pendingBatch = null;
        }
        prePlayContext = null;
        latestProbe = null;
        ROOM_PROBE_CACHE.clear();
        SOURCE_ROOM_PROBE_CACHE.clear();
        cancelPreparedComputations();
        REALTIME_STATES.clear();
        REALTIME_READY.clear();
        PENDING_LISTENER_APPLICATION.clear();
        SOUND_REQUEST_CACHE.clear();
        lastSubmittedBatchKey = null;
        AcousticSceneManager.clear();
    }

    private static void switchLevel(ClientLevel level) {
        generation++;
        if (pendingBatch != null) {
            pendingBatch.cancel(false);
            pendingBatch = null;
        }
        currentLevel = level;
        prePlayContext = null;
        latestProbe = null;
        latestObservedListener = null;
        ROOM_PROBE_CACHE.clear();
        SOURCE_ROOM_PROBE_CACHE.clear();
        cancelPreparedComputations();
        REALTIME_STATES.clear();
        REALTIME_READY.clear();
        PENDING_LISTENER_APPLICATION.clear();
        SOUND_REQUEST_CACHE.clear();
        lastSubmittedBatchKey = null;
        AcousticSceneManager.clear();
    }

    /**
     * Coalesced latest-state transport. Each voice owns at most one running calculation
     * and one replacement request, so high FPS cannot create an audio-work backlog.
     * It runs the same requested physical trace quality as the continuous batch; there
     * is no millisecond cutoff or fixed low-quality mode. A slower machine skips stale
     * intermediate positions but never skips the newest position.
     */
    private static void submitRealtimeUpdates(
            AcousticScene scene,
            Vec3 listener,
            List<SoundRequest> sounds,
            long expectedGeneration,
            long sequence
    ) {
        boolean scheduled = false;
        for (SoundRequest sound : sounds) {
            RealtimeState state = REALTIME_STATES.computeIfAbsent(
                    sound.sound(), ignored -> new RealtimeState()
            );
            if (state.offer(
                    scene, listener, sound, expectedGeneration, sequence
            )) {
                REALTIME_READY.offer(state);
                scheduled = true;
            }
        }
        if (scheduled) {
            startRealtimeDrainers();
        }
    }

    /**
     * Keeps one propagation worker in reserve and uses the rest for realtime drains.
     * Each drain publishes a completed voice before taking more work, so one expensive
     * source cannot hold back unrelated channels. A continuously moving source is
     * requeued at the tail and therefore cannot monopolize a lane.
     */
    private static void startRealtimeDrainers() {
        while (!REALTIME_READY.isEmpty()) {
            int running = REALTIME_DRAINERS.get();
            if (running >= REALTIME_BATCH_LANES
                    || !REALTIME_DRAINERS.compareAndSet(running, running + 1)) {
                if (running >= REALTIME_BATCH_LANES) {
                    return;
                }
                continue;
            }
            PROPAGATION_WORKERS.execute(AcousticRuntime::drainRealtimeReadyVoices);
        }
    }

    private static void drainRealtimeReadyVoices() {
        try {
            RealtimeState state;
            while ((state = REALTIME_READY.poll()) != null) {
                boolean parked = false;
                try {
                    RealtimeRequest request = state.take();
                    if (request != null) {
                        parked = processRealtimeRequest(state, request);
                    }
                } catch (RuntimeException exception) {
                    AcousticSystem.LOGGER.warn(
                            "Asynchronous real-time acoustic update failed",
                            exception
                    );
                } finally {
                    if (!parked && state.hasPending()) {
                        REALTIME_READY.offer(state);
                    }
                }
            }
        } finally {
            REALTIME_DRAINERS.decrementAndGet();
            if (!REALTIME_READY.isEmpty()) {
                startRealtimeDrainers();
            }
        }
    }

    /**
     * @return true when this voice was parked on a shared listener-field completion.
     */
    private static boolean processRealtimeRequest(
            RealtimeState state,
            RealtimeRequest request
    ) {
        long workerStartedNanoseconds = System.nanoTime();
        SoundRequest source = request.sound();
        ChannelAccess.ChannelHandle handle = source.handle();
        if (handle == null || handle.isStopped()
                || request.generation() != generation) {
            return false;
        }
        RoomProbe sourceProbe = cachedSourceRoomProbe(
                request.scene(), source.source(), request.generation(),
                request.sequence()
        );
        long probeFinishedNanoseconds = System.nanoTime();
        AcousticResult result;
        try {
            result = AcousticTracer.traceNonBlocking(
                    request.scene(), source.source(), request.listener(),
                    sourceProbe, request.listenerProbe(), source.quality()
            );
        } catch (AcousticTracer.AirFieldPendingException pending) {
            if (!state.park(request)) {
                return false;
            }
            pending.readiness().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    AcousticSystem.LOGGER.warn(
                            "Shared listener air field failed",
                            failure
                    );
                }
                if (state.resume()) {
                    REALTIME_READY.offer(state);
                    startRealtimeDrainers();
                }
            });
            return true;
        }
        if (request.generation() != generation || handle.isStopped()) {
            return false;
        }
        PendingApplication application = new PendingApplication(
                handle, result, request.generation(), request.sequence(),
                request.listener(), source.source(), workerStartedNanoseconds,
                probeFinishedNanoseconds,
                AcousticTracer.lastTraceTiming(),
                System.nanoTime()
        );
        state.offerApplication(application);
        requestApplicationDrain();
        return false;
    }

    /**
     * Runs a full trace without tying up a carrier worker when another source owns the
     * same cold listener-field build. Completion resubmits the exact trace operation;
     * no reduced-quality result or timeout is substituted.
     */
    private static CompletableFuture<AcousticResult> submitNonBlockingTrace(
            Supplier<AcousticResult> trace
    ) {
        CompletableFuture<AcousticResult> result = new CompletableFuture<>();
        submitNonBlockingTraceAttempt(trace, result);
        return result;
    }

    private static void submitNonBlockingTraceAttempt(
            Supplier<AcousticResult> trace,
            CompletableFuture<AcousticResult> result
    ) {
        if (result.isDone()) {
            return;
        }
        PROPAGATION_WORKERS.execute(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                result.complete(trace.get());
            } catch (AcousticTracer.AirFieldPendingException pending) {
                pending.readiness().whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        submitNonBlockingTraceAttempt(trace, result);
                    }
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
    }

    private static RoomProbe realtimeRoomProbe(
            AcousticScene scene,
            Vec3 listener,
            long expectedGeneration
    ) {
        CachedRoomProbe cached = ROOM_PROBE_CACHE.get(ProbeCell.from(listener));
        if (cached != null
                && cached.generation() == expectedGeneration
                && cached.sceneRevision() == scene.revision()) {
            return cached.probe();
        }
        ProbeSnapshot recent = latestProbe;
        if (recent != null && recent.generation() == expectedGeneration) {
            return recent.roomProbe();
        }
        return new RoomProbe(RoomAcoustics.OUTDOORS, List.of(), List.of());
    }

    private static void submitBatch(
            ClientLevel level,
            AcousticScene scene,
            Vec3 listener,
            List<TailFieldRequest> tailFields,
            long batchGeneration,
            ChannelAccess channelAccess,
            long submittedNanoseconds
    ) {
        // Retired reverb fields are independent of the current listener probe.  Start
        // them immediately on their own lanes so neither side waits behind the other
        // and a long tail trace can never occupy the listener-field worker.
        for (TailFieldRequest tailField : tailFields) {
            CompletableFuture.runAsync(() -> {
                RoomProbe tailProbe = cachedSourceRoomProbe(
                        scene,
                        tailField.position(),
                        batchGeneration,
                        submittedNanoseconds
                );
                deliverCompletedTailField(
                        channelAccess,
                        tailField,
                        tailProbe,
                        batchGeneration,
                        submittedNanoseconds
                );
            }, TAIL_WORKERS).exceptionally(exception -> {
                AcousticSystem.LOGGER.warn(
                        "Asynchronous acoustic tail probe failed",
                        exception.getCause() == null ? exception : exception.getCause()
                );
                return null;
            });
        }
        CompletableFuture<RoomProbe> roomFuture = CompletableFuture.supplyAsync(
                () -> {
                    ROOM_PROBE_CACHE.entrySet().removeIf(entry ->
                            entry.getValue().generation() != batchGeneration
                                    || submittedNanoseconds
                                    - entry.getValue().computedNanoseconds()
                                    > ROOM_PROBE_RETENTION_NANOSECONDS
                    );
                    SOURCE_ROOM_PROBE_CACHE.entrySet().removeIf(entry ->
                            entry.getValue().generation() != batchGeneration
                                    || entry.getValue().sceneRevision() != scene.revision()
                    );
                    return cachedRoomProbe(
                            scene, listener, batchGeneration, submittedNanoseconds
                    );
                },
                ROOM_WORKER
        );
        pendingBatch = roomFuture.thenApply(roomProbe -> {
            long listenerProbeCompletedNanoseconds = System.nanoTime();
            long listenerProbeElapsed = listenerProbeCompletedNanoseconds - submittedNanoseconds;
            if (listenerProbeElapsed >= 25_000_000L
                    && listenerProbeCompletedNanoseconds - lastSlowListenerProbeLogNanoseconds
                    >= 1_000_000_000L) {
                lastSlowListenerProbeLogNanoseconds = listenerProbeCompletedNanoseconds;
                AcousticSystem.LOGGER.warn(
                        "Listener acoustic field arrived late: probe={} ms",
                        listenerProbeElapsed / 1_000_000.0
                );
            }
            deliverCompletedListenerRoom(
                    roomProbe,
                    batchGeneration,
                    scene.revision(),
                    listener,
                    submittedNanoseconds
            );
            // Listener-space completion owns pendingBatch. Retired tails continue on
            // their independent pool and can never block the next listener probe.
            return new BatchResult(
                    level,
                    batchGeneration,
                    scene.revision(),
                    listener,
                    roomProbe,
                    submittedNanoseconds
            );
        });
    }

    private static void deliverCompletedTailField(
            ChannelAccess channelAccess,
            TailFieldRequest request,
            RoomProbe probe,
            long batchGeneration,
            long submittedNanoseconds
    ) {
        if (batchGeneration != generation) {
            return;
        }
        channelAccess.executeOnChannels(ignored ->
                OpenALAcousticEffects.updateTailField(
                        request.fieldToken(), probe, request.position()
                )
        );
    }

    private static void publishCompletedBatch(
            ClientLevel level,
            Vec3 currentListener
    ) {
        if (pendingBatch == null || !pendingBatch.isDone()) {
            return;
        }

        CompletableFuture<BatchResult> completed = pendingBatch;
        pendingBatch = null;
        try {
            BatchResult batch = completed.join();
            if (batch.level() != level
                    || batch.generation() != generation) {
                return;
            }

            latestProbe = new ProbeSnapshot(
                    batch.generation(), batch.sceneRevision(), batch.listener(), batch.roomProbe()
            );
            if (Boolean.getBoolean("acousticsystem.debug")
                    && System.nanoTime() - lastDebugLogNanoseconds >= 1_000_000_000L) {
                RoomAcoustics room = batch.roomProbe().acoustics();
                AcousticSystem.LOGGER.info(
                        "Acoustic room: density={}, gain={}, decay={}s, reflections={}, late={}, surfaces={}, openings={}",
                        room.density(), room.gain(), room.decayTime(), room.reflectionsGain(),
                        room.lateReverbGain(), batch.roomProbe().surfaces().size(),
                        batch.roomProbe().openings().size()
                );
                lastDebugLogNanoseconds = System.nanoTime();
            }
            // Per-source propagation is owned by the coalesced real-time path. This
            // batch only commits the listener field and retired tails, avoiding a
            // second full trace of every active sound.
        } catch (CompletionException exception) {
            lastSubmittedBatchKey = null;
            AcousticSystem.LOGGER.warn("Asynchronous acoustic batch failed; retrying on the next client update", exception.getCause());
        }
    }

    private static void deliverCompletedListenerRoom(
            RoomProbe roomProbe,
            long batchGeneration,
            long sceneRevision,
            Vec3 computedListener,
            long sequence
    ) {
        if (batchGeneration != generation) {
            return;
        }
        Vec3 observedListener = latestObservedListener;
        if (observedListener == null) {
            return;
        }
        // Listener fields used to enqueue one independent executeOnChannels task for
        // every completed probe. Under continuous movement that FIFO could preserve
        // seconds of obsolete room states even though every individual calculation was
        // fast. Publish only the newest complete measurement into the same coalesced
        // sound-thread drain used by voices. This changes transport, not the physical
        // probe or DSP response.
        PENDING_LISTENER_APPLICATION.publish(
                sequence,
                new PendingListenerApplication(
                        roomProbe,
                        computedListener,
                        batchGeneration,
                        sceneRevision,
                        sequence,
                        System.nanoTime()
                )
        );
        requestApplicationDrain();
    }

    private static boolean apply(Channel channel, AcousticResult result, long sequence) {
        int source = ((ChannelAccessor) channel).acousticsystem$getSource();
        return OpenALAcousticEffects.applySequenced(source, result, sequence);
    }

    private static void applyOnsetCorrection(
            Channel channel,
            AcousticResult result,
            long sequence
    ) {
        int source = ((ChannelAccessor) channel).acousticsystem$getSource();
        OpenALAcousticEffects.applyOnsetCorrection(source, result, sequence);
    }

    private static void resumeDeferredOrCorrect(
            Channel channel,
            AcousticResult result,
            long sequence
    ) {
        int source = ((ChannelAccessor) channel).acousticsystem$getSource();
        if (DEFERRED_ONSET_SOURCES.remove(source)) {
            channel.play();
        } else {
            OpenALAcousticEffects.applyOnsetCorrection(source, result, sequence);
        }
    }

    private static void resumeDeferredWithoutResult(Channel channel) {
        int source = ((ChannelAccessor) channel).acousticsystem$getSource();
        if (DEFERRED_ONSET_SOURCES.remove(source)) {
            channel.play();
        }
    }

    private static RoomProbe cachedRoomProbe(
            AcousticScene scene,
            Vec3 position,
            long expectedGeneration,
            long now
    ) {
        ProbeCell cell = ProbeCell.from(position);
        long probeStartedNanoseconds = System.nanoTime();
        RoomProbe probe = AcousticTracer.probeRoom(scene, position);
        if (Boolean.getBoolean("acousticsystem.debug")) {
            AcousticSystem.LOGGER.info(
                    "Acoustic room probe completed in {} ms",
                    (System.nanoTime() - probeStartedNanoseconds) / 1_000_000.0
            );
        }
        ROOM_PROBE_CACHE.put(cell, new CachedRoomProbe(expectedGeneration, scene.revision(), now, probe));
        return probe;
    }

    private static RoomProbe cachedSourceRoomProbe(
            AcousticScene scene,
            Vec3 position,
            long expectedGeneration,
            long now
    ) {
        SourceCell cell = SourceCell.from(position);
        if (SOURCE_ROOM_PROBE_CACHE.size() >= 512) {
            SOURCE_ROOM_PROBE_CACHE.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().computedNanoseconds()))
                    .ifPresent(entry -> SOURCE_ROOM_PROBE_CACHE.remove(entry.getKey(), entry.getValue()));
        }
        // compute() is the exact single-flight boundary for one source cell. Sound
        // bursts often create several playback occurrences at the same coordinates;
        // a get/trace/put sequence allowed every propagation worker to perform the
        // same ray probe concurrently before the first result was published.
        CachedRoomProbe resolved = SOURCE_ROOM_PROBE_CACHE.compute(cell, (ignored, cached) -> {
            if (cached != null
                    && cached.generation() == expectedGeneration
                    && cached.sceneRevision() == scene.revision()) {
                return cached;
            }
            return new CachedRoomProbe(
                    expectedGeneration,
                    scene.revision(),
                    now,
                    AcousticTracer.probeSourceRoom(scene, position)
            );
        });
        return resolved.probe();
    }

    private static RoomProbe prePlayRoomProbe(
            AcousticScene scene,
            Vec3 position,
            long expectedGeneration,
            long now
    ) {
        CachedRoomProbe cached = ROOM_PROBE_CACHE.get(ProbeCell.from(position));
        ProbeCell cell = ProbeCell.from(position);
        if (cached != null
                && cached.generation() == expectedGeneration
                && cached.sceneRevision() == scene.revision()) {
            return cached.probe();
        }
        ProbeSnapshot recent = latestProbe;
        if (recent != null && canReuseRoomForOnset(
                recent.generation(), expectedGeneration
        )) {
            // A block edit changes the scene revision immediately, often in the same
            // tick as a very short break/place sound. Reusing the continuously updated
            // listener field is the asynchronous predictor; the new-scene worker result
            // corrects it later. Returning OUTDOORS here made transients end before they
            // ever reached the acoustic pipeline.
            return recent.roomProbe();
        }
        // Only the first few ticks after entering a world can reach this fallback. It is
        // deliberately cheap; the worker publishes the precise probe without blocking
        // sound creation on the client/render thread.
        return new RoomProbe(RoomAcoustics.OUTDOORS, List.of(), List.of());
    }

    static boolean canReuseRoomForOnset(
            long probeGeneration,
            long expectedGeneration
    ) {
        // This is only a non-blocking first-frame predictor. A probe-cell crossing must
        // not replace a known indoor field with OUTDOORS and commit silence to the
        // listener-shared bus. The precise current-position batch still uses the strict
        // cell/revision checks and corrects this prediction asynchronously.
        return probeGeneration == expectedGeneration;
    }

    static boolean isListenerResultCurrent(Vec3 computedListener, Vec3 currentListener) {
        return ProbeCell.from(computedListener).equals(ProbeCell.from(currentListener));
    }

    private static void cancelPreparedComputations() {
        for (PreparedResult prepared : PREPARED_RESULTS.values()) {
            prepared.computation().cancel(false);
        }
        for (PreparedResult prepared : PREPARED_SOUNDS.values()) {
            prepared.computation().cancel(false);
        }
        PREPARED_RESULTS.clear();
        PREPARED_SOUNDS.clear();
        DEFERRED_ONSET_SOURCES.clear();
    }

    private record SoundRequest(
            SoundInstance sound,
            ChannelAccess.ChannelHandle handle,
            Vec3 source,
            AcousticTracer.TraceQuality quality
    ) {
        private boolean sourceMatches(double x, double y, double z) {
            return Double.doubleToLongBits(source.x) == Double.doubleToLongBits(x)
                    && Double.doubleToLongBits(source.y) == Double.doubleToLongBits(y)
                    && Double.doubleToLongBits(source.z) == Double.doubleToLongBits(z);
        }
    }

    private record BatchResult(
            ClientLevel level,
            long generation,
            long sceneRevision,
            Vec3 listener,
            RoomProbe roomProbe,
            long submittedNanoseconds
    ) {
    }

    private record PrePlayContext(ClientLevel level, AcousticScene scene, Vec3 listener, long generation) {
    }

    private record ProbeSnapshot(long generation, long sceneRevision, Vec3 listener, RoomProbe roomProbe) {
    }

    private record CachedRoomProbe(long generation, long sceneRevision, long computedNanoseconds, RoomProbe probe) {
    }

    private record PreparedResult(
            long generation,
            long sceneRevision,
            Vec3 listener,
            Vec3 source,
            long computedNanoseconds,
            AcousticResult immediate,
            CompletableFuture<AcousticResult> computation
    ) {
    }

    private record ProbeCell(int x, int y, int z) {
        private static ProbeCell from(Vec3 position) {
            return new ProbeCell(
                    (int) Math.floor(position.x * ROOM_PROBE_CELL_SCALE),
                    (int) Math.floor(position.y * ROOM_PROBE_CELL_SCALE),
                    (int) Math.floor(position.z * ROOM_PROBE_CELL_SCALE)
            );
        }
    }

    private record SourceCell(long x, long y, long z) {
        private static final double SCALE = 64.0;

        private static SourceCell from(Vec3 position) {
            return new SourceCell(
                    Math.round(position.x * SCALE),
                    Math.round(position.y * SCALE),
                    Math.round(position.z * SCALE)
            );
        }
    }

    private record RealtimeKey(
            long sceneRevision,
            long listenerX,
            long listenerY,
            long listenerZ,
            long sourceX,
            long sourceY,
            long sourceZ,
            RoomProbe listenerProbe
    ) {
        private static RealtimeKey from(
                AcousticScene scene,
                Vec3 listener,
                Vec3 source,
                RoomProbe listenerProbe
        ) {
            return new RealtimeKey(
                    scene.revision(),
                    Double.doubleToLongBits(listener.x),
                    Double.doubleToLongBits(listener.y),
                    Double.doubleToLongBits(listener.z),
                    Double.doubleToLongBits(source.x),
                    Double.doubleToLongBits(source.y),
                    Double.doubleToLongBits(source.z),
                    listenerProbe
            );
        }

        private boolean matches(
                AcousticScene scene,
                Vec3 listener,
                Vec3 source,
                RoomProbe currentListenerProbe
        ) {
            return sceneRevision == scene.revision()
                    && listenerX == Double.doubleToLongBits(listener.x)
                    && listenerY == Double.doubleToLongBits(listener.y)
                    && listenerZ == Double.doubleToLongBits(listener.z)
                    && sourceX == Double.doubleToLongBits(source.x)
                    && sourceY == Double.doubleToLongBits(source.y)
                    && sourceZ == Double.doubleToLongBits(source.z)
                    && listenerProbe == currentListenerProbe;
        }
    }

    static record BatchKey(
            long generation,
            long sceneRevision,
            long listenerX,
            long listenerY,
            long listenerZ,
            List<Long> tailFieldTokens
    ) {
        static BatchKey from(
                AcousticScene scene,
                Vec3 listener,
                List<TailFieldRequest> tailFields,
                long generation
        ) {
            List<Long> tokens = new ArrayList<>(tailFields.size());
            for (TailFieldRequest tailField : tailFields) {
                tokens.add(tailField.fieldToken());
            }
            tokens.sort(Long::compareTo);
            return new BatchKey(
                    generation,
                    scene.revision(),
                    Double.doubleToLongBits(listener.x),
                    Double.doubleToLongBits(listener.y),
                    Double.doubleToLongBits(listener.z),
                    List.copyOf(tokens)
            );
        }

        boolean matches(
                AcousticScene scene,
                Vec3 listener,
                List<TailFieldRequest> tailFields,
                long expectedGeneration
        ) {
            if (generation != expectedGeneration
                    || sceneRevision != scene.revision()
                    || !matchesListener(listener)
                    || tailFieldTokens.size() != tailFields.size()) {
                return false;
            }
            for (TailFieldRequest tailField : tailFields) {
                if (!tailFieldTokens.contains(tailField.fieldToken())) {
                    return false;
                }
            }
            return true;
        }

        boolean matchesListener(Vec3 listener) {
            return listenerX == Double.doubleToLongBits(listener.x)
                    && listenerY == Double.doubleToLongBits(listener.y)
                    && listenerZ == Double.doubleToLongBits(listener.z);
        }
    }

    private record RealtimeRequest(
            RealtimeKey key,
            AcousticScene scene,
            Vec3 listener,
            SoundRequest sound,
            RoomProbe listenerProbe,
            long generation,
            long sequence
    ) {
    }

    private record PendingApplication(
            ChannelAccess.ChannelHandle handle,
            AcousticResult result,
            long generation,
            long sequence,
            Vec3 listener,
            Vec3 source,
            long workerStartedNanoseconds,
            long probeFinishedNanoseconds,
            AcousticTracer.TraceTiming traceTiming,
            long completedNanoseconds
    ) {
    }

    private record PendingListenerApplication(
            RoomProbe probe,
            Vec3 listener,
            long generation,
            long sceneRevision,
            long sequence,
            long completedNanoseconds
    ) {
    }

    private static final class RealtimeState {
        private RealtimeKey mostRecentKey;
        private final LatestComputationQueue<RealtimeRequest, PendingApplication> queue =
                new LatestComputationQueue<>();

        private synchronized boolean offer(
                AcousticScene scene,
                Vec3 listener,
                SoundRequest sound,
                long expectedGeneration,
                long sequence
        ) {
            RoomProbe listenerProbe = realtimeRoomProbe(
                    scene, listener, expectedGeneration
            );
            if (mostRecentKey != null
                    && mostRecentKey.matches(
                    scene, listener, sound.source(), listenerProbe
            )) {
                return false;
            }
            RealtimeKey key = RealtimeKey.from(
                    scene, listener, sound.source(), listenerProbe
            );
            mostRecentKey = key;
            RealtimeRequest request = new RealtimeRequest(
                    key, scene, listener, sound, listenerProbe,
                    expectedGeneration, sequence
            );
            queue.offer(request);
            return true;
        }

        private RealtimeRequest take() {
            return queue.take();
        }

        private boolean idle() {
            return queue.idle();
        }

        private void offerApplication(PendingApplication application) {
            queue.publish(application);
        }

        private PendingApplication takeApplication() {
            return queue.takePublished();
        }

        private boolean hasPendingApplication() {
            return queue.hasPublished();
        }

        private boolean hasPending() {
            return queue.continueOrRelease();
        }

        private boolean park(RealtimeRequest request) {
            return queue.park(request);
        }

        private boolean resume() {
            return queue.resume();
        }
    }

    /**
     * One running calculation, one replaceable newest request, and one independently
     * deliverable completion. Publishing a completion never removes or waits for the
     * newest moving-listener request.
     */
    static final class LatestComputationQueue<T, A> {
        private T pending;
        private boolean running;
        private boolean parked;
        private A published;

        synchronized boolean offer(T request) {
            pending = request;
            if (parked) {
                parked = false;
                return true;
            }
            if (running) {
                return false;
            }
            running = true;
            return true;
        }

        synchronized T take() {
            T next = pending;
            pending = null;
            if (next == null) {
                running = false;
            }
            return next;
        }

        synchronized boolean park(T request) {
            // A newer request arrived while the attempted trace was running. It may
            // target another listener field, so process it immediately instead of
            // parking behind the obsolete field.
            if (pending != null) {
                return false;
            }
            pending = request;
            parked = true;
            return true;
        }

        synchronized boolean resume() {
            if (!parked) {
                return false;
            }
            parked = false;
            if (pending != null) {
                return true;
            }
            running = false;
            return false;
        }

        synchronized void publish(A application) {
            published = application;
        }

        synchronized A takePublished() {
            A latest = published;
            published = null;
            return latest;
        }

        synchronized boolean hasPublished() {
            return published != null;
        }

        synchronized boolean continueOrRelease() {
            if (parked) {
                return false;
            }
            if (pending != null) {
                return true;
            }
            running = false;
            return false;
        }

        synchronized boolean idle() {
            return !running && !parked && pending == null;
        }
    }

    /** Lock-free newest-only publication for results produced by a serial or parallel worker. */
    static final class LatestPublication<T> {
        private final AtomicReference<Stamped<T>> latest = new AtomicReference<>();

        void publish(long sequence, T value) {
            Stamped<T> candidate = new Stamped<>(sequence, value);
            latest.accumulateAndGet(candidate, (current, next) ->
                    current == null || next.sequence() >= current.sequence()
                            ? next
                            : current
            );
        }

        T take() {
            Stamped<T> stamped = latest.getAndSet(null);
            return stamped == null ? null : stamped.value();
        }

        boolean hasPending() {
            return latest.get() != null;
        }

        void clear() {
            latest.set(null);
        }

        private record Stamped<T>(long sequence, T value) {
        }
    }

    private static final class FieldTask implements Runnable, Comparable<FieldTask> {
        private final int priority;
        private final long sequence = FIELD_TASK_SEQUENCE.getAndIncrement();
        private final Runnable command;

        private FieldTask(int priority, Runnable command) {
            this.priority = priority;
            this.command = command;
        }

        @Override
        public void run() {
            command.run();
        }

        @Override
        public int compareTo(FieldTask other) {
            int priorityOrder = Integer.compare(priority, other.priority);
            return priorityOrder != 0
                    ? priorityOrder
                    : Long.compare(sequence, other.sequence);
        }
    }
}
