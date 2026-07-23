package org.macaroon.acousticsystem.client.scene;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.macaroon.acousticsystem.client.material.AcousticTuning;
import org.macaroon.acousticsystem.client.scene.AcousticScene.SectionKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Main-thread scene capture with copy-on-write section reuse. */
public final class AcousticSceneManager {
    private static final AtomicInteger CAPTURE_WORKER_SEQUENCE = new AtomicInteger();
    private static final ExecutorService CAPTURE_WORKER = Executors.newSingleThreadExecutor(
            task -> {
                Thread thread = new Thread(
                        task,
                        "AcousticSystem-SceneCapture-"
                                + CAPTURE_WORKER_SEQUENCE.incrementAndGet()
                );
                thread.setDaemon(true);
                thread.setPriority(Math.max(
                        Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1
                ));
                return thread;
            }
    );
    private static final AtomicReference<CaptureRequest> LATEST_CAPTURE =
            new AtomicReference<>();
    private static final AtomicBoolean CAPTURE_RUNNING = new AtomicBoolean();
    private static final AtomicLong RESET_EPOCH = new AtomicLong();
    private static final AtomicLong DIRTY_VERSION = new AtomicLong();
    private static volatile CaptureRequest lastRequestedCapture;
    private static final AtomicReference<LiveSectionRegistry> LIVE_SECTIONS =
            new AtomicReference<>(new LiveSectionRegistry(
                    null, new ConcurrentHashMap<>()
            ));
    private static final int SECTION_DECODER_COUNT = Math.max(
            1,
            Math.min(4, Runtime.getRuntime().availableProcessors() / 8)
    );
    private static final AtomicInteger SECTION_DECODER_SEQUENCE = new AtomicInteger();
    private static final ExecutorService SECTION_DECODER = Executors.newFixedThreadPool(
            SECTION_DECODER_COUNT,
            task -> {
                Thread thread = new Thread(
                        task,
                        "AcousticSystem-SceneDecoder-"
                                + SECTION_DECODER_SEQUENCE.incrementAndGet()
                );
                thread.setDaemon(true);
                thread.setPriority(Math.max(
                        Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2
                ));
                return thread;
            }
    );
    private static final Map<SectionKey, AcousticSection> SECTION_CACHE = new ConcurrentHashMap<>();
    private static final Map<SectionKey, Long> LAST_USED_CAPTURE = new ConcurrentHashMap<>();
    private static final Set<SectionKey> DIRTY_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Map<Long, FluidState> DISPLACED_FLUIDS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<BlockChange> BLOCK_CHANGES =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ChunkChange> CHUNK_CHANGES =
            new ConcurrentLinkedQueue<>();
    private static final long UNUSED_CAPTURE_RETENTION = 400L;
    private static final int[][] FACE_NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private static volatile ClientLevel currentLevel;
    private static long revision;
    private static long captureSequence;
    private static volatile Set<SectionKey> lastRequiredSections = Set.of();
    private static volatile AcousticScene lastScene;
    private static long workerEpoch = Long.MIN_VALUE;
    private static BlockPos lastCaptureListenerCell;
    private static List<BlockPos> lastCaptureSourceCells = List.of();
    private static BlockPos sparseProbeCell;
    private static float sparseProbeDenseDistance;
    private static float sparseProbeMaximumDistance;
    private static int sparseProbeRayCount;
    private static Set<SectionKey> sparseProbeSections = Set.of();

    private AcousticSceneManager() {
    }

    /**
     * Publishes only immutable coordinates from the client/render thread. Geometry
     * coverage, chunk lookup and palette copying are performed by the scene worker;
     * callers immediately receive the newest complete snapshot and never wait for it.
     */
    public static AcousticScene requestCapture(
            ClientLevel level,
            Vec3 listener,
            List<Vec3> sources
    ) {
        long epoch = RESET_EPOCH.get();
        long dirtyVersion = DIRTY_VERSION.get();
        CaptureRequest previous = lastRequestedCapture;
        if (previous != null
                && previous.epoch() == epoch
                && previous.dirtyVersion() == dirtyVersion
                && previous.level() == level
                && sameCoverageCells(previous.listener(), listener)
                && sameCoverageCells(previous.sources(), sources)) {
            AcousticScene published = lastScene;
            return currentLevel == level ? published : null;
        }
        CaptureRequest request = new CaptureRequest(
                level, listener, List.copyOf(sources), epoch, dirtyVersion
        );
        lastRequestedCapture = request;
        LATEST_CAPTURE.set(request);
        scheduleCaptureWorker();
        AcousticScene published = lastScene;
        return currentLevel == level ? published : null;
    }

    public static AcousticScene latest(ClientLevel level) {
        AcousticScene published = lastScene;
        return currentLevel == level ? published : null;
    }

    /** Publishes stable section references while Minecraft owns the client thread. */
    public static void registerChunk(ClientLevel level, LevelChunk chunk) {
        LiveSectionRegistry registry = liveRegistry(level);
        LevelChunkSection[] sections = chunk.getSections();
        int chunkX = Math.floorDiv(chunk.getPos().getMinBlockX(), 16);
        int chunkZ = Math.floorDiv(chunk.getPos().getMinBlockZ(), 16);
        for (int index = 0; index < sections.length; index++) {
            registry.sections().put(
                    new SectionKey(
                            chunkX,
                            level.getSectionYFromSectionIndex(index),
                            chunkZ
                    ),
                    sections[index]
            );
        }
        DIRTY_VERSION.incrementAndGet();
    }

    public static void unregisterChunk(ClientLevel level, LevelChunk chunk) {
        LiveSectionRegistry registry = LIVE_SECTIONS.get();
        if (registry.level() != level) {
            return;
        }
        int chunkX = Math.floorDiv(chunk.getPos().getMinBlockX(), 16);
        int chunkZ = Math.floorDiv(chunk.getPos().getMinBlockZ(), 16);
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            registry.sections().remove(new SectionKey(
                    chunkX,
                    level.getSectionYFromSectionIndex(index),
                    chunkZ
            ));
        }
        DIRTY_VERSION.incrementAndGet();
    }

    private static LiveSectionRegistry liveRegistry(ClientLevel level) {
        while (true) {
            LiveSectionRegistry registry = LIVE_SECTIONS.get();
            if (registry.level() == level) {
                return registry;
            }
            LiveSectionRegistry replacement = new LiveSectionRegistry(
                    level, new ConcurrentHashMap<>()
            );
            if (LIVE_SECTIONS.compareAndSet(registry, replacement)) {
                return replacement;
            }
        }
    }

    private static void scheduleCaptureWorker() {
        if (CAPTURE_RUNNING.compareAndSet(false, true)) {
            CAPTURE_WORKER.execute(AcousticSceneManager::drainCaptureRequests);
        }
    }

    private static boolean sameCoverageCells(Vec3 first, Vec3 second) {
        return Mth.floor(first.x) == Mth.floor(second.x)
                && Mth.floor(first.y) == Mth.floor(second.y)
                && Mth.floor(first.z) == Mth.floor(second.z);
    }

    private static boolean sameCoverageCells(List<Vec3> first, List<Vec3> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!sameCoverageCells(first.get(index), second.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static void drainCaptureRequests() {
        try {
            CaptureRequest request;
            while ((request = LATEST_CAPTURE.getAndSet(null)) != null) {
                if (request.epoch() != RESET_EPOCH.get()) {
                    continue;
                }
                if (workerEpoch != request.epoch()) {
                    clearWorkerState();
                    workerEpoch = request.epoch();
                }
                capture(
                        request.level(), request.listener(), request.sources(),
                        request.epoch()
                );
            }
        } finally {
            CAPTURE_RUNNING.set(false);
        }
        if (LATEST_CAPTURE.get() != null) {
            scheduleCaptureWorker();
        }
    }

    /**
     * Reuses the current listener sphere for a newly-created nearby sound. Sound bursts
     * must not shrink and rebuild the tick snapshot once per SoundInstance.
     */
    public static AcousticScene captureForImmediateSound(
            ClientLevel level,
            AcousticScene preferredScene,
            Vec3 listener,
            Vec3 source
    ) {
        return requestCapture(level, listener, List.of(source));
    }

    private static AcousticScene capture(
            ClientLevel level,
            Vec3 listener,
            List<Vec3> sources,
            long expectedEpoch
    ) {
        if (expectedEpoch != RESET_EPOCH.get()) {
            return null;
        }
        if (currentLevel != level) {
            currentLevel = level;
            SECTION_CACHE.clear();
            LAST_USED_CAPTURE.clear();
            DIRTY_SECTIONS.clear();
            DISPLACED_FLUIDS.clear();
            lastRequiredSections = Set.of();
            lastScene = null;
            clearCaptureCells();
            clearSparseProbe();
            revision++;
        }
        drainWorldChanges(level);

        if (canReuseCapture(level, listener, sources)) {
            return lastScene;
        }

        Set<SectionKey> required = requiredSections(level, listener, sources);
        long currentCapture = ++captureSequence;
        boolean requiredGeometryChanged = false;
        for (SectionKey key : required) {
            if (DIRTY_SECTIONS.contains(key)) {
                requiredGeometryChanged = true;
                break;
            }
        }
        if (lastScene != null
                && !requiredGeometryChanged
                && required.equals(lastRequiredSections)) {
            for (SectionKey key : required) {
                LAST_USED_CAPTURE.put(key, currentCapture);
            }
            rememberCaptureCells(listener, sources);
            return lastScene;
        }

        Map<SectionKey, AcousticSection> sceneSections = new HashMap<>(required.size());
        List<CompletableFuture<Void>> pendingDecodes = new ArrayList<>();
        for (SectionKey key : required) {
            if (!isAvailable(level, key)) {
                boolean changed = DIRTY_SECTIONS.remove(key);
                changed |= SECTION_CACHE.remove(key) != null;
                LAST_USED_CAPTURE.remove(key);
                if (changed) {
                    revision++;
                }
                continue;
            }
            AcousticSection section = SECTION_CACHE.get(key);
            boolean dirty = DIRTY_SECTIONS.remove(key);
            if (section == null || dirty) {
                section = captureSection(level, key);
                SECTION_CACHE.put(key, section);
                if (dirty) {
                    revision++;
                }
            }
            if (!section.isDecoded()) {
                AcousticSection captured = section;
                pendingDecodes.add(CompletableFuture.runAsync(
                        captured::decode, SECTION_DECODER
                ));
            }
            sceneSections.put(key, section);
            LAST_USED_CAPTURE.put(key, currentCapture);
        }

        // Never publish a scene which still falls back to synchronized palette reads.
        // The wait is entirely on the scene worker; render/audio threads continue using
        // the previous immutable scene until all newly captured sections are expanded.
        if (!pendingDecodes.isEmpty()) {
            CompletableFuture.allOf(
                    pendingDecodes.toArray(CompletableFuture[]::new)
            ).join();
        }

        SECTION_CACHE.keySet().removeIf(key -> {
            long lastUsed = LAST_USED_CAPTURE.getOrDefault(key, Long.MIN_VALUE);
            if (currentCapture - lastUsed <= UNUSED_CAPTURE_RETENTION) {
                return false;
            }
            LAST_USED_CAPTURE.remove(key);
            DIRTY_SECTIONS.remove(key);
            return true;
        });
        AcousticScene scene = new AcousticScene(
                sceneSections,
                level.getSectionYFromSectionIndex(0) * 16,
                level.getHeight(),
                revision,
                level,
                DISPLACED_FLUIDS
        );
        lastRequiredSections = Set.copyOf(required);
        if (expectedEpoch != RESET_EPOCH.get()) {
            return null;
        }
        lastScene = scene;
        rememberCaptureCells(listener, sources);
        return scene;
    }

    public static void markDirty(ClientLevel level, BlockPos pos) {
        DIRTY_VERSION.incrementAndGet();
        BLOCK_CHANGES.add(new BlockChange(
                level, pos.immutable(), null, null
        ));
    }

    private static void markDirtySection(BlockPos pos) {
        int sectionX = Math.floorDiv(pos.getX(), 16);
        int sectionY = Math.floorDiv(pos.getY(), 16);
        int sectionZ = Math.floorDiv(pos.getZ(), 16);
        SectionKey changedSection = new SectionKey(sectionX, sectionY, sectionZ);
        DIRTY_SECTIONS.add(changedSection);
        if (sparseProbeSections.contains(changedSection)) {
            sparseProbeCell = null;
        }

        int localX = Math.floorMod(pos.getX(), 16);
        int localY = Math.floorMod(pos.getY(), 16);
        int localZ = Math.floorMod(pos.getZ(), 16);
        if (localX == 0) DIRTY_SECTIONS.add(new SectionKey(sectionX - 1, sectionY, sectionZ));
        if (localX == 15) DIRTY_SECTIONS.add(new SectionKey(sectionX + 1, sectionY, sectionZ));
        if (localY == 0) DIRTY_SECTIONS.add(new SectionKey(sectionX, sectionY - 1, sectionZ));
        if (localY == 15) DIRTY_SECTIONS.add(new SectionKey(sectionX, sectionY + 1, sectionZ));
        if (localZ == 0) DIRTY_SECTIONS.add(new SectionKey(sectionX, sectionY, sectionZ - 1));
        if (localZ == 15) DIRTY_SECTIONS.add(new SectionKey(sectionX, sectionY, sectionZ + 1));
    }

    /**
     * Records the pressure medium displaced by a solid-to-empty transition. The entry
     * has no timer: it exists exactly while the current empty cell remains bounded on
     * every face by full fluid or a closed solid. This repairs client update ordering
     * without turning a real opening or partially wetted source into water.
     */
    public static void markDirty(
            ClientLevel level,
            BlockPos pos,
            BlockState oldState,
            BlockState newState
    ) {
        DIRTY_VERSION.incrementAndGet();
        BLOCK_CHANGES.add(new BlockChange(
                level, pos.immutable(), oldState, newState
        ));
    }

    static FluidState enclosedFluidBoundary(BlockGetter level, BlockPos position) {
        FluidState selected = Fluids.EMPTY.defaultFluidState();
        int selectedAmount = -1;
        for (int[] offset : FACE_NEIGHBORS) {
            BlockPos neighbour = position.offset(offset[0], offset[1], offset[2]);
            FluidState fluid = level.getFluidState(neighbour);
            if (!fluid.isEmpty() && fluid.getAmount() >= 8) {
                if (fluid.getAmount() > selectedAmount) {
                    selected = fluid;
                    selectedAmount = fluid.getAmount();
                }
                continue;
            }
            BlockState state = level.getBlockState(neighbour);
            if (!state.isCollisionShapeFullBlock(level, neighbour)) {
                return Fluids.EMPTY.defaultFluidState();
            }
        }
        return selected;
    }

    public static void clear() {
        RESET_EPOCH.incrementAndGet();
        DIRTY_VERSION.incrementAndGet();
        LATEST_CAPTURE.set(null);
        lastRequestedCapture = null;
        currentLevel = null;
        lastScene = null;
    }

    private static void clearWorkerState() {
        SECTION_CACHE.clear();
        LAST_USED_CAPTURE.clear();
        DIRTY_SECTIONS.clear();
        DISPLACED_FLUIDS.clear();
        lastRequiredSections = Set.of();
        lastScene = null;
        clearCaptureCells();
        clearSparseProbe();
        revision++;
    }

    public static void markChunkDirty(ClientLevel level, int chunkX, int chunkZ) {
        DIRTY_VERSION.incrementAndGet();
        CHUNK_CHANGES.add(new ChunkChange(level, chunkX, chunkZ));
    }

    private static void markChunkDirtyNow(ClientLevel level, int chunkX, int chunkZ) {
        int minimumY = level.getSectionYFromSectionIndex(0) * 16;
        int minimumSectionY = Math.floorDiv(minimumY, 16);
        int maximumSectionY = Math.floorDiv(minimumY + level.getHeight() - 1, 16);
        for (int sectionY = minimumSectionY; sectionY <= maximumSectionY; sectionY++) {
            DIRTY_SECTIONS.add(new SectionKey(chunkX, sectionY, chunkZ));
        }
    }

    public static void markSectionDirty(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        if (currentLevel == level) {
            DIRTY_VERSION.incrementAndGet();
            DIRTY_SECTIONS.add(new SectionKey(sectionX, sectionY, sectionZ));
        }
    }

    public static void markSectionRangeDirty(
            ClientLevel level,
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            int maxSectionX,
            int maxSectionY,
            int maxSectionZ
    ) {
        if (currentLevel != level) {
            return;
        }
        DIRTY_VERSION.incrementAndGet();
        for (int y = minSectionY; y <= maxSectionY; y++) {
            for (int z = minSectionZ; z <= maxSectionZ; z++) {
                for (int x = minSectionX; x <= maxSectionX; x++) {
                    DIRTY_SECTIONS.add(new SectionKey(x, y, z));
                }
            }
        }
    }

    /**
     * Geometry coverage changes only when an endpoint enters another block cell or a
     * covered section is dirtied. The captured volume is deliberately conservative for
     * every sub-block position in those cells, so this fast path skips hundreds of
     * temporary SectionKey allocations without reducing propagation resolution.
     */
    private static boolean canReuseCapture(
            ClientLevel level,
            Vec3 listener,
            List<Vec3> sources
    ) {
        if (currentLevel != level || lastScene == null
                || lastCaptureListenerCell == null
                || !isInsideCell(lastCaptureListenerCell, listener)
                || sources.size() != lastCaptureSourceCells.size()) {
            return false;
        }
        for (int index = 0; index < sources.size(); index++) {
            if (!isInsideCell(lastCaptureSourceCells.get(index), sources.get(index))) {
                return false;
            }
        }
        for (SectionKey dirty : DIRTY_SECTIONS) {
            if (lastRequiredSections.contains(dirty)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInsideCell(BlockPos cell, Vec3 position) {
        return cell.getX() == Mth.floor(position.x)
                && cell.getY() == Mth.floor(position.y)
                && cell.getZ() == Mth.floor(position.z);
    }

    private static void rememberCaptureCells(Vec3 listener, List<Vec3> sources) {
        lastCaptureListenerCell = BlockPos.containing(listener).immutable();
        List<BlockPos> cells = new ArrayList<>(sources.size());
        for (Vec3 source : sources) {
            cells.add(BlockPos.containing(source).immutable());
        }
        lastCaptureSourceCells = List.copyOf(cells);
    }

    private static void clearCaptureCells() {
        lastCaptureListenerCell = null;
        lastCaptureSourceCells = List.of();
    }

    private static Set<SectionKey> requiredSections(ClientLevel level, Vec3 listener, List<Vec3> sources) {
        Set<SectionKey> required = new HashSet<>();
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        double probeDistance = tuning.roomProbeDistance();
        BlockPos listenerCell = BlockPos.containing(listener);
        Vec3 listenerCenter = Vec3.atCenterOf(listenerCell);
        // Covers the probe sphere for every possible ear position in this block cell.
        double cellCornerDistance = Math.sqrt(3.0) * 0.5;
        addSphere(required, listenerCenter, probeDistance + cellCornerDistance);
        required.addAll(sparseProbeSections(level, listenerCenter, tuning));
        double probeDistanceSquared = probeDistance * probeDistance;
        for (Vec3 source : sources) {
            Vec3 sourceCenter = Vec3.atCenterOf(BlockPos.containing(source));
            // The listener sphere already contains the complete straight segment for
            // nearby sources. Adding a 3x3x3 corridor for each sound only creates and
            // hashes thousands of duplicate SectionKey objects on the game thread.
            if (sourceCenter.distanceToSqr(listenerCenter) > probeDistanceSquared) {
                addSectionCorridor(required, sourceCenter, listenerCenter);
            }
        }

        int minimumY = level.getSectionYFromSectionIndex(0) * 16;
        int minimumSectionY = Math.floorDiv(minimumY, 16);
        int maximumSectionY = Math.floorDiv(minimumY + level.getHeight() - 1, 16);
        required.removeIf(key -> key.y() < minimumSectionY || key.y() > maximumSectionY);
        return required;
    }

    /**
     * Extends only the directions which leave the dense listener sphere. Copying a
     * second complete sphere would make a 64-block probe roughly eight times larger;
     * section corridors retain the far walls needed by the worker while empty space
     * remains represented by a few cheap palette copies.
     */
    private static Set<SectionKey> sparseProbeSections(
            ClientLevel level,
            Vec3 listener,
            AcousticTuning tuning
    ) {
        float denseDistance = tuning.roomProbeDistance();
        float maximumDistance = tuning.adaptiveRoomProbeDistance();
        int rayCount = Math.min(256, tuning.roomRayCount());
        BlockPos listenerCell = BlockPos.containing(listener);
        if (listenerCell.equals(sparseProbeCell)
                && denseDistance == sparseProbeDenseDistance
                && maximumDistance == sparseProbeMaximumDistance
                && rayCount == sparseProbeRayCount) {
            return sparseProbeSections;
        }

        Set<SectionKey> sections = new HashSet<>();
        int elevationSamples = Math.max(4, (int) Math.round(Math.sqrt(rayCount / 4.0)));
        int azimuthSamples = Math.max(8, (int) Math.ceil(rayCount / (double) elevationSamples));
        int minimumY = level.getSectionYFromSectionIndex(0) * 16;
        int minimumSectionY = Math.floorDiv(minimumY, 16);
        int maximumSectionY = Math.floorDiv(minimumY + level.getHeight() - 1, 16);
        Map<SectionKey, Boolean> emptySections = new HashMap<>();
        for (int row = 0; row < elevationSamples; row++) {
            double y = -1.0 + (row + 0.5) * 2.0 / elevationSamples;
            double horizontal = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double phase = (row & 1) == 0 ? 0.0 : Math.PI / azimuthSamples;
            for (int column = 0; column < azimuthSamples; column++) {
                double azimuth = column * Math.PI * 2.0 / azimuthSamples + phase;
                Vec3 direction = new Vec3(
                        Math.cos(azimuth) * horizontal,
                        y,
                        Math.sin(azimuth) * horizontal
                );
                SectionKey previous = null;
                for (double distance = denseDistance + 4.0;
                     distance <= maximumDistance + 1.0E-6;
                     distance += 4.0) {
                    Vec3 point = listener.add(direction.scale(distance));
                    SectionKey key = SectionKey.fromBlock(
                            Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z)
                    );
                    if (key.equals(previous)) {
                        continue;
                    }
                    previous = key;
                    if (key.y() < minimumSectionY || key.y() > maximumSectionY
                            || !isAvailable(level, key)) {
                        break;
                    }
                    sections.add(key);
                    boolean empty = emptySections.computeIfAbsent(key, section -> {
                        LevelChunkSection live = liveSection(level, section);
                        return live == null || live.hasOnlyAir();
                    });
                    if (!empty) {
                        break;
                    }
                }
            }
        }
        sparseProbeCell = listenerCell.immutable();
        sparseProbeDenseDistance = denseDistance;
        sparseProbeMaximumDistance = maximumDistance;
        sparseProbeRayCount = rayCount;
        sparseProbeSections = Set.copyOf(sections);
        return sparseProbeSections;
    }

    private static void clearSparseProbe() {
        sparseProbeCell = null;
        sparseProbeDenseDistance = 0.0F;
        sparseProbeMaximumDistance = 0.0F;
        sparseProbeRayCount = 0;
        sparseProbeSections = Set.of();
    }

    private static void addSphere(Set<SectionKey> target, Vec3 center, double radius) {
        int minX = Math.floorDiv(Mth.floor(center.x - radius), 16);
        int minY = Math.floorDiv(Mth.floor(center.y - radius), 16);
        int minZ = Math.floorDiv(Mth.floor(center.z - radius), 16);
        int maxX = Math.floorDiv(Mth.floor(center.x + radius), 16);
        int maxY = Math.floorDiv(Mth.floor(center.y + radius), 16);
        int maxZ = Math.floorDiv(Mth.floor(center.z + radius), 16);
        double radiusSquared = radius * radius;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    double minimumX = x * 16.0;
                    double minimumY = y * 16.0;
                    double minimumZ = z * 16.0;
                    double closestX = Mth.clamp(center.x, minimumX, minimumX + 16.0);
                    double closestY = Mth.clamp(center.y, minimumY, minimumY + 16.0);
                    double closestZ = Mth.clamp(center.z, minimumZ, minimumZ + 16.0);
                    double dx = center.x - closestX;
                    double dy = center.y - closestY;
                    double dz = center.z - closestZ;
                    if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                        target.add(new SectionKey(x, y, z));
                    }
                }
            }
        }
    }

    private static void addSectionCorridor(Set<SectionKey> target, Vec3 from, Vec3 to) {
        double distance = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(distance / 8.0));
        for (int step = 0; step <= steps; step++) {
            Vec3 point = from.lerp(to, step / (double) steps);
            SectionKey center = SectionKey.fromBlock(Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z));
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    for (int x = -1; x <= 1; x++) {
                        target.add(new SectionKey(center.x() + x, center.y() + y, center.z() + z));
                    }
                }
            }
        }
    }

    private static boolean isAvailable(ClientLevel level, SectionKey key) {
        return liveSection(level, key) != null;
    }

    private static AcousticSection captureSection(ClientLevel level, SectionKey key) {
        LevelChunkSection live = liveSection(level, key);
        if (live == null) {
            throw new IllegalStateException("Acoustic section disappeared during capture: " + key);
        }
        AcousticSection snapshot = new AcousticSection(live);
        return snapshot;
    }

    private static LevelChunkSection liveSection(ClientLevel level, SectionKey key) {
        LiveSectionRegistry registry = LIVE_SECTIONS.get();
        return registry.level() == level ? registry.sections().get(key) : null;
    }

    private static void drainWorldChanges(ClientLevel level) {
        ChunkChange chunk;
        while ((chunk = CHUNK_CHANGES.poll()) != null) {
            if (chunk.level() == level) {
                markChunkDirtyNow(level, chunk.chunkX(), chunk.chunkZ());
            }
        }
        BlockChange change;
        while ((change = BLOCK_CHANGES.poll()) != null) {
            if (change.level() != level) {
                continue;
            }
            BlockPos pos = change.position();
            if (change.oldState() != null && change.newState() != null) {
                updateDisplacedFluid(level, pos, change.oldState(), change.newState());
            }
            markDirtySection(pos);
        }
    }

    private static void updateDisplacedFluid(
            ClientLevel level,
            BlockPos pos,
            BlockState oldState,
            BlockState newState
    ) {
        long packed = pos.asLong();
        boolean oldOccupied = !oldState.getCollisionShape(level, pos).isEmpty();
        boolean newOpen = newState.getCollisionShape(level, pos).isEmpty()
                && newState.getFluidState().isEmpty();
        if (oldOccupied && newOpen) {
            FluidState displaced = enclosedFluidBoundary(level, pos);
            if (displaced.isEmpty()) {
                DISPLACED_FLUIDS.remove(packed);
            } else {
                DISPLACED_FLUIDS.put(packed, displaced);
            }
        } else {
            DISPLACED_FLUIDS.remove(packed);
        }
        for (int[] offset : FACE_NEIGHBORS) {
            BlockPos neighbour = pos.offset(offset[0], offset[1], offset[2]);
            long neighbourKey = neighbour.asLong();
            if (!DISPLACED_FLUIDS.containsKey(neighbourKey)) {
                continue;
            }
            FluidState displaced = enclosedFluidBoundary(level, neighbour);
            if (displaced.isEmpty()) {
                DISPLACED_FLUIDS.remove(neighbourKey);
            } else {
                DISPLACED_FLUIDS.put(neighbourKey, displaced);
            }
        }
    }

    private record CaptureRequest(
            ClientLevel level,
            Vec3 listener,
            List<Vec3> sources,
            long epoch,
            long dirtyVersion
    ) {
    }

    private record BlockChange(
            ClientLevel level,
            BlockPos position,
            BlockState oldState,
            BlockState newState
    ) {
    }

    private record ChunkChange(ClientLevel level, int chunkX, int chunkZ) {
    }

    private record LiveSectionRegistry(
            ClientLevel level,
            ConcurrentHashMap<SectionKey, LevelChunkSection> sections
    ) {
    }
}
