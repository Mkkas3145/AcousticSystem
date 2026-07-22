package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AcousticTracerFluidIntegrationTest {
    private static final RoomProbe OUTDOORS = new RoomProbe(
            RoomAcoustics.OUTDOORS,
            List.of(),
            List.of()
    );

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void submergedPathKeepsBulkEnergyButChangesTheAuditorySpectrum() {
        AcousticResult shortPath = trace(
                new FluidWorld(position -> true),
                new Vec3(0.5, 2.25, 0.5),
                new Vec3(2.5, 2.25, 0.5)
        );
        AcousticResult longPath = trace(
                new FluidWorld(position -> true),
                new Vec3(0.5, 2.25, 0.5),
                new Vec3(20.5, 2.25, 0.5)
        );

        assertTrue(
                shortPath.midLowBandGain() > shortPath.lowBandGain() * 1.4F,
                () -> "A submerged listener should receive a mid-forward spectrum: " + shortPath
        );
        assertTrue(
                shortPath.midLowBandGain() > shortPath.highBandGain() * 1.8F,
                () -> "A submerged listener should not sound like broadband volume attenuation: " + shortPath
        );
        assertTrue(
                longPath.midLowBandGain() > shortPath.midLowBandGain() * 0.50F,
                () -> "A wide finite wavefront crossing twenty metres of water must not collapse like insulation: short="
                        + shortPath + ", long=" + longPath
        );
    }

    @Test
    void crossingTheAirWaterInterfaceIsFarQuieterThanStayingInOneMedium() {
        AcousticResult submerged = trace(
                new FluidWorld(position -> true),
                new Vec3(-4.5, 2.25, 0.5),
                new Vec3(4.5, 2.25, 0.5)
        );
        FluidWorld splitWorld = new FluidWorld(position -> position.getX() < 0);
        Vec3 source = new Vec3(-4.5, 2.25, 0.5);
        BlockPos sourceBlock = BlockPos.containing(source);
        AABB sourceFluidBounds = splitWorld.getFluidState(sourceBlock)
                .getShape(splitWorld, sourceBlock).bounds().move(sourceBlock);
        assertTrue(sourceFluidBounds.contains(source), () -> "fluidBounds=" + sourceFluidBounds);
        AcousticResult crossesSurface = trace(
                splitWorld,
                source,
                new Vec3(4.5, 2.25, 0.5)
        );

        assertTrue(
                crossesSurface.directGain() < submerged.directGain() * 0.06F,
                () -> "The impedance discontinuity must dominate an air/water crossing: submerged="
                        + submerged + ", crossing=" + crossesSurface
        );
    }

    @Test
    void submergedSourceKeepsItsMediumSpectrumAcrossANearbySurface() {
        FluidWorld surface = new FluidWorld(position -> position.getY() < 1);
        AcousticResult result = trace(
                surface,
                new Vec3(0.5, 0.50, 0.5),
                new Vec3(0.5, 1.50, 0.5)
        );

        assertTrue(
                result.midLowBandGain() > result.highBandGain() * 1.8F,
                () -> "A water-to-air source one metre away lost its medium colour: " + result
        );
    }

    @Test
    void transientEmptyEmitterCellUsesItsWettedBoundary() {
        BlockPos transientCell = new BlockPos(0, 0, 0);
        FluidWorld wettedCavity = new FluidWorld(position -> !position.equals(transientCell));
        AcousticResult result = trace(
                wettedCavity,
                new Vec3(0.50, 0.50, 0.50),
                new Vec3(0.56, 0.50, 0.50)
        );
        AcousticResult dry = trace(
                new FluidWorld(position -> false),
                new Vec3(0.50, 0.50, 0.50),
                new Vec3(0.56, 0.50, 0.50)
        );
        float[] boundary = AcousticTracer.solveEmitterBoundary(
                wettedCavity,
                new Vec3(0.50, 0.50, 0.50)
        ).amplitude();

        assertTrue(
                result.midLowBandGain() > result.highBandGain() * 1.5F
                        && result.highBandGain() < dry.highBandGain() * 0.60F,
                () -> "An update-order air voxel must not erase the surrounding fluid boundary: "
                        + result + ", dry=" + dry + ", boundary=" + Arrays.toString(boundary)
        );
    }

    @Test
    void displacedFluidSnapshotMatchesTheStableFluidPropagationTopology() {
        Vec3 source = new Vec3(0.50, 0.50, 0.50);
        Vec3 listener = new Vec3(3.50, 0.50, 0.50);
        AcousticResult stable = trace(new FluidWorld(position -> true), source, listener);
        AcousticResult displaced = trace(
                new DisplacedFluidWorld(BlockPos.ZERO),
                source,
                listener
        );

        assertEquals(stable.lowBandGain(), displaced.lowBandGain(), 1.0E-4F);
        assertEquals(stable.midLowBandGain(), displaced.midLowBandGain(), 1.0E-4F);
        assertEquals(stable.midHighBandGain(), displaced.midHighBandGain(), 1.0E-4F);
        assertEquals(stable.highBandGain(), displaced.highBandGain(), 1.0E-4F);
    }

    @Test
    void endpointMediumTransferIsReciprocal() {
        FluidWorld surface = new FluidWorld(position -> position.getY() < 1);
        Vec3 underwater = new Vec3(0.5, 0.50, 0.5);
        Vec3 airborne = new Vec3(0.5, 1.50, 0.5);
        AcousticResult waterToAir = trace(surface, underwater, airborne);
        AcousticResult airToWater = trace(surface, airborne, underwater);

        // Antipodal finite-wavefront quadrature reverses floating-point summation order
        // and clips different voxel-face contacts with path direction. Endpoint transfer
        // remains reciprocal to comfortably below one percent.
        assertEquals(waterToAir.lowBandGain(), airToWater.lowBandGain(), 1.0E-4F);
        assertEquals(waterToAir.midLowBandGain(), airToWater.midLowBandGain(), 1.0E-4F);
        assertEquals(waterToAir.midHighBandGain(), airToWater.midHighBandGain(), 1.0E-4F);
        assertEquals(waterToAir.highBandGain(), airToWater.highBandGain(), 1.0E-4F);
    }

    @Test
    void aSourceBarelyTouchingWaterTransitionsContinuously() {
        FluidWorld surface = new FluidWorld(position -> position.getY() < 1);
        Vec3 listener = new Vec3(0.5, 2.5, 0.5);
        AcousticResult barelyWet = trace(
                surface,
                new Vec3(0.5, 0.98, 0.5),
                listener
        );
        AcousticResult submerged = trace(
                surface,
                new Vec3(0.5, 0.50, 0.5),
                listener
        );

        assertTrue(
                barelyWet.directGain() > submerged.directGain() * 4.0F,
                () -> "A shallow source must retain its air-exposed radiation instead of "
                        + "switching to a fully submerged interface: shallow="
                        + barelyWet + ", submerged=" + submerged
        );
        assertTrue(
                barelyWet.directGain() > 0.45F,
                () -> "A two-centimetre immersion should remain clearly audible: " + barelyWet
        );
    }

    @Test
    void submergedRoomRaysReflectFromTheWaterSurface() {
        FluidWorld ocean = new FluidWorld(position -> position.getY() < 4);
        Vec3 listener = new Vec3(0.5, 2.25, 0.5);
        AcousticTracer.SurfaceHit[] holder = new AcousticTracer.SurfaceHit[1];
        AcousticTracer.SurfaceHit surface = AcousticTracer.firstAcousticSurface(
                ocean,
                listener,
                new Vec3(0.5, 10.0, 0.5),
                holder
        );

        assertTrue(surface != null && surface.fluidBoundary(), () -> "surface=" + surface);
        assertTrue(
                Math.abs(surface.location().y - surface.bounds().maxY) < 1.0E-6,
                () -> "surface=" + surface
        );
        assertTrue(
                AcousticTracer.surfaceReflectedPower(surface, 4, 1.0) > 0.998F,
                () -> "surface=" + surface
        );
    }

    @Test
    void unboundedWaterDoesNotManufactureACaveReverbPreset() {
        RoomProbe probe = AcousticTracer.probeRoom(
                new FluidWorld(position -> true),
                new Vec3(0.5, 2.25, 0.5)
        );

        assertEquals(0.0F, probe.acoustics().gain(), 1.0E-6F);
        assertEquals(0.0F, probe.acoustics().reflectionsGain(), 1.0E-6F);
        assertEquals(0.0F, probe.acoustics().lateReverbGain(), 1.0E-6F);
    }

    @Test
    void submergedDynamicUpdateCompletesAndCanRunTheOptionalPerformanceBenchmark() {
        FluidWorld tank = new FluidWorld(position ->
                position.getX() >= -6 && position.getX() <= 6
                        && position.getY() >= 0 && position.getY() <= 6
                        && position.getZ() >= -6 && position.getZ() <= 6
        );
        Vec3 source = new Vec3(-3.5, 2.25, 0.5);
        double[] measured = new double[7];
        for (int sample = -2; sample < measured.length; sample++) {
            Vec3 listener = new Vec3(2.5, 2.25, 0.5 + Math.max(sample, 0) * 0.013);
            long started = System.nanoTime();
            RoomProbe listenerRoom = AcousticTracer.probeRoom(tank, listener);
            RoomProbe sourceRoom = AcousticTracer.probeSourceRoom(tank, source);
            AcousticTracer.trace(
                    tank,
                    source,
                    listener,
                    sourceRoom,
                    listenerRoom,
                    AcousticTracer.TraceQuality.FULL
            );
            if (sample >= 0) {
                measured[sample] = (System.nanoTime() - started) / 1_000_000.0;
            }
        }
        Arrays.sort(measured);
        double median = measured[measured.length / 2];
        assertTrue(Double.isFinite(median) && median >= 0.0);
        if (Boolean.getBoolean("acousticsystem.enforcePerformanceBudgets")) {
            assertTrue(median < 10.0, () ->
                    "submerged dynamic update exceeded the optional 10 ms benchmark: " + median
            );
        }
    }

    private static AcousticResult trace(BlockGetter world, Vec3 source, Vec3 listener) {
        return AcousticTracer.traceImmediate(world, source, listener, OUTDOORS);
    }

    @FunctionalInterface
    private interface FluidGeometry {
        boolean contains(BlockPos position);
    }

    private record FluidWorld(FluidGeometry fluid) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return fluid.contains(position)
                    ? Blocks.WATER.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
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

    private record DisplacedFluidWorld(BlockPos emptyBlock) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return position.equals(emptyBlock)
                    ? Blocks.AIR.defaultBlockState()
                    : Blocks.WATER.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return Blocks.WATER.defaultBlockState().getFluidState();
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
