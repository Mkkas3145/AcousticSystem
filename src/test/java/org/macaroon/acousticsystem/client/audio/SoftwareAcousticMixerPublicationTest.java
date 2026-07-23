package org.macaroon.acousticsystem.client.audio;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.macaroon.acousticsystem.client.simulation.EarlyReflection;
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareAcousticMixerPublicationTest {
    @Test
    void everyCompletedMovingResultIsVisibleToTheNextAudioRender() throws Exception {
        Class<?> voiceType = Class.forName(
                "org.macaroon.acousticsystem.client.audio.SoftwareAcousticMixer$Voice"
        );
        Constructor<?> constructor = voiceType.getDeclaredConstructor(
                AcousticPcmAsset.class, int.class
        );
        constructor.setAccessible(true);
        Object voice = constructor.newInstance(null, SoftwareAcousticMixer.OUTPUT_RATE);

        Field voicesField = SoftwareAcousticMixer.class.getDeclaredField("CURRENT_VOICES");
        voicesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> voices = (Map<Integer, Object>) voicesField.get(null);
        int source = 0x5A17;
        voices.put(source, voice);

        Method render = voiceType.getDeclaredMethod(
                "render",
                float[].class, float[].class, float[].class, float[].class,
                int.class, boolean.class
        );
        render.setAccessible(true);
        Field renderedField = voiceType.getDeclaredField("renderedAcoustics");
        renderedField.setAccessible(true);

        float[] first = new float[1];
        float[] second = new float[1];
        float[] third = new float[1];
        float[] fourth = new float[1];
        try {
            long previousSequence = Long.MIN_VALUE;
            for (int frame = 0; frame < 256; frame++) {
                long sequence = System.nanoTime();
                assertTrue(sequence > previousSequence);
                previousSequence = sequence;
                assertTrue(SoftwareAcousticMixer.apply(
                        source,
                        EarlyReflection.SILENT,
                        0.0F,
                        Vec3.ZERO,
                        new Vec3(frame + 1.0, 0.0, 1.0),
                        RoomAcoustics.OUTDOORS,
                        frame / 255.0F,
                        1.0F,
                        RoomAcoustics.OUTDOORS,
                        0.0F,
                        1.0F,
                        sequence
                ));

                render.invoke(
                        voice, first, second, third, fourth, 1, false
                );
                Object rendered = renderedField.get(voice);
                Method sequenceAccessor = rendered.getClass().getDeclaredMethod("sequence");
                sequenceAccessor.setAccessible(true);
                assertEquals(
                        sequence,
                        ((Long) sequenceAccessor.invoke(rendered)).longValue(),
                        "movement must not leave the callback on an older acoustic result"
                );
            }

            SoftwareAcousticMixer.updateSpatial(
                    source, Vec3.ZERO, new Vec3(1.0, 0.0, 0.0),
                    0.25F, 0.40F, 0.70F
            );
            render.invoke(voice, first, second, third, fourth, 1, false);
            Object moved = renderedField.get(voice);
            Method outputGainAccessor = moved.getClass().getDeclaredMethod(
                    "listenerOutputGain"
            );
            outputGainAccessor.setAccessible(true);
            assertEquals(
                    0.25F,
                    ((Float) outputGainAccessor.invoke(moved)).floatValue(),
                    1.0E-6F,
                    "crossing a measured exit must retarget the already-running diffuse output"
            );
        } finally {
            voices.remove(source);
        }
    }
}
