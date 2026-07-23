package org.macaroon.acousticsystem.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.audio.AcousticPcmProvider;
import org.macaroon.acousticsystem.client.audio.AcousticTeeAudioStream;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects;
import org.macaroon.acousticsystem.client.audio.SoftwareAcousticMixer;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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

    @Unique
    private float acousticsystem$volume = 1.0F;

    @Unique
    private float acousticsystem$pitch = 1.0F;

    @Unique
    private boolean acousticsystem$looping;

    @Inject(method = "setSelfPosition", at = @At("HEAD"))
    private void acousticsystem$capturePosition(Vec3 position, CallbackInfo ci) {
        acousticsystem$position = position;
    }

    @Inject(method = "setVolume", at = @At("HEAD"))
    private void acousticsystem$setWetVolume(float volume, CallbackInfo ci) {
        acousticsystem$volume = volume;
        SoftwareAcousticMixer.setVolume(source, volume);
    }

    @Inject(method = "setPitch", at = @At("HEAD"))
    private void acousticsystem$setWetPitch(float pitch, CallbackInfo ci) {
        acousticsystem$pitch = pitch;
        SoftwareAcousticMixer.setPitch(source, pitch);
    }

    @Inject(method = "setLooping", at = @At("HEAD"))
    private void acousticsystem$setWetLooping(boolean looping, CallbackInfo ci) {
        acousticsystem$looping = looping;
        SoftwareAcousticMixer.setLooping(source, looping);
    }

    @Inject(method = "setRelative", at = @At("HEAD"))
    private void acousticsystem$captureRelative(boolean relative, CallbackInfo ci) {
        acousticsystem$relative = relative;
    }

    @Inject(method = "attachStaticBuffer", at = @At("HEAD"))
    private void acousticsystem$attachWetStatic(
            SoundBuffer buffer,
            CallbackInfo ci
    ) {
        if (!acousticsystem$relative) {
            SoftwareAcousticMixer.attachStatic(
                    source,
                    ((AcousticPcmProvider) buffer).acousticsystem$pcmAsset()
            );
            acousticsystem$publishWetPlaybackState();
        }
    }

    @ModifyVariable(method = "attachBufferStream", at = @At("HEAD"), argsOnly = true)
    private AudioStream acousticsystem$attachWetStream(AudioStream stream) {
        if (acousticsystem$relative) {
            return stream;
        }
        AudioStream wrapped = new AcousticTeeAudioStream(stream, source);
        acousticsystem$publishWetPlaybackState();
        return wrapped;
    }

    @Inject(method = "linearAttenuation", at = @At("TAIL"))
    private void acousticsystem$usePhysicalDistanceAttenuation(
            float maximumDistance,
            CallbackInfo ci
    ) {
        // Relative/UI sounds call disableAttenuation instead and never pass here.
        OpenALAcousticEffects.configureDistanceAttenuation(source, maximumDistance);
    }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void acousticsystem$applyBeforePlayback(CallbackInfo ci) {
        if (!AcousticRuntime.applyBeforePlay(
                source, acousticsystem$position, acousticsystem$relative
        )) {
            ci.cancel();
            return;
        }
        SoftwareAcousticMixer.play(source);
    }

    @Inject(method = "pause", at = @At("HEAD"))
    private void acousticsystem$pauseWet(CallbackInfo ci) {
        SoftwareAcousticMixer.pause(source);
    }

    @Inject(method = "unpause", at = @At("HEAD"))
    private void acousticsystem$unpauseWet(CallbackInfo ci) {
        SoftwareAcousticMixer.play(source);
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void acousticsystem$stopWet(CallbackInfo ci) {
        SoftwareAcousticMixer.stop(source);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void acousticsystem$releaseEffects(CallbackInfo ci) {
        AcousticRuntime.forgetDeferredSource(source);
        OpenALAcousticEffects.releaseSource(source);
        SoftwareAcousticMixer.releaseSource(source);
    }

    @Unique
    private void acousticsystem$publishWetPlaybackState() {
        SoftwareAcousticMixer.setVolume(source, acousticsystem$volume);
        SoftwareAcousticMixer.setPitch(source, acousticsystem$pitch);
        SoftwareAcousticMixer.setLooping(source, acousticsystem$looping);
    }
}
