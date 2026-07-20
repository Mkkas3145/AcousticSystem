package org.macaroon.acousticsystem.mixin.client;

import com.mojang.blaze3d.audio.SoundBuffer;
import org.macaroon.acousticsystem.client.audio.AcousticPcmAsset;
import org.macaroon.acousticsystem.client.audio.AcousticPcmProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

@Mixin(SoundBuffer.class)
abstract class SoundBufferMixin implements AcousticPcmProvider {
    @Unique
    private AcousticPcmAsset acousticsystem$pcmAsset;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void acousticsystem$capturePcm(
            ByteBuffer data,
            AudioFormat format,
            CallbackInfo ci
    ) {
        acousticsystem$pcmAsset = AcousticPcmAsset.capture(data, format);
    }

    @Override
    public AcousticPcmAsset acousticsystem$pcmAsset() {
        return acousticsystem$pcmAsset;
    }
}
