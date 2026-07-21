package org.macaroon.acousticsystem.client.simulation;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.macaroon.acousticsystem.client.material.AcousticTuning;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LateReverbTracerIntegrationTest {
    private static final Vec3 LISTENER = new Vec3(0.5, 3.0, 0.5);
    private static final Vec3[] DIRECTIONS = directions(256);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void smallHouseAndLargeCavernKeepDistinctLateFields() {
        SolidGeometry house = position ->
                position.getY() <= 0 || position.getY() >= 5
                        || position.getX() <= -4 || position.getX() >= 4
                        || position.getZ() <= -4 || position.getZ() >= 4;
        SolidGeometry cavern = position ->
                position.getY() <= -8 || position.getY() >= 16
                        || position.getX() <= -18 || position.getX() >= 18
                        || position.getZ() <= -18 || position.getZ() >= 18;
        SolidGeometry widerCavern = position ->
                position.getY() <= -8 || position.getY() >= 16
                        || position.getX() <= -35 || position.getX() >= 35
                        || position.getZ() <= -35 || position.getZ() >= 35;
        LateReverbTracer.Estimate houseEstimate = trace(house);
        LateReverbTracer.Estimate cavernEstimate = trace(cavern);
        RoomProbe houseProbe = AcousticTracer.probeRoom(new TestWorld(house), LISTENER);
        RoomProbe cavernProbe = AcousticTracer.probeRoom(new TestWorld(cavern), LISTENER);
        RoomProbe widerCavernProbe = AcousticTracer.probeRoom(new TestWorld(widerCavern), LISTENER);
        RoomAcoustics houseRoom = houseProbe.acoustics();
        RoomAcoustics cavernRoom = cavernProbe.acoustics();
        RoomAcoustics widerCavernRoom = widerCavernProbe.acoustics();

        assertTrue(houseRoom.decayTime() < cavernRoom.decayTime() * 0.55F);
        assertTrue(houseEstimate.earlyDelay() < cavernEstimate.earlyDelay() * 0.35F);
        assertTrue(houseRoom.density() > cavernRoom.density());
        assertTrue(
                cavernRoom.gain() < houseRoom.gain() * 0.50F,
                () -> "A larger absorption area must lower the diffuse field level: house="
                        + houseRoom + ", cavern=" + cavernRoom
        );
        assertTrue(
                cavernRoom.gain() > 0.12F
                        && cavernRoom.lateReverbGain() > 0.75F,
                () -> "A large enclosed cavern must keep an audible long tail instead of "
                        + "collapsing to a weak dry sound: " + cavernRoom
        );
        assertTrue(
                widerCavernProbe.openings().isEmpty(),
                () -> "A sealed cavern wall beyond the dense probe radius was mistaken "
                        + "for an outdoor opening: " + widerCavernProbe.openings()
        );
        assertTrue(
                widerCavernRoom.decayTime() > cavernRoom.decayTime() * 0.90F
                        && widerCavernRoom.reflectionsDelay()
                        > cavernRoom.reflectionsDelay() * 1.25F
                        && widerCavernRoom.lateReverbGain() > 0.70F,
                () -> "The wider cavern must become quieter and later without losing its tail: medium="
                        + cavernRoom + ", wider=" + widerCavernRoom
        );
    }

    @Test
    void openStoneTunnelRetainsMoreLateEnergyThanFlatOutdoors() {
        LateReverbTracer.Estimate tunnel = trace(position ->
                position.getY() <= 0
                        || position.getY() >= 6
                        || position.getX() <= -4
                        || position.getX() >= 4
        );
        LateReverbTracer.Estimate outdoors = trace(position -> position.getY() <= 0);

        assertTrue(tunnel.fieldStrength() > 0.45F);
        assertTrue(tunnel.fieldStrength() > outdoors.fieldStrength() + 0.35F);
        assertTrue(outdoors.fieldStrength() < 0.10F);
        assertTrue(tunnel.lateEnergy() > outdoors.lateEnergy());
    }

    @Test
    void sealedStoneRoomProducesDenseMultiBounceTail() {
        LateReverbTracer.Estimate room = trace(position ->
                position.getY() <= 0
                        || position.getY() >= 7
                        || position.getX() <= -5
                        || position.getX() >= 5
                        || position.getZ() <= -5
                        || position.getZ() >= 5
        );

        assertTrue(room.fieldStrength() > 0.70F);
        assertTrue(room.density() > 0.45F);
        assertTrue(room.lateEnergy() > room.earlyEnergy());
        assertTrue(room.earlyDelay() > 0.003F);
    }

    @Test
    void cavernFieldRemainsContinuousAcrossHalfBlockSamplingBoundary() {
        SolidGeometry cavern = position ->
                position.getY() <= -8 || position.getY() >= 16
                        || position.getX() <= -18 || position.getX() >= 18
                        || position.getZ() <= -18 || position.getZ() >= 18;
        LateReverbTracer.Estimate before = traceAt(
                cavern, new Vec3(0.49, LISTENER.y, LISTENER.z), AcousticTuning.DEFAULT
        );
        LateReverbTracer.Estimate after = traceAt(
                cavern, new Vec3(0.51, LISTENER.y, LISTENER.z), AcousticTuning.DEFAULT
        );

        assertTrue(Math.abs(before.fieldStrength() - after.fieldStrength()) < 0.08F);
        assertTrue(Math.abs(before.decayTime() - after.decayTime()) < 0.15F);
        assertTrue(Math.abs(before.earlyDelay() - after.earlyDelay()) < 0.003F);
    }

    @Test
    void unchangedTwentyMeterRoomKeepsTheSameCalculatedParameters() {
        SolidGeometry room = position ->
                position.getY() <= -7 || position.getY() >= 13
                        || position.getX() <= -10 || position.getX() >= 10
                        || position.getZ() <= -10 || position.getZ() >= 10;
        TestWorld world = new TestWorld(room);

        RoomProbe first = AcousticTracer.probeRoom(world, LISTENER);
        RoomProbe second = AcousticTracer.probeRoom(world, LISTENER);

        assertSame(first.impulseResponse(), second.impulseResponse());
        assertEquals(first.acoustics(), second.acoustics());
    }

    @Test
    void smallDoorRetainsMoreLateFieldThanAnOpenWall() {
        LateReverbTracer.Estimate smallDoor = trace(position -> {
            boolean boundary = finiteRoomBoundary(position, true);
            boolean doorway = position.getZ() == 5
                    && position.getX() >= 0 && position.getX() <= 1
                    && position.getY() >= 1 && position.getY() <= 3;
            return boundary && !doorway;
        });
        LateReverbTracer.Estimate openWall = trace(position -> finiteRoomBoundary(position, false));

        assertTrue(smallDoor.fieldStrength() > 0.65F);
        assertTrue(
                smallDoor.fieldStrength() > openWall.fieldStrength() + 0.12F,
                () -> "smallDoor=" + smallDoor + ", openWall=" + openWall
        );
    }

    @Test
    void metricScaleControlsPhysicalArrivalAndImpulseResponseTimes() {
        SolidGeometry room = position ->
                position.getY() <= 0
                        || position.getY() >= 7
                        || position.getX() <= -5
                        || position.getX() >= 5
                        || position.getZ() <= -5
                        || position.getZ() >= 5;
        JsonObject compactScaleOverride = new JsonObject();
        compactScaleOverride.addProperty("meters_per_block", 0.70F);
        AcousticTuning compactScale = AcousticTuning.fromJson(
                compactScaleOverride,
                AcousticTuning.DEFAULT
        );

        LateReverbTracer.Estimate oneMeterScale = trace(room, AcousticTuning.DEFAULT);
        LateReverbTracer.Estimate compactScaleResult = trace(room, compactScale);

        assertTrue(compactScaleResult.earlyDelay() < oneMeterScale.earlyDelay() * 0.72F);
        assertTrue(compactScaleResult.earlyDelay() > oneMeterScale.earlyDelay() * 0.68F);
    }

    private static boolean finiteRoomBoundary(BlockPos position, boolean positiveZWall) {
        boolean withinX = position.getX() >= -5 && position.getX() <= 5;
        boolean withinY = position.getY() >= 0 && position.getY() <= 7;
        boolean withinZ = position.getZ() >= -5 && position.getZ() <= 5;
        boolean floorOrCeiling = (position.getY() == 0 || position.getY() == 7) && withinX && withinZ;
        boolean sideWall = (position.getX() == -5 || position.getX() == 5) && withinY && withinZ;
        boolean backWall = position.getZ() == -5 && withinX && withinY;
        boolean frontWall = positiveZWall && position.getZ() == 5 && withinX && withinY;
        return floorOrCeiling || sideWall || backWall || frontWall;
    }

    private static LateReverbTracer.Estimate trace(SolidGeometry geometry) {
        return trace(geometry, AcousticTuning.DEFAULT);
    }

    private static LateReverbTracer.Estimate trace(
            SolidGeometry geometry,
            AcousticTuning tuning
    ) {
        return traceAt(geometry, LISTENER, tuning);
    }

    private static LateReverbTracer.Estimate traceAt(
            SolidGeometry geometry,
            Vec3 listener,
            AcousticTuning tuning
    ) {
        TestWorld world = new TestWorld(geometry);
        AcousticTracer.SurfaceHit[] firstHits = new AcousticTracer.SurfaceHit[DIRECTIONS.length];
        for (int ray = 0; ray < DIRECTIONS.length; ray++) {
            firstHits[ray] = AcousticTracer.firstSurface(
                world,
                listener,
                listener.add(DIRECTIONS[ray].scale(tuning.adaptiveRoomProbeDistance()))
            );
        }
        return LateReverbTracer.trace(
                world,
                listener,
                DIRECTIONS,
                firstHits,
                tuning,
                0.8F
        );
    }

    private static Vec3[] directions(int count) {
        Vec3[] directions = new Vec3[count];
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int index = 0; index < count; index++) {
            double y = 1.0 - (index + 0.5) * 2.0 / count;
            double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double angle = index * goldenAngle;
            directions[index] = new Vec3(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
        }
        return directions;
    }

    @FunctionalInterface
    private interface SolidGeometry {
        boolean isSolid(BlockPos position);
    }

    private record TestWorld(SolidGeometry geometry) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return geometry.isSolid(position) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getHeight() {
            return 384;
        }
    }
}
