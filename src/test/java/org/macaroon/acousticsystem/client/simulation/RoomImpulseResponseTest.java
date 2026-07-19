package org.macaroon.acousticsystem.client.simulation;

import org.junit.jupiter.api.Test;
import org.macaroon.acousticsystem.client.material.AcousticBands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomImpulseResponseTest {
    private static final double BIN_SECONDS = 0.01;

    @Test
    void synthesisIsDeterministicForTheSameRoomField() {
        float[][] histogram = histogram(24);
        histogram[3][4] = 0.6F;
        histogram[5][13] = 0.2F;

        RoomImpulseResponse first = RoomImpulseResponse.synthesize(histogram, BIN_SECONDS, 12345L);
        RoomImpulseResponse second = RoomImpulseResponse.synthesize(histogram, BIN_SECONDS, 12345L);

        assertEquals(first.signature(), second.signature());
        assertArrayEquals(first.copySamples(), second.copySamples());
    }

    @Test
    void reflectionEnergyAppearsOnlyAtCalculatedArrivalBins() {
        float[][] histogram = histogram(30);
        histogram[2][18] = 0.8F;
        RoomImpulseResponse response = RoomImpulseResponse.synthesize(histogram, BIN_SECONDS, 91L);

        assertTrue(energy(response, 0.0, 0.18) < 1.0E-12);
        assertTrue(energy(response, 0.18, 0.19) > 0.0);
        assertTrue(energy(response, 0.19, response.durationSeconds()) > 0.0);
        assertTrue(energy(response, 0.18, response.durationSeconds()) > 0.99);
    }

    @Test
    void distinctCalculatedEchoesRemainDistinctInsteadOfBecomingAFixedDelay() {
        float[][] histogram = histogram(40);
        histogram[4][11] = 1.0F;
        histogram[4][27] = 0.25F;
        RoomImpulseResponse response = RoomImpulseResponse.synthesize(histogram, BIN_SECONDS, 7L);

        double firstEnergy = energy(response, 0.11, 0.12);
        double secondEnergy = energy(response, 0.27, 0.28);
        assertTrue(firstEnergy > secondEnergy * 3.5);
        assertTrue(secondEnergy > 0.0);
        assertTrue(energy(response, 0.075, 0.085) < 1.0E-12);
    }

    @Test
    void emptyReflectionFieldProducesSilentResponse() {
        RoomImpulseResponse response = RoomImpulseResponse.synthesize(histogram(8), BIN_SECONDS, 1L);

        assertTrue(response.isSilent());
        assertEquals(Float.POSITIVE_INFINITY, response.firstArrivalSeconds());
    }

    @Test
    void highDiffusionTurnsSparseRayHitsIntoAContinuousCleanTail() {
        float[][] histogram = histogram(300);
        histogram[4][12] = 1.0F;
        histogram[4][55] = 0.35F;
        histogram[4][110] = 0.12F;
        float[] rt60 = {0.65F, 0.62F, 0.58F, 0.54F, 0.48F, 0.42F, 0.35F, 0.28F};

        RoomImpulseResponse diffuse = RoomImpulseResponse.synthesizeLateField(
                histogram, 0.002, 44L, 0.002, rt60, 1.0F
        );
        RoomImpulseResponse coherent = RoomImpulseResponse.synthesizeLateField(
                histogram, 0.002, 44L, 0.002, rt60, 0.0F
        );

        assertEquals(coherent.firstArrivalSeconds(), diffuse.firstArrivalSeconds(), 0.002F);
        assertTrue(peak(diffuse) < peak(coherent));
        for (double start = 0.04; start < 0.30; start += 0.01) {
            double windowStart = start;
            assertTrue(energy(diffuse, start, start + 0.01) > 0.0,
                    () -> "silent window at " + windowStart);
        }
    }

    @Test
    void lateFieldContinuesUntilTheCalculatedRt60InsteadOfCuttingAtTraceWindow() {
        float[][] histogram = histogram(220);
        histogram[3][20] = 1.0F;
        float[] rt60 = {2.4F, 2.4F, 2.4F, 2.4F, 2.4F, 2.4F, 2.4F, 2.4F};

        RoomImpulseResponse response = RoomImpulseResponse.synthesizeLateField(
                histogram, 0.002, 81L, 0.004, rt60, 1.0F
        );

        assertTrue(response.durationSeconds() >= 2.43F);
        assertTrue(energy(response, 1.2, 1.4) > 0.0);
        assertTrue(energy(response, 2.2, 2.4) > 0.0);
    }

    @Test
    void coupledRoomsRetainArrivalsFromBothCalculatedFields() {
        float[][] sourceHistogram = histogram(40);
        float[][] listenerHistogram = histogram(40);
        sourceHistogram[2][5] = 1.0F;
        listenerHistogram[5][24] = 1.0F;
        RoomImpulseResponse source = RoomImpulseResponse.synthesize(
                sourceHistogram, BIN_SECONDS, 13L
        );
        RoomImpulseResponse listener = RoomImpulseResponse.synthesize(
                listenerHistogram, BIN_SECONDS, 29L
        );

        RoomImpulseResponse coupled = RoomImpulseResponse.blendEnergy(
                source, 0.75F, listener, 0.25F
        );

        assertTrue(energy(coupled, 0.05, 0.08) > 0.0);
        assertTrue(energy(coupled, 0.24, 0.28) > 0.0);
        assertTrue(coupled.signature() != source.signature());
        assertTrue(coupled.signature() != listener.signature());
    }

    private static float[][] histogram(int bins) {
        return new float[AcousticBands.COUNT][bins];
    }

    private static double energy(RoomImpulseResponse response, double fromSeconds, double toSeconds) {
        float[] samples = response.copySamples();
        int from = Math.max(0, (int) Math.round(fromSeconds * response.sampleRate()));
        int to = Math.min(samples.length, (int) Math.round(toSeconds * response.sampleRate()));
        double energy = 0.0;
        for (int sample = from; sample < to; sample++) {
            energy += samples[sample] * samples[sample];
        }
        return energy;
    }

    private static float peak(RoomImpulseResponse response) {
        float peak = 0.0F;
        for (float sample : response.copySamples()) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }
}
