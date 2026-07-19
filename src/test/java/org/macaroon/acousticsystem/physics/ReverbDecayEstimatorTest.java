package org.macaroon.acousticsystem.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReverbDecayEstimatorTest {
    @Test
    void recoversRt60FromAnExponentialEnergyTail() {
        double binSeconds = 0.01;
        float expectedRt60 = 1.8F;
        float[] impulse = exponentialTail(240, binSeconds, expectedRt60);

        float estimated = ReverbDecayEstimator.estimateRt60(impulse, binSeconds, 0.4F);

        assertEquals(expectedRt60, estimated, 0.04F);
    }

    @Test
    void usesFallbackWhenNoMeasurableTailExists() {
        float[] impulse = new float[128];
        impulse[2] = 1.0F;

        assertEquals(0.35F, ReverbDecayEstimator.estimateRt60(impulse, 0.01, 0.35F), 1.0E-6F);
    }

    @Test
    void fasterEnergyDecayProducesShorterRt60() {
        float fast = ReverbDecayEstimator.estimateRt60(exponentialTail(180, 0.01, 0.6F), 0.01, 0.3F);
        float slow = ReverbDecayEstimator.estimateRt60(exponentialTail(180, 0.01, 1.5F), 0.01, 0.3F);

        org.junit.jupiter.api.Assertions.assertTrue(fast < slow);
    }

    @Test
    void recoversRt60FromPopulationEnergySamples() {
        float expectedRt60 = 1.2F;
        float[] energy = new float[8];
        double[] times = new double[8];
        double decayRate = Math.log(1_000_000.0) / expectedRt60;
        for (int index = 0; index < energy.length; index++) {
            times[index] = index * 0.08;
            energy[index] = (float) Math.exp(-decayRate * times[index]);
        }

        assertEquals(
                expectedRt60,
                ReverbDecayEstimator.estimateRt60FromDecay(energy, times, 0.4F),
                0.03F
        );
    }

    private static float[] exponentialTail(int bins, double binSeconds, float rt60) {
        float[] impulse = new float[bins];
        double decayRate = Math.log(1_000_000.0) / rt60;
        for (int bin = 0; bin < bins; bin++) {
            impulse[bin] = (float) Math.exp(-decayRate * bin * binSeconds);
        }
        return impulse;
    }
}
