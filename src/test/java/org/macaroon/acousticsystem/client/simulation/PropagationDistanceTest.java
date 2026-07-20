package org.macaroon.acousticsystem.client.simulation;

import org.junit.jupiter.api.Test;
import org.macaroon.acousticsystem.client.material.AcousticTuning;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PropagationDistanceTest {
    @Test
    void freeFieldPressureDropsSixDecibelsPerDistanceDoubling() {
        float atOneMeter = AcousticTracer.sphericalSpreadingGain(
                1.0, AcousticTuning.DEFAULT
        );
        float atTwoMeters = AcousticTracer.sphericalSpreadingGain(
                2.0, AcousticTuning.DEFAULT
        );
        float atFiveHundredMeters = AcousticTracer.sphericalSpreadingGain(
                500.0, AcousticTuning.DEFAULT
        );

        assertEquals(1.0F, atOneMeter, 1.0E-6F);
        assertEquals(0.5F, atTwoMeters, 1.0E-6F);
        assertEquals(0.002F, atFiveHundredMeters, 1.0E-6F);
    }
}
