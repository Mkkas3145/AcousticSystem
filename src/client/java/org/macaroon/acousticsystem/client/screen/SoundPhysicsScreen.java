package org.macaroon.acousticsystem.client.screen;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.QualityPreset;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig.Settings;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;

import java.util.List;

public final class SoundPhysicsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.translatable("acousticsystem.options.title");

    private long appliedRevision;

    public SoundPhysicsScreen(Screen parent, Options options) {
        super(parent, options, TITLE);
        appliedRevision = AcousticQualityConfig.revision();
    }

    @Override
    protected void addOptions() {
        Settings current = AcousticQualityConfig.settings();
        CycleButton<Boolean> enabled = CycleButton.booleanBuilder(
                        Component.translatable("options.on"),
                        Component.translatable("options.off"),
                        current.enabled()
                )
                .withTooltip(value -> Tooltip.create(Component.translatable(
                        "acousticsystem.options.enabled.tooltip"
                )))
                .create(
                        Component.translatable("acousticsystem.options.enabled"),
                        (button, value) -> AcousticQualityConfig.setEnabled(value)
                );
        OptionsListCompat.addBig(list, enabled);

        list.addHeader(Component.translatable("acousticsystem.options.quality"));
        CycleButton<QualityPreset> preset = CycleButton.builder(
                        SoundPhysicsScreen::presetName,
                        current.preset()
                )
                .withValues(List.of(QualityPreset.values()))
                .withTooltip(value -> Tooltip.create(Component.translatable(
                        "acousticsystem.options.preset." + value.serializedName() + ".tooltip"
                )))
                .create(
                        Component.translatable("acousticsystem.options.preset"),
                        (button, value) -> AcousticQualityConfig.applyPreset(value)
                );
        OptionsListCompat.addBig(list, preset);

        Button details = Button.builder(
                        Component.translatable("acousticsystem.options.details"),
                        button -> minecraft.setScreenAndShow(new SoundPhysicsDetailsScreen(this, options))
                )
                .tooltip(Tooltip.create(Component.translatable(
                        "acousticsystem.options.details.tooltip"
                )))
                .build();
        OptionsListCompat.addBig(list, details);

        Button resetAll = Button.builder(
                        Component.translatable("acousticsystem.options.reset_all"),
                        button -> {
                            AcousticQualityConfig.resetAll();
                            minecraft.setScreenAndShow(new SoundPhysicsScreen(lastScreen, options));
                        }
                )
                .tooltip(Tooltip.create(Component.translatable(
                        "acousticsystem.options.reset_all.tooltip"
                )))
                .build();
        OptionsListCompat.addBig(list, resetAll);
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

    private static Component presetName(QualityPreset preset) {
        Component name = Component.translatable(
                "acousticsystem.options.preset." + preset.serializedName()
        );
        Settings current = AcousticQualityConfig.settings();
        if (current.preset() == preset && current.customized()) {
            return Component.translatable("acousticsystem.options.preset.customized", name);
        }
        return name;
    }
}
