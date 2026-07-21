package org.macaroon.acousticsystem.client.screen;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.PhysicsParameter;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.PhysicsSettings;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.Settings;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Locale;

public final class SoundPhysicsDetailsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.translatable(
            "acousticsystem.options.details.title"
    );

    private long appliedRevision;

    public SoundPhysicsDetailsScreen(Screen parent, Options options) {
        super(parent, options, TITLE);
        appliedRevision = AcousticQualityConfig.revision();
    }

    @Override
    protected void addOptions() {
        Settings current = AcousticQualityConfig.settings();
        list.addHeader(Component.translatable("acousticsystem.options.effects"));
        list.addSmall(
                toggle(
                        "reflections", current.reflectionsEnabled(),
                        value -> update(settings -> settings.withReflections(value))
                ),
                toggle(
                        "diffraction", current.diffractionEnabled(),
                        value -> update(settings -> settings.withDiffraction(value))
                )
        );
        OptionsListCompat.addBig(list, toggle(
                "reverb", current.reverbEnabled(),
                value -> update(settings -> settings.withReverb(value))
        ));

        list.addHeader(Component.translatable("acousticsystem.options.sampling"));
        list.addSmall(
                slider(
                        "room_rays", 32, 1024, 32, current.roomRayCount(),
                        value -> update(settings -> settings.withRoomRays(value))
                ),
                slider(
                        "source_room_rays", 16, 1024, 16, current.sourceRoomRayCount(),
                        value -> update(settings -> settings.withSourceRoomRays(value))
                )
        );
        list.addSmall(
                slider(
                        "late_reverb_rays", 24, 512, 8, current.lateReverbRayCount(),
                        value -> update(settings -> settings.withLateReverbRays(value))
                ),
                slider(
                        "late_reverb_bounces", 2, 16, 1, current.lateReverbMaxBounces(),
                        value -> update(settings -> settings.withLateReverbBounces(value))
                )
        );
        OptionsListCompat.addBig(list, slider(
                "diffraction_paths", 1, 8, 1, current.diffractionMaxPaths(),
                value -> update(settings -> settings.withDiffractionPaths(value))
        ));

        PhysicsSettings physics = current.physics();
        list.addHeader(Component.translatable("acousticsystem.options.distance"));
        OptionsListCompat.addBig(list, toggle(
                "realistic_distance", physics.realisticDistanceAttenuation(),
                value -> AcousticQualityConfig.updatePhysics(
                        settings -> settings.withRealisticDistance(value)
                )
        ));
        addPhysicsParameters(
                PhysicsParameter.METERS_PER_BLOCK,
                PhysicsParameter.DISTANCE_REFERENCE,
                PhysicsParameter.DISTANCE_ROLLOFF
        );

        list.addHeader(Component.translatable("acousticsystem.options.atmosphere"));
        OptionsListCompat.addBig(list, toggle(
                "atmospheric_absorption", physics.atmosphericAbsorptionEnabled(),
                value -> AcousticQualityConfig.updatePhysics(
                        settings -> settings.withAtmosphericAbsorption(value)
                )
        ));
        addPhysicsParameters(
                PhysicsParameter.AIR_TEMPERATURE,
                PhysicsParameter.RELATIVE_HUMIDITY,
                PhysicsParameter.AIR_PRESSURE,
                PhysicsParameter.AIR_ABSORPTION_SCALE
        );

        list.addHeader(Component.translatable("acousticsystem.options.levels"));
        addPhysicsParameters(
                PhysicsParameter.REFLECTION_GAIN,
                PhysicsParameter.DIFFRACTION_GAIN,
                PhysicsParameter.REVERB_SEND,
                PhysicsParameter.ROOM_GAIN,
                PhysicsParameter.LATE_REVERB_GAIN,
                PhysicsParameter.ENCLOSED_ROOM_SEND,
                PhysicsParameter.DECAY_TIME_SCALE,
                PhysicsParameter.MAX_DECAY_TIME,
                PhysicsParameter.LATE_REVERB_CUTOFF
        );

        list.addHeader(Component.translatable("acousticsystem.options.room_detection"));
        addPhysicsParameters(
                PhysicsParameter.ROOM_PROBE_DISTANCE,
                PhysicsParameter.ROOM_OPENING_CONTRAST,
                PhysicsParameter.ROOM_OPENING_AREA,
                PhysicsParameter.ROOM_COUPLED_ESCAPE,
                PhysicsParameter.REVERB_DISTANCE
        );

        list.addHeader(Component.translatable("acousticsystem.options.occlusion"));
        addPhysicsParameters(
                PhysicsParameter.OCCLUSION_RADIUS_MIN,
                PhysicsParameter.OCCLUSION_RADIUS_MAX,
                PhysicsParameter.OCCLUSION_ANGULAR_SPREAD
        );

        list.addHeader(Component.translatable("acousticsystem.options.updates"));
        addPhysicsParameters(
                PhysicsParameter.RESPONSE_TIME,
                PhysicsParameter.MAX_RESULT_AGE
        );

    }

    @Override
    public void removed() {
        AcousticQualityConfig.save();
        long currentRevision = AcousticQualityConfig.revision();
        if (currentRevision != appliedRevision) {
            AcousticRuntime.configurationChanged();
            appliedRevision = currentRevision;
        }
        super.removed();
    }

    private static CycleButton<Boolean> toggle(
            String key,
            boolean value,
            Consumer<Boolean> setter
    ) {
        return CycleButton.booleanBuilder(
                        Component.translatable("options.on"),
                        Component.translatable("options.off"),
                        value
                )
                .withTooltip(enabled -> Tooltip.create(Component.translatable(
                        "acousticsystem.options." + key + ".tooltip"
                )))
                .create(
                        Component.translatable("acousticsystem.options." + key),
                        (button, enabled) -> setter.accept(enabled)
                );
    }

    private static QualitySlider slider(
            String key,
            int minimum,
            int maximum,
            int step,
            int value,
            Consumer<Integer> setter
    ) {
        return new QualitySlider(
                Component.translatable("acousticsystem.options." + key),
                Component.translatable("acousticsystem.options." + key + ".tooltip"),
                minimum, maximum, step, value, setter
        );
    }

    private void addPhysicsParameters(PhysicsParameter... parameters) {
        for (int index = 0; index < parameters.length; index += 2) {
            FloatQualitySlider first = physicsSlider(parameters[index]);
            if (index + 1 < parameters.length) {
                list.addSmall(first, physicsSlider(parameters[index + 1]));
            } else {
                OptionsListCompat.addBig(list, first);
            }
        }
    }

    private static FloatQualitySlider physicsSlider(PhysicsParameter parameter) {
        PhysicsSettings current = AcousticQualityConfig.settings().physics();
        return new FloatQualitySlider(
                Component.translatable("acousticsystem.options." + parameter.key()),
                Component.translatable("acousticsystem.options." + parameter.key() + ".tooltip"),
                parameter.minimum(), parameter.maximum(), parameter.step(),
                parameter.decimals(), current.value(parameter),
                value -> AcousticQualityConfig.updatePhysics(
                        settings -> settings.with(parameter, value)
                )
        );
    }

    private static void update(Function<Settings, Settings> change) {
        AcousticQualityConfig.update(change.apply(AcousticQualityConfig.settings()));
    }

    private static final class QualitySlider extends AbstractSliderButton {
        private final Component caption;
        private final int minimum;
        private final int maximum;
        private final int step;
        private final Consumer<Integer> setter;

        private QualitySlider(
                Component caption,
                Component tooltip,
                int minimum,
                int maximum,
                int step,
                int current,
                Consumer<Integer> setter
        ) {
            super(0, 0, 150, 20, Component.empty(), normalize(current, minimum, maximum));
            this.caption = caption;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.setter = setter;
            setTooltip(Tooltip.create(tooltip));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                    "acousticsystem.options.numeric_value", caption, selectedValue()
            ));
        }

        @Override
        protected void applyValue() {
            int selected = selectedValue();
            value = normalize(selected, minimum, maximum);
            setter.accept(selected);
        }

        private int selectedValue() {
            int steps = Math.round((float) ((maximum - minimum) * value / step));
            return Math.clamp(minimum + steps * step, minimum, maximum);
        }

        private static double normalize(int value, int minimum, int maximum) {
            return (Math.clamp(value, minimum, maximum) - minimum) / (double) (maximum - minimum);
        }
    }

    private static final class FloatQualitySlider extends AbstractSliderButton {
        private final Component caption;
        private final float minimum;
        private final float maximum;
        private final float step;
        private final int decimals;
        private final Consumer<Float> setter;

        private FloatQualitySlider(
                Component caption,
                Component tooltip,
                float minimum,
                float maximum,
                float step,
                int decimals,
                float current,
                Consumer<Float> setter
        ) {
            super(0, 0, 150, 20, Component.empty(), normalize(current, minimum, maximum));
            this.caption = caption;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.decimals = decimals;
            this.setter = setter;
            setTooltip(Tooltip.create(tooltip));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            String format = "%." + decimals + "f";
            setMessage(Component.translatable(
                    "acousticsystem.options.numeric_value",
                    caption,
                    Component.literal(String.format(Locale.ROOT, format, selectedValue()))
            ));
        }

        @Override
        protected void applyValue() {
            float selected = selectedValue();
            value = normalize(selected, minimum, maximum);
            setter.accept(selected);
        }

        private float selectedValue() {
            long steps = Math.round((maximum - minimum) * value / step);
            return Math.clamp(minimum + steps * step, minimum, maximum);
        }

        private static double normalize(float value, float minimum, float maximum) {
            return (Math.clamp(value, minimum, maximum) - minimum) / (maximum - minimum);
        }
    }
}
