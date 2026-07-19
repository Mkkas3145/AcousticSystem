package org.macaroon.acousticsystem.client.simulation;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.macaroon.acousticsystem.client.material.AcousticTuning;
import org.macaroon.acousticsystem.client.scene.AcousticScene;
import org.macaroon.acousticsystem.client.scene.AcousticSceneManager;
import org.macaroon.acousticsystem.mixin.client.ChannelAccessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class AcousticRuntime {
    private static final int WORKER_COUNT = Math.max(
            1,
            Math.min(4, (Runtime.getRuntime().availableProcessors() - 2) / 2)
    );
    private static final long ROOM_PROBE_RETENTION_NANOSECONDS = 2_000_000_000L;
    private static final long PREPARED_RESULT_RETENTION_NANOSECONDS = 2_000_000_000L;
    private static final double ROOM_PROBE_CELL_SCALE = 2.0;
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            WORKER_COUNT,
            new AcousticThreadFactory()
    );
    // Onset bursts can contain any number of sounds. The work-stealing pool accepts all
    // of them and scales physical parallelism from the machine instead of imposing a
    // two-sound ceiling, while reserving cores for Minecraft and the audio thread.
    private static final int ONSET_WORKER_COUNT = Math.max(
            2,
            (Runtime.getRuntime().availableProcessors() - 2) / 2
    );
    private static final AtomicInteger ONSET_THREAD_SEQUENCE = new AtomicInteger();
    private static final ExecutorService ONSET_WORKERS = new ForkJoinPool(
            ONSET_WORKER_COUNT,
            pool -> {
                ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory
                        .newThread(pool);
                thread.setName("AcousticSystem-Onset-" + ONSET_THREAD_SEQUENCE.incrementAndGet());
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            },
            null,
            true
    );

    private static CompletableFuture<BatchResult> pendingBatch;
    private static ClientLevel currentLevel;
    private static volatile PrePlayContext prePlayContext;
    private static volatile ProbeSnapshot latestProbe;
    private static final Map<ProbeCell, CachedRoomProbe> ROOM_PROBE_CACHE = new ConcurrentHashMap<>();
    private static final Map<SourceCell, CachedRoomProbe> SOURCE_ROOM_PROBE_CACHE = new ConcurrentHashMap<>();
    private static final Map<SourceCell, PreparedResult> PREPARED_RESULTS = new ConcurrentHashMap<>();
    // Source-cell caching avoids duplicate traces, but delivery belongs to a playback
    // occurrence. Keeping only the newest cell entry caused rapid repeated sounds to
    // invalidate every earlier completion before it reached its own channel.
    private static final Map<SoundInstance, PreparedResult> PREPARED_SOUNDS = new ConcurrentHashMap<>();
    private static volatile long generation;
    private static long lastDebugLogNanoseconds;

    private AcousticRuntime() {
    }

    public static int workerCount() {
        return WORKER_COUNT;
    }

    static int onsetWorkerCount() {
        return ONSET_WORKER_COUNT;
    }

    public static void tick(ClientLevel level, Vec3 listener, Map<SoundInstance, ChannelAccess.ChannelHandle> activeSounds) {
        if (currentLevel != level) {
            switchLevel(level);
        }

        List<SoundRequest> positionalSounds = new ArrayList<>();
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : activeSounds.entrySet()) {
            SoundInstance sound = entry.getKey();
            if (!sound.isRelative()) {
                positionalSounds.add(new SoundRequest(
                        sound,
                        entry.getValue(),
                        new Vec3(sound.getX(), sound.getY(), sound.getZ()),
                        AcousticTracer.TraceQuality.FULL
                ));
            }
        }

        publishCompletedBatch(level, listener);
        long now = System.nanoTime();
        // There is no fixed update clock. A completed snapshot is consumed immediately
        // and the next immutable-world calculation is launched on the next available
        // client observation. Static scenes are still re-evaluated continuously, while
        // slow machines naturally apply back-pressure by keeping one batch in flight.
        if (pendingBatch != null) {
            return;
        }

        List<Vec3> sourcePositions = positionalSounds.stream().map(SoundRequest::source).toList();
        AcousticScene scene = AcousticSceneManager.capture(level, listener, sourcePositions);
        prePlayContext = new PrePlayContext(level, scene, listener, generation);
        PREPARED_RESULTS.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().generation() != generation
                    || now - entry.getValue().computedNanoseconds()
                    > PREPARED_RESULT_RETENTION_NANOSECONDS;
            if (expired) {
                entry.getValue().computation().cancel(false);
            }
            return expired;
        });
        PREPARED_SOUNDS.entrySet().removeIf(entry ->
                entry.getValue().generation() != generation
                        || now - entry.getValue().computedNanoseconds()
                        > PREPARED_RESULT_RETENTION_NANOSECONDS
        );
        submitBatch(level, scene, listener, positionalSounds, generation);
    }

    /** Called on the client thread before Minecraft allocates the OpenAL channel. */
    public static void prepareSound(SoundInstance sound) {
        PrePlayContext context = prePlayContext;
        if (sound.isRelative()) {
            return;
        }
        if (context == null || currentLevel != context.level()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!minecraft.isSameThread()
                    || minecraft.level == null
                    || !minecraft.gameRenderer.mainCamera().isInitialized()) {
                return;
            }
            if (currentLevel != minecraft.level) {
                switchLevel(minecraft.level);
            }
            Vec3 listener = minecraft.gameRenderer.mainCamera().position();
            Vec3 source = new Vec3(sound.getX(), sound.getY(), sound.getZ());
            AcousticScene initialScene = AcousticSceneManager.captureForImmediateSound(
                    minecraft.level,
                    null,
                    listener,
                    source
            );
            context = new PrePlayContext(minecraft.level, initialScene, listener, generation);
            prePlayContext = context;
        }
        Vec3 source = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        AcousticScene scene = AcousticSceneManager.captureForImmediateSound(
                context.level(),
                context.scene(),
                context.listener(),
                source
        );
        long now = System.nanoTime();
        PreparedResult existing = PREPARED_RESULTS.get(SourceCell.from(source));
        if (existing != null
                && existing.generation() == context.generation()
                && existing.sceneRevision() == scene.revision()
                && isListenerResultCurrent(existing.listener(), context.listener())
                && !existing.computation().isCancelled()
                && !existing.computation().isCompletedExceptionally()
                && now - existing.computedNanoseconds() <= PREPARED_RESULT_RETENTION_NANOSECONDS) {
            PREPARED_SOUNDS.put(sound, existing);
            return;
        }
        RoomProbe roomProbe = prePlayRoomProbe(scene, context.listener(), context.generation(), now);
        PrePlayContext preparedContext = context;
        AcousticResult immediate = AcousticTracer.traceImmediate(
                scene,
                source,
                preparedContext.listener(),
                roomProbe
        );
        CompletableFuture<AcousticResult> computation = CompletableFuture.supplyAsync(
                () -> {
                    RoomProbe sourceRoomProbe = cachedSourceRoomProbe(
                            scene, source, preparedContext.generation(), now
                    );
                    return AcousticTracer.trace(
                            scene,
                            source,
                            preparedContext.listener(),
                            sourceRoomProbe,
                            roomProbe,
                            AcousticTracer.TraceQuality.BASIC
                    );
                },
                ONSET_WORKERS
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

    public static void applyBeforePlay(int source, Vec3 sourcePosition, boolean relative) {
        PrePlayContext context = prePlayContext;
        if (relative || context == null) {
            return;
        }

        PreparedResult prepared = PREPARED_RESULTS.get(SourceCell.from(sourcePosition));
        long now = System.nanoTime();
        if (prepared != null
                && prepared.generation() == context.generation()
                && now - prepared.computedNanoseconds() <= PREPARED_RESULT_RETENTION_NANOSECONDS) {
            try {
                AcousticResult result = prepared.computation().getNow(prepared.immediate());
                OpenALAcousticEffects.prepareListenerRoomForOnset(result.reverbRoom());
                OpenALAcousticEffects.applyBeforePlay(source, result);
            } catch (CompletionException | CancellationException exception) {
                AcousticSystem.LOGGER.warn(
                        "Asynchronous first-frame acoustic calculation failed",
                        exception.getCause() == null ? exception : exception.getCause()
                );
            }
            return;
        }

        // prepareSound normally populated the exact source cell. Never fall back to a
        // ray trace here: Channel.play can run on the sound/OpenAL thread, where world
        // traversal and allocation cause audible stalls and game-thread contention.
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
        Vec3 source = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        PreparedResult prepared = PREPARED_SOUNDS.get(sound);
        if (prepared == null
                || prepared.generation() != generation
                || !isListenerResultCurrent(
                        prepared.listener(),
                        prePlayContext == null ? prepared.listener() : prePlayContext.listener()
                )) {
            PREPARED_SOUNDS.remove(sound, prepared);
            return;
        }
        prepared.computation().thenAccept(result -> {
            if (handle.isStopped() || PREPARED_SOUNDS.get(sound) != prepared) {
                PREPARED_SOUNDS.remove(sound, prepared);
                return;
            }
            Vec3 currentSource = new Vec3(sound.getX(), sound.getY(), sound.getZ());
            double maximumMovement = AcousticMaterialRegistry.tuning().maxSourceMovement();
            if (currentSource.distanceToSqr(prepared.source())
                    > maximumMovement * maximumMovement) {
                PREPARED_SOUNDS.remove(sound, prepared);
                return;
            }
            handle.execute(channel -> {
                if (PREPARED_SOUNDS.remove(sound, prepared)) {
                    applyOnsetCorrection(channel, result);
                }
            });
        }).exceptionally(exception -> {
            PREPARED_SOUNDS.remove(sound, prepared);
            return null;
        });
    }

    public static void shutdown() {
        if (pendingBatch != null) {
            pendingBatch.cancel(false);
            pendingBatch = null;
        }
        WORKERS.shutdownNow();
        ONSET_WORKERS.shutdownNow();
        currentLevel = null;
        prePlayContext = null;
        latestProbe = null;
        ROOM_PROBE_CACHE.clear();
        SOURCE_ROOM_PROBE_CACHE.clear();
        cancelPreparedComputations();
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
        ROOM_PROBE_CACHE.clear();
        SOURCE_ROOM_PROBE_CACHE.clear();
        cancelPreparedComputations();
        AcousticSceneManager.clear();
    }

    private static void submitBatch(
            ClientLevel level,
            AcousticScene scene,
            Vec3 listener,
            List<SoundRequest> sounds,
            long batchGeneration
    ) {
        long submittedNanoseconds = System.nanoTime();
        CompletableFuture<RoomProbe> roomFuture = CompletableFuture.supplyAsync(
                () -> cachedRoomProbe(scene, listener, batchGeneration, submittedNanoseconds),
                WORKERS
        );
        ROOM_PROBE_CACHE.entrySet().removeIf(entry ->
                entry.getValue().generation() != batchGeneration
                        || submittedNanoseconds - entry.getValue().computedNanoseconds()
                        > ROOM_PROBE_RETENTION_NANOSECONDS
        );
        SOURCE_ROOM_PROBE_CACHE.entrySet().removeIf(entry ->
                entry.getValue().generation() != batchGeneration
                        || entry.getValue().sceneRevision() != scene.revision()
        );
        pendingBatch = roomFuture.thenCompose(roomProbe -> {
            if (sounds.isEmpty()) {
                return CompletableFuture.completedFuture(new BatchResult(
                        level,
                        batchGeneration,
                        scene.revision(),
                        listener,
                        roomProbe,
                        submittedNanoseconds
                ));
            }
            Map<TraceKey, AcousticResult> traceCache = new ConcurrentHashMap<>();
            int partitionCount = Math.min(WORKER_COUNT, sounds.size());
            List<CompletableFuture<Void>> partitions = new ArrayList<>(partitionCount);
            for (int partition = 0; partition < partitionCount; partition++) {
                int firstIndex = partition;
                partitions.add(CompletableFuture.supplyAsync(() -> {
                    for (int index = firstIndex; index < sounds.size(); index += partitionCount) {
                        SoundRequest request = sounds.get(index);
                        TraceKey key = TraceKey.from(request);
                        AcousticResult result = traceCache.computeIfAbsent(key, ignored -> {
                            RoomProbe sourceRoomProbe = cachedSourceRoomProbe(
                                    scene, request.source(), batchGeneration,
                                    submittedNanoseconds
                            );
                            return AcousticTracer.trace(
                                    scene,
                                    request.source(),
                                    listener,
                                    sourceRoomProbe,
                                    roomProbe,
                                    request.quality()
                            );
                        });
                        deliverCompletedSound(
                                request,
                                result,
                                batchGeneration,
                                listener,
                                submittedNanoseconds
                        );
                    }
                    return null;
                }, WORKERS));
            }
            CompletableFuture<?>[] all = partitions.toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(all).thenApply(ignored ->
                    new BatchResult(
                        level,
                        batchGeneration,
                        scene.revision(),
                        listener,
                        roomProbe,
                        submittedNanoseconds
                    )
            );
        });
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
                    || batch.generation() != generation
                    || !isListenerResultCurrent(batch.listener(), currentListener)) {
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
            AcousticTuning tuning = AcousticMaterialRegistry.tuning();
            long ageMilliseconds = (System.nanoTime() - batch.submittedNanoseconds()) / 1_000_000L;
            if (ageMilliseconds > tuning.maxResultAgeMilliseconds()) {
                return;
            }

            // Source results are delivered individually by their worker partition as
            // soon as they finish. The completed batch only commits the listener probe
            // and opens the next continuously sampled generation; it must not apply the
            // same coefficients a second time.
        } catch (CompletionException exception) {
            AcousticSystem.LOGGER.warn("Asynchronous acoustic batch failed; retrying on the next client tick", exception.getCause());
        }
    }

    private static void deliverCompletedSound(
            SoundRequest request,
            AcousticResult result,
            long batchGeneration,
            Vec3 computedListener,
            long submittedNanoseconds
    ) {
        ChannelAccess.ChannelHandle handle = request.handle();
        if (handle == null || handle.isStopped() || batchGeneration != generation) {
            return;
        }
        PrePlayContext context = prePlayContext;
        if (context == null
                || context.generation() != batchGeneration
                || !isListenerResultCurrent(computedListener, context.listener())) {
            return;
        }
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        long ageMilliseconds = (System.nanoTime() - submittedNanoseconds) / 1_000_000L;
        if (ageMilliseconds > tuning.maxResultAgeMilliseconds()) {
            return;
        }
        Vec3 currentPosition = new Vec3(
                request.sound().getX(),
                request.sound().getY(),
                request.sound().getZ()
        );
        double maximumMovement = tuning.maxSourceMovement();
        if (currentPosition.distanceToSqr(request.source())
                > maximumMovement * maximumMovement) {
            return;
        }
        handle.execute(channel -> apply(channel, result, submittedNanoseconds));
    }

    private static void apply(Channel channel, AcousticResult result, long sequence) {
        int source = ((ChannelAccessor) channel).acousticsystem$getSource();
        OpenALAcousticEffects.applySequenced(source, result, sequence);
    }

    private static void applyOnsetCorrection(Channel channel, AcousticResult result) {
        int source = ((ChannelAccessor) channel).acousticsystem$getSource();
        OpenALAcousticEffects.applyOnsetCorrection(source, result);
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
        CachedRoomProbe cached = SOURCE_ROOM_PROBE_CACHE.get(cell);
        if (cached != null
                && cached.generation() == expectedGeneration
                && cached.sceneRevision() == scene.revision()) {
            return cached.probe();
        }
        RoomProbe probe = AcousticTracer.probeSourceRoom(scene, position);
        if (SOURCE_ROOM_PROBE_CACHE.size() >= 512) {
            SOURCE_ROOM_PROBE_CACHE.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().computedNanoseconds()))
                    .ifPresent(entry -> SOURCE_ROOM_PROBE_CACHE.remove(entry.getKey(), entry.getValue()));
        }
        SOURCE_ROOM_PROBE_CACHE.put(
                cell,
                new CachedRoomProbe(expectedGeneration, scene.revision(), now, probe)
        );
        return probe;
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
    }

    private record SoundRequest(
            SoundInstance sound,
            ChannelAccess.ChannelHandle handle,
            Vec3 source,
            AcousticTracer.TraceQuality quality
    ) {
    }

    private record TraceKey(long x, long y, long z, AcousticTracer.TraceQuality quality) {
        private static TraceKey from(SoundRequest request) {
            return new TraceKey(
                    Double.doubleToLongBits(request.source().x),
                    Double.doubleToLongBits(request.source().y),
                    Double.doubleToLongBits(request.source().z),
                    request.quality()
            );
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

    private static final class AcousticThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "AcousticSystem-Worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }
}
