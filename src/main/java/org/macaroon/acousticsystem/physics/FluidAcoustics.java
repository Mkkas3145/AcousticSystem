package org.macaroon.acousticsystem.physics;

/**
 * Lossless, normal-incidence acoustic relations between two bulk media.
 * The returned transmission value is a power-normalized pressure amplitude,
 * so squaring it gives the fraction of incident acoustic power transmitted.
 */
public final class FluidAcoustics {
    public static final double AIR_SOUND_SPEED_METERS_PER_SECOND = 343.0;
    public static final double AIR_IMPEDANCE_RAYL = 415.0;

    private FluidAcoustics() {
    }

    public static float interfaceTransmissionAmplitude(
            double firstImpedanceRayl,
            double secondImpedanceRayl
    ) {
        double first = positive(firstImpedanceRayl);
        double second = positive(secondImpedanceRayl);
        return (float) Math.min(
                1.0,
                2.0 * Math.sqrt(first * second) / (first + second)
        );
    }

    public static float interfaceReflectionAmplitude(
            double firstImpedanceRayl,
            double secondImpedanceRayl
    ) {
        double first = positive(firstImpedanceRayl);
        double second = positive(secondImpedanceRayl);
        return (float) Math.min(1.0, Math.abs(second - first) / (first + second));
    }

    public static double travelTimeSeconds(double distanceMeters, double soundSpeedMetersPerSecond) {
        return Math.max(0.0, distanceMeters) / positive(soundSpeedMetersPerSecond);
    }

    private static double positive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("Acoustic medium properties must be finite and positive");
        }
        return value;
    }
}
