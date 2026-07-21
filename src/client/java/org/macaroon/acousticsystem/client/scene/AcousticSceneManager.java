package org.macaroon.acousticsystem.client.scene;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.macaroon.acousticsystem.client.material.AcousticTuning;
import org.macaroon.acousticsystem.client.scene.AcousticScene.SectionKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread scene capture with copy-on-write section reuse. */
public final class AcousticSceneManager {
    private static final Map<SectionKey, AcousticSection> SECTION_CACHE = new HashMap<>();
    private static final Map<SectionKey, Long> LAST_USED_CAPTURE = new HashMap<>();
    private static final Set<SectionKey> DIRTY_SECTIONS = new HashSet<>();
    private static final long UNUSED_CAPTURE_RETENTION = 400L;

    private static ClientLevel currentLevel;
    private static long revision;
    private static long captureSequence;
    private static Set<SectionKey> lastRequiredSections = Set.of();
    private static AcousticScene lastScene;
    private static BlockPos sparseProbeCell;
    private static float sparseProbeDenseDistance;
    private static float sparseProbeMaximumDistance;
    private static int sparseProbeRayCount;
    private static Set<SectionKey> sparseProbeSections = Set.of();

    private AcousticSceneManager() {
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
        double probeDistance = AcousticMaterialRegistry.tuning().roomProbeDistance();
        if (currentLevel == level
                && lastScene != null
                && lastScene == preferredScene
                && listener.distanceTo(source) <= probeDistance) {
            boolean clean = true;
            for (SectionKey key : DIRTY_SECTIONS) {
                if (lastRequiredSections.contains(key)) {
                    clean = false;
                    break;
                }
            }
            if (clean) {
                return lastScene;
            }
        }
        return capture(level, listener, List.of(source));
    }

    public static AcousticScene capture(ClientLevel level, Vec3 listener, List<Vec3> sources) {
        if (currentLevel != level) {
            currentLevel = level;
            SECTION_CACHE.clear();
            LAST_USED_CAPTURE.clear();
            DIRTY_SECTIONS.clear();
            lastRequiredSections = Set.of();
            lastScene = null;
            clearSparseProbe();
            revision++;
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
            return lastScene;
        }

        Map<SectionKey, AcousticSection> sceneSections = new HashMap<>(required.size());
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
            sceneSections.put(key, section);
            LAST_USED_CAPTURE.put(key, currentCapture);
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
                level.getMinY(),
                level.getHeight(),
                revision
        );
        lastRequiredSections = Set.copyOf(required);
        lastScene = scene;
        return scene;
    }

    public static void markDirty(ClientLevel level, BlockPos pos) {
        if (currentLevel != level) {
            return;
        }
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

    public static void clear() {
        currentLevel = null;
        SECTION_CACHE.clear();
        LAST_USED_CAPTURE.clear();
        DIRTY_SECTIONS.clear();
        lastRequiredSections = Set.of();
        lastScene = null;
        clearSparseProbe();
        revision++;
    }

    public static void markChunkDirty(ClientLevel level, int chunkX, int chunkZ) {
        if (currentLevel != level) {
            return;
        }
        int minimumSectionY = Math.floorDiv(level.getMinY(), 16);
        int maximumSectionY = Math.floorDiv(level.getMinY() + level.getHeight() - 1, 16);
        for (int sectionY = minimumSectionY; sectionY <= maximumSectionY; sectionY++) {
            DIRTY_SECTIONS.add(new SectionKey(chunkX, sectionY, chunkZ));
        }
    }

    public static void markSectionDirty(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        if (currentLevel == level) {
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
        for (int y = minSectionY; y <= maxSectionY; y++) {
            for (int z = minSectionZ; z <= maxSectionZ; z++) {
                for (int x = minSectionX; x <= maxSectionX; x++) {
                    DIRTY_SECTIONS.add(new SectionKey(x, y, z));
                }
            }
        }
    }

    private static Set<SectionKey> requiredSections(ClientLevel level, Vec3 listener, List<Vec3> sources) {
        Set<SectionKey> required = new HashSet<>();
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        double probeDistance = tuning.roomProbeDistance();
        addSphere(required, listener, probeDistance);
        required.addAll(sparseProbeSections(level, listener, tuning));
        double probeDistanceSquared = probeDistance * probeDistance;
        for (Vec3 source : sources) {
            // The listener sphere already contains the complete straight segment for
            // nearby sources. Adding a 3x3x3 corridor for each sound only creates and
            // hashes thousands of duplicate SectionKey objects on the game thread.
            if (source.distanceToSqr(listener) > probeDistanceSquared) {
                addSectionCorridor(required, source, listener);
            }
        }

        int minimumSectionY = Math.floorDiv(level.getMinY(), 16);
        int maximumSectionY = Math.floorDiv(level.getMinY() + level.getHeight() - 1, 16);
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
        int minimumSectionY = Math.floorDiv(level.getMinY(), 16);
        int maximumSectionY = Math.floorDiv(level.getMinY() + level.getHeight() - 1, 16);
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
                        int sectionIndex = level.getSectionIndexFromSectionY(section.y());
                        return level.getChunk(section.x(), section.z())
                                .getSection(sectionIndex)
                                .hasOnlyAir();
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
        return level.hasChunk(key.x(), key.z());
    }

    private static AcousticSection captureSection(ClientLevel level, SectionKey key) {
        int sectionIndex = level.getSectionIndexFromSectionY(key.y());
        return new AcousticSection(level.getChunk(key.x(), key.z()).getSection(sectionIndex).copy());
    }
}
