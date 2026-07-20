package org.macaroon.acousticsystem.client.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcousticPcmAssetTest {
    @Test
    void capturesSignedLittleEndianStereoWithoutMovingTheInput() {
        ByteBuffer pcm = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
        pcm.putShort((short) 16_384).putShort((short) -16_384);
        pcm.putShort((short) 32_767).putShort((short) 0).flip();
        int originalPosition = pcm.position();
        AcousticPcmAsset asset = AcousticPcmAsset.capture(
                pcm,
                new AudioFormat(48_000.0F, 16, 2, true, false)
        );
        assertEquals(originalPosition, pcm.position());
        assertEquals(2, asset.frameCount());
        assertEquals(0.0F, asset.monoFrame(0), 1.0E-5F);
        assertEquals(0.5F, asset.monoFrame(1), 1.0E-3F);
    }

    @Test
    void capturesUnsignedEightBitMono() {
        ByteBuffer pcm = ByteBuffer.allocateDirect(3);
        pcm.put((byte) 0).put((byte) 128).put((byte) 255).flip();
        AcousticPcmAsset asset = AcousticPcmAsset.capture(
                pcm,
                new AudioFormat(
                        AudioFormat.Encoding.PCM_UNSIGNED,
                        22_050.0F,
                        8,
                        1,
                        1,
                        22_050.0F,
                        false
                )
        );
        assertEquals(-1.0F, asset.monoFrame(0), 1.0E-6F);
        assertEquals(0.0F, asset.monoFrame(1), 1.0E-6F);
        assertEquals(127.0F / 128.0F, asset.monoFrame(2), 1.0E-6F);
    }
}
