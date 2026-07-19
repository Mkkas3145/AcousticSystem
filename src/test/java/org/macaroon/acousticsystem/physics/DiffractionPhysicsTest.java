package org.macaroon.acousticsystem.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffractionPhysicsTest {
    @Test
    void grazingEdgeIsContinuousAndFrequencyIndependent() {
        float low = DiffractionPhysics.knifeEdgeAmplitude(63.0, 0.0);
        float high = DiffractionPhysics.knifeEdgeAmplitude(8000.0, 0.0);

        assertEquals(low, high, 1.0E-6F);
        assertTrue(low > 0.49F && low < 0.51F);
    }

    @Test
    void lowFrequenciesDiffractMoreAroundTheSameObstacle() {
        float low = DiffractionPhysics.knifeEdgeAmplitude(63.0, 0.10);
        float middle = DiffractionPhysics.knifeEdgeAmplitude(1000.0, 0.10);
        float high = DiffractionPhysics.knifeEdgeAmplitude(8000.0, 0.10);

        assertTrue(low > middle);
        assertTrue(middle > high);
    }

    @Test
    void deeperShadowProducesMoreAttenuation() {
        float nearEdge = DiffractionPhysics.knifeEdgeAmplitude(1000.0, 0.01);
        float deepShadow = DiffractionPhysics.knifeEdgeAmplitude(1000.0, 0.50);

        assertTrue(nearEdge > deepShadow);
    }

    @Test
    void finiteObstacleAndSealedBarrierRemainDistinct() {
        float lowEdge = DiffractionPhysics.knifeEdgeAmplitude(63.0, 0.10);
        float highEdge = DiffractionPhysics.knifeEdgeAmplitude(8000.0, 0.10);
        float finiteObstacleLow = incoherent(0.25F, 2.0F * lowEdge);
        float finiteObstacleHigh = incoherent(0.002F, 2.0F * highEdge);

        // A closed wall also attenuates both legs leading to every candidate edge.
        float sealedWallLow = incoherent(0.25F, 2.0F * lowEdge * 0.25F * 0.25F);
        float sealedWallHigh = incoherent(0.002F, 2.0F * highEdge * 0.002F * 0.002F);

        assertTrue(finiteObstacleLow > 0.75F);
        assertTrue(finiteObstacleHigh > 0.10F);
        assertTrue(sealedWallLow < 0.30F);
        assertTrue(sealedWallHigh < 0.01F);
    }

    private static float incoherent(float first, float second) {
        return (float) Math.min(1.0, Math.sqrt(first * first + second * second));
    }
}
