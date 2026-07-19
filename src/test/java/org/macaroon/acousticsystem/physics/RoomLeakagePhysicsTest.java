package org.macaroon.acousticsystem.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomLeakagePhysicsTest {
    @Test
    void openingAreaDoesNotChangeWithListenerDistance() {
        double near = RoomLeakagePhysics.projectedOpeningArea(2.0, 1.0);
        double far = RoomLeakagePhysics.projectedOpeningArea(0.08, 5.0);

        assertEquals(2.0, near, 1.0E-6);
        assertEquals(near, far, 1.0E-6);
    }

    @Test
    void normalDoorOnlyModeratelyShortensReverberation() {
        RoomLeakagePhysics.Result closed = RoomLeakagePhysics.evaluate(
                300.0, 320.0, 16.0, 0.0, 0.0
        );
        RoomLeakagePhysics.Result door = RoomLeakagePhysics.evaluate(
                300.0, 318.0, 15.9, 2.0, 2.0
        );

        assertEquals(3.01875, closed.reverberationTimeSeconds(), 0.001);
        assertEquals(2.698, door.reverberationTimeSeconds(), 0.01);
        assertTrue(door.levelRetention() > 0.93F);
    }

    @Test
    void largeOpeningStronglyShortensReverberation() {
        RoomLeakagePhysics.Result door = RoomLeakagePhysics.evaluate(
                300.0, 318.0, 15.9, 2.0, 2.0
        );
        RoomLeakagePhysics.Result openWall = RoomLeakagePhysics.evaluate(
                300.0, 300.0, 15.0, 20.0, 20.0
        );

        assertTrue(openWall.reverberationTimeSeconds() < door.reverberationTimeSeconds() * 0.60F);
        assertTrue(openWall.levelRetention() < door.levelRetention());
    }

    @Test
    void coupledRoomReturnsMoreEnergyThanOutdoors() {
        RoomLeakagePhysics.Result outdoors = RoomLeakagePhysics.evaluate(
                300.0, 318.0, 15.9, 2.0, 2.0
        );
        RoomLeakagePhysics.Result coupledRoom = RoomLeakagePhysics.evaluate(
                300.0, 318.0, 15.9, 2.0, 0.7
        );

        assertTrue(coupledRoom.reverberationTimeSeconds() > outdoors.reverberationTimeSeconds());
        assertTrue(coupledRoom.levelRetention() > outdoors.levelRetention());
    }

}
