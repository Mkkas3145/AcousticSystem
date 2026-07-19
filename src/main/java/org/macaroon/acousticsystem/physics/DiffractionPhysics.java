package org.macaroon.acousticsystem.physics;

public final class DiffractionPhysics {
    public static final double SPEED_OF_SOUND_METERS_PER_SECOND = 343.0;

    private DiffractionPhysics() {
    }

    /**
     * Broadband knife-edge diffraction amplitude using the Fresnel parameter and
     * the standard smooth approximation to the Fresnel-Kirchhoff attenuation.
     */
    public static float knifeEdgeAmplitude(double frequencyHz, double pathExcessMeters) {
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("frequencyHz must be finite and positive");
        }
        double excess = Math.max(0.0, pathExcessMeters);
        double wavelength = SPEED_OF_SOUND_METERS_PER_SECOND / frequencyHz;
        double fresnelParameter = Math.sqrt(4.0 * excess / wavelength);
        double shifted = fresnelParameter - 0.1;
        double lossDb = 6.9 + 20.0 * Math.log10(Math.sqrt(shifted * shifted + 1.0) + shifted);
        return (float) Math.max(0.0, Math.min(1.0, Math.pow(10.0, -lossDb / 20.0)));
    }
}
