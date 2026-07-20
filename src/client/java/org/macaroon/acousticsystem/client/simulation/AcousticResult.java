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
        Vec3 sourcePosition,
        float propagationGain,
        DirectionalArrivalField directionalField
) {
    public AcousticResult {
        propagationGain = Math.max(0.0F, Math.min(1.0F, propagationGain));
        directionalField = directionalField == null
                ? DirectionalArrivalField.legacy(apparentPosition)
                : directionalField;
    }

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
            RoomImpulseResponse impulseResponse,
            RoomProbe sourceRoomProbe,
            Vec3 sourcePosition,
            float propagationGain
    ) {
        this(
                directGain, highFrequencyGain,
                lowBandGain, midLowBandGain, midHighBandGain, highBandGain,
                reverbSend, reverbHighFrequencyGain, earlyReflection,
                diffractionContribution, propagationDistance, apparentPosition,
                reverbRoom, impulseResponse, sourceRoomProbe, sourcePosition,
                propagationGain, DirectionalArrivalField.legacy(apparentPosition)
        );
    }

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
            RoomImpulseResponse impulseResponse,
            RoomProbe sourceRoomProbe,
            Vec3 sourcePosition
    ) {
        this(
                directGain, highFrequencyGain,
                lowBandGain, midLowBandGain, midHighBandGain, highBandGain,
                reverbSend, reverbHighFrequencyGain, earlyReflection,
                diffractionContribution, propagationDistance, apparentPosition,
                reverbRoom, impulseResponse, sourceRoomProbe, sourcePosition, 1.0F,
                DirectionalArrivalField.legacy(apparentPosition)
        );
    }

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
                reverbRoom, impulseResponse, null, null, 1.0F,
                DirectionalArrivalField.legacy(apparentPosition)
        );
    }

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
            RoomImpulseResponse impulseResponse,
            float propagationGain
    ) {
        this(
                directGain, highFrequencyGain,
                lowBandGain, midLowBandGain, midHighBandGain, highBandGain,
                reverbSend, reverbHighFrequencyGain, earlyReflection,
                diffractionContribution, propagationDistance, apparentPosition,
                reverbRoom, impulseResponse, null, null, propagationGain,
                DirectionalArrivalField.legacy(apparentPosition)
        );
    }
}
