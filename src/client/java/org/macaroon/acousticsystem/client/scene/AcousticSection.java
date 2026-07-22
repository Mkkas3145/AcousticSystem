package org.macaroon.acousticsystem.client.scene;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

final class AcousticSection {
    private final BlockState[] blocks = new BlockState[16 * 16 * 16];
    private final FluidState[] fluids = new FluidState[16 * 16 * 16];
    private final byte[] fullCubeClassification = new byte[16 * 16 * 16];

    AcousticSection(LevelChunkSection section) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = index(x, y, z);
                    blocks[index] = section.getBlockState(x, y, z);
                    fluids[index] = section.getFluidState(x, y, z);
                }
            }
        }
    }

    BlockState block(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    FluidState fluid(int x, int y, int z) {
        return fluids[index(x, y, z)];
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

    private static int index(int x, int y, int z) {
        return (x & 15) | (z & 15) << 4 | (y & 15) << 8;
    }
}
