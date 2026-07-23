package org.macaroon.acousticsystem.client.scene;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.Arrays;
import java.util.IdentityHashMap;

final class AcousticSection {
    private final PalettedContainer<BlockState> palette;
    private final byte[] fullCubeClassification = new byte[16 * 16 * 16];
    private volatile DecodedSection decoded;

    AcousticSection(LevelChunkSection section) {
        // Capture owns a compact palette copy; the scene manager expands it completely
        // before publishing the containing immutable scene to propagation workers.
        palette = section.getStates().copy();
    }

    /** Synthetic decoded section used by connectivity regression tests. */
    AcousticSection(long[] airMask) {
        palette = null;
        ComponentLabels labels = labelAirComponents(airMask);
        BlockState[] blocks = new BlockState[16 * 16 * 16];
        FluidState[] fluids = new FluidState[16 * 16 * 16];
        for (int index = 0; index < blocks.length; index++) {
            blocks[index] = (airMask[index >>> 6] & 1L << (index & 63)) != 0L
                    ? Blocks.AIR.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
            fluids[index] = Fluids.EMPTY.defaultFluidState();
        }
        decoded = new DecodedSection(
                blocks,
                fluids,
                airMask.clone(),
                airMask.clone(),
                labels.components(),
                labels.count()
        );
    }

    /** Synthetic state-backed section used by topology regression fixtures. */
    AcousticSection(BlockState[] sourceBlocks) {
        if (sourceBlocks.length != 16 * 16 * 16) {
            throw new IllegalArgumentException("Expected exactly 4096 block states");
        }
        palette = null;
        BlockState[] blocks = sourceBlocks.clone();
        FluidState[] fluids = new FluidState[blocks.length];
        long[] airMask = new long[blocks.length >>> 6];
        long[] propagationMask = new long[blocks.length >>> 6];
        IdentityHashMap<BlockState, Boolean> propagationStates = new IdentityHashMap<>();
        for (int index = 0; index < blocks.length; index++) {
            BlockState state = blocks[index];
            fluids[index] = state.getFluidState();
            if (state.isAir()) {
                airMask[index >>> 6] |= 1L << (index & 63);
            }
            if (isPropagationOpen(state, propagationStates)) {
                propagationMask[index >>> 6] |= 1L << (index & 63);
            }
        }
        ComponentLabels labels = labelAirComponents(propagationMask);
        decoded = new DecodedSection(
                blocks, fluids, airMask, propagationMask,
                labels.components(), labels.count()
        );
    }

    void decode() {
        BlockState[] blocks = new BlockState[16 * 16 * 16];
        FluidState[] fluids = new FluidState[16 * 16 * 16];
        long[] airMask = new long[(16 * 16 * 16) >>> 6];
        long[] propagationMask = new long[(16 * 16 * 16) >>> 6];
        IdentityHashMap<BlockState, Boolean> propagationStates = new IdentityHashMap<>();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = index(x, y, z);
                    BlockState state = palette.get(x, y, z);
                    blocks[index] = state;
                    fluids[index] = state.getFluidState();
                    if (state.isAir()) {
                        airMask[index >>> 6] |= 1L << (index & 63);
                    }
                    if (isPropagationOpen(state, propagationStates)) {
                        propagationMask[index >>> 6] |= 1L << (index & 63);
                    }
                }
            }
        }
        ComponentLabels labels = labelAirComponents(propagationMask);
        // One volatile publication exposes every complete array atomically. Published
        // scenes therefore use only these flat immutable arrays, never the palette.
        decoded = new DecodedSection(
                blocks, fluids, airMask, propagationMask,
                labels.components(), labels.count()
        );
    }

    private static boolean isPropagationOpen(
            BlockState state,
            IdentityHashMap<BlockState, Boolean> cache
    ) {
        if (state.isAir()) {
            return true;
        }
        Boolean known = cache.get(state);
        if (known != null) {
            return known;
        }
        boolean open = state.getCollisionShape(
                EmptyBlockGetter.INSTANCE, BlockPos.ZERO
        ).isEmpty();
        cache.put(state, open);
        return open;
    }

    private static ComponentLabels labelAirComponents(long[] airMask) {
        short[] airComponents = new short[16 * 16 * 16];
        Arrays.fill(airComponents, (short) -1);
        int[] queue = new int[16 * 16 * 16];
        int componentCount = 0;
        for (int seed = 0; seed < airComponents.length; seed++) {
            if (airComponents[seed] != -1
                    || (airMask[seed >>> 6] & 1L << (seed & 63)) == 0L) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = seed;
            airComponents[seed] = (short) componentCount;
            while (head < tail) {
                int cell = queue[head++];
                int x = cell & 15;
                int z = cell >>> 4 & 15;
                int y = cell >>> 8;
                if (x > 0) tail = enqueueAirComponent(
                        cell - 1, componentCount, airMask, airComponents, queue, tail
                );
                if (x < 15) tail = enqueueAirComponent(
                        cell + 1, componentCount, airMask, airComponents, queue, tail
                );
                if (z > 0) tail = enqueueAirComponent(
                        cell - 16, componentCount, airMask, airComponents, queue, tail
                );
                if (z < 15) tail = enqueueAirComponent(
                        cell + 16, componentCount, airMask, airComponents, queue, tail
                );
                if (y > 0) tail = enqueueAirComponent(
                        cell - 256, componentCount, airMask, airComponents, queue, tail
                );
                if (y < 15) tail = enqueueAirComponent(
                        cell + 256, componentCount, airMask, airComponents, queue, tail
                );
            }
            componentCount++;
        }
        return new ComponentLabels(airComponents, componentCount);
    }

    boolean isDecoded() {
        return decoded != null;
    }

    int airComponent(int x, int y, int z) {
        DecodedSection ready = decoded;
        return ready == null ? -1 : ready.airComponents()[index(x, y, z)];
    }

    int airComponentCount() {
        DecodedSection ready = decoded;
        return ready == null ? 0 : ready.airComponentCount();
    }

    BlockState block(int x, int y, int z) {
        DecodedSection ready = decoded;
        return ready == null
                ? palette.get(local(x), local(y), local(z))
                : ready.blocks()[index(x, y, z)];
    }

    FluidState fluid(int x, int y, int z) {
        DecodedSection ready = decoded;
        return ready == null
                ? palette.get(local(x), local(y), local(z)).getFluidState()
                : ready.fluids()[index(x, y, z)];
    }

    boolean isAir(int x, int y, int z) {
        DecodedSection ready = decoded;
        if (ready == null) {
            return palette.get(local(x), local(y), local(z)).isAir();
        }
        int index = index(x, y, z);
        return (ready.airMask()[index >>> 6] & 1L << (index & 63)) != 0L;
    }

    boolean isAirIndex(int index) {
        DecodedSection ready = decoded;
        return ready != null
                && (ready.airMask()[index >>> 6] & 1L << (index & 63)) != 0L;
    }

    boolean isPropagationOpen(int x, int y, int z) {
        DecodedSection ready = decoded;
        if (ready == null) {
            return false;
        }
        int index = index(x, y, z);
        return (ready.propagationMask()[index >>> 6]
                & 1L << (index & 63)) != 0L;
    }

    boolean isPropagationOpenIndex(int index) {
        DecodedSection ready = decoded;
        return ready != null
                && (ready.propagationMask()[index >>> 6]
                & 1L << (index & 63)) != 0L;
    }

    boolean hasOnlyAir() {
        DecodedSection ready = decoded;
        if (ready == null) {
            return false;
        }
        for (long word : ready.airMask()) {
            if (word != -1L) {
                return false;
            }
        }
        return true;
    }

    /** Conservative occupancy query used while constructing the immutable scene BVH. */
    boolean hasNonAirBrick(int minimumX, int minimumY, int minimumZ, int size) {
        DecodedSection ready = decoded;
        if (ready == null) {
            return true;
        }
        long[] air = ready.airMask();
        int maximumX = Math.min(16, minimumX + size);
        int maximumY = Math.min(16, minimumY + size);
        int maximumZ = Math.min(16, minimumZ + size);
        for (int y = minimumY; y < maximumY; y++) {
            for (int z = minimumZ; z < maximumZ; z++) {
                for (int x = minimumX; x < maximumX; x++) {
                    int cell = x | z << 4 | y << 8;
                    if ((air[cell >>> 6] & 1L << (cell & 63)) == 0L) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean isCollisionShapeFullBlock(BlockGetter level, BlockPos pos, BlockState state) {
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;
        int index = localX | localZ << 4 | localY << 8;
        byte cached = fullCubeClassification[index];
        if (cached != 0) {
            return cached == 1;
        }
        boolean fullCube = state.isCollisionShapeFullBlock(level, pos);
        // Benign duplicate computation is preferable to a lock in worker ray traversal;
        // byte stores are atomic and the immutable scene always yields the same answer.
        fullCubeClassification[index] = (byte) (fullCube ? 1 : 2);
        return fullCube;
    }

    /** 0 = air, 1 = full cube, 2 = partial/non-colliding shape. */
    int collisionClass(BlockGetter level, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (isAir(x, y, z)) {
            return 0;
        }
        int index = index(x, y, z);
        byte cached = fullCubeClassification[index];
        if (cached != 0) {
            return cached;
        }
        BlockState state = block(x, y, z);
        boolean fullCube = state.isCollisionShapeFullBlock(level, pos);
        byte classification = (byte) (fullCube ? 1 : 2);
        fullCubeClassification[index] = classification;
        return classification;
    }

    private static int index(int x, int y, int z) {
        return local(x) | local(z) << 4 | local(y) << 8;
    }

    private static int enqueueAirComponent(
            int cell,
            int component,
            long[] airMask,
            short[] components,
            int[] queue,
            int tail
    ) {
        if (components[cell] != -1
                || (airMask[cell >>> 6] & 1L << (cell & 63)) == 0L) {
            return tail;
        }
        components[cell] = (short) component;
        queue[tail] = cell;
        return tail + 1;
    }

    static int local(int coordinate) {
        return coordinate & 15;
    }

    private record DecodedSection(
            BlockState[] blocks,
            FluidState[] fluids,
            long[] airMask,
            long[] propagationMask,
            short[] airComponents,
            int airComponentCount
    ) {
    }

    private record ComponentLabels(short[] components, int count) {
    }
}
