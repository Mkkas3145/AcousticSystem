package org.macaroon.acousticsystem.client.simulation;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
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
import org.macaroon.acousticsystem.mixin.client.EntityBoundSoundInstanceAccessor;

import java.util.ArrayList;
import java.util.ArrayDeque;
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

    /*
     * The listener field follows the same newest-only discipline as diffraction:
     * one worker may finish its current trace, but movement replaces the queued
     * request instead of making the client thread wait for a "batch" to clear.
     */
    private static final LatestComputationQueue<ListenerFieldRequest, Void>
            LISTENER_FIELD_QUEUE = new LatestComputationQueue<>();
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
    private static volatile long lastFluidEmitterDiagnosticNanoseconds;
    // tick() is invoked serially by SoundEngine on the client thread. Reusing these
    // containers prevents high uncapped frame rates from turning stable audio into a
    // stream of short-lived lists and records which periodically trigger GC pauses.
    private static final ArrayList<SoundRequest> POSITIONAL_SOUND_SCRATCH = new ArrayList<>();
    private static final ArrayList<Vec3> SOURCE_POSITION_SCRATCH = new ArrayList<>();
    private static final Map<SoundInstance, SoundRequest> SOUND_REQUEST_CACHE = new IdentityHashMap<>();
    private static final Map<SoundInstance, AABB> SOUND_EMITTER_BOUNDS = new IdentityHashMap<>();
    private static final ThreadLocal<ArrayDeque<AABB>> ENTITY_SOUND_CONTEXT =
            ThreadLocal.withInitial(ArrayDeque::new);
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
                Vec3 sourcePosition = sourcePosition(sound);
                AABB emitterBounds = emitterBounds(sound);
                double sourceX = sourcePosition.x;
                double sourceY = sourcePosition.y;
                double sourceZ = sourcePosition.z;
                SoundRequest request = SOUND_REQUEST_CACHE.get(sound);
                if (request == null
                        || request.handle() != handle
                        || !request.sourceMatches(sourceX, sourceY, sourceZ)
                        || !java.util.Objects.equals(request.emitterBounds(), emitterBounds)) {
                    request = new SoundRequest(
                            sound,
                            handle,
                            new Vec3(sourceX, sourceY, sourceZ),
                            emitterBounds,
                            AcousticTracer.TraceQuality.FULL
                    );
                    SOUND_REQUEST_CACHE.put(sound, request);
                }
                POSITIONAL_SOUND_SCRATCH.add(request);
            }
        }

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
        if (lastSubmittedBatchKey != null
                && lastSubmittedBatchKey.matches(
                scene, listener, tailFields, generation
        )) {
            return;
        }
        BatchKey batchKey = BatchKey.from(scene, listener, tailFields, generation);
        lastSubmittedBatchKey = batchKey;
        submitFieldUpdates(
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
        SOUND_EMITTER_BOUNDS.keySet().removeIf(sound -> !activeSounds.containsKey(sound)
                && !PREPARED_SOUNDS.containsKey(sound));
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
        AABB contextualBounds = ENTITY_SOUND_CONTEXT.get().peekLast();
        Vec3 reportedSource = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        if (contextualBounds != null
                && contextualBounds.inflate(0.75).contains(reportedSource)) {
            SOUND_EMITTER_BOUNDS.put(
                    sound, freeSurfaceWakeBounds(sound, contextualBounds)
            );
        } else if (sound.getIdentifier().getPath().startsWith("entity.")) {
            AABB resolvedBounds = resolveEmitterEntity(reportedSource);
            if (resolvedBounds != null) {
                SOUND_EMITTER_BOUNDS.put(
                        sound, freeSurfaceWakeBounds(sound, resolvedBounds)
                );
            }
        }
        reportFluidEmitterDiagnostic(sound, SOUND_EMITTER_BOUNDS.get(sound));
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
            Vec3 source = sourcePosition(sound);
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
        Vec3 source = sourcePosition(sound);
        AABB emitterBounds = emitterBounds(sound);
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
        if (existing == null) {
            existing = PREPARED_RESULTS.get(SourceCell.from(reportedSource));
        }
        if (existing != null
                && existing.generation() == context.generation()
                && existing.sceneRevision() == scene.revision()
                && java.util.Objects.equals(existing.emitterBounds(), emitterBounds)
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
                            AcousticTracer.TraceQuality.FULL,
                            emitterBounds
                    );
                }
        );
        PreparedResult prepared = new PreparedResult(
                        context.generation(),
                        scene.revision(),
                        context.listener(),
                        source,
                        emitterBounds,
                        now,
                        immediate,
                        computation
                );
        PREPARED_RESULTS.put(SourceCell.from(source), prepared);
        // Channel.play still reports Minecraft's original emitter coordinate. Map it
        // to the same computation so short surface splashes wait for and receive their
        // first acoustic frame instead of finishing before the corrected result lands.
        PREPARED_RESULTS.put(SourceCell.from(reportedSource), prepared);
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
        Vec3 source = sourcePosition(sound);
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
        LISTENER_FIELD_QUEUE.discard();
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
        SOUND_EMITTER_BOUNDS.clear();
        POSITIONAL_SOUND_SCRATCH.clear();
        SOURCE_POSITION_SCRATCH.clear();
        lastSubmittedBatchKey = null;
        AcousticSceneManager.clear();
    }

    /** Drops results measured with a previous GUI quality configuration. */
    public static void configurationChanged() {
        processingEnabled = AcousticQualityConfig.settings().enabled();
        generation++;
        LISTENER_FIELD_QUEUE.discard();
        prePlayContext = null;
        latestProbe = null;
        ROOM_PROBE_CACHE.clear();
        SOURCE_ROOM_PROBE_CACHE.clear();
        cancelPreparedComputations();
        REALTIME_STATES.clear();
        REALTIME_READY.clear();
        PENDING_LISTENER_APPLICATION.clear();
        SOUND_REQUEST_CACHE.clear();
        SOUND_EMITTER_BOUNDS.clear();
        lastSubmittedBatchKey = null;
        AcousticSceneManager.clear();
    }

    private static void switchLevel(ClientLevel level) {
        generation++;
        LISTENER_FIELD_QUEUE.discard();
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
        SOUND_EMITTER_BOUNDS.clear();
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
                    sourceProbe, request.listenerProbe(), source.quality(),
                    source.emitterBounds()
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

    private static void submitFieldUpdates(
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
        ListenerFieldRequest request = new ListenerFieldRequest(
                level, scene, listener, batchGeneration, submittedNanoseconds
        );
        if (LISTENER_FIELD_QUEUE.offer(request)) {
            ROOM_WORKER.execute(AcousticRuntime::drainListenerFieldRequests);
        }
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

    /** Runs only on the reserved field worker; never from SoundEngine's client thread. */
    private static void drainListenerFieldRequests() {
        ListenerFieldRequest request;
        while ((request = LISTENER_FIELD_QUEUE.take()) != null) {
            try {
                ListenerFieldRequest fieldRequest = request;
                long submittedNanoseconds = fieldRequest.submittedNanoseconds();
                ROOM_PROBE_CACHE.entrySet().removeIf(entry ->
                        entry.getValue().generation() != fieldRequest.generation()
                                || submittedNanoseconds - entry.getValue().computedNanoseconds()
                                > ROOM_PROBE_RETENTION_NANOSECONDS
                );
                SOURCE_ROOM_PROBE_CACHE.entrySet().removeIf(entry ->
                        entry.getValue().generation() != fieldRequest.generation()
                                || entry.getValue().sceneRevision() != fieldRequest.scene().revision()
                );
                RoomProbe roomProbe = cachedRoomProbe(
                        fieldRequest.scene(), fieldRequest.listener(),
                        fieldRequest.generation(), submittedNanoseconds
                );
                long completedNanoseconds = System.nanoTime();
                if (fieldRequest.generation() == generation) {
                    latestProbe = new ProbeSnapshot(
                            fieldRequest.generation(), fieldRequest.scene().revision(),
                            fieldRequest.listener(), roomProbe
                    );
                    reportListenerProbe(fieldRequest, roomProbe, completedNanoseconds);
                    deliverCompletedListenerRoom(
                            roomProbe, fieldRequest.generation(), fieldRequest.scene().revision(),
                            fieldRequest.listener(), fieldRequest.submittedNanoseconds()
                    );
                }
            } catch (RuntimeException exception) {
                lastSubmittedBatchKey = null;
                AcousticSystem.LOGGER.warn(
                        "Asynchronous listener acoustic field failed; retrying with the newest request",
                        exception
                );
            }
            if (!LISTENER_FIELD_QUEUE.continueOrRelease()) {
                return;
            }
        }
    }

    private static void reportListenerProbe(
            ListenerFieldRequest request,
            RoomProbe roomProbe,
            long completedNanoseconds
    ) {
        long elapsed = completedNanoseconds - request.submittedNanoseconds();
        if (elapsed >= 25_000_000L
                && completedNanoseconds - lastSlowListenerProbeLogNanoseconds
                >= 1_000_000_000L) {
            lastSlowListenerProbeLogNanoseconds = completedNanoseconds;
            AcousticSystem.LOGGER.warn(
                    "Listener acoustic field arrived late: probe={} ms", elapsed / 1_000_000.0
            );
        }
        if (Boolean.getBoolean("acousticsystem.debug")
                && completedNanoseconds - lastDebugLogNanoseconds >= 1_000_000_000L) {
            RoomAcoustics room = roomProbe.acoustics();
            AcousticSystem.LOGGER.info(
                    "Acoustic room: density={}, gain={}, decay={}s, reflections={}, late={}, surfaces={}, openings={}",
                    room.density(), room.gain(), room.decayTime(), room.reflectionsGain(),
                    room.lateReverbGain(), roomProbe.surfaces().size(), roomProbe.openings().size()
            );
            lastDebugLogNanoseconds = completedNanoseconds;
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
            AABB emitterBounds,
            AcousticTracer.TraceQuality quality
    ) {
        private boolean sourceMatches(double x, double y, double z) {
            return Double.doubleToLongBits(source.x) == Double.doubleToLongBits(x)
                    && Double.doubleToLongBits(source.y) == Double.doubleToLongBits(y)
                    && Double.doubleToLongBits(source.z) == Double.doubleToLongBits(z);
        }
    }

    private record ListenerFieldRequest(
            ClientLevel level,
            AcousticScene scene,
            Vec3 listener,
            long generation,
            long submittedNanoseconds
    ) {
    }

    /**
     * Water placement is a surface splash/radiation event, not an underwater emitter.
     * SoundEngine reports its position after the target cell has become water, so the
     * raw point lies inside the new voxel. Lift only this event to the free-air side of
     * that same surface; genuine underwater sounds retain their original coordinates.
     */
    private static Vec3 sourcePosition(SoundInstance sound) {
        Vec3 source = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        String soundPath = sound.getIdentifier().getPath();
        if ("item.bucket.empty".equals(soundPath)) {
            return new Vec3(
                    source.x,
                    Math.floor(source.y) + 1.001,
                    source.z
            );
        }
        AABB bounds = emitterBounds(sound);
        if (bounds != null && currentLevel != null) {
            Vec3 acousticCenter = fluidRadiationCenter(currentLevel, bounds);
            if (acousticCenter != null) {
                return acousticCenter;
            }
        }
        return source;
    }

    /** Admittance-weighted centroid of the air/fluid portions of an entity radiator. */
    private static Vec3 fluidRadiationCenter(ClientLevel level, AABB emitter) {
        double totalVolume = (emitter.maxX - emitter.minX)
                * (emitter.maxY - emitter.minY)
                * (emitter.maxZ - emitter.minZ);
        if (totalVolume <= 1.0E-12) {
            return null;
        }
        double fluidVolume = 0.0;
        double fluidMomentX = 0.0;
        double fluidMomentY = 0.0;
        double fluidMomentZ = 0.0;
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightedZ = 0.0;
        double totalWeight = 0.0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = (int) Math.floor(emitter.minY);
             y <= (int) Math.floor(Math.nextDown(emitter.maxY)); y++) {
            for (int z = (int) Math.floor(emitter.minZ);
                 z <= (int) Math.floor(Math.nextDown(emitter.maxZ)); z++) {
                for (int x = (int) Math.floor(emitter.minX);
                     x <= (int) Math.floor(Math.nextDown(emitter.maxX)); x++) {
                    pos.set(x, y, z);
                    FluidState fluid = level.getFluidState(pos);
                    if (fluid.isEmpty()) {
                        continue;
                    }
                    double fluidTop = y + Math.min(1.0, fluid.getAmount() / 8.0);
                    double minX = Math.max(emitter.minX, x);
                    double minY = Math.max(emitter.minY, y);
                    double minZ = Math.max(emitter.minZ, z);
                    double maxX = Math.min(emitter.maxX, x + 1.0);
                    double maxY = Math.min(emitter.maxY, fluidTop);
                    double maxZ = Math.min(emitter.maxZ, z + 1.0);
                    double volume = Math.max(0.0, maxX - minX)
                            * Math.max(0.0, maxY - minY)
                            * Math.max(0.0, maxZ - minZ);
                    if (volume <= 1.0E-12) {
                        continue;
                    }
                    double centerX = (minX + maxX) * 0.5;
                    double centerY = (minY + maxY) * 0.5;
                    double centerZ = (minZ + maxZ) * 0.5;
                    fluidVolume += volume;
                    fluidMomentX += centerX * volume;
                    fluidMomentY += centerY * volume;
                    fluidMomentZ += centerZ * volume;
                    double weight = volume / AcousticMaterialRegistry.findFluid(fluid)
                            .medium().acousticImpedanceRayl();
                    totalWeight += weight;
                    weightedX += centerX * weight;
                    weightedY += centerY * weight;
                    weightedZ += centerZ * weight;
                }
            }
        }
        if (fluidVolume <= 1.0E-12) {
            return null;
        }
        double airVolume = Math.max(0.0, totalVolume - Math.min(totalVolume, fluidVolume));
        if (airVolume > 1.0E-12) {
            double emitterCenterX = (emitter.minX + emitter.maxX) * 0.5;
            double emitterCenterY = (emitter.minY + emitter.maxY) * 0.5;
            double emitterCenterZ = (emitter.minZ + emitter.maxZ) * 0.5;
            double airCenterX = (emitterCenterX * totalVolume - fluidMomentX) / airVolume;
            double airCenterY = (emitterCenterY * totalVolume - fluidMomentY) / airVolume;
            double airCenterZ = (emitterCenterZ * totalVolume - fluidMomentZ) / airVolume;
            double airWeight = airVolume / 415.0;
            totalWeight += airWeight;
            weightedX += airCenterX * airWeight;
            weightedY += airCenterY * airWeight;
            weightedZ += airCenterZ * airWeight;
        }
        return totalWeight <= 1.0E-12 ? null : new Vec3(
                weightedX / totalWeight,
                weightedY / totalWeight,
                weightedZ / totalWeight
        );
    }

    private static AABB emitterBounds(SoundInstance sound) {
        AABB captured = SOUND_EMITTER_BOUNDS.get(sound);
        if (captured != null) {
            return captured;
        }
        if (sound instanceof EntityBoundSoundInstance entitySound) {
            Entity entity = ((EntityBoundSoundInstanceAccessor) entitySound)
                    .acousticsystem$getEntity();
            if (entity != null) {
                return freeSurfaceWakeBounds(sound, entity.getBoundingBox());
            }
        }
        return sound.getIdentifier().getPath().startsWith("entity.")
                ? resolveEmitterEntity(new Vec3(sound.getX(), sound.getY(), sound.getZ()))
                : null;
    }

    private static AABB resolveEmitterEntity(Vec3 source) {
        ClientLevel level = currentLevel;
        if (level == null) {
            return null;
        }
        AABB search = AABB.ofSize(source, 3.0, 4.0, 3.0);
        Entity nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Entity candidate : level.getEntities((Entity) null, search)) {
            AABB bounds = candidate.getBoundingBox();
            double dx = Math.max(bounds.minX - source.x, Math.max(0.0, source.x - bounds.maxX));
            double dy = Math.max(bounds.minY - source.y, Math.max(0.0, source.y - bounds.maxY));
            double dz = Math.max(bounds.minZ - source.z, Math.max(0.0, source.z - bounds.maxZ));
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest == null ? null : nearest.getBoundingBox();
    }

    /**
     * A swimming sound is radiated by the free-surface wake, not only by the submerged
     * collision box. If the nearest connected surface lies within one body scale, add
     * that real water column to the emitter. Deeper entities remain purely submerged.
     */
    private static AABB freeSurfaceWakeBounds(SoundInstance sound, AABB body) {
        String path = sound.getIdentifier().getPath();
        if (!path.contains("swim") && !path.contains("splash")) {
            return body;
        }
        ClientLevel level = currentLevel;
        if (level == null) {
            return body;
        }
        int x = (int) Math.floor((body.minX + body.maxX) * 0.5);
        int z = (int) Math.floor((body.minZ + body.maxZ) * 0.5);
        int startY = (int) Math.floor(Math.nextDown(body.maxY));
        FluidState fluid = level.getFluidState(new BlockPos(x, startY, z));
        if (fluid.isEmpty()) {
            startY = (int) Math.floor(body.minY);
            fluid = level.getFluidState(new BlockPos(x, startY, z));
        }
        if (fluid.isEmpty()) {
            return body;
        }
        double bodyScale = Math.max(
                Math.max(body.maxX - body.minX, body.maxZ - body.minZ),
                body.maxY - body.minY
        );
        int maximumSteps = Math.max(1, (int) Math.ceil(bodyScale) + 1);
        int surfaceCellY = startY;
        FluidState surfaceFluid = fluid;
        for (int step = 0; step < maximumSteps; step++) {
            double fill = Math.min(1.0, surfaceFluid.getAmount() / 8.0);
            BlockPos above = new BlockPos(x, surfaceCellY + 1, z);
            FluidState upper = level.getFluidState(above);
            if (fill < 1.0 - 1.0E-6
                    || upper.isEmpty()
                    || upper.getType() != fluid.getType()) {
                double surfaceY = surfaceCellY + fill;
                double gap = surfaceY - body.maxY;
                if (gap >= -1.0E-6 && gap <= bodyScale + 1.0E-6) {
                    return new AABB(
                            body.minX, body.minY, body.minZ,
                            body.maxX, Math.max(body.maxY, surfaceY + 0.001), body.maxZ
                    );
                }
                return body;
            }
            surfaceCellY++;
            surfaceFluid = upper;
        }
        return body;
    }

    private static void reportFluidEmitterDiagnostic(
            SoundInstance sound,
            AABB contextualBounds
    ) {
        String path = sound.getIdentifier().getPath();
        if (!path.contains("swim") && !path.contains("splash")) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastFluidEmitterDiagnosticNanoseconds < 500_000_000L) {
            return;
        }
        lastFluidEmitterDiagnosticNanoseconds = now;
        AABB bounds = contextualBounds != null ? contextualBounds : emitterBounds(sound);
        AcousticSystem.LOGGER.info(
                "Fluid emitter diagnostic: sound={}, class={}, bounds={}, source=({}, {}, {})",
                sound.getIdentifier(), sound.getClass().getName(), bounds,
                sound.getX(), sound.getY(), sound.getZ()
        );
    }

    public static void beginEntitySound(Entity entity) {
        if (entity != null) {
            ENTITY_SOUND_CONTEXT.get().addLast(entity.getBoundingBox());
        }
    }

    public static void endEntitySound(Entity entity) {
        if (entity == null) {
            return;
        }
        ArrayDeque<AABB> stack = ENTITY_SOUND_CONTEXT.get();
        if (!stack.isEmpty()) {
            stack.removeLast();
        }
        if (stack.isEmpty()) {
            ENTITY_SOUND_CONTEXT.remove();
        }
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
            AABB emitterBounds,
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

        synchronized void discard() {
            pending = null;
            published = null;
            parked = false;
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
