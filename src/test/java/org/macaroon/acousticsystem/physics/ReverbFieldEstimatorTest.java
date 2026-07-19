package org.macaroon.acousticsystem.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReverbFieldEstimatorTest {
    @Test
    void singleOutdoorReflectionDoesNotCreateALateField() {
        assertEquals(0.0F, ReverbFieldEstimator.fieldStrength(0.45F, 0.0F, 0.0F), 1.0E-6F);
    }

    @Test
    void persistentMultiBounceEnergyCreatesAStrongField() {
        float cave = ReverbFieldEstimator.fieldStrength(0.75F, 2.6F, 0.88F);

        assertTrue(cave > 0.80F);
    }

    @Test
    void largerEscapeFractionReducesFieldContinuously() {
        float mostlyContained = ReverbFieldEstimator.fieldStrength(0.7F, 1.8F, 0.85F);
        float mostlyEscaped = ReverbFieldEstimator.fieldStrength(0.7F, 0.25F, 0.20F);

        assertTrue(mostlyContained > mostlyEscaped);
    }

    @Test
    void escapedParticlesReduceMultiBounceRetention() {
        float enclosed = ReverbFieldEstimator.multiBounceRetention(0.80F, 0.55F);
        float open = ReverbFieldEstimator.multiBounceRetention(0.80F, 0.18F);

        assertTrue(enclosed > open);
    }
}
