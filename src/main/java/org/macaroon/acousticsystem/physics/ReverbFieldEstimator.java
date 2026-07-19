package org.macaroon.acousticsystem.physics;

public final class ReverbFieldEstimator {
    private ReverbFieldEstimator() {
    }

    public static float fieldStrength(float earlyEnergy, float lateEnergy, float multiBounceFraction) {
        double safeEarly = Math.max(0.0, earlyEnergy);
        double safeLate = Math.max(0.0, lateEnergy);
        double tailRatio = safeLate / Math.max(safeEarly + safeLate, 1.0E-8);
        double pathFraction = Math.max(0.0, Math.min(1.0, multiBounceFraction));
        return (float) Math.max(0.0, Math.min(1.0, Math.sqrt(tailRatio * pathFraction)));
    }

    public static float density(float multiBounceFraction, float temporalOccupancy) {
        double pathFraction = Math.max(0.0, Math.min(1.0, multiBounceFraction));
        double occupancy = Math.max(0.0, Math.min(1.0, temporalOccupancy));
        return (float) Math.sqrt(pathFraction * occupancy);
    }

    public static float multiBounceRetention(float firstBounceEnergy, float laterBounceEnergy) {
        return multiBounceRetention(firstBounceEnergy, laterBounceEnergy, 2);
    }

    public static float multiBounceRetention(
            float firstBounceEnergy,
            float laterBounceEnergy,
            int additionalBounces
    ) {
        double first = Math.max(0.0, firstBounceEnergy);
        double later = Math.max(0.0, laterBounceEnergy);
        if (first <= 1.0E-8 || additionalBounces <= 0) {
            return 0.0F;
        }
        double ratio = Math.max(0.0, Math.min(1.0, later / first));
        return (float) Math.pow(ratio, 1.0 / additionalBounces);
    }
}
