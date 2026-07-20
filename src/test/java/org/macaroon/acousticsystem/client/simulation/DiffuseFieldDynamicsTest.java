package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffuseFieldDynamicsTest {
    @Test
    void rt60ControlsStoredEnergyWithoutAReset() {
        RoomAcoustics energized = room(1.0F, 2.0F, 0.8F, 0.9F);
        RoomAcoustics absorbing = room(0.0F, 2.0F, 0.4F, 0.7F);

        assertSame(
                energized,
                DiffuseFieldDynamics.advance(energized, absorbing, 0.0),
                "Changing a boundary must not alter the field at zero elapsed time"
        );
        RoomAcoustics afterRt60 = DiffuseFieldDynamics.advance(
                energized, absorbing, 2.0
        );
        assertEquals(
                1.0E-3F,
                afterRt60.gain(),
                2.0E-6F,
                "The stored amplitude must reach -60 dB at the measured RT60"
        );
    }

    @Test
    void arbitrarilyFrequentBlockChangesKeepOneContinuousState() {
        RoomAcoustics state = room(0.65F, 2.8F, 0.72F, 0.94F);
        RoomAcoustics open = room(0.12F, 0.45F, 0.48F, 0.82F);
        RoomAcoustics closed = room(0.78F, 3.2F, 0.76F, 0.96F);
        float previous = state.gain();

        for (int edit = 0; edit < 2_000; edit++) {
            RoomAcoustics boundary = (edit & 1) == 0 ? open : closed;
            state = DiffuseFieldDynamics.advance(state, boundary, 0.001);
            assertTrue(Float.isFinite(state.gain()));
            assertTrue(
                    Math.abs(state.gain() - previous) < 0.02F,
                    "A block event changed stored diffuse energy discontinuously"
            );
            previous = state.gain();
        }
    }

    @Test
    void highAndLowBandsRetainIndependentPhysicalDecay() {
        RoomAcoustics state = room(0.8F, 2.0F, 0.9F, 0.5F);
        RoomAcoustics boundary = room(0.2F, 2.0F, 0.4F, 0.2F);

        RoomAcoustics evolved = DiffuseFieldDynamics.advance(
                state, boundary, 0.1
        );
        assertTrue(evolved.gain() < state.gain());
        assertTrue(evolved.gainHighFrequency() < state.gainHighFrequency());
        assertTrue(evolved.gainLowFrequency() <= 1.0F);
    }

    private static RoomAcoustics room(
            float gain,
            float decay,
            float low,
            float high
    ) {
        return new RoomAcoustics(
                0.7F, 0.75F, gain, high, low,
                decay, 0.65F, 1.25F,
                0.3F, 0.02F, Vec3.ZERO,
                1.1F, 0.035F, Vec3.ZERO,
                0.5F, 0.02F, 0.95F
        );
    }
}
