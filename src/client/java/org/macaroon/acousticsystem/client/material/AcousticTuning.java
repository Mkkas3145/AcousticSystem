package org.macaroon.acousticsystem.client.material;

import com.google.gson.JsonObject;
import net.minecraft.util.Mth;

public record AcousticTuning(
        float metersPerBlock,
        boolean realisticDistanceAttenuation,
        float distanceReferenceMeters,
        float distanceRolloffFactor,
        float airTemperatureCelsius,
        float relativeHumidityPercent,
        float airPressureKilopascals,
        float airAbsorptionScale,
        float reflectionGainScale,
        float reverbSendScale,
        float roomGainScale,
        float lateReverbGainScale,
        float decayTimeScale,
        float enclosedRoomSend,
        float acousticResponseTimeMilliseconds,
        int maxResultAgeMilliseconds,
        float maxDecayTime,
        int roomRayCount,
        int sourceRoomRayCount,
        float roomProbeDistance,
        float roomOpeningContrast,
        float roomOpeningAreaScale,
        float roomCoupledOpeningEscape,
        float reverbDistanceScale,
        float occlusionSampleRadiusMin,
        float occlusionSampleRadiusMax,
        float occlusionSampleAngularSpread,
        float diffractionGainScale,
        int diffractionMaxPaths,
        int lateReverbRayCount,
        int lateReverbMaxBounces,
        float lateReverbEnergyCutoff
) {
    public static final AcousticTuning DEFAULT = new AcousticTuning(
            1.0F,
            true,
            1.0F,
            1.0F,
            20.0F,
            50.0F,
            101.325F,
            1.0F,
            1.0F,
            1.0F,
            1.0F,
            1.0F,
            1.0F,
            1.0F,
            8.0F,
            500,
            3.0F,
            256,
            128,
            32.0F,
            1.35F,
            1.0F,
            0.35F,
            12.0F,
            0.22F,
            0.55F,
            0.035F,
            1.0F,
            4,
            96,
            12,
            0.002F
    );

    public AcousticTuning {
        metersPerBlock = Mth.clamp(metersPerBlock, 0.25F, 2.0F);
        distanceReferenceMeters = Mth.clamp(distanceReferenceMeters, 0.1F, 8.0F);
        distanceRolloffFactor = Mth.clamp(distanceRolloffFactor, 0.1F, 4.0F);
        airTemperatureCelsius = Mth.clamp(airTemperatureCelsius, -40.0F, 50.0F);
        relativeHumidityPercent = Mth.clamp(relativeHumidityPercent, 1.0F, 100.0F);
        airPressureKilopascals = Mth.clamp(airPressureKilopascals, 60.0F, 110.0F);
        airAbsorptionScale = Mth.clamp(airAbsorptionScale, 0.0F, 4.0F);
        reflectionGainScale = Mth.clamp(reflectionGainScale, 0.0F, 4.0F);
        reverbSendScale = Mth.clamp(reverbSendScale, 0.0F, 4.0F);
        roomGainScale = Mth.clamp(roomGainScale, 0.0F, 4.0F);
        lateReverbGainScale = Mth.clamp(lateReverbGainScale, 0.0F, 4.0F);
        decayTimeScale = Mth.clamp(decayTimeScale, 0.25F, 4.0F);
        enclosedRoomSend = Mth.clamp(enclosedRoomSend, 0.0F, 2.0F);
        acousticResponseTimeMilliseconds = Mth.clamp(
                acousticResponseTimeMilliseconds, 1.0F, 2000.0F
        );
        maxResultAgeMilliseconds = Mth.clamp(maxResultAgeMilliseconds, 25, 2000);
        maxDecayTime = Mth.clamp(maxDecayTime, 0.1F, 20.0F);
        roomRayCount = Mth.clamp(roomRayCount, 32, 1024);
        sourceRoomRayCount = Mth.clamp(sourceRoomRayCount, 16, roomRayCount);
        roomProbeDistance = Mth.clamp(roomProbeDistance, 8.0F, 128.0F);
        roomOpeningContrast = Mth.clamp(roomOpeningContrast, 1.05F, 4.0F);
        roomOpeningAreaScale = Mth.clamp(roomOpeningAreaScale, 0.0F, 4.0F);
        roomCoupledOpeningEscape = Mth.clamp(roomCoupledOpeningEscape, 0.0F, 1.0F);
        reverbDistanceScale = Mth.clamp(reverbDistanceScale, 1.0F, 128.0F);
        occlusionSampleRadiusMin = Mth.clamp(occlusionSampleRadiusMin, 0.05F, 1.5F);
        occlusionSampleRadiusMax = Mth.clamp(
                occlusionSampleRadiusMax,
                occlusionSampleRadiusMin,
                2.0F
        );
        occlusionSampleAngularSpread = Mth.clamp(occlusionSampleAngularSpread, 0.0F, 0.20F);
        diffractionGainScale = Mth.clamp(diffractionGainScale, 0.0F, 2.0F);
        diffractionMaxPaths = Mth.clamp(diffractionMaxPaths, 1, 8);
        lateReverbRayCount = Mth.clamp(lateReverbRayCount, 24, 512);
        lateReverbMaxBounces = Mth.clamp(lateReverbMaxBounces, 2, 16);
        lateReverbEnergyCutoff = Mth.clamp(lateReverbEnergyCutoff, 0.00001F, 0.05F);
    }

    public static AcousticTuning fromJson(JsonObject object, AcousticTuning fallback) {
        if (object == null) {
            return fallback;
        }
        return new AcousticTuning(
                read(object, "meters_per_block", fallback.metersPerBlock),
                object.has("realistic_distance_attenuation")
                        ? object.get("realistic_distance_attenuation").getAsBoolean()
                        : fallback.realisticDistanceAttenuation,
                read(object, "distance_reference_meters", fallback.distanceReferenceMeters),
                read(object, "distance_rolloff_factor", fallback.distanceRolloffFactor),
                read(object, "air_temperature_celsius", fallback.airTemperatureCelsius),
                read(object, "relative_humidity_percent", fallback.relativeHumidityPercent),
                read(object, "air_pressure_kpa", fallback.airPressureKilopascals),
                read(object, "air_absorption_scale", fallback.airAbsorptionScale),
                read(object, "reflection_gain_scale", fallback.reflectionGainScale),
                read(object, "reverb_send_scale", fallback.reverbSendScale),
                read(object, "room_gain_scale", fallback.roomGainScale),
                read(object, "late_reverb_gain_scale", fallback.lateReverbGainScale),
                read(object, "decay_time_scale", fallback.decayTimeScale),
                read(object, "enclosed_room_send", fallback.enclosedRoomSend),
                readResponseTime(object, fallback.acousticResponseTimeMilliseconds),
                object.has("max_result_age_ms")
                        ? object.get("max_result_age_ms").getAsInt()
                        : fallback.maxResultAgeMilliseconds,
                read(object, "max_decay_time", fallback.maxDecayTime),
                object.has("room_ray_count")
                        ? object.get("room_ray_count").getAsInt()
                        : fallback.roomRayCount,
                object.has("source_room_ray_count")
                        ? object.get("source_room_ray_count").getAsInt()
                        : fallback.sourceRoomRayCount,
                read(object, "room_probe_distance", fallback.roomProbeDistance),
                read(object, "room_opening_contrast", fallback.roomOpeningContrast),
                readOpeningAreaScale(object, fallback.roomOpeningAreaScale),
                read(object, "room_coupled_opening_escape", fallback.roomCoupledOpeningEscape),
                read(object, "reverb_distance_scale", fallback.reverbDistanceScale),
                read(object, "occlusion_sample_radius_min", fallback.occlusionSampleRadiusMin),
                read(object, "occlusion_sample_radius_max", fallback.occlusionSampleRadiusMax),
                read(object, "occlusion_sample_angular_spread", fallback.occlusionSampleAngularSpread),
                read(object, "diffraction_gain_scale", fallback.diffractionGainScale),
                object.has("diffraction_max_paths")
                        ? object.get("diffraction_max_paths").getAsInt()
                        : fallback.diffractionMaxPaths,
                object.has("late_reverb_ray_count")
                        ? object.get("late_reverb_ray_count").getAsInt()
                        : fallback.lateReverbRayCount,
                object.has("late_reverb_max_bounces")
                        ? object.get("late_reverb_max_bounces").getAsInt()
                        : fallback.lateReverbMaxBounces,
                read(object, "late_reverb_energy_cutoff", fallback.lateReverbEnergyCutoff)
        );
    }

    private static float read(JsonObject object, String name, float fallback) {
        return object.has(name) ? object.get(name).getAsFloat() : fallback;
    }

    private static float readResponseTime(JsonObject object, float fallback) {
        if (object.has("acoustic_response_time_ms")) {
            return object.get("acoustic_response_time_ms").getAsFloat();
        }
        // Backward-compatible conversion from the former per-update retention. The new
        // value is a real exponential time constant and is independent of update rate.
        if (object.has("reverb_parameter_smoothing")) {
            double retention = Mth.clamp(
                    object.get("reverb_parameter_smoothing").getAsDouble(),
                    0.01,
                    0.9999
            );
            return (float) (-50.0 / Math.log(retention));
        }
        return fallback;
    }

    public double meters(double blocks) {
        return blocks * metersPerBlock;
    }

    public double blocks(double meters) {
        return meters / metersPerBlock;
    }

    private static float readOpeningAreaScale(JsonObject object, float fallback) {
        if (object.has("room_opening_area_scale")) {
            return object.get("room_opening_area_scale").getAsFloat();
        }
        if (object.has("room_opening_leakage_scale")) {
            return object.get("room_opening_leakage_scale").getAsFloat() * 0.1F;
        }
        return fallback;
    }
}
