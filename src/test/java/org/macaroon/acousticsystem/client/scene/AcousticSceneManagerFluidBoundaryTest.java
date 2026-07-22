package org.macaroon.acousticsystem.client.scene;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticSceneManagerFluidBoundaryTest {
    private static final int[][] FACES = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aFullyWettedEmptyCellRetainsTheDisplacedPressureMedium() {
        BoundaryWorld world = surroundedBy(Blocks.WATER.defaultBlockState());

        assertFalse(
                AcousticSceneManager.enclosedFluidBoundary(world, BlockPos.ZERO).isEmpty()
        );
    }

    @Test
    void oneRealAirOpeningPreventsAFalseSubmergedClassification() {
        BoundaryWorld world = surroundedBy(Blocks.WATER.defaultBlockState());
        world.states().put(BlockPos.ZERO.above().asLong(), Blocks.AIR.defaultBlockState());

        assertTrue(
                AcousticSceneManager.enclosedFluidBoundary(world, BlockPos.ZERO).isEmpty()
        );
    }

    @Test
    void aClosedSolidFaceDoesNotBecomeAnImaginaryAirOutlet() {
        BoundaryWorld world = surroundedBy(Blocks.WATER.defaultBlockState());
        world.states().put(BlockPos.ZERO.below().asLong(), Blocks.STONE.defaultBlockState());

        assertFalse(
                AcousticSceneManager.enclosedFluidBoundary(world, BlockPos.ZERO).isEmpty()
        );
    }

    private static BoundaryWorld surroundedBy(BlockState state) {
        Map<Long, BlockState> states = new HashMap<>();
        for (int[] face : FACES) {
            states.put(
                    BlockPos.ZERO.offset(face[0], face[1], face[2]).asLong(),
                    state
            );
        }
        return new BoundaryWorld(states);
    }

    private record BoundaryWorld(Map<Long, BlockState> states) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return states.getOrDefault(position.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return getBlockState(position).getFluidState();
        }

        public int getMinY() {
            return -64;
        }

        public int getMinBuildHeight() {
            return -64;
        }

        @Override
        public int getHeight() {
            return 384;
        }
    }
}
