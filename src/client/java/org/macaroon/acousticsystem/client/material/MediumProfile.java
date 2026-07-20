package org.macaroon.acousticsystem.client.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;

public final class MediumProfile {
    public static final MediumProfile AIR = new MediumProfile(
            new float[]{1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F},
            0.0F, 1.0F,
            0.0F, 0.2F, 0.02F, 0.9F, 0.95F,
            0.15F, 0.8F, 1.0F,
            0.01F, 0.0F, 0.05F, 0.01F,
            0.25F, 0.0F, 0.92F, 0.18F,
            343.0F, 415.0F
    );
    public static final MediumProfile WATER = new MediumProfile(
            new float[]{0.34F, 0.48F, 0.68F, 0.86F, 0.82F, 0.64F, 0.42F, 0.23F},
            0.28F, 0.36F,
            0.95F, 0.90F, 0.19F, 0.16F, 0.85F,
            0.93F, 0.32F, 1.25F,
            0.13F, 0.012F, 0.68F, 0.018F,
            0.45F, 0.07F, 0.995F, 0.18F,
            1480.0F, 1_480_000.0F
    );

    private final float[] gain;
    private final float reverbSend;
    private final float reverbHighFrequencyGain;
    private final float density;
    private final float diffusion;
    private final float roomGain;
    private final float roomGainHighFrequency;
    private final float roomGainLowFrequency;
    private final float decayTime;
    private final float decayHighFrequencyRatio;
    private final float decayLowFrequencyRatio;
    private final float reflectionsGain;
    private final float reflectionsDelay;
    private final float lateReverbGain;
    private final float lateReverbDelay;
    private final float modulationTime;
    private final float modulationDepth;
    private final float airAbsorptionGainHighFrequency;
    private final float transitionDepth;
    private final float soundSpeedMetersPerSecond;
    private final float acousticImpedanceRayl;

    public MediumProfile(
            float[] gain,
            float reverbSend,
            float reverbHighFrequencyGain,
            float density,
            float diffusion,
            float roomGain,
            float roomGainHighFrequency,
            float roomGainLowFrequency,
            float decayTime,
            float decayHighFrequencyRatio,
            float decayLowFrequencyRatio,
            float reflectionsGain,
            float reflectionsDelay,
            float lateReverbGain,
            float lateReverbDelay,
            float modulationTime,
            float modulationDepth,
            float airAbsorptionGainHighFrequency,
            float transitionDepth,
            float soundSpeedMetersPerSecond,
            float acousticImpedanceRayl
    ) {
        this.gain = clampBands(gain);
        this.reverbSend = Mth.clamp(reverbSend, 0.0F, 1.0F);
        this.reverbHighFrequencyGain = Mth.clamp(reverbHighFrequencyGain, 0.0F, 1.0F);
        this.density = Mth.clamp(density, 0.0F, 1.0F);
        this.diffusion = Mth.clamp(diffusion, 0.0F, 1.0F);
        this.roomGain = Mth.clamp(roomGain, 0.0F, 1.0F);
        this.roomGainHighFrequency = Mth.clamp(roomGainHighFrequency, 0.0F, 1.0F);
        this.roomGainLowFrequency = Mth.clamp(roomGainLowFrequency, 0.0F, 1.0F);
        this.decayTime = Mth.clamp(decayTime, 0.1F, 20.0F);
        this.decayHighFrequencyRatio = Mth.clamp(decayHighFrequencyRatio, 0.1F, 2.0F);
        this.decayLowFrequencyRatio = Mth.clamp(decayLowFrequencyRatio, 0.1F, 2.0F);
        this.reflectionsGain = Mth.clamp(reflectionsGain, 0.0F, 3.16F);
        this.reflectionsDelay = Mth.clamp(reflectionsDelay, 0.0F, 0.3F);
        this.lateReverbGain = Mth.clamp(lateReverbGain, 0.0F, 10.0F);
        this.lateReverbDelay = Mth.clamp(lateReverbDelay, 0.0F, 0.1F);
        this.modulationTime = Mth.clamp(modulationTime, 0.04F, 4.0F);
        this.modulationDepth = Mth.clamp(modulationDepth, 0.0F, 1.0F);
        this.airAbsorptionGainHighFrequency = Mth.clamp(airAbsorptionGainHighFrequency, 0.892F, 1.0F);
        this.transitionDepth = Mth.clamp(transitionDepth, 0.01F, 1.0F);
        this.soundSpeedMetersPerSecond = Mth.clamp(soundSpeedMetersPerSecond, 100.0F, 10_000.0F);
        this.acousticImpedanceRayl = Mth.clamp(acousticImpedanceRayl, 1.0F, 100_000_000.0F);
    }

    public static MediumProfile fromJson(JsonObject object, MediumProfile fallback) {
        if (object == null) {
            return fallback;
        }
        return new MediumProfile(
                readBands(object, "gain", fallback.gain),
                read(object, "reverb_send", fallback.reverbSend),
                read(object, "reverb_high_frequency_gain", fallback.reverbHighFrequencyGain),
                read(object, "density", fallback.density),
                read(object, "diffusion", fallback.diffusion),
                read(object, "room_gain", fallback.roomGain),
                read(object, "room_gain_high_frequency", fallback.roomGainHighFrequency),
                read(object, "room_gain_low_frequency", fallback.roomGainLowFrequency),
                read(object, "decay_time", fallback.decayTime),
                read(object, "decay_high_frequency_ratio", fallback.decayHighFrequencyRatio),
                read(object, "decay_low_frequency_ratio", fallback.decayLowFrequencyRatio),
                read(object, "reflections_gain", fallback.reflectionsGain),
                read(object, "reflections_delay", fallback.reflectionsDelay),
                read(object, "late_reverb_gain", fallback.lateReverbGain),
                read(object, "late_reverb_delay", fallback.lateReverbDelay),
                read(object, "modulation_time", fallback.modulationTime),
                read(object, "modulation_depth", fallback.modulationDepth),
                read(object, "air_absorption_gain_high_frequency", fallback.airAbsorptionGainHighFrequency),
                read(object, "transition_depth", fallback.transitionDepth),
                read(object, "sound_speed_meters_per_second", fallback.soundSpeedMetersPerSecond),
                read(object, "acoustic_impedance_rayl", fallback.acousticImpedanceRayl)
        );
    }

    public float gain(int band) { return gain[band]; }
    public float reverbSend() { return reverbSend; }
    public float reverbHighFrequencyGain() { return reverbHighFrequencyGain; }
    public float density() { return density; }
    public float diffusion() { return diffusion; }
    public float roomGain() { return roomGain; }
    public float roomGainHighFrequency() { return roomGainHighFrequency; }
    public float roomGainLowFrequency() { return roomGainLowFrequency; }
    public float decayTime() { return decayTime; }
    public float decayHighFrequencyRatio() { return decayHighFrequencyRatio; }
    public float decayLowFrequencyRatio() { return decayLowFrequencyRatio; }
    public float reflectionsGain() { return reflectionsGain; }
    public float reflectionsDelay() { return reflectionsDelay; }
    public float lateReverbGain() { return lateReverbGain; }
    public float lateReverbDelay() { return lateReverbDelay; }
    public float modulationTime() { return modulationTime; }
    public float modulationDepth() { return modulationDepth; }
    public float airAbsorptionGainHighFrequency() { return airAbsorptionGainHighFrequency; }
    public float transitionDepth() { return transitionDepth; }
    public float soundSpeedMetersPerSecond() { return soundSpeedMetersPerSecond; }
    public float acousticImpedanceRayl() { return acousticImpedanceRayl; }

    private static float read(JsonObject object, String name, float fallback) {
        return object.has(name) ? object.get(name).getAsFloat() : fallback;
    }

    private static float[] readBands(JsonObject object, String name, float[] fallback) {
        if (!object.has(name)) {
            return fallback.clone();
        }
        JsonArray array = object.getAsJsonArray(name);
        return AcousticBands.read(array, "medium." + name);
    }

    private static float[] clampBands(float[] values) {
        return AcousticBands.clamp(values);
    }
}
