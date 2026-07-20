package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A source-owned first-order reflection cluster, kept separate from the room field. */
public record EarlyReflection(
        float gain,
        float highFrequencyGain,
        float delay,
        Vec3 arrivalDirection
) {
    public static final EarlyReflection SILENT = new EarlyReflection(
            0.0F, 1.0F, 0.0F, Vec3.ZERO
    );

    public EarlyReflection {
        gain = Mth.clamp(gain, 0.0F, 1.0F);
        highFrequencyGain = Mth.clamp(highFrequencyGain, 0.0F, 1.0F);
        delay = Mth.clamp(delay, 0.0F, 0.3F);
        arrivalDirection = arrivalDirection == null || arrivalDirection.lengthSqr() < 1.0E-12
                ? Vec3.ZERO
                : arrivalDirection.normalize();
    }
}
