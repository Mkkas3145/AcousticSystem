package org.macaroon.acousticsystem.client.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.QualityPreset;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.PhysicsParameter;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.PhysicsSettings;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.Settings;
import org.macaroon.acousticsystem.client.material.AcousticTuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticQualityConfigTest {
    @AfterEach
    void restoreDefault() {
        AcousticQualityConfig.setEnabled(true);
        AcousticQualityConfig.resetPhysics();
        AcousticQualityConfig.applyPreset(QualityPreset.HIGH);
    }

    @Test
    void masterSwitchPreservesPresetAndSurvivesPersistence() {
        AcousticQualityConfig.applyPreset(QualityPreset.ULTRA);
        AcousticQualityConfig.setEnabled(false);
        AcousticQualityConfig.applyPreset(QualityPreset.LOW);

        Settings current = AcousticQualityConfig.settings();
        assertFalse(current.enabled());
        assertEquals(QualityPreset.LOW, current.preset());
        assertFalse(AcousticQualityConfig.deserialize(
                AcousticQualityConfig.serialize(current)
        ).enabled());
        assertTrue(AcousticQualityConfig.deserialize("{\"preset\":\"high\"}").enabled());
    }

    @Test
    void presetsIncreaseEveryExpensiveSamplingBudgetMonotonically() {
        Settings previous = null;
        for (QualityPreset preset : QualityPreset.values()) {
            Settings current = Settings.fromPreset(preset);
            if (previous != null) {
                assertTrue(current.roomRayCount() >= previous.roomRayCount());
                assertTrue(current.sourceRoomRayCount() >= previous.sourceRoomRayCount());
                assertTrue(current.lateReverbRayCount() >= previous.lateReverbRayCount());
                assertTrue(current.lateReverbMaxBounces() >= previous.lateReverbMaxBounces());
                assertTrue(current.diffractionMaxPaths() >= previous.diffractionMaxPaths());
            }
            previous = current;
        }
    }

    @Test
    void highPresetPreservesTheExistingSolverBudgets() {
        AcousticQualityConfig.applyPreset(QualityPreset.HIGH);
        AcousticTuning effective = AcousticQualityConfig.apply(AcousticTuning.DEFAULT);

        assertEquals(AcousticTuning.DEFAULT.roomRayCount(), effective.roomRayCount());
        assertEquals(AcousticTuning.DEFAULT.sourceRoomRayCount(), effective.sourceRoomRayCount());
        assertEquals(AcousticTuning.DEFAULT.lateReverbRayCount(), effective.lateReverbRayCount());
        assertEquals(AcousticTuning.DEFAULT.lateReverbMaxBounces(), effective.lateReverbMaxBounces());
        assertEquals(AcousticTuning.DEFAULT.diffractionMaxPaths(), effective.diffractionMaxPaths());
    }

    @Test
    void detailedChangesAreValidatedAndDisableTheirPhysicalStages() {
        Settings custom = Settings.fromPreset(QualityPreset.ULTRA)
                .withRoomRays(49)
                .withSourceRoomRays(240)
                .withReflections(false)
                .withDiffraction(false)
                .withReverb(false)
                .validated();
        AcousticQualityConfig.update(custom);
        AcousticTuning effective = AcousticQualityConfig.apply(AcousticTuning.DEFAULT);

        assertTrue(AcousticQualityConfig.settings().customized());
        assertEquals(64, effective.roomRayCount());
        assertEquals(64, effective.sourceRoomRayCount());
        assertEquals(0.0F, effective.reflectionGainScale());
        assertEquals(0.0F, effective.diffractionGainScale());
        assertEquals(0.0F, effective.reverbSendScale());
        assertEquals(24, effective.lateReverbRayCount());
        assertEquals(2, effective.lateReverbMaxBounces());
        assertFalse(effective.diffractionMaxPaths() > 1);
    }

    @Test
    void everyAdvancedPhysicalParameterCanBeChanged() {
        PhysicsSettings physics = PhysicsSettings.DEFAULT;
        for (PhysicsParameter parameter : PhysicsParameter.values()) {
            float target = Math.abs(physics.value(parameter) - parameter.minimum()) > 1.0E-6F
                    ? parameter.minimum()
                    : parameter.maximum();
            physics = physics.with(parameter, target);
            assertEquals(target, physics.value(parameter), parameter.step() * 0.51F, parameter.key());
        }
    }

    @Test
    void atmosphericAbsorptionSwitchAndStrengthReachTheEffectiveTuning() {
        PhysicsSettings doubled = PhysicsSettings.DEFAULT
                .with(PhysicsParameter.AIR_ABSORPTION_SCALE, 2.0F)
                .withRealisticDistance(false)
                .withAtmosphericAbsorption(false);
        AcousticQualityConfig.updatePhysics(ignored -> doubled);
        AcousticTuning disabled = AcousticQualityConfig.apply(AcousticTuning.DEFAULT);
        assertFalse(disabled.realisticDistanceAttenuation());
        assertEquals(0.0F, disabled.airAbsorptionScale());

        AcousticQualityConfig.updatePhysics(settings -> settings.withAtmosphericAbsorption(true));
        AcousticTuning enabled = AcousticQualityConfig.apply(AcousticTuning.DEFAULT);
        assertEquals(2.0F, enabled.airAbsorptionScale());
    }

    @Test
    void everyAdvancedValueSurvivesJsonPersistence() {
        Settings original = Settings.fromPreset(QualityPreset.ULTRA)
                .withRoomRays(448)
                .withPhysics(PhysicsSettings.DEFAULT
                        .with(PhysicsParameter.AIR_TEMPERATURE, -12.0F)
                        .with(PhysicsParameter.RELATIVE_HUMIDITY, 83.0F)
                        .with(PhysicsParameter.AIR_ABSORPTION_SCALE, 1.75F)
                        .with(PhysicsParameter.ROOM_OPENING_CONTRAST, 2.15F)
                        .withAtmosphericAbsorption(false));

        Settings restored = AcousticQualityConfig.deserialize(
                AcousticQualityConfig.serialize(original)
        );

        assertEquals(original.validated(), restored);
    }
}
