package org.macaroon.acousticsystem.mixin.client;

import com.mojang.blaze3d.audio.Library;
import org.lwjgl.system.MemoryStack;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects;
import org.macaroon.acousticsystem.client.audio.OpenALContextAttributes;
import org.macaroon.acousticsystem.client.audio.SoftwareAcousticMixer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

@Mixin(Library.class)
abstract class LibraryMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void acousticsystem$initializeEffects(
            CallbackInfo ci
    ) {
        SoftwareAcousticMixer.initialize();
        OpenALAcousticEffects.initialize();
    }

    @Inject(method = "getChannelCount", at = @At("RETURN"), cancellable = true)
    private void acousticsystem$reserveMixerOutputSource(
            CallbackInfoReturnable<Integer> cir
    ) {
        // Minecraft divides this exact device total between its static and streaming
        // pools. The software field is itself one continuously playing OpenAL source,
        // so account for it before those pools are sized. Otherwise the last vanilla
        // allocation exceeds the context's real source count and leaves AL_OUT_OF_MEMORY
        // pending, after which unrelated sounds fail with cascading AL_INVALID_NAME.
        cir.setReturnValue(Math.max(1, cir.getReturnValue() - 1));
    }

    @Inject(method = "createAttributes", at = @At("RETURN"))
    private void acousticsystem$requestAcousticFieldSends(
            MemoryStack stack,
            boolean enableHrtf,
            CallbackInfoReturnable<IntBuffer> cir
    ) {
        // Vanilla does not include ALC_MAX_AUXILIARY_SENDS in this buffer. Insert the
        // request before its terminating zero instead of only trying to replace a pair
        // that is not present.
        OpenALContextAttributes.requestAuxiliarySends(cir.getReturnValue(), 3);
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void acousticsystem$shutdownEffects(CallbackInfo ci) {
        OpenALAcousticEffects.shutdown();
        SoftwareAcousticMixer.shutdown();
    }
}
