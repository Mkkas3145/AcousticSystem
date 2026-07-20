package org.macaroon.acousticsystem.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtmosphericAbsorptionTest {
    @Test
    void standardAtmosphereAbsorbsHighFrequenciesMoreOverLongPaths() {
        double oneKilohertz = AtmosphericAbsorption.amplitudeNepersPerMeter(
                1_000.0, 20.0, 50.0, 101.325
        );
        double eightKilohertz = AtmosphericAbsorption.amplitudeNepersPerMeter(
                8_000.0, 20.0, 50.0, 101.325
        );

        assertTrue(oneKilohertz > 0.0);
        assertTrue(eightKilohertz > oneKilohertz * 3.0);
        assertTrue(
                AtmosphericAbsorption.amplitudeGain(eightKilohertz, 500.0)
                        < AtmosphericAbsorption.amplitudeGain(oneKilohertz, 500.0)
        );
    }

    @Test
    void zeroLengthPathConservesPressureAmplitude() {
        double coefficient = AtmosphericAbsorption.amplitudeNepersPerMeter(
                4_000.0, 20.0, 50.0, 101.325
        );
        assertEquals(1.0F, AtmosphericAbsorption.amplitudeGain(coefficient, 0.0));
    }
}
