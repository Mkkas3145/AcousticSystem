package org.macaroon.acousticsystem.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
abstract class ChannelMixin {
    @Shadow
    @Final
    private int source;

    @Unique
    private Vec3 acousticsystem$position = Vec3.ZERO;

    @Unique
    private boolean acousticsystem$relative;

    @Inject(method = "setSelfPosition", at = @At("HEAD"))
    private void acousticsystem$capturePosition(Vec3 position, CallbackInfo ci) {
        acousticsystem$position = position;
    }

    @Inject(method = "setRelative", at = @At("HEAD"))
    private void acousticsystem$captureRelative(boolean relative, CallbackInfo ci) {
        acousticsystem$relative = relative;
    }

    @Inject(method = "linearAttenuation", at = @At("TAIL"))
    private void acousticsystem$usePhysicalDistanceAttenuation(
            float maximumDistance,
            CallbackInfo ci
    ) {
        // Relative/UI sounds call disableAttenuation instead and never pass here.
        OpenALAcousticEffects.configureDistanceAttenuation(source, maximumDistance);
    }

    @Inject(method = "play", at = @At("HEAD"))
    private void acousticsystem$applyBeforePlayback(CallbackInfo ci) {
        AcousticRuntime.applyBeforePlay(source, acousticsystem$position, acousticsystem$relative);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void acousticsystem$releaseEffects(CallbackInfo ci) {
        OpenALAcousticEffects.releaseSource(source);
    }
}
