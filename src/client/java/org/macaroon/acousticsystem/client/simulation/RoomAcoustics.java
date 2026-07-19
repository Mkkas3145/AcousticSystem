package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;

public record RoomAcoustics(
        float density,
        float diffusion,
        float gain,
        float gainHighFrequency,
        float gainLowFrequency,
        float decayTime,
        float decayHighFrequencyRatio,
        float decayLowFrequencyRatio,
        float reflectionsGain,
        float reflectionsDelay,
        Vec3 reflectionsPan,
        float lateReverbGain,
        float lateReverbDelay,
        Vec3 lateReverbPan,
        float modulationTime,
        float modulationDepth,
        float airAbsorptionGainHighFrequency
) {
    public static final RoomAcoustics OUTDOORS = new RoomAcoustics(
            0.0F, 0.0F, 0.0F, 1.0F, 1.0F,
            0.1F, 1.0F, 1.0F,
            0.0F, 0.0F, Vec3.ZERO,
            0.0F, 0.0F, Vec3.ZERO,
            0.25F, 0.0F, 1.0F
    );

}
