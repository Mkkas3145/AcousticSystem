package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;

public record AcousticResult(
        float directGain,
        float highFrequencyGain,
        float lowBandGain,
        float midLowBandGain,
        float midHighBandGain,
        float highBandGain,
        float reverbSend,
        float reverbHighFrequencyGain,
        float diffractionContribution,
        double propagationDistance,
        Vec3 apparentPosition,
        RoomAcoustics reverbRoom,
        RoomImpulseResponse impulseResponse
) {
}
