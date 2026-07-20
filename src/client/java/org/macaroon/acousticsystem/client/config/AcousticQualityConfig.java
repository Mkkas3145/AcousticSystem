package org.macaroon.acousticsystem.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.material.AcousticTuning;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.UnaryOperator;

/** Persistent quality and advanced physical controls for the client acoustic solver. */
public final class AcousticQualityConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "acousticsystem.json";
    private static volatile Settings settings = Settings.fromPreset(QualityPreset.HIGH);
    private static volatile long revision;

    private AcousticQualityConfig() {
    }

    public static void load() {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            StoredSettings stored = GSON.fromJson(reader, StoredSettings.class);
            settings = stored == null ? Settings.fromPreset(QualityPreset.HIGH) : stored.validated();
            revision++;
        } catch (Exception exception) {
            AcousticSystem.LOGGER.warn("Could not read {}; using the high quality preset", path, exception);
            settings = Settings.fromPreset(QualityPreset.HIGH);
            revision++;
        }
    }

    public static void save() {
        Path path = configPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(StoredSettings.from(settings), writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            AcousticSystem.LOGGER.warn("Could not save {}", path, exception);
        }
    }

    public static Settings settings() {
        return settings;
    }

    public static long revision() {
        return revision;
    }

    public static void applyPreset(QualityPreset preset) {
        update(Settings.fromPreset(preset)
                .withEnabled(settings.enabled())
                .withPhysics(settings.physics()));
    }

    public static void setEnabled(boolean enabled) {
        update(settings.withEnabled(enabled));
    }

    public static void updatePhysics(UnaryOperator<PhysicsSettings> change) {
        update(settings.withPhysics(change.apply(settings.physics())));
    }

    public static void resetPhysics() {
        update(settings.withPhysics(PhysicsSettings.DEFAULT));
    }

    public static void resetAll() {
        update(Settings.fromPreset(QualityPreset.HIGH));
    }

    public static void update(Settings updated) {
        Settings validated = updated.validated();
        if (!validated.equals(settings)) {
            settings = validated;
            revision++;
        }
    }

    static String serialize(Settings value) {
        return GSON.toJson(StoredSettings.from(value.validated()));
    }

    static Settings deserialize(String json) {
        StoredSettings stored = GSON.fromJson(json, StoredSettings.class);
        return stored == null ? Settings.fromPreset(QualityPreset.HIGH) : stored.validated();
    }

    public static AcousticTuning apply(AcousticTuning base) {
        Settings quality = settings;
        PhysicsSettings physics = quality.physics();
        return new AcousticTuning(
                physics.metersPerBlock(),
                physics.realisticDistanceAttenuation(),
                physics.distanceReferenceMeters(),
                physics.distanceRolloffFactor(),
                physics.airTemperatureCelsius(),
                physics.relativeHumidityPercent(),
                physics.airPressureKilopascals(),
                physics.atmosphericAbsorptionEnabled() ? physics.airAbsorptionScale() : 0.0F,
                quality.reflectionsEnabled() ? physics.reflectionGainScale() : 0.0F,
                quality.reverbEnabled() ? physics.reverbSendScale() : 0.0F,
                quality.reverbEnabled() ? physics.roomGainScale() : 0.0F,
                quality.reverbEnabled() ? physics.lateReverbGainScale() : 0.0F,
                physics.decayTimeScale(),
                quality.reverbEnabled() ? physics.enclosedRoomSend() : 0.0F,
                physics.acousticResponseTimeMilliseconds(),
                Math.round(physics.maxResultAgeMilliseconds()),
                physics.maxDecayTime(),
                quality.roomRayCount(),
                Math.min(quality.sourceRoomRayCount(), quality.roomRayCount()),
                physics.roomProbeDistance(),
                physics.roomOpeningContrast(),
                physics.roomOpeningAreaScale(),
                physics.roomCoupledOpeningEscape(),
                physics.reverbDistanceScale(),
                physics.occlusionSampleRadiusMin(),
                physics.occlusionSampleRadiusMax(),
                physics.occlusionSampleAngularSpread(),
                quality.diffractionEnabled() ? physics.diffractionGainScale() : 0.0F,
                quality.diffractionEnabled() ? quality.diffractionMaxPaths() : 1,
                quality.reverbEnabled() ? quality.lateReverbRayCount() : 24,
                quality.reverbEnabled() ? quality.lateReverbMaxBounces() : 2,
                physics.lateReverbEnergyCutoff()
        );
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public enum QualityPreset {
        LOW("low"), MEDIUM("medium"), HIGH("high"), ULTRA("ultra");

        private final String serializedName;

        QualityPreset(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        static QualityPreset parse(String value) {
            if (value == null) {
                return HIGH;
            }
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return HIGH;
            }
        }
    }

    public enum PhysicsParameter {
        METERS_PER_BLOCK("meters_per_block", 0.25F, 2.0F, 0.05F, 2),
        DISTANCE_REFERENCE("distance_reference", 0.1F, 8.0F, 0.1F, 1),
        DISTANCE_ROLLOFF("distance_rolloff", 0.1F, 4.0F, 0.05F, 2),
        AIR_TEMPERATURE("air_temperature", -40.0F, 50.0F, 1.0F, 0),
        RELATIVE_HUMIDITY("relative_humidity", 1.0F, 100.0F, 1.0F, 0),
        AIR_PRESSURE("air_pressure", 60.0F, 110.0F, 0.5F, 1),
        AIR_ABSORPTION_SCALE("air_absorption_scale", 0.0F, 4.0F, 0.05F, 2),
        REFLECTION_GAIN("reflection_gain", 0.0F, 4.0F, 0.05F, 2),
        REVERB_SEND("reverb_send", 0.0F, 4.0F, 0.05F, 2),
        ROOM_GAIN("room_gain", 0.0F, 4.0F, 0.05F, 2),
        LATE_REVERB_GAIN("late_reverb_gain", 0.0F, 4.0F, 0.05F, 2),
        DECAY_TIME_SCALE("decay_time_scale", 0.25F, 4.0F, 0.05F, 2),
        ENCLOSED_ROOM_SEND("enclosed_room_send", 0.0F, 2.0F, 0.05F, 2),
        RESPONSE_TIME("response_time", 1.0F, 2000.0F, 1.0F, 0),
        MAX_RESULT_AGE("max_result_age", 25.0F, 2000.0F, 25.0F, 0),
        MAX_DECAY_TIME("max_decay_time", 0.1F, 20.0F, 0.1F, 1),
        ROOM_PROBE_DISTANCE("room_probe_distance", 8.0F, 128.0F, 1.0F, 0),
        ROOM_OPENING_CONTRAST("room_opening_contrast", 1.05F, 4.0F, 0.05F, 2),
        ROOM_OPENING_AREA("room_opening_area", 0.0F, 4.0F, 0.05F, 2),
        ROOM_COUPLED_ESCAPE("room_coupled_escape", 0.0F, 1.0F, 0.01F, 2),
        REVERB_DISTANCE("reverb_distance", 1.0F, 128.0F, 1.0F, 0),
        OCCLUSION_RADIUS_MIN("occlusion_radius_min", 0.05F, 1.5F, 0.01F, 2),
        OCCLUSION_RADIUS_MAX("occlusion_radius_max", 0.05F, 2.0F, 0.01F, 2),
        OCCLUSION_ANGULAR_SPREAD("occlusion_angular_spread", 0.0F, 0.20F, 0.005F, 3),
        DIFFRACTION_GAIN("diffraction_gain", 0.0F, 2.0F, 0.05F, 2),
        LATE_REVERB_CUTOFF("late_reverb_cutoff", 0.00001F, 0.05F, 0.00001F, 5);

        private final String key;
        private final float minimum;
        private final float maximum;
        private final float step;
        private final int decimals;

        PhysicsParameter(String key, float minimum, float maximum, float step, int decimals) {
            this.key = key;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.decimals = decimals;
        }

        public String key() { return key; }
        public float minimum() { return minimum; }
        public float maximum() { return maximum; }
        public float step() { return step; }
        public int decimals() { return decimals; }
    }

    public record PhysicsSettings(
            boolean realisticDistanceAttenuation,
            boolean atmosphericAbsorptionEnabled,
            float metersPerBlock,
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
            float maxResultAgeMilliseconds,
            float maxDecayTime,
            float roomProbeDistance,
            float roomOpeningContrast,
            float roomOpeningAreaScale,
            float roomCoupledOpeningEscape,
            float reverbDistanceScale,
            float occlusionSampleRadiusMin,
            float occlusionSampleRadiusMax,
            float occlusionSampleAngularSpread,
            float diffractionGainScale,
            float lateReverbEnergyCutoff
    ) {
        public static final PhysicsSettings DEFAULT = fromTuning(AcousticTuning.DEFAULT, true);

        public PhysicsSettings validated() {
            return fromTuning(toTuning(), atmosphericAbsorptionEnabled);
        }

        public float value(PhysicsParameter parameter) {
            return switch (parameter) {
                case METERS_PER_BLOCK -> metersPerBlock;
                case DISTANCE_REFERENCE -> distanceReferenceMeters;
                case DISTANCE_ROLLOFF -> distanceRolloffFactor;
                case AIR_TEMPERATURE -> airTemperatureCelsius;
                case RELATIVE_HUMIDITY -> relativeHumidityPercent;
                case AIR_PRESSURE -> airPressureKilopascals;
                case AIR_ABSORPTION_SCALE -> airAbsorptionScale;
                case REFLECTION_GAIN -> reflectionGainScale;
                case REVERB_SEND -> reverbSendScale;
                case ROOM_GAIN -> roomGainScale;
                case LATE_REVERB_GAIN -> lateReverbGainScale;
                case DECAY_TIME_SCALE -> decayTimeScale;
                case ENCLOSED_ROOM_SEND -> enclosedRoomSend;
                case RESPONSE_TIME -> acousticResponseTimeMilliseconds;
                case MAX_RESULT_AGE -> maxResultAgeMilliseconds;
                case MAX_DECAY_TIME -> maxDecayTime;
                case ROOM_PROBE_DISTANCE -> roomProbeDistance;
                case ROOM_OPENING_CONTRAST -> roomOpeningContrast;
                case ROOM_OPENING_AREA -> roomOpeningAreaScale;
                case ROOM_COUPLED_ESCAPE -> roomCoupledOpeningEscape;
                case REVERB_DISTANCE -> reverbDistanceScale;
                case OCCLUSION_RADIUS_MIN -> occlusionSampleRadiusMin;
                case OCCLUSION_RADIUS_MAX -> occlusionSampleRadiusMax;
                case OCCLUSION_ANGULAR_SPREAD -> occlusionSampleAngularSpread;
                case DIFFRACTION_GAIN -> diffractionGainScale;
                case LATE_REVERB_CUTOFF -> lateReverbEnergyCutoff;
            };
        }

        public PhysicsSettings with(PhysicsParameter parameter, float value) {
            return new PhysicsSettings(
                    realisticDistanceAttenuation, atmosphericAbsorptionEnabled,
                    parameter == PhysicsParameter.METERS_PER_BLOCK ? value : metersPerBlock,
                    parameter == PhysicsParameter.DISTANCE_REFERENCE ? value : distanceReferenceMeters,
                    parameter == PhysicsParameter.DISTANCE_ROLLOFF ? value : distanceRolloffFactor,
                    parameter == PhysicsParameter.AIR_TEMPERATURE ? value : airTemperatureCelsius,
                    parameter == PhysicsParameter.RELATIVE_HUMIDITY ? value : relativeHumidityPercent,
                    parameter == PhysicsParameter.AIR_PRESSURE ? value : airPressureKilopascals,
                    parameter == PhysicsParameter.AIR_ABSORPTION_SCALE ? value : airAbsorptionScale,
                    parameter == PhysicsParameter.REFLECTION_GAIN ? value : reflectionGainScale,
                    parameter == PhysicsParameter.REVERB_SEND ? value : reverbSendScale,
                    parameter == PhysicsParameter.ROOM_GAIN ? value : roomGainScale,
                    parameter == PhysicsParameter.LATE_REVERB_GAIN ? value : lateReverbGainScale,
                    parameter == PhysicsParameter.DECAY_TIME_SCALE ? value : decayTimeScale,
                    parameter == PhysicsParameter.ENCLOSED_ROOM_SEND ? value : enclosedRoomSend,
                    parameter == PhysicsParameter.RESPONSE_TIME ? value : acousticResponseTimeMilliseconds,
                    parameter == PhysicsParameter.MAX_RESULT_AGE ? value : maxResultAgeMilliseconds,
                    parameter == PhysicsParameter.MAX_DECAY_TIME ? value : maxDecayTime,
                    parameter == PhysicsParameter.ROOM_PROBE_DISTANCE ? value : roomProbeDistance,
                    parameter == PhysicsParameter.ROOM_OPENING_CONTRAST ? value : roomOpeningContrast,
                    parameter == PhysicsParameter.ROOM_OPENING_AREA ? value : roomOpeningAreaScale,
                    parameter == PhysicsParameter.ROOM_COUPLED_ESCAPE ? value : roomCoupledOpeningEscape,
                    parameter == PhysicsParameter.REVERB_DISTANCE ? value : reverbDistanceScale,
                    parameter == PhysicsParameter.OCCLUSION_RADIUS_MIN ? value : occlusionSampleRadiusMin,
                    parameter == PhysicsParameter.OCCLUSION_RADIUS_MAX ? value : occlusionSampleRadiusMax,
                    parameter == PhysicsParameter.OCCLUSION_ANGULAR_SPREAD ? value : occlusionSampleAngularSpread,
                    parameter == PhysicsParameter.DIFFRACTION_GAIN ? value : diffractionGainScale,
                    parameter == PhysicsParameter.LATE_REVERB_CUTOFF ? value : lateReverbEnergyCutoff
            ).validated();
        }

        public PhysicsSettings withRealisticDistance(boolean enabled) {
            return new PhysicsSettings(
                    enabled, atmosphericAbsorptionEnabled, metersPerBlock,
                    distanceReferenceMeters, distanceRolloffFactor, airTemperatureCelsius,
                    relativeHumidityPercent, airPressureKilopascals, airAbsorptionScale,
                    reflectionGainScale, reverbSendScale, roomGainScale, lateReverbGainScale,
                    decayTimeScale, enclosedRoomSend, acousticResponseTimeMilliseconds,
                    maxResultAgeMilliseconds, maxDecayTime,
                    roomProbeDistance, roomOpeningContrast, roomOpeningAreaScale,
                    roomCoupledOpeningEscape, reverbDistanceScale, occlusionSampleRadiusMin,
                    occlusionSampleRadiusMax, occlusionSampleAngularSpread,
                    diffractionGainScale, lateReverbEnergyCutoff
            );
        }

        public PhysicsSettings withAtmosphericAbsorption(boolean enabled) {
            return new PhysicsSettings(
                    realisticDistanceAttenuation, enabled, metersPerBlock,
                    distanceReferenceMeters, distanceRolloffFactor, airTemperatureCelsius,
                    relativeHumidityPercent, airPressureKilopascals, airAbsorptionScale,
                    reflectionGainScale, reverbSendScale, roomGainScale, lateReverbGainScale,
                    decayTimeScale, enclosedRoomSend, acousticResponseTimeMilliseconds,
                    maxResultAgeMilliseconds, maxDecayTime,
                    roomProbeDistance, roomOpeningContrast, roomOpeningAreaScale,
                    roomCoupledOpeningEscape, reverbDistanceScale, occlusionSampleRadiusMin,
                    occlusionSampleRadiusMax, occlusionSampleAngularSpread,
                    diffractionGainScale, lateReverbEnergyCutoff
            );
        }

        private AcousticTuning toTuning() {
            return new AcousticTuning(
                    metersPerBlock, realisticDistanceAttenuation,
                    distanceReferenceMeters, distanceRolloffFactor,
                    airTemperatureCelsius, relativeHumidityPercent, airPressureKilopascals,
                    airAbsorptionScale, reflectionGainScale, reverbSendScale, roomGainScale,
                    lateReverbGainScale, decayTimeScale, enclosedRoomSend,
                    acousticResponseTimeMilliseconds, Math.round(maxResultAgeMilliseconds),
                    maxDecayTime,
                    AcousticTuning.DEFAULT.roomRayCount(), AcousticTuning.DEFAULT.sourceRoomRayCount(),
                    roomProbeDistance, roomOpeningContrast, roomOpeningAreaScale,
                    roomCoupledOpeningEscape, reverbDistanceScale,
                    occlusionSampleRadiusMin, occlusionSampleRadiusMax,
                    occlusionSampleAngularSpread, diffractionGainScale,
                    AcousticTuning.DEFAULT.diffractionMaxPaths(),
                    AcousticTuning.DEFAULT.lateReverbRayCount(),
                    AcousticTuning.DEFAULT.lateReverbMaxBounces(), lateReverbEnergyCutoff
            );
        }

        private static PhysicsSettings fromTuning(AcousticTuning tuning, boolean airEnabled) {
            return new PhysicsSettings(
                    tuning.realisticDistanceAttenuation(), airEnabled,
                    tuning.metersPerBlock(), tuning.distanceReferenceMeters(),
                    tuning.distanceRolloffFactor(), tuning.airTemperatureCelsius(),
                    tuning.relativeHumidityPercent(), tuning.airPressureKilopascals(),
                    tuning.airAbsorptionScale(), tuning.reflectionGainScale(),
                    tuning.reverbSendScale(), tuning.roomGainScale(), tuning.lateReverbGainScale(),
                    tuning.decayTimeScale(), tuning.enclosedRoomSend(),
                    tuning.acousticResponseTimeMilliseconds(), tuning.maxResultAgeMilliseconds(),
                    tuning.maxDecayTime(), tuning.roomProbeDistance(),
                    tuning.roomOpeningContrast(), tuning.roomOpeningAreaScale(),
                    tuning.roomCoupledOpeningEscape(), tuning.reverbDistanceScale(),
                    tuning.occlusionSampleRadiusMin(), tuning.occlusionSampleRadiusMax(),
                    tuning.occlusionSampleAngularSpread(), tuning.diffractionGainScale(),
                    tuning.lateReverbEnergyCutoff()
            );
        }
    }

    public record Settings(
            QualityPreset preset,
            boolean customized,
            boolean enabled,
            boolean reflectionsEnabled,
            boolean diffractionEnabled,
            boolean reverbEnabled,
            int roomRayCount,
            int sourceRoomRayCount,
            int lateReverbRayCount,
            int lateReverbMaxBounces,
            int diffractionMaxPaths,
            PhysicsSettings physics
    ) {
        public static Settings fromPreset(QualityPreset preset) {
            return switch (preset) {
                case LOW -> new Settings(preset, false, true, true, true, true,
                        64, 32, 24, 5, 2, PhysicsSettings.DEFAULT);
                case MEDIUM -> new Settings(preset, false, true, true, true, true,
                        128, 64, 48, 8, 3, PhysicsSettings.DEFAULT);
                case HIGH -> new Settings(preset, false, true, true, true, true,
                        256, 128, 96, 12, 4, PhysicsSettings.DEFAULT);
                case ULTRA -> new Settings(preset, false, true, true, true, true,
                        320, 160, 128, 14, 6, PhysicsSettings.DEFAULT);
            };
        }

        public Settings validated() {
            QualityPreset safePreset = preset == null ? QualityPreset.HIGH : preset;
            int safeRoomRays = clampStep(roomRayCount, 32, 1024, 32);
            int safeSourceRays = Math.min(clampStep(sourceRoomRayCount, 16, 1024, 16), safeRoomRays);
            return new Settings(
                    safePreset, customized, enabled,
                    reflectionsEnabled, diffractionEnabled, reverbEnabled,
                    safeRoomRays, safeSourceRays, clampStep(lateReverbRayCount, 24, 512, 8),
                    Math.clamp(lateReverbMaxBounces, 2, 16),
                    Math.clamp(diffractionMaxPaths, 1, 8),
                    (physics == null ? PhysicsSettings.DEFAULT : physics).validated()
            );
        }

        public Settings withPhysics(PhysicsSettings value) {
            return new Settings(preset, customized, enabled,
                    reflectionsEnabled, diffractionEnabled,
                    reverbEnabled, roomRayCount, sourceRoomRayCount, lateReverbRayCount,
                    lateReverbMaxBounces, diffractionMaxPaths, value);
        }

        public Settings withEnabled(boolean value) {
            return new Settings(preset, customized, value,
                    reflectionsEnabled, diffractionEnabled, reverbEnabled,
                    roomRayCount, sourceRoomRayCount, lateReverbRayCount,
                    lateReverbMaxBounces, diffractionMaxPaths, physics);
        }

        public Settings withReflections(boolean value) {
            return copy(true, value, diffractionEnabled, reverbEnabled, roomRayCount,
                    sourceRoomRayCount, lateReverbRayCount, lateReverbMaxBounces,
                    diffractionMaxPaths);
        }

        public Settings withDiffraction(boolean value) {
            return copy(true, reflectionsEnabled, value, reverbEnabled, roomRayCount,
                    sourceRoomRayCount, lateReverbRayCount, lateReverbMaxBounces,
                    diffractionMaxPaths);
        }

        public Settings withReverb(boolean value) {
            return copy(true, reflectionsEnabled, diffractionEnabled, value, roomRayCount,
                    sourceRoomRayCount, lateReverbRayCount, lateReverbMaxBounces,
                    diffractionMaxPaths);
        }

        public Settings withRoomRays(int value) {
            return copy(true, reflectionsEnabled, diffractionEnabled, reverbEnabled, value,
                    Math.min(sourceRoomRayCount, value), lateReverbRayCount,
                    lateReverbMaxBounces, diffractionMaxPaths);
        }

        public Settings withSourceRoomRays(int value) {
            return copy(true, reflectionsEnabled, diffractionEnabled, reverbEnabled,
                    roomRayCount, value, lateReverbRayCount, lateReverbMaxBounces,
                    diffractionMaxPaths);
        }

        public Settings withLateReverbRays(int value) {
            return copy(true, reflectionsEnabled, diffractionEnabled, reverbEnabled,
                    roomRayCount, sourceRoomRayCount, value, lateReverbMaxBounces,
                    diffractionMaxPaths);
        }

        public Settings withLateReverbBounces(int value) {
            return copy(true, reflectionsEnabled, diffractionEnabled, reverbEnabled,
                    roomRayCount, sourceRoomRayCount, lateReverbRayCount, value,
                    diffractionMaxPaths);
        }

        public Settings withDiffractionPaths(int value) {
            return copy(true, reflectionsEnabled, diffractionEnabled, reverbEnabled,
                    roomRayCount, sourceRoomRayCount, lateReverbRayCount,
                    lateReverbMaxBounces, value);
        }

        private Settings copy(
                boolean custom, boolean reflections, boolean diffraction, boolean reverb,
                int roomRays, int sourceRays, int lateRays, int bounces, int paths
        ) {
            return new Settings(preset, custom, enabled, reflections, diffraction, reverb,
                    roomRays, sourceRays, lateRays, bounces, paths, physics);
        }

        private static int clampStep(int value, int minimum, int maximum, int step) {
            int clamped = Math.clamp(value, minimum, maximum);
            return minimum + Math.round((clamped - minimum) / (float) step) * step;
        }
    }

    private static final class StoredSettings {
        private String preset;
        private boolean customized;
        private Boolean enabled;
        private boolean reflectionsEnabled;
        private boolean diffractionEnabled;
        private boolean reverbEnabled;
        private int roomRayCount;
        private int sourceRoomRayCount;
        private int lateReverbRayCount;
        private int lateReverbMaxBounces;
        private int diffractionMaxPaths;
        private PhysicsSettings physics;

        private Settings validated() {
            QualityPreset quality = QualityPreset.parse(preset);
            Settings defaults = Settings.fromPreset(quality);
            return new Settings(
                    quality, customized, enabled == null || enabled,
                    roomRayCount == 0 ? defaults.reflectionsEnabled() : reflectionsEnabled,
                    roomRayCount == 0 ? defaults.diffractionEnabled() : diffractionEnabled,
                    roomRayCount == 0 ? defaults.reverbEnabled() : reverbEnabled,
                    roomRayCount == 0 ? defaults.roomRayCount() : roomRayCount,
                    sourceRoomRayCount == 0 ? defaults.sourceRoomRayCount() : sourceRoomRayCount,
                    lateReverbRayCount == 0 ? defaults.lateReverbRayCount() : lateReverbRayCount,
                    lateReverbMaxBounces == 0 ? defaults.lateReverbMaxBounces() : lateReverbMaxBounces,
                    diffractionMaxPaths == 0 ? defaults.diffractionMaxPaths() : diffractionMaxPaths,
                    physics == null ? PhysicsSettings.DEFAULT : physics
            ).validated();
        }

        private static StoredSettings from(Settings settings) {
            StoredSettings stored = new StoredSettings();
            stored.preset = settings.preset().serializedName();
            stored.customized = settings.customized();
            stored.enabled = settings.enabled();
            stored.reflectionsEnabled = settings.reflectionsEnabled();
            stored.diffractionEnabled = settings.diffractionEnabled();
            stored.reverbEnabled = settings.reverbEnabled();
            stored.roomRayCount = settings.roomRayCount();
            stored.sourceRoomRayCount = settings.sourceRoomRayCount();
            stored.lateReverbRayCount = settings.lateReverbRayCount();
            stored.lateReverbMaxBounces = settings.lateReverbMaxBounces();
            stored.diffractionMaxPaths = settings.diffractionMaxPaths();
            stored.physics = settings.physics();
            return stored;
        }
    }
}
