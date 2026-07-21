package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A source-owned first-order reflection cluster, kept separate from the room field. */
public record EarlyReflection(
        float gain,
        float lowFrequencyGain,
        float highFrequencyGain,
        float delay,
        Vec3 arrivalDirection,
        DirectionalArrivalField directionalField
) {
    public static final EarlyReflection SILENT = new EarlyReflection(
            0.0F, 1.0F, 1.0F, 0.0F, Vec3.ZERO, DirectionalArrivalField.EMPTY
    );

    public EarlyReflection(
            float gain,
            float highFrequencyGain,
            float delay,
            Vec3 arrivalDirection
    ) {
        this(
                gain, 1.0F, highFrequencyGain, delay,
                arrivalDirection, DirectionalArrivalField.EMPTY
        );
    }

    public EarlyReflection(
            float gain,
            float highFrequencyGain,
            float delay,
            Vec3 arrivalDirection,
            DirectionalArrivalField directionalField
    ) {
        this(gain, 1.0F, highFrequencyGain, delay, arrivalDirection, directionalField);
    }

    public EarlyReflection {
        gain = Mth.clamp(gain, 0.0F, 1.0F);
        lowFrequencyGain = Mth.clamp(lowFrequencyGain, 0.0F, 1.0F);
        highFrequencyGain = Mth.clamp(highFrequencyGain, 0.0F, 1.0F);
        delay = Mth.clamp(delay, 0.0F, 0.3F);
        arrivalDirection = arrivalDirection == null || arrivalDirection.lengthSqr() < 1.0E-12
                ? Vec3.ZERO
                : arrivalDirection.normalize();
        directionalField = directionalField == null
                ? DirectionalArrivalField.EMPTY
                : directionalField;
    }

    public Vec3 directionFrom(Vec3 listener) {
        if (directionalField.hasArrivals()) {
            return directionalField.apparentDirection(listener);
        }
        return arrivalDirection;
    }
}
