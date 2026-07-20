package org.macaroon.acousticsystem.client.audio;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.EXTSourceDistanceModel;
import org.macaroon.acousticsystem.client.simulation.EarlyReflection;
import org.macaroon.acousticsystem.client.simulation.AcousticResult;
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;
import org.macaroon.acousticsystem.client.simulation.RoomImpulseResponse;

import javax.sound.sampled.AudioFormat;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareAcousticMixerOpenALTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "ACOUSTICSYSTEM_OPENAL_SMOKE", matches = "true")
    void oneCallbackSourceCarriesManyIndependentResponsesWithoutEfxExhaustion()
            throws Exception {
        long device = ALC10.alcOpenDevice((ByteBuffer) null);
        assertTrue(device != 0L);
        var alcCapabilities = ALC.createCapabilities(device);
        IntBuffer attributes = BufferUtils.createIntBuffer(3);
        attributes.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(3).put(0).flip();
        long context = ALC10.alcCreateContext(device, attributes);
        assertTrue(context != 0L);
        assertTrue(ALC10.alcMakeContextCurrent(context));
        var alCapabilities = AL.createCapabilities(alcCapabilities);
        assertTrue(alCapabilities.AL_SOFT_callback_buffer);
        AL10.alEnable(EXTSourceDistanceModel.AL_SOURCE_DISTANCE_MODEL);

        List<Integer> sources = new ArrayList<>();
        try {
            SoftwareAcousticMixer.initialize();
            assertTrue(SoftwareAcousticMixer.available());
            OpenALAcousticEffects.initialize();
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            AcousticPcmAsset pcm = shortImpulse();
            RoomAcoustics room = new RoomAcoustics(
                    0.8F, 0.75F, 0.28F, 0.68F, 0.96F,
                    0.1F, 0.62F, 1.2F,
                    0.3F, 0.012F, Vec3.ZERO,
                    1.0F, 0.018F, Vec3.ZERO,
                    0.6F, 0.04F, 0.96F
            );
            for (int index = 0; index < 128; index++) {
                int source = AL10.alGenSources();
                assertTrue(source != 0, "OpenAL source allocation failed at " + index);
                sources.add(source);
                SoftwareAcousticMixer.attachStatic(source, pcm);
                OpenALAcousticEffects.applyBeforePlay(
                        source,
                        new AcousticResult(
                                0.8F, 0.75F,
                                0.8F, 0.7F, 0.6F, 0.5F,
                                0.24F, 0.8F,
                                new EarlyReflection(
                                0.12F, 0.75F, 0.015F,
                                new Vec3(1.0, 0.0, 0.0)
                                ),
                                0.0F, 6.0,
                                new Vec3(index % 2 == 0 ? -6.0 : 6.0, 0.0, 0.0),
                                room, RoomImpulseResponse.SILENT,
                                0.16F
                        )
                );
                SoftwareAcousticMixer.play(source);
            }
            Thread.sleep(80L);
            assertEquals(128, SoftwareAcousticMixer.liveRenderVoiceCount());
            long spatialStarted = System.nanoTime();
            OpenALAcousticEffects.updateListenerPosition(
                    new Vec3(0.25, 1.62, -0.5)
            );
            double spatialMilliseconds = (System.nanoTime() - spatialStarted) / 1_000_000.0;
            assertTrue(
                    spatialMilliseconds < 10.0,
                    () -> "128-source frame reprojection exceeded 10 ms: "
                            + spatialMilliseconds
            );
            assertEquals(
                    AL10.AL_PLAYING,
                    AL10.alGetSourcei(outputSource(), AL10.AL_SOURCE_STATE)
            );
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());

            // The old design allocated at least one auxiliary slot for every distinct
            // response. The callback design must own none, regardless of voice count.
            assertEquals(0, effectPoolSize("REVERB_POOL"));
            assertEquals(0, effectPoolSize("EARLY_REFLECTION_POOL"));

            for (int source : sources) {
                OpenALAcousticEffects.releaseSource(source);
                SoftwareAcousticMixer.releaseSource(source);
                AL10.alDeleteSources(source);
            }
            sources.clear();
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            Thread.sleep(450L);

            // Publishing the next voice reaps every mathematically finished detached
            // response; stale tails cannot accumulate for the rest of the session.
            int probe = AL10.alGenSources();
            sources.add(probe);
            SoftwareAcousticMixer.attachStatic(probe, pcm);
            assertTrue(
                    SoftwareAcousticMixer.liveRenderVoiceCount() <= 1,
                    "Completed responses remained retained: "
                            + SoftwareAcousticMixer.liveRenderVoiceCount()
            );
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
        } finally {
            for (int source : sources) {
                OpenALAcousticEffects.releaseSource(source);
                SoftwareAcousticMixer.releaseSource(source);
                AL10.alDeleteSources(source);
            }
            OpenALAcousticEffects.shutdown();
            SoftwareAcousticMixer.shutdown();
            ALC10.alcMakeContextCurrent(0L);
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
        }
    }

    private static AcousticPcmAsset shortImpulse() {
        ByteBuffer pcm = ByteBuffer.allocateDirect(4_800 * Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        pcm.putShort((short) 16_000);
        while (pcm.hasRemaining()) {
            pcm.putShort((short) 0);
        }
        pcm.flip();
        return AcousticPcmAsset.capture(
                pcm, new AudioFormat(48_000.0F, 16, 1, true, false)
        );
    }

    private static int outputSource() throws Exception {
        Field field = SoftwareAcousticMixer.class.getDeclaredField("outputSource");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static int effectPoolSize(String name) throws Exception {
        Class<?> effects = Class.forName(
                "org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects"
        );
        Field field = effects.getDeclaredField(name);
        field.setAccessible(true);
        return ((List<?>) field.get(null)).size();
    }

}
