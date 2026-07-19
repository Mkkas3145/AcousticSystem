package org.macaroon.acousticsystem.client.scene;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

final class AcousticSection {
    private final LevelChunkSection section;
    private final byte[] fullCubeClassification = new byte[16 * 16 * 16];

    AcousticSection(LevelChunkSection section) {
        this.section = section;
    }

    BlockState block(int x, int y, int z) {
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    FluidState fluid(int x, int y, int z) {
        return section.getFluidState(x & 15, y & 15, z & 15);
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
}
