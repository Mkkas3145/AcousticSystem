package org.macaroon.acousticsystem.client.audio;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Immutable normalized mono PCM retained for the software acoustic field renderer. */
public final class AcousticPcmAsset {
    private final float[] samples;
    private final int sampleRate;

    private AcousticPcmAsset(float[] samples, int sampleRate) {
        this.samples = samples;
        this.sampleRate = Math.max(1, sampleRate);
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int frameCount() {
        return samples.length;
    }

    public float monoFrame(int frame) {
        return samples[frame];
    }

    public static AcousticPcmAsset capture(ByteBuffer encoded, AudioFormat format) {
        ByteBuffer input = encoded.duplicate().order(
                format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN
        );
        int channels = Math.max(1, format.getChannels());
        int bits = Math.max(8, format.getSampleSizeInBits());
        int bytesPerSample = Math.max(1, (bits + 7) / 8);
        int frameSize = format.getFrameSize() > 0
                ? format.getFrameSize()
                : bytesPerSample * channels;
        int frames = input.remaining() / frameSize;
        float[] samples = new float[frames];
        AudioFormat.Encoding encoding = format.getEncoding();
        boolean floating = AudioFormat.Encoding.PCM_FLOAT.equals(encoding);
        boolean unsigned = AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding);
        int base = input.position();
        for (int frame = 0; frame < frames; frame++) {
            int frameOffset = base + frame * frameSize;
            float sum = 0.0F;
            for (int channel = 0; channel < channels; channel++) {
                int sampleOffset = frameOffset + channel * bytesPerSample;
                sum += decode(
                        input, sampleOffset, bits, bytesPerSample, floating, unsigned
                );
            }
            samples[frame] = sum / channels;
        }
        return new AcousticPcmAsset(
                samples, Math.round(format.getSampleRate())
        );
    }

    private static float decode(
            ByteBuffer input,
            int offset,
            int bits,
            int bytes,
            boolean floating,
            boolean unsigned
    ) {
        if (floating && bits == 32) {
            return clamp(input.getFloat(offset));
        }
        long raw = 0L;
        if (input.order() == ByteOrder.LITTLE_ENDIAN) {
            for (int index = bytes - 1; index >= 0; index--) {
                raw = raw << 8 | Byte.toUnsignedLong(input.get(offset + index));
            }
        } else {
            for (int index = 0; index < bytes; index++) {
                raw = raw << 8 | Byte.toUnsignedLong(input.get(offset + index));
            }
        }
        int effectiveBits = Math.min(bits, bytes * 8);
        double scale = Math.scalb(1.0, effectiveBits - 1);
        double value;
        if (unsigned) {
            value = (raw - scale) / scale;
        } else {
            long signBit = 1L << (effectiveBits - 1);
            long signed = (raw & signBit) == 0L
                    ? raw
                    : raw - (1L << effectiveBits);
            value = signed / scale;
        }
        return clamp((float) value);
    }

    private static float clamp(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }
}
