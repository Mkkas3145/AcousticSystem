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
        EarlyReflection earlyReflection,
        float diffractionContribution,
        double propagationDistance,
        Vec3 apparentPosition,
        RoomAcoustics reverbRoom,
        RoomImpulseResponse impulseResponse,
        RoomProbe sourceRoomProbe,
        Vec3 sourcePosition
) {
    public AcousticResult(
            float directGain,
            float highFrequencyGain,
            float lowBandGain,
            float midLowBandGain,
            float midHighBandGain,
            float highBandGain,
            float reverbSend,
            float reverbHighFrequencyGain,
            EarlyReflection earlyReflection,
            float diffractionContribution,
            double propagationDistance,
            Vec3 apparentPosition,
            RoomAcoustics reverbRoom,
            RoomImpulseResponse impulseResponse
    ) {
        this(
                directGain, highFrequencyGain,
                lowBandGain, midLowBandGain, midHighBandGain, highBandGain,
                reverbSend, reverbHighFrequencyGain, earlyReflection,
                diffractionContribution, propagationDistance, apparentPosition,
                reverbRoom, impulseResponse, null, null
        );
    }
}
