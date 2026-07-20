package org.macaroon.acousticsystem.client.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Mirrors decoded streaming PCM into the wet renderer while vanilla keeps ownership. */
public final class AcousticTeeAudioStream implements AudioStream {
    private final AudioStream delegate;
    private final int source;

    public AcousticTeeAudioStream(AudioStream delegate, int source) {
        this.delegate = delegate;
        this.source = source;
        SoftwareAcousticMixer.attachStream(source, delegate.getFormat());
    }

    @Override
    public AudioFormat getFormat() {
        return delegate.getFormat();
    }

    @Override
    public ByteBuffer read(int capacity) throws IOException {
        ByteBuffer data = delegate.read(capacity);
        if (data != null) {
            SoftwareAcousticMixer.appendStream(
                    source, data.duplicate(), delegate.getFormat()
            );
        }
        return data;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
