package org.macaroon.acousticsystem.client.audio;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiffuseFieldOverlapTest {
    private static final RoomAcoustics ROOM = new RoomAcoustics(
            0.75F, 0.68F, 0.32F, 0.72F, 0.95F,
            1.8F, 0.65F, 1.15F,
            0.28F, 0.018F, Vec3.ZERO,
            1.2F, 0.032F, Vec3.ZERO,
            0.6F, 0.08F, 0.94F
    );

    @Test
    void unchangedRoomKeepsTheStoredTailAtItsMeasuredLevel() {
        assertEquals(
                ROOM.gain(),
                OpenALAcousticEffects.diffuseFieldOverlapGain(ROOM, ROOM),
                1.0E-5F
        );
    }

    @Test
    void openListenerFieldDoesNotCarryTheOldRoomTailOutside() {
        assertEquals(
                0.0F,
                OpenALAcousticEffects.diffuseFieldOverlapGain(
                        ROOM, RoomAcoustics.OUTDOORS
                )
        );
    }

    @Test
    void transitionLevelChangesContinuouslyWithTheObservedDiffuseField() {
        RoomAcoustics weakField = withGain(ROOM, 0.08F);
        float overlap = OpenALAcousticEffects.diffuseFieldOverlapGain(ROOM, weakField);
        assertTrue(overlap > 0.0F);
        assertTrue(overlap < ROOM.gain());
    }

    @Test
    void retiredTailCannotGrowBackAfterItHasDecayed() {
        float alreadyDecayed = 0.025F;
        assertEquals(
                alreadyDecayed,
                OpenALAcousticEffects.retiredDiffuseFieldGain(
                        alreadyDecayed, ROOM, ROOM
                ),
                1.0E-6F
        );
        assertEquals(
                0.0F,
                OpenALAcousticEffects.retiredDiffuseFieldGain(
                        alreadyDecayed, ROOM, RoomAcoustics.OUTDOORS
                )
        );
    }

    @Test
    void finiteApertureLeaksContinuouslyInsteadOfCuttingAtTheDoorPlane() {
        double area = 4.0;
        double justOutside = OpenALAcousticEffects.aperturePowerFraction(
                area, 0.10, 0.10 * 0.10
        );
        double fartherOutside = OpenALAcousticEffects.aperturePowerFraction(
                area, 4.0, 4.0 * 4.0
        );
        assertTrue(justOutside > fartherOutside);
        assertTrue(fartherOutside > 0.0);
    }

    @Test
    void listenerFieldChangesOnlyAfterCrossingTheFiniteOpening() {
        Vec3 inside = Vec3.ZERO;
        Vec3 opening = new Vec3(2.0, 0.0, 0.0);
        Vec3 outward = new Vec3(1.0, 0.0, 0.0);
        double area = 4.0;

        assertTrue(OpenALAcousticEffects.segmentCrossesAperture(
                inside, new Vec3(3.0, 0.0, 0.0), opening, outward, area
        ));
        assertEquals(false, OpenALAcousticEffects.segmentCrossesAperture(
                inside, new Vec3(3.0, 4.0, 0.0), opening, outward, area
        ));
    }

    @Test
    void apertureTransportOverridesStaleSameRoomOverlapAfterCrossingTheExit() {
        float nearExit = OpenALAcousticEffects.transportedDiffuseFieldGain(
                ROOM, ROOM, 0.80F, true
        );
        float fartherOutside = OpenALAcousticEffects.transportedDiffuseFieldGain(
                ROOM, ROOM, 0.25F, true
        );

        assertEquals(ROOM.gain() * 0.80F, nearExit, 1.0E-6F);
        assertEquals(ROOM.gain() * 0.25F, fartherOutside, 1.0E-6F);
        assertTrue(fartherOutside < nearExit);
        assertEquals(
                ROOM.gain(),
                OpenALAcousticEffects.transportedDiffuseFieldGain(
                        ROOM, ROOM, 0.0F, false
                ),
                1.0E-6F
        );
    }

    private static RoomAcoustics withGain(RoomAcoustics room, float gain) {
        return new RoomAcoustics(
                room.density(), room.diffusion(), gain,
                room.gainHighFrequency(), room.gainLowFrequency(),
                room.decayTime(), room.decayHighFrequencyRatio(),
                room.decayLowFrequencyRatio(), room.reflectionsGain(),
                room.reflectionsDelay(), room.reflectionsPan(),
                room.lateReverbGain(), room.lateReverbDelay(),
                room.lateReverbPan(), room.modulationTime(),
                room.modulationDepth(), room.airAbsorptionGainHighFrequency()
        );
    }
}
