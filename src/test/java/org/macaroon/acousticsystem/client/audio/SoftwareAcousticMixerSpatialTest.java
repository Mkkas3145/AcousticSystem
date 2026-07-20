package org.macaroon.acousticsystem.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoftwareAcousticMixerSpatialTest {
    private static final float EPSILON = 1.0E-6F;

    @Test
    void verticalArrivalIsRetainedInTheAmbisonicHeightChannel() {
        float[] w = new float[1];
        float[] front = new float[1];
        float[] left = new float[1];
        float[] up = new float[1];

        SoftwareAcousticMixer.addAmbisonicPoint(
                1.0F,
                0.0F, 1.0F, 0.0F,
                w, front, left, up, 0
        );

        assertEquals(0.70710677F, w[0], EPSILON);
        assertEquals(0.0F, front[0], EPSILON);
        assertEquals(0.0F, left[0], EPSILON);
        assertEquals(1.0F, up[0], EPSILON);
    }

    @Test
    void horizontalArrivalKeepsTheExistingLeftRightSign() {
        float[] w = new float[1];
        float[] front = new float[1];
        float[] left = new float[1];
        float[] up = new float[1];

        SoftwareAcousticMixer.addAmbisonicPoint(
                1.0F,
                1.0F, 0.0F, 0.0F,
                w, front, left, up, 0
        );

        assertEquals(0.70710677F, w[0], EPSILON);
        assertEquals(0.0F, front[0], EPSILON);
        assertEquals(-1.0F, left[0], EPSILON);
        assertEquals(0.0F, up[0], EPSILON);
    }
}
