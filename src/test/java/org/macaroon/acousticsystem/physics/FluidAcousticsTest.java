package org.macaroon.acousticsystem.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidAcousticsTest {
    @Test
    void equalMediaHaveNoBoundaryLoss() {
        assertEquals(1.0F, FluidAcoustics.interfaceTransmissionAmplitude(1_480_000.0, 1_480_000.0));
        assertEquals(0.0F, FluidAcoustics.interfaceReflectionAmplitude(1_480_000.0, 1_480_000.0));
    }

    @Test
    void airWaterBoundaryReflectsAlmostAllIncidentPower() {
        float transmission = FluidAcoustics.interfaceTransmissionAmplitude(415.0, 1_480_000.0);
        float reflection = FluidAcoustics.interfaceReflectionAmplitude(415.0, 1_480_000.0);

        assertTrue(transmission > 0.03F && transmission < 0.04F, () -> "transmission=" + transmission);
        assertTrue(reflection > 0.999F, () -> "reflection=" + reflection);
        assertEquals(1.0F, transmission * transmission + reflection * reflection, 1.0E-5F);
    }

    @Test
    void waterArrivalIsAboutFourTimesEarlierThanAirArrival() {
        double air = FluidAcoustics.travelTimeSeconds(20.0, 343.0);
        double water = FluidAcoustics.travelTimeSeconds(20.0, 1_480.0);
        assertEquals(343.0 / 1_480.0, water / air, 1.0E-9);
    }
}
