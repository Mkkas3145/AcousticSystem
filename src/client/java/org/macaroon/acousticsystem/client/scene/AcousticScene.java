package org.macaroon.acousticsystem.client.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable world view safe to read from acoustic worker and sound threads. */
public final class AcousticScene implements BlockGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final FluidState EMPTY_FLUID = Fluids.EMPTY.defaultFluidState();

    private final long[] sectionKeys;
    private final AcousticSection[] sections;
    private final int[] sectionXs;
    private final int[] sectionYs;
    private final int[] sectionZs;
    private final int[] componentBases;
    private final int[] componentParents;
    private final boolean[] componentOpenBoundaries;
    private final PortalLink[][] portalLinks;
    private final SectionBvhNode collisionBvh;
    private final ConcurrentHashMap<LocalPortalFieldKey, LocalPortalField>
            localPortalFields = new ConcurrentHashMap<>();
    private final int sectionMask;
    private final int minY;
    private final int height;
    private final long revision;
    private final Object cacheIdentity;
    private final Map<Long, FluidState> displacedFluids;

    AcousticScene(
            Map<SectionKey, AcousticSection> sections,
            int minY,
            int height,
            long revision,
            Object cacheIdentity,
            Map<Long, FluidState> displacedFluids
    ) {
        int tableSize = 1;
        while (tableSize < Math.max(2, sections.size() * 2)) {
            tableSize <<= 1;
        }
        this.sectionKeys = new long[tableSize];
        this.sections = new AcousticSection[tableSize];
        this.sectionXs = new int[tableSize];
        this.sectionYs = new int[tableSize];
        this.sectionZs = new int[tableSize];
        this.sectionMask = tableSize - 1;
        for (Map.Entry<SectionKey, AcousticSection> entry : sections.entrySet()) {
            SectionKey key = entry.getKey();
            if (!entry.getValue().isDecoded()) {
                throw new IllegalStateException(
                        "Cannot publish an undecoded acoustic section: " + key
                );
            }
            long packed = packSection(key.x(), key.y(), key.z());
            int slot = tableSlot(packed);
            while (this.sections[slot] != null) {
                slot = (slot + 1) & sectionMask;
            }
            sectionKeys[slot] = packed;
            this.sections[slot] = entry.getValue();
            sectionXs[slot] = key.x();
            sectionYs[slot] = key.y();
            sectionZs[slot] = key.z();
        }
        AirConnectivity connectivity = buildAirConnectivity();
        componentBases = connectivity.componentBases();
        componentParents = connectivity.parents();
        componentOpenBoundaries = connectivity.openBoundaries();
        portalLinks = buildPortalLinks();
        collisionBvh = buildCollisionBvh();
        this.minY = minY;
        this.height = height;
        this.revision = revision;
        this.cacheIdentity = cacheIdentity;
        this.displacedFluids = Map.copyOf(displacedFluids);
    }

    public long revision() {
        return revision;
    }

    /** Stable identity shared by immutable snapshots of the same client world. */
    public Object cacheIdentity() {
        return cacheIdentity;
    }

    public boolean isCollisionShapeFullBlock(BlockPos pos, BlockState state) {
        AcousticSection section = section(pos.getX(), pos.getY(), pos.getZ());
        return section != null && section.isCollisionShapeFullBlock(this, pos, state);
    }

    public boolean isAir(int blockX, int blockY, int blockZ) {
        AcousticSection section = section(blockX, blockY, blockZ);
        return section == null || section.isAir(blockX, blockY, blockZ);
    }

    /** True when the cell has no collision volume and can carry an acoustic path. */
    public boolean isPropagationOpen(int blockX, int blockY, int blockZ) {
        AcousticSection section = section(blockX, blockY, blockZ);
        return section == null
                || section.isPropagationOpen(blockX, blockY, blockZ);
    }

    /** Dense immutable cell id used by the allocation-free propagation graph. */
    public int cellIndex(int blockX, int blockY, int blockZ) {
        int slot = sectionSlot(blockX >> 4, blockY >> 4, blockZ >> 4);
        if (slot < 0) {
            return -1;
        }
        int local = (blockX & 15) | (blockZ & 15) << 4 | (blockY & 15) << 8;
        return slot << 12 | local;
    }

    public int airCellIndex(int blockX, int blockY, int blockZ) {
        int index = cellIndex(blockX, blockY, blockZ);
        return index >= 0 && sections[index >>> 12].isPropagationOpen(
                blockX, blockY, blockZ
        ) ? index : -1;
    }

    public boolean isAirCellIndex(int cellIndex) {
        return cellIndex >= 0
                && sections[cellIndex >>> 12].isPropagationOpenIndex(
                cellIndex & 4095
        );
    }

    public int neighborCellIndex(int cellIndex, int dx, int dy, int dz) {
        int local = cellIndex & 4095;
        int localX = local & 15;
        int localY = local >>> 8 & 15;
        int localZ = local >>> 4 & 15;
        int nextX = localX + dx;
        int nextY = localY + dy;
        int nextZ = localZ + dz;
        if ((nextX | nextY | nextZ) >= 0
                && nextX < 16 && nextY < 16 && nextZ < 16) {
            return (cellIndex & ~4095)
                    | nextX | nextZ << 4 | nextY << 8;
        }
        return cellIndex(
                cellX(cellIndex) + dx,
                cellY(cellIndex) + dy,
                cellZ(cellIndex) + dz
        );
    }

    public int cellCapacity() {
        return sections.length << 12;
    }

    public int cellX(int cellIndex) {
        int slot = cellIndex >>> 12;
        return sectionXs[slot] * 16 + (cellIndex & 15);
    }

    public int cellY(int cellIndex) {
        int slot = cellIndex >>> 12;
        return sectionYs[slot] * 16 + (cellIndex >>> 8 & 15);
    }

    public int cellZ(int cellIndex) {
        int slot = cellIndex >>> 12;
        return sectionZs[slot] * 16 + (cellIndex >>> 4 & 15);
    }

    /** Returns -2 outside the captured graph, -1 for solid, or a stable component id. */
    public int airComponentId(int blockX, int blockY, int blockZ) {
        int slot = sectionSlot(blockX >> 4, blockY >> 4, blockZ >> 4);
        if (slot < 0) {
            return -2;
        }
        int local = sections[slot].airComponent(blockX, blockY, blockZ);
        return local < 0 ? -1 : componentParents[componentBases[slot] + local];
    }

    public boolean isAirComponentClosed(int componentId) {
        return componentId >= 0
                && componentId < componentOpenBoundaries.length
                && !componentOpenBoundaries[componentId];
    }

    /** Section-local air-region id used by the hierarchical portal graph. */
    public int portalRegionId(int blockX, int blockY, int blockZ) {
        int slot = sectionSlot(blockX >> 4, blockY >> 4, blockZ >> 4);
        if (slot < 0) {
            return -1;
        }
        int local = sections[slot].airComponent(blockX, blockY, blockZ);
        return local < 0 ? -1 : componentBases[slot] + local;
    }

    /**
     * Returns up to {@code maximumPaths} distinct hierarchical portal routes. Portal
     * A* runs on section boundary patches; each selected region leg is then rebuilt by
     * an exact local BFS, so the returned voxel chain is physically traversable.
     */
    public List<List<Long>> portalPaths(
            int sourceX,
            int sourceY,
            int sourceZ,
            int listenerX,
            int listenerY,
            int listenerZ,
            int maximumPaths
    ) {
        int sourceRegion = portalRegionId(sourceX, sourceY, sourceZ);
        int listenerRegion = portalRegionId(listenerX, listenerY, listenerZ);
        if (sourceRegion < 0 || listenerRegion < 0 || maximumPaths <= 0) {
            return List.of();
        }
        long sourceCell = BlockPos.asLong(sourceX, sourceY, sourceZ);
        long listenerCell = BlockPos.asLong(listenerX, listenerY, listenerZ);
        if (sourceRegion == listenerRegion) {
            List<Long> local = localPortalLeg(
                    sourceRegion, sourceCell, listenerCell
            );
            return local == null ? List.of() : List.of(local);
        }
        PriorityQueue<PortalState> frontier = new PriorityQueue<>(
                Comparator.comparingDouble(PortalState::priority)
                        .thenComparingDouble(PortalState::cost)
        );
        frontier.add(new PortalState(
                sourceRegion,
                sourceCell,
                0.0,
                manhattan(sourceCell, listenerCell),
                null,
                null
        ));
        int[] visits = new int[portalLinks.length];
        List<PortalState> completed = new ArrayList<>(maximumPaths);
        int expansionLimit = Math.max(64, portalLinks.length * maximumPaths * 4);
        int expanded = 0;
        while (!frontier.isEmpty()
                && completed.size() < maximumPaths
                && expanded++ < expansionLimit) {
            PortalState state = frontier.poll();
            if (visits[state.region()]++ >= maximumPaths) {
                continue;
            }
            if (state.region() == listenerRegion) {
                completed.add(state);
                continue;
            }
            for (PortalLink link : portalLinks[state.region()]) {
                if (containsRegion(state, link.toRegion())) {
                    continue;
                }
                double cost = state.cost()
                        + manhattan(state.cell(), link.fromCell()) + 1.0;
                frontier.add(new PortalState(
                        link.toRegion(),
                        link.toCell(),
                        cost,
                        cost + manhattan(link.toCell(), listenerCell),
                        state,
                        link
                ));
            }
        }
        if (completed.isEmpty()) {
            return List.of();
        }
        List<List<Long>> paths = new ArrayList<>(completed.size());
        for (PortalState goal : completed) {
            ArrayDeque<PortalLink> links = new ArrayDeque<>();
            for (PortalState cursor = goal;
                 cursor != null && cursor.via() != null;
                 cursor = cursor.previous()) {
                links.addFirst(cursor.via());
            }
            List<Long> stitched = new ArrayList<>();
            long current = sourceCell;
            int currentRegion = sourceRegion;
            boolean valid = true;
            for (PortalLink link : links) {
                List<Long> leg = localPortalLeg(
                        currentRegion, current, link.fromCell()
                );
                if (leg == null) {
                    valid = false;
                    break;
                }
                appendWithoutDuplicate(stitched, leg);
                appendWithoutDuplicate(stitched, List.of(link.toCell()));
                current = link.toCell();
                currentRegion = link.toRegion();
            }
            if (!valid) {
                continue;
            }
            List<Long> finalLeg = localPortalLeg(
                    listenerRegion, current, listenerCell
            );
            if (finalLeg != null) {
                appendWithoutDuplicate(stitched, finalLeg);
                paths.add(List.copyOf(stitched));
            }
        }
        paths.sort(Comparator.comparingInt(List::size));
        return List.copyOf(paths);
    }

    /** Returns 0 for air, 1 for a full collision cube, and 2 for a partial shape. */
    public int collisionClass(BlockPos pos) {
        AcousticSection section = section(pos.getX(), pos.getY(), pos.getZ());
        return section == null ? 0 : section.collisionClass(this, pos);
    }

    /**
     * Conservative broad phase over occupied 4x4x4 bricks. False proves that the
     * segment is clear; true is always followed by the exact block/VoxelShape query.
     */
    public boolean mayIntersectPotentialCollision(Vec3 from, Vec3 to) {
        return collisionBvh != null && collisionBvh.intersects(from, to);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        AcousticSection section = section(pos.getX(), pos.getY(), pos.getZ());
        return section == null ? AIR : section.block(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        AcousticSection section = section(pos.getX(), pos.getY(), pos.getZ());
        FluidState captured = section == null
                ? EMPTY_FLUID
                : section.fluid(pos.getX(), pos.getY(), pos.getZ());
        if (!captured.isEmpty()) {
            return captured;
        }
        return displacedFluids.getOrDefault(pos.asLong(), EMPTY_FLUID);
    }

    public int getMinY() {
        return minY;
    }

    public int getMinBuildHeight() {
        return minY;
    }

    @Override
    public int getHeight() {
        return height;
    }

    private AcousticSection section(int blockX, int blockY, int blockZ) {
        int slot = sectionSlot(blockX >> 4, blockY >> 4, blockZ >> 4);
        return slot < 0 ? null : sections[slot];
    }

    private SectionBvhNode buildCollisionBvh() {
        List<SectionBvhNode> leaves = new ArrayList<>();
        final int brickSize = 4;
        for (int slot = 0; slot < sections.length; slot++) {
            AcousticSection section = sections[slot];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int baseX = sectionXs[slot] << 4;
            int baseY = sectionYs[slot] << 4;
            int baseZ = sectionZs[slot] << 4;
            for (int localY = 0; localY < 16; localY += brickSize) {
                for (int localZ = 0; localZ < 16; localZ += brickSize) {
                    for (int localX = 0; localX < 16; localX += brickSize) {
                        if (!section.hasNonAirBrick(
                                localX, localY, localZ, brickSize
                        )) {
                            continue;
                        }
                        leaves.add(new SectionBvhNode(
                                baseX + localX,
                                baseY + localY,
                                baseZ + localZ,
                                baseX + localX + brickSize,
                                baseY + localY + brickSize,
                                baseZ + localZ + brickSize,
                                null,
                                null
                        ));
                    }
                }
            }
        }
        return buildCollisionBvh(leaves, 0, leaves.size());
    }

    private static SectionBvhNode buildCollisionBvh(
            List<SectionBvhNode> nodes,
            int start,
            int end
    ) {
        int count = end - start;
        if (count <= 0) {
            return null;
        }
        if (count == 1) {
            return nodes.get(start);
        }
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double minimumZ = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        double maximumZ = Double.NEGATIVE_INFINITY;
        for (int index = start; index < end; index++) {
            SectionBvhNode node = nodes.get(index);
            minimumX = Math.min(minimumX, node.minimumX());
            minimumY = Math.min(minimumY, node.minimumY());
            minimumZ = Math.min(minimumZ, node.minimumZ());
            maximumX = Math.max(maximumX, node.maximumX());
            maximumY = Math.max(maximumY, node.maximumY());
            maximumZ = Math.max(maximumZ, node.maximumZ());
        }
        double extentX = maximumX - minimumX;
        double extentY = maximumY - minimumY;
        double extentZ = maximumZ - minimumZ;
        int axis = extentX >= extentY && extentX >= extentZ
                ? 0 : extentY >= extentZ ? 1 : 2;
        nodes.subList(start, end).sort(Comparator.comparingDouble(node ->
                axis == 0
                        ? node.minimumX() + node.maximumX()
                        : axis == 1
                        ? node.minimumY() + node.maximumY()
                        : node.minimumZ() + node.maximumZ()
        ));
        int middle = start + count / 2;
        SectionBvhNode left = buildCollisionBvh(nodes, start, middle);
        SectionBvhNode right = buildCollisionBvh(nodes, middle, end);
        return new SectionBvhNode(
                Math.min(left.minimumX(), right.minimumX()),
                Math.min(left.minimumY(), right.minimumY()),
                Math.min(left.minimumZ(), right.minimumZ()),
                Math.max(left.maximumX(), right.maximumX()),
                Math.max(left.maximumY(), right.maximumY()),
                Math.max(left.maximumZ(), right.maximumZ()),
                left,
                right
        );
    }

    private int sectionSlot(int sectionX, int sectionY, int sectionZ) {
        long packed = packSection(sectionX, sectionY, sectionZ);
        int slot = tableSlot(packed);
        while (sections[slot] != null) {
            if (sectionKeys[slot] == packed) {
                return slot;
            }
            slot = (slot + 1) & sectionMask;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private PortalLink[][] buildPortalLinks() {
        List<PortalLink>[] links = new List[componentParents.length];
        for (int index = 0; index < links.length; index++) {
            links[index] = new ArrayList<>();
        }
        for (int slot = 0; slot < sections.length; slot++) {
            if (sections[slot] == null) {
                continue;
            }
            appendFacePortalPatches(slot, 0, links);
            appendFacePortalPatches(slot, 1, links);
            appendFacePortalPatches(slot, 2, links);
        }
        PortalLink[][] result = new PortalLink[links.length][];
        for (int index = 0; index < links.length; index++) {
            result[index] = links[index].toArray(PortalLink[]::new);
        }
        return result;
    }

    private void appendFacePortalPatches(
            int slot,
            int axis,
            List<PortalLink>[] links
    ) {
        int neighbourSlot = sectionSlot(
                sectionXs[slot] + (axis == 0 ? 1 : 0),
                sectionYs[slot] + (axis == 1 ? 1 : 0),
                sectionZs[slot] + (axis == 2 ? 1 : 0)
        );
        if (neighbourSlot < 0) {
            return;
        }
        int[] fromRegions = new int[256];
        int[] toRegions = new int[256];
        Arrays.fill(fromRegions, -1);
        Arrays.fill(toRegions, -1);
        for (int second = 0; second < 16; second++) {
            for (int first = 0; first < 16; first++) {
                int index = first | second << 4;
                int x = axis == 0 ? 15 : first;
                int y = axis == 1 ? 15 : axis == 0 ? first : second;
                int z = axis == 2 ? 15 : second;
                int fromLocal = sections[slot].airComponent(x, y, z);
                int toLocal = sections[neighbourSlot].airComponent(
                        axis == 0 ? 0 : x,
                        axis == 1 ? 0 : y,
                        axis == 2 ? 0 : z
                );
                if (fromLocal >= 0 && toLocal >= 0) {
                    fromRegions[index] = componentBases[slot] + fromLocal;
                    toRegions[index] = componentBases[neighbourSlot] + toLocal;
                }
            }
        }
        boolean[] visited = new boolean[256];
        int[] queue = new int[256];
        int[] patch = new int[256];
        for (int seed = 0; seed < 256; seed++) {
            if (visited[seed] || fromRegions[seed] < 0) {
                continue;
            }
            int fromRegion = fromRegions[seed];
            int toRegion = toRegions[seed];
            int head = 0;
            int tail = 0;
            int patchSize = 0;
            queue[tail++] = seed;
            visited[seed] = true;
            double sumFirst = 0.0;
            double sumSecond = 0.0;
            while (head < tail) {
                int cell = queue[head++];
                patch[patchSize++] = cell;
                int first = cell & 15;
                int second = cell >>> 4;
                sumFirst += first;
                sumSecond += second;
                if (first > 0) tail = enqueuePortalCell(
                        cell - 1, fromRegion, toRegion,
                        fromRegions, toRegions, visited, queue, tail
                );
                if (first < 15) tail = enqueuePortalCell(
                        cell + 1, fromRegion, toRegion,
                        fromRegions, toRegions, visited, queue, tail
                );
                if (second > 0) tail = enqueuePortalCell(
                        cell - 16, fromRegion, toRegion,
                        fromRegions, toRegions, visited, queue, tail
                );
                if (second < 15) tail = enqueuePortalCell(
                        cell + 16, fromRegion, toRegion,
                        fromRegions, toRegions, visited, queue, tail
                );
            }
            double centerFirst = sumFirst / patchSize;
            double centerSecond = sumSecond / patchSize;
            int representative = patch[0];
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int index = 0; index < patchSize; index++) {
                int cell = patch[index];
                double df = (cell & 15) - centerFirst;
                double ds = (cell >>> 4) - centerSecond;
                double distance = df * df + ds * ds;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    representative = cell;
                }
            }
            int first = representative & 15;
            int second = representative >>> 4;
            int localX = axis == 0 ? 15 : first;
            int localY = axis == 1 ? 15 : axis == 0 ? first : second;
            int localZ = axis == 2 ? 15 : second;
            int worldX = sectionXs[slot] * 16 + localX;
            int worldY = sectionYs[slot] * 16 + localY;
            int worldZ = sectionZs[slot] * 16 + localZ;
            long fromCell = BlockPos.asLong(worldX, worldY, worldZ);
            long toCell = BlockPos.asLong(
                    worldX + (axis == 0 ? 1 : 0),
                    worldY + (axis == 1 ? 1 : 0),
                    worldZ + (axis == 2 ? 1 : 0)
            );
            links[fromRegion].add(new PortalLink(
                    fromRegion, toRegion, fromCell, toCell
            ));
            links[toRegion].add(new PortalLink(
                    toRegion, fromRegion, toCell, fromCell
            ));
        }
    }

    private static int enqueuePortalCell(
            int cell,
            int fromRegion,
            int toRegion,
            int[] fromRegions,
            int[] toRegions,
            boolean[] visited,
            int[] queue,
            int tail
    ) {
        if (!visited[cell]
                && fromRegions[cell] == fromRegion
                && toRegions[cell] == toRegion) {
            visited[cell] = true;
            queue[tail++] = cell;
        }
        return tail;
    }

    private List<Long> localPortalLeg(
            int region,
            long fromCell,
            long toCell
    ) {
        if (fromCell == toCell) {
            return List.of(fromCell);
        }
        int from = cellIndex(
                BlockPos.getX(fromCell), BlockPos.getY(fromCell), BlockPos.getZ(fromCell)
        );
        int to = cellIndex(
                BlockPos.getX(toCell), BlockPos.getY(toCell), BlockPos.getZ(toCell)
        );
        if (from < 0 || to < 0
                || portalRegionId(
                BlockPos.getX(fromCell), BlockPos.getY(fromCell), BlockPos.getZ(fromCell)
        ) != region
                || portalRegionId(
                BlockPos.getX(toCell), BlockPos.getY(toCell), BlockPos.getZ(toCell)
                ) != region) {
            return null;
        }
        boolean toIsPortal = isPortalCell(region, toCell);
        boolean fromIsPortal = isPortalCell(region, fromCell);
        if (toIsPortal || fromIsPortal) {
            long root = toIsPortal ? toCell : fromCell;
            long start = toIsPortal ? fromCell : toCell;
            LocalPortalField field = localPortalFields.computeIfAbsent(
                    new LocalPortalFieldKey(region, root),
                    ignored -> buildLocalPortalField(region, root)
            );
            List<Long> route = routeToPortalRoot(start, root, field);
            if (route == null) {
                return null;
            }
            if (toIsPortal) {
                return route;
            }
            ArrayList<Long> reversed = new ArrayList<>(route);
            java.util.Collections.reverse(reversed);
            return List.copyOf(reversed);
        }
        int[] previous = new int[4096];
        Arrays.fill(previous, -2);
        int[] queue = new int[4096];
        int head = 0;
        int tail = 0;
        queue[tail++] = from;
        previous[from & 4095] = -1;
        while (head < tail && previous[to & 4095] == -2) {
            int cell = queue[head++];
            for (int axis = 0; axis < 6; axis++) {
                int dx = axis == 0 ? 1 : axis == 1 ? -1 : 0;
                int dy = axis == 2 ? 1 : axis == 3 ? -1 : 0;
                int dz = axis == 4 ? 1 : axis == 5 ? -1 : 0;
                int next = neighborCellIndex(cell, dx, dy, dz);
                if (next < 0 || (next >>> 12) != (from >>> 12)) {
                    continue;
                }
                int local = next & 4095;
                if (previous[local] != -2
                        || !sections[next >>> 12].isPropagationOpenIndex(local)
                        || componentBases[next >>> 12]
                        + sections[next >>> 12].airComponent(
                        cellX(next), cellY(next), cellZ(next)
                ) != region) {
                    continue;
                }
                previous[local] = cell;
                queue[tail++] = next;
            }
        }
        if (previous[to & 4095] == -2) {
            return null;
        }
        ArrayDeque<Long> reversed = new ArrayDeque<>();
        for (int cell = to; cell >= 0; cell = previous[cell & 4095]) {
            reversed.addFirst(BlockPos.asLong(
                    cellX(cell), cellY(cell), cellZ(cell)
            ));
        }
        return List.copyOf(reversed);
    }

    private boolean isPortalCell(int region, long cell) {
        for (PortalLink link : portalLinks[region]) {
            if (link.fromCell() == cell) {
                return true;
            }
        }
        return false;
    }

    private LocalPortalField buildLocalPortalField(int region, long rootCell) {
        int root = cellIndex(
                BlockPos.getX(rootCell),
                BlockPos.getY(rootCell),
                BlockPos.getZ(rootCell)
        );
        int[] nextTowardRoot = new int[4096];
        Arrays.fill(nextTowardRoot, -2);
        if (root < 0) {
            return new LocalPortalField(-1, nextTowardRoot);
        }
        int[] queue = new int[4096];
        int head = 0;
        int tail = 0;
        queue[tail++] = root;
        nextTowardRoot[root & 4095] = -1;
        while (head < tail) {
            int cell = queue[head++];
            for (int axis = 0; axis < 6; axis++) {
                int dx = axis == 0 ? 1 : axis == 1 ? -1 : 0;
                int dy = axis == 2 ? 1 : axis == 3 ? -1 : 0;
                int dz = axis == 4 ? 1 : axis == 5 ? -1 : 0;
                int next = neighborCellIndex(cell, dx, dy, dz);
                if (next < 0 || (next >>> 12) != (root >>> 12)) {
                    continue;
                }
                int local = next & 4095;
                if (nextTowardRoot[local] != -2
                        || !sections[next >>> 12].isPropagationOpenIndex(local)
                        || componentBases[next >>> 12]
                        + sections[next >>> 12].airComponent(
                        cellX(next), cellY(next), cellZ(next)
                ) != region) {
                    continue;
                }
                nextTowardRoot[local] = cell;
                queue[tail++] = next;
            }
        }
        return new LocalPortalField(root, nextTowardRoot);
    }

    private List<Long> routeToPortalRoot(
            long startCell,
            long rootCell,
            LocalPortalField field
    ) {
        int start = cellIndex(
                BlockPos.getX(startCell),
                BlockPos.getY(startCell),
                BlockPos.getZ(startCell)
        );
        if (start < 0 || field.root() < 0
                || field.nextTowardRoot()[start & 4095] == -2) {
            return null;
        }
        ArrayList<Long> route = new ArrayList<>();
        int cell = start;
        while (cell >= 0) {
            route.add(BlockPos.asLong(cellX(cell), cellY(cell), cellZ(cell)));
            cell = field.nextTowardRoot()[cell & 4095];
        }
        if (route.isEmpty() || route.get(route.size() - 1) != rootCell) {
            return null;
        }
        return List.copyOf(route);
    }

    private static boolean containsRegion(PortalState state, int region) {
        for (PortalState cursor = state; cursor != null; cursor = cursor.previous()) {
            if (cursor.region() == region) {
                return true;
            }
        }
        return false;
    }

    private static double manhattan(long first, long second) {
        return Math.abs(BlockPos.getX(first) - BlockPos.getX(second))
                + Math.abs(BlockPos.getY(first) - BlockPos.getY(second))
                + Math.abs(BlockPos.getZ(first) - BlockPos.getZ(second));
    }

    private static void appendWithoutDuplicate(List<Long> target, List<Long> values) {
        for (long value : values) {
            if (target.isEmpty() || target.get(target.size() - 1) != value) {
                target.add(value);
            }
        }
    }

    private AirConnectivity buildAirConnectivity() {
        int[] bases = new int[sections.length];
        int totalComponents = 0;
        for (int slot = 0; slot < sections.length; slot++) {
            bases[slot] = totalComponents;
            if (sections[slot] != null) {
                totalComponents += sections[slot].airComponentCount();
            }
        }
        int[] parents = new int[totalComponents];
        boolean[] open = new boolean[totalComponents];
        for (int component = 0; component < totalComponents; component++) {
            parents[component] = component;
        }
        for (int slot = 0; slot < sections.length; slot++) {
            if (sections[slot] == null) {
                continue;
            }
            connectSectionFace(slot, 0, 1, bases, parents, open);
            connectSectionFace(slot, 0, -1, bases, parents, open);
            connectSectionFace(slot, 1, 1, bases, parents, open);
            connectSectionFace(slot, 1, -1, bases, parents, open);
            connectSectionFace(slot, 2, 1, bases, parents, open);
            connectSectionFace(slot, 2, -1, bases, parents, open);
        }
        for (int component = 0; component < totalComponents; component++) {
            int root = find(parents, component);
            if (open[component]) {
                open[root] = true;
            }
            parents[component] = root;
        }
        return new AirConnectivity(bases, parents, open);
    }

    private void connectSectionFace(
            int slot,
            int axis,
            int direction,
            int[] bases,
            int[] parents,
            boolean[] open
    ) {
        int neighbourX = sectionXs[slot] + (axis == 0 ? direction : 0);
        int neighbourY = sectionYs[slot] + (axis == 1 ? direction : 0);
        int neighbourZ = sectionZs[slot] + (axis == 2 ? direction : 0);
        int neighbourSlot = sectionSlot(neighbourX, neighbourY, neighbourZ);
        AcousticSection current = sections[slot];
        for (int first = 0; first < 16; first++) {
            for (int second = 0; second < 16; second++) {
                int x = axis == 0 ? (direction > 0 ? 15 : 0) : first;
                int y = axis == 1 ? (direction > 0 ? 15 : 0)
                        : axis == 0 ? first : second;
                int z = axis == 2 ? (direction > 0 ? 15 : 0) : second;
                int localComponent = current.airComponent(x, y, z);
                if (localComponent < 0) {
                    continue;
                }
                int component = bases[slot] + localComponent;
                if (neighbourSlot < 0) {
                    open[component] = true;
                    continue;
                }
                if (direction < 0) {
                    continue;
                }
                int neighbourLocal = sections[neighbourSlot].airComponent(
                        axis == 0 ? 0 : x,
                        axis == 1 ? 0 : y,
                        axis == 2 ? 0 : z
                );
                if (neighbourLocal >= 0) {
                    union(
                            parents,
                            component,
                            bases[neighbourSlot] + neighbourLocal
                    );
                }
            }
        }
    }

    private static int find(int[] parents, int component) {
        int root = component;
        while (parents[root] != root) {
            root = parents[root];
        }
        while (parents[component] != component) {
            int next = parents[component];
            parents[component] = root;
            component = next;
        }
        return root;
    }

    private static void union(int[] parents, int first, int second) {
        int firstRoot = find(parents, first);
        int secondRoot = find(parents, second);
        if (firstRoot != secondRoot) {
            parents[secondRoot] = firstRoot;
        }
    }

    private int tableSlot(long packed) {
        long mixed = packed;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        return (int) mixed & sectionMask;
    }

    private static long packSection(int x, int y, int z) {
        return ((long) x & 0x3FFFFFL) << 42
                | ((long) z & 0x3FFFFFL) << 20
                | ((long) y & 0xFFFFFL);
    }

    record SectionKey(int x, int y, int z) {
        static SectionKey fromBlock(int x, int y, int z) {
            return new SectionKey(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
        }
    }

    private record AirConnectivity(
            int[] componentBases,
            int[] parents,
            boolean[] openBoundaries
    ) {
    }

    private record PortalLink(
            int fromRegion,
            int toRegion,
            long fromCell,
            long toCell
    ) {
    }

    private record PortalState(
            int region,
            long cell,
            double cost,
            double priority,
            PortalState previous,
            PortalLink via
    ) {
    }

    private record LocalPortalFieldKey(int region, long rootCell) {
    }

    private record LocalPortalField(int root, int[] nextTowardRoot) {
    }

    private record SectionBvhNode(
            double minimumX,
            double minimumY,
            double minimumZ,
            double maximumX,
            double maximumY,
            double maximumZ,
            SectionBvhNode left,
            SectionBvhNode right
    ) {
        private boolean intersects(Vec3 from, Vec3 to) {
            if (!segmentIntersects(this, from, to)) {
                return false;
            }
            if (left == null) {
                return true;
            }
            return left.intersects(from, to) || right.intersects(from, to);
        }

        private static boolean segmentIntersects(
                SectionBvhNode node,
                Vec3 from,
                Vec3 to
        ) {
            double minimumTime = 0.0;
            double maximumTime = 1.0;
            double direction = to.x - from.x;
            if (Math.abs(direction) < 1.0E-12) {
                if (from.x < node.minimumX || from.x > node.maximumX) {
                    return false;
                }
            } else {
                double first = (node.minimumX - from.x) / direction;
                double second = (node.maximumX - from.x) / direction;
                minimumTime = Math.max(minimumTime, Math.min(first, second));
                maximumTime = Math.min(maximumTime, Math.max(first, second));
                if (minimumTime > maximumTime) return false;
            }
            direction = to.y - from.y;
            if (Math.abs(direction) < 1.0E-12) {
                if (from.y < node.minimumY || from.y > node.maximumY) {
                    return false;
                }
            } else {
                double first = (node.minimumY - from.y) / direction;
                double second = (node.maximumY - from.y) / direction;
                minimumTime = Math.max(minimumTime, Math.min(first, second));
                maximumTime = Math.min(maximumTime, Math.max(first, second));
                if (minimumTime > maximumTime) return false;
            }
            direction = to.z - from.z;
            if (Math.abs(direction) < 1.0E-12) {
                return from.z >= node.minimumZ && from.z <= node.maximumZ;
            }
            double first = (node.minimumZ - from.z) / direction;
            double second = (node.maximumZ - from.z) / direction;
            minimumTime = Math.max(minimumTime, Math.min(first, second));
            maximumTime = Math.min(maximumTime, Math.max(first, second));
            return minimumTime <= maximumTime;
        }
    }
}
