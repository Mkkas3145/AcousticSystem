package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;

class AcousticRuntimeTest {
    @Test
    void subCellEarMovementSchedulesANewListenerField() {
        Vec3 initial = new Vec3(12.10, 64.20, -3.10);
        AcousticRuntime.BatchKey key = new AcousticRuntime.BatchKey(
                1L,
                2L,
                Double.doubleToLongBits(initial.x),
                Double.doubleToLongBits(initial.y),
                Double.doubleToLongBits(initial.z),
                List.of()
        );

        assertTrue(key.matchesListener(initial));
        assertFalse(key.matchesListener(new Vec3(12.11, 64.20, -3.10)));
        assertFalse(key.matchesListener(new Vec3(12.10, 64.21, -3.10)));
        assertFalse(key.matchesListener(new Vec3(12.10, 64.20, -3.09)));
    }

    @Test
    void onsetParallelismPreservesRenderThreadHeadroom() {
        int totalBudget = Math.max(
                1,
                Math.min(12, (Runtime.getRuntime().availableProcessors() + 1) / 2)
        );
        assertEquals(Math.max(1, totalBudget - 1), AcousticRuntime.onsetWorkerCount());
        assertEquals(totalBudget, AcousticRuntime.workerCount());
        assertTrue(AcousticRuntime.realtimeBatchLaneCount() >= 1);
        assertTrue(AcousticRuntime.realtimeBatchLaneCount() <= 4);
        if (AcousticRuntime.onsetWorkerCount() > 1) {
            assertTrue(
                    AcousticRuntime.realtimeBatchLaneCount()
                            < AcousticRuntime.onsetWorkerCount()
            );
        }
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

    @Test
    void continuousMovementCannotStarveCompletedAcousticResults() {
        AcousticRuntime.LatestComputationQueue<String, String> queue =
                new AcousticRuntime.LatestComputationQueue<>();

        assertTrue(queue.offer("position-0"));
        assertEquals("position-0", queue.take());

        // Several render frames arrive while position-0 is being traced. Only the
        // newest request needs calculation, but position-0's completed result must be
        // independently deliverable immediately instead of waiting for movement to stop.
        assertFalse(queue.offer("position-1"));
        assertFalse(queue.offer("position-2"));
        queue.publish("result-0");
        assertEquals("result-0", queue.takePublished());
        assertTrue(queue.continueOrRelease());
        assertEquals("position-2", queue.take());

        assertFalse(queue.offer("position-3"));
        queue.publish("result-2");
        assertEquals("result-2", queue.takePublished());
        assertTrue(queue.continueOrRelease());
        assertEquals("position-3", queue.take());
        queue.publish("result-3");
        assertEquals("result-3", queue.takePublished());
        assertFalse(queue.continueOrRelease());
        assertTrue(queue.idle());
    }

    @Test
    void continuousListenerFieldsCollapseToNewestCompletedMeasurement() {
        AcousticRuntime.LatestPublication<String> publication =
                new AcousticRuntime.LatestPublication<>();

        publication.publish(10L, "room-10");
        publication.publish(12L, "room-12");
        publication.publish(11L, "late-room-11");

        assertTrue(publication.hasPending());
        assertEquals("room-12", publication.take());
        assertFalse(publication.hasPending());

        publication.publish(13L, "room-13");
        assertEquals("room-13", publication.take());
    }

    @Test
    void manyMovingVoicesCollapseIntoOneNewestBatchSnapshot() {
        int voiceCount = 64;
        List<AcousticRuntime.LatestComputationQueue<Integer, String>> voices =
                new ArrayList<>(voiceCount);
        for (int voice = 0; voice < voiceCount; voice++) {
            AcousticRuntime.LatestComputationQueue<Integer, String> queue =
                    new AcousticRuntime.LatestComputationQueue<>();
            assertTrue(queue.offer(0));
            voices.add(queue);
        }

        // Render frames can outnumber completed acoustic batches by orders of
        // magnitude.  Every voice retains only the newest endpoint; a batch sweep does
        // not inherit frames * voices worth of propagation work.
        for (int frame = 1; frame <= 2_000; frame++) {
            for (AcousticRuntime.LatestComputationQueue<Integer, String> voice : voices) {
                assertFalse(voice.offer(frame));
            }
        }
        for (AcousticRuntime.LatestComputationQueue<Integer, String> voice : voices) {
            assertEquals(2_000, voice.take());
            assertFalse(voice.continueOrRelease());
            assertTrue(voice.idle());
        }
    }
}
