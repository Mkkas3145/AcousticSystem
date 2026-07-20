package org.macaroon.acousticsystem.mixin.client;

import com.mojang.blaze3d.audio.Library;
import com.mojang.blaze3d.audio.DeviceList;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryStack;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects;
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
            String preferredDevice,
            DeviceList devices,
            boolean enableHrtf,
            CallbackInfo ci
    ) {
        OpenALAcousticEffects.initialize();
    }

    @Inject(method = "createAttributes", at = @At("RETURN"))
    private void acousticsystem$requestAcousticFieldSends(
            MemoryStack stack,
            boolean enableHrtf,
            CallbackInfoReturnable<IntBuffer> cir
    ) {
        IntBuffer attributes = cir.getReturnValue();
        for (int index = attributes.position(); index + 1 < attributes.limit(); index += 2) {
            int attribute = attributes.get(index);
            if (attribute == 0) {
                break;
            }
            if (attribute == EXTEfx.ALC_MAX_AUXILIARY_SENDS) {
                // Early reflections, the listener room, and a remote source-room field.
                // OpenAL Soft supports more, while devices exposing only two sends keep
                // the original listener-field pipeline as a compatible fallback.
                attributes.put(index + 1, 3);
                break;
            }
        }
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void acousticsystem$shutdownEffects(CallbackInfo ci) {
        OpenALAcousticEffects.shutdown();
    }
}
