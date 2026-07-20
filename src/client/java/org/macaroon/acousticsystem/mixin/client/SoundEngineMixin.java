package org.macaroon.acousticsystem.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Shadow
    @Final
    private ChannelAccess channelAccess;

    @Inject(method = "play", at = @At("HEAD"))
    private void acousticsystem$prepareFirstFrame(
            SoundInstance sound,
            CallbackInfoReturnable<SoundEngine.PlayResult> cir
    ) {
        AcousticRuntime.prepareSound(sound);
    }

    @Inject(method = "play", at = @At("RETURN"))
    private void acousticsystem$bindCompletedOnset(
            SoundInstance sound,
            CallbackInfoReturnable<SoundEngine.PlayResult> cir
    ) {
        AcousticRuntime.bindPreparedSound(sound, instanceToChannel.get(sound));
    }

    @Inject(method = "updateSource", at = @At("TAIL"))
    private void acousticsystem$recalculateFromCurrentListener(
            Camera camera,
            CallbackInfo ci
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !camera.isInitialized()) {
            return;
        }
        Vec3 listener = camera.position();
        AcousticRuntime.tick(level, listener, instanceToChannel, channelAccess);
    }
}
