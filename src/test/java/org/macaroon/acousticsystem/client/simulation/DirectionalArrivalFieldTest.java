package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectionalArrivalFieldTest {
    @Test
    void fixedWorldArrivalReprojectsFromTheCurrentEarPosition() {
        DirectionalArrivalField field = new DirectionalArrivalField(
                List.of(new DirectionalArrivalField.Arrival(
                        new Vec3(10.0, 2.0, 0.0), 1.0
                )),
                new Vec3(10.0, 2.0, 0.0)
        );

        Vec3 initial = field.apparentDirection(new Vec3(0.0, 2.0, 0.0));
        Vec3 moved = field.apparentDirection(new Vec3(9.0, 2.0, 5.0));

        assertEquals(1.0, initial.x, 1.0E-9);
        assertTrue(moved.x > 0.19 && moved.x < 0.20, () -> "moved=" + moved);
        assertTrue(moved.z < -0.98, () -> "moved=" + moved);
    }

    @Test
    void symmetricOpeningsStayCentredOnTheRealSource() {
        DirectionalArrivalField field = new DirectionalArrivalField(
                List.of(
                        new DirectionalArrivalField.Arrival(new Vec3(-4.0, 1.6, 0.0), 1.0),
                        new DirectionalArrivalField.Arrival(new Vec3(4.0, 1.6, 0.0), 1.0)
                ),
                new Vec3(0.0, 1.6, 8.0)
        );

        Vec3 direction = field.apparentDirection(new Vec3(0.0, 1.6, 0.0));
        assertEquals(0.0, direction.x, 1.0E-9);
        assertEquals(1.0, direction.z, 1.0E-9);
    }

    @Test
    void earlyReflectionUsesItsWorldSpaceArrivalField() {
        DirectionalArrivalField field = new DirectionalArrivalField(
                List.of(new DirectionalArrivalField.Arrival(
                        new Vec3(0.0, 2.0, 6.0), 1.0
                )),
                new Vec3(0.0, 2.0, 6.0)
        );
        EarlyReflection reflection = new EarlyReflection(
                0.5F, 0.8F, 0.02F, new Vec3(1.0, 0.0, 0.0), field
        );

        Vec3 direction = reflection.directionFrom(new Vec3(3.0, 2.0, 5.0));
        assertTrue(direction.x < -0.94, () -> "direction=" + direction);
        assertTrue(direction.z > 0.31, () -> "direction=" + direction);
    }
}
