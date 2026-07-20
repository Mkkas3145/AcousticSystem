package org.macaroon.acousticsystem.client.audio;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticFeedbackFieldTest {
    private static final RoomAcoustics ROOM = new RoomAcoustics(
            0.82F, 0.76F, 0.34F, 0.68F, 0.95F,
            0.75F, 0.58F, 1.22F,
            0.31F, 0.018F, Vec3.ZERO,
            1.15F, 0.027F, Vec3.ZERO,
            0.7F, 0.06F, 0.95F
    );

    @Test
    void silentFieldDoesNotInventEnergy() {
        AcousticFeedbackField field = new AcousticFeedbackField();
        field.configure(RoomAcoustics.OUTDOORS, 0.0F);
        for (int sample = 0; sample < 10_000; sample++) {
            assertEquals(0L, field.process(0.0F, 1.0F));
        }
    }

    @Test
    void subSampleDelayAtTheRingBoundaryWrapsToAValidIndex() {
        float wrapped = SoftwareAcousticMixer.wrapRingPosition(
                -0.0001F, 16_384
        );
        assertTrue(wrapped >= 0.0F && wrapped < 16_384.0F);
    }

    @Test
    void impulseFormsAStableDecayingDiffuseTail() {
        AcousticFeedbackField field = new AcousticFeedbackField();
        field.configure(ROOM, 0.4F);
        double earlyEnergy = 0.0;
        double lateEnergy = 0.0;
        for (int sample = 0; sample < 72_000; sample++) {
            long output = field.process(sample == 0 ? 1.0F : 0.0F, 0.8F);
            float left = unpackLeft(output);
            float right = unpackRight(output);
            assertTrue(Float.isFinite(left) && Float.isFinite(right));
            double energy = left * left + right * right;
            if (sample >= 3_000 && sample < 15_000) {
                earlyEnergy += energy;
            }
            if (sample >= 48_000 && sample < 60_000) {
                lateEnergy += energy;
            }
        }
        assertTrue(earlyEnergy > 1.0E-8, "The delay network must emit the impulse");
        assertTrue(lateEnergy > 0.0, "RT60 energy must remain continuous in the tail");
        assertTrue(lateEnergy < earlyEnergy, "A passive room field must decay");
    }

    @Test
    void surfaceDiffusionIncreasesEarlyTailDensity() {
        AcousticFeedbackField sparse = new AcousticFeedbackField();
        AcousticFeedbackField dense = new AcousticFeedbackField();
        RoomAcoustics lowDiffusion = withDiffusion(ROOM, 0.0F);
        RoomAcoustics highDiffusion = withDiffusion(ROOM, 0.92F);
        sparse.configure(lowDiffusion, 0.4F);
        dense.configure(highDiffusion, 0.4F);
        int sparseSamples = 0;
        int denseSamples = 0;
        double sparseEnergy = 0.0;
        double denseEnergy = 0.0;
        for (int sample = 0; sample < 48_000; sample++) {
            float input = sample == 0 ? 1.0F : 0.0F;
            long sparsePacked = sparse.process(input, 1.0F);
            long densePacked = dense.process(input, 1.0F);
            float sparseOutput = Math.abs(unpackLeft(sparsePacked))
                    + Math.abs(unpackRight(sparsePacked));
            float denseOutput = Math.abs(unpackLeft(densePacked))
                    + Math.abs(unpackRight(densePacked));
            sparseEnergy += sparseOutput * sparseOutput;
            denseEnergy += denseOutput * denseOutput;
            if (sample >= 2_000 && sparseOutput > 1.0E-8F) {
                sparseSamples++;
            }
            if (sample >= 2_000 && denseOutput > 1.0E-8F) {
                denseSamples++;
            }
        }
        assertTrue(
                denseSamples > sparseSamples,
                "A reflective diffuse room must fill more tail samples: sparse="
                        + sparseSamples + ", dense=" + denseSamples
        );
        double energyRatio = denseEnergy / Math.max(1.0E-12, sparseEnergy);
        assertTrue(
                energyRatio > 0.65 && energyRatio < 1.55,
                "Diffusion must preserve reverberant energy; ratio=" + energyRatio
        );
    }

    @Test
    void fullOpenAlVoiceCountRendersInsideTheAudioDeadline() {
        AcousticFeedbackField[] fields = new AcousticFeedbackField[255];
        for (int index = 0; index < fields.length; index++) {
            fields[index] = new AcousticFeedbackField();
            fields[index].configure(ROOM, 0.3F);
            fields[index].process(0.2F, 0.75F);
        }
        for (int warmup = 0; warmup < 5; warmup++) {
            render(fields, 480);
        }
        long best = Long.MAX_VALUE;
        for (int trial = 0; trial < 7; trial++) {
            long started = System.nanoTime();
            render(fields, 480);
            best = Math.min(best, System.nanoTime() - started);
        }
        double milliseconds = best / 1_000_000.0;
        assertTrue(
                milliseconds < 10.0,
                "255 independent late fields missed the 10 ms deadline: "
                        + milliseconds + " ms"
        );
    }

    private static void render(AcousticFeedbackField[] fields, int frames) {
        for (AcousticFeedbackField field : fields) {
            for (int frame = 0; frame < frames; frame++) {
                field.process(0.0F, 0.75F);
            }
        }
    }

    private static float unpackLeft(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    private static float unpackRight(long packed) {
        return Float.intBitsToFloat((int) packed);
    }

    private static RoomAcoustics withDiffusion(
            RoomAcoustics room,
            float diffusion
    ) {
        return new RoomAcoustics(
                room.density(), diffusion,
                room.gain(), room.gainHighFrequency(), room.gainLowFrequency(),
                room.decayTime(), room.decayHighFrequencyRatio(),
                room.decayLowFrequencyRatio(), room.reflectionsGain(),
                room.reflectionsDelay(), room.reflectionsPan(),
                room.lateReverbGain(), room.lateReverbDelay(),
                room.lateReverbPan(), room.modulationTime(),
                room.modulationDepth(), room.airAbsorptionGainHighFrequency()
        );
    }
}
