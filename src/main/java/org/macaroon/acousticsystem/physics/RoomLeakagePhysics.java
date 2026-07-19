package org.macaroon.acousticsystem.physics;

public final class RoomLeakagePhysics {
    private static final double SABINE_METRIC_CONSTANT = 0.161;
    private static final double MINIMUM_SURFACE_ABSORPTION = 0.01;

    private RoomLeakagePhysics() {
    }

    public static Result evaluate(
            double volume,
            double solidSurfaceArea,
            double solidAbsorptionArea,
            double openingArea,
            double openingAbsorptionArea
    ) {
        double safeVolume = Math.max(0.0, volume);
        double safeSurfaceArea = Math.max(0.0, solidSurfaceArea);
        double safeOpeningArea = Math.max(0.0, openingArea);
        if (safeVolume <= 1.0E-6 || safeSurfaceArea <= 1.0E-6) {
            return new Result(0.12F, 0.0F, 0.0F, 0.0F);
        }

        double surfaceAbsorption = Math.max(
                Math.max(0.0, solidAbsorptionArea),
                safeSurfaceArea * MINIMUM_SURFACE_ABSORPTION
        );
        double openingAbsorption = Math.max(0.0, openingAbsorptionArea);
        double totalAbsorption = surfaceAbsorption + openingAbsorption;
        double decay = SABINE_METRIC_CONSTANT * safeVolume / totalAbsorption;
        double decayRatio = surfaceAbsorption / totalAbsorption;
        double levelRetention = Math.sqrt(decayRatio);
        double enclosure = safeSurfaceArea / (safeSurfaceArea + safeOpeningArea);
        return new Result(
                (float) decay,
                (float) decayRatio,
                (float) levelRetention,
                (float) enclosure
        );
    }

    public static double projectedOpeningArea(double solidAngleSteradians, double distanceMeters) {
        return Math.max(0.0, solidAngleSteradians)
                * Math.max(0.0, distanceMeters)
                * Math.max(0.0, distanceMeters);
    }

    public record Result(
            float reverberationTimeSeconds,
            float decayTimeRatio,
            float levelRetention,
            float enclosure
    ) {
    }
}
