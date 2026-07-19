package org.macaroon.acousticsystem.physics;

public final class ReverbDecayEstimator {
    private ReverbDecayEstimator() {
    }

    public static float estimateRt60(float[] impulseEnergy, double binSeconds, float fallbackSeconds) {
        if (impulseEnergy.length < 4 || !Double.isFinite(binSeconds) || binSeconds <= 0.0) {
            return fallbackSeconds;
        }

        double[] decay = new double[impulseEnergy.length];
        double cumulative = 0.0;
        for (int bin = impulseEnergy.length - 1; bin >= 0; bin--) {
            cumulative += Math.max(0.0F, impulseEnergy[bin]);
            decay[bin] = cumulative;
        }
        if (decay[0] <= 1.0E-12) {
            return fallbackSeconds;
        }

        float estimate = regressionEstimate(decay, binSeconds, -5.0, -25.0);
        if (!Float.isFinite(estimate)) {
            estimate = regressionEstimate(decay, binSeconds, -3.0, -15.0);
        }
        if (!Float.isFinite(estimate) || estimate <= 0.0F) {
            return fallbackSeconds;
        }
        return Math.max(0.1F, Math.min(20.0F, estimate));
    }

    public static float estimateRt60FromDecay(
            float[] remainingEnergy,
            double[] sampleTimes,
            float fallbackSeconds
    ) {
        if (remainingEnergy.length != sampleTimes.length || remainingEnergy.length < 3) {
            return fallbackSeconds;
        }
        int referenceIndex = -1;
        for (int index = 0; index < remainingEnergy.length; index++) {
            if (remainingEnergy[index] > 1.0E-10F && Double.isFinite(sampleTimes[index])) {
                referenceIndex = index;
                break;
            }
        }
        if (referenceIndex < 0) {
            return fallbackSeconds;
        }

        double referenceEnergy = remainingEnergy[referenceIndex];
        double referenceTime = sampleTimes[referenceIndex];
        double sumTime = 0.0;
        double sumDb = 0.0;
        double sumTimeSquared = 0.0;
        double sumTimeDb = 0.0;
        int samples = 0;
        for (int index = referenceIndex; index < remainingEnergy.length; index++) {
            float energy = remainingEnergy[index];
            double time = sampleTimes[index] - referenceTime;
            if (energy <= 1.0E-10F || !Double.isFinite(time) || time < 0.0) {
                continue;
            }
            double db = 10.0 * Math.log10(energy / referenceEnergy);
            if (db > 0.5 || db < -45.0) {
                continue;
            }
            sumTime += time;
            sumDb += db;
            sumTimeSquared += time * time;
            sumTimeDb += time * db;
            samples++;
        }
        if (samples < 3) {
            return fallbackSeconds;
        }
        double denominator = samples * sumTimeSquared - sumTime * sumTime;
        if (Math.abs(denominator) < 1.0E-12) {
            return fallbackSeconds;
        }
        double slope = (samples * sumTimeDb - sumTime * sumDb) / denominator;
        if (!Double.isFinite(slope) || slope >= -1.0E-6) {
            return fallbackSeconds;
        }
        return Math.max(0.1F, Math.min(20.0F, (float) (-60.0 / slope)));
    }

    private static float regressionEstimate(
            double[] decay,
            double binSeconds,
            double upperDb,
            double lowerDb
    ) {
        double reference = decay[0];
        double sumTime = 0.0;
        double sumDb = 0.0;
        double sumTimeSquared = 0.0;
        double sumTimeDb = 0.0;
        int samples = 0;
        for (int bin = 0; bin < decay.length; bin++) {
            if (decay[bin] <= 1.0E-15) {
                continue;
            }
            double db = 10.0 * Math.log10(decay[bin] / reference);
            if (db > upperDb || db < lowerDb) {
                continue;
            }
            double time = bin * binSeconds;
            sumTime += time;
            sumDb += db;
            sumTimeSquared += time * time;
            sumTimeDb += time * db;
            samples++;
        }
        if (samples < 4) {
            return Float.NaN;
        }
        double denominator = samples * sumTimeSquared - sumTime * sumTime;
        if (Math.abs(denominator) < 1.0E-12) {
            return Float.NaN;
        }
        double slope = (samples * sumTimeDb - sumTime * sumDb) / denominator;
        if (!Double.isFinite(slope) || slope >= -1.0E-6) {
            return Float.NaN;
        }
        return (float) (-60.0 / slope);
    }
}
