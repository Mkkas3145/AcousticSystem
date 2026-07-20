package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;

/**
 * Exact control-rate evolution of a diffuse acoustic field. The state is stored as
 * band energy, so changing geometry or moving between coupled regions changes the
 * boundary conditions without replacing the energy already present in the field.
 */
public final class DiffuseFieldDynamics {
    private static final double SIXTY_DECIBEL_ENERGY_RATIO = 1.0E-6;
    private static final double SIXTY_DECIBEL_EXPONENT =
            -Math.log(SIXTY_DECIBEL_ENERGY_RATIO);

    private DiffuseFieldDynamics() {
    }

    public static RoomAcoustics advance(
            RoomAcoustics state,
            RoomAcoustics boundary,
            double elapsedSeconds
    ) {
        if (!(elapsedSeconds > 0.0) || !Double.isFinite(elapsedSeconds)) {
            return state;
        }

        double midRt60 = Math.max(0.1, boundary.decayTime());
        double lowRt60 = Math.max(
                0.1,
                midRt60 * boundary.decayLowFrequencyRatio()
        );
        double highRt60 = Math.max(
                0.1,
                midRt60 * boundary.decayHighFrequencyRatio()
        );
        float midAmplitude = evolveAmplitude(
                state.gain(), boundary.gain(), midRt60, elapsedSeconds
        );
        float lowAmplitude = evolveAmplitude(
                state.gain() * state.gainLowFrequency(),
                boundary.gain() * boundary.gainLowFrequency(),
                lowRt60,
                elapsedSeconds
        );
        float highAmplitude = evolveAmplitude(
                state.gain() * state.gainHighFrequency(),
                boundary.gain() * boundary.gainHighFrequency(),
                highRt60,
                elapsedSeconds
        );
        float lowRatio = midAmplitude <= 1.0E-8F
                ? boundary.gainLowFrequency()
                : clamp(lowAmplitude / midAmplitude, 0.1F, 1.0F);
        float highRatio = midAmplitude <= 1.0E-8F
                ? boundary.gainHighFrequency()
                : clamp(highAmplitude / midAmplitude, 0.0F, 1.0F);
        float response = responseAmount(midRt60, elapsedSeconds);

        return new RoomAcoustics(
                lerp(state.density(), boundary.density(), response),
                lerp(state.diffusion(), boundary.diffusion(), response),
                midAmplitude,
                highRatio,
                lowRatio,
                lerp(state.decayTime(), boundary.decayTime(), response),
                lerp(
                        state.decayHighFrequencyRatio(),
                        boundary.decayHighFrequencyRatio(),
                        response
                ),
                lerp(
                        state.decayLowFrequencyRatio(),
                        boundary.decayLowFrequencyRatio(),
                        response
                ),
                lerp(state.reflectionsGain(), boundary.reflectionsGain(), response),
                lerp(state.reflectionsDelay(), boundary.reflectionsDelay(), response),
                lerp(state.reflectionsPan(), boundary.reflectionsPan(), response),
                lerp(state.lateReverbGain(), boundary.lateReverbGain(), response),
                lerp(state.lateReverbDelay(), boundary.lateReverbDelay(), response),
                lerp(state.lateReverbPan(), boundary.lateReverbPan(), response),
                lerp(state.modulationTime(), boundary.modulationTime(), response),
                lerp(state.modulationDepth(), boundary.modulationDepth(), response),
                lerp(
                        state.airAbsorptionGainHighFrequency(),
                        boundary.airAbsorptionGainHighFrequency(),
                        response
                )
        );
    }

    static float evolveAmplitude(
            float currentAmplitude,
            float targetAmplitude,
            double rt60Seconds,
            double elapsedSeconds
    ) {
        double currentEnergy = Math.max(0.0, currentAmplitude)
                * Math.max(0.0, currentAmplitude);
        double targetEnergy = Math.max(0.0, targetAmplitude)
                * Math.max(0.0, targetAmplitude);
        double retention = Math.exp(
                -SIXTY_DECIBEL_EXPONENT * elapsedSeconds
                        / Math.max(0.1, rt60Seconds)
        );
        return (float) Math.sqrt(Math.max(
                0.0,
                targetEnergy + (currentEnergy - targetEnergy) * retention
        ));
    }

    static float responseAmount(double rt60Seconds, double elapsedSeconds) {
        return (float) (1.0 - Math.exp(
                -SIXTY_DECIBEL_EXPONENT * elapsedSeconds
                        / Math.max(0.1, rt60Seconds)
        ));
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static Vec3 lerp(Vec3 from, Vec3 to, float amount) {
        return from.lerp(to, amount);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
