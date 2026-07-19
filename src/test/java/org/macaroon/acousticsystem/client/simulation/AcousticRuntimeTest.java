package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticRuntimeTest {
    @Test
    void onsetParallelismScalesWithTheMachineInsteadOfStoppingAtTwo() {
        int expected = Math.max(
                2,
                (Runtime.getRuntime().availableProcessors() - 2) / 2
        );
        assertEquals(expected, AcousticRuntime.onsetWorkerCount());
    }

    @Test
    void completedRoomResultCannotFollowTheListenerIntoAnotherProbeCell() {
        Vec3 computedInsideRoom = new Vec3(3.10, 2.10, 4.10);

        assertTrue(AcousticRuntime.isListenerResultCurrent(
                computedInsideRoom,
                new Vec3(3.40, 2.30, 4.40)
        ));
        assertFalse(AcousticRuntime.isListenerResultCurrent(
                computedInsideRoom,
                new Vec3(3.60, 2.30, 4.40)
        ));
    }

    @Test
    void shortOnsetKeepsTheKnownFieldAcrossBlockRevisionAndProbeCellMovement() {
        assertTrue(AcousticRuntime.canReuseRoomForOnset(
                7L, 7L
        ));
        assertFalse(AcousticRuntime.canReuseRoomForOnset(
                7L, 8L
        ));
        assertTrue(AcousticRuntime.canReuseRoomForOnset(
                7L, 7L
        ));
    }
}
