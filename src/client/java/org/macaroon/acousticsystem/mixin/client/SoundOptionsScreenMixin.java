package org.macaroon.acousticsystem.mixin.client;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.macaroon.acousticsystem.client.screen.SoundPhysicsScreen;
import org.macaroon.acousticsystem.client.screen.OptionsListCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundOptionsScreen.class)
abstract class SoundOptionsScreenMixin extends OptionsSubScreen {
    private SoundOptionsScreenMixin(Screen parent, Options options, Component title) {
        super(parent, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void acousticsystem$addSoundPhysicsButton(CallbackInfo ci) {
        OptionsListCompat.addBig(list, Button.builder(
                        Component.translatable("acousticsystem.options.title"),
                        button -> minecraft.setScreenAndShow(new SoundPhysicsScreen(
                                (Screen) (Object) this,
                                options
                        ))
                )
                .tooltip(Tooltip.create(Component.translatable(
                        "acousticsystem.options.open.tooltip"
                )))
                .build());
    }
}
