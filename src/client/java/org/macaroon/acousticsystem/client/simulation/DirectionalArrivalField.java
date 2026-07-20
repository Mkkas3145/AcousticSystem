package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Immutable world-space first-arrival anchors which can be reprojected at frame rate. */
public record DirectionalArrivalField(
        List<Arrival> arrivals,
        Vec3 fallbackSource
) {
    public static final DirectionalArrivalField EMPTY = new DirectionalArrivalField(
            List.of(), null
    );

    public DirectionalArrivalField {
        List<Arrival> normalized = new ArrayList<>(arrivals == null ? 0 : arrivals.size());
        if (arrivals != null) {
            for (Arrival arrival : arrivals) {
                if (arrival != null && arrival.point() != null && arrival.power() > 1.0E-12) {
                    normalized.add(arrival);
                }
            }
        }
        arrivals = List.copyOf(normalized);
    }

    public static DirectionalArrivalField legacy(Vec3 apparentPosition) {
        return apparentPosition == null
                ? EMPTY
                : new DirectionalArrivalField(
                        List.of(new Arrival(apparentPosition, 1.0)),
                        apparentPosition
                );
    }

    public Vec3 apparentPosition(Vec3 listener, double propagationDistance) {
        Vec3 direction = apparentDirection(listener);
        return listener.add(direction.scale(Math.max(1.0, propagationDistance)));
    }

    public boolean hasArrivals() {
        return !arrivals.isEmpty();
    }

    public Vec3 apparentDirection(Vec3 listener) {
        Vec3 fallback = fallbackSource == null
                ? Vec3.ZERO
                : fallbackSource.subtract(listener);
        Vec3 fallbackDirection = fallback.lengthSqr() > 1.0E-10
                ? fallback.normalize()
                : Vec3.ZERO;
        if (arrivals.isEmpty()) {
            return fallbackDirection.lengthSqr() > 1.0E-10
                    ? fallbackDirection
                    : new Vec3(0.0, 0.0, 1.0);
        }

        Vec3 moment = Vec3.ZERO;
        double totalPower = 0.0;
        for (Arrival arrival : arrivals) {
            Vec3 direction = arrival.point().subtract(listener);
            if (direction.lengthSqr() <= 1.0E-10) {
                continue;
            }
            moment = moment.add(direction.normalize().scale(arrival.power()));
            totalPower += arrival.power();
        }
        if (totalPower <= 1.0E-12 || moment.lengthSqr() <= 1.0E-10) {
            return fallbackDirection;
        }
        Vec3 mean = moment.scale(1.0 / totalPower);
        double coherence = Mth.clamp(mean.length(), 0.0, 1.0);
        Vec3 resolved = fallbackDirection.lengthSqr() > 1.0E-10
                ? fallbackDirection.scale(1.0 - coherence).add(mean)
                : mean;
        return resolved.lengthSqr() > 1.0E-10
                ? resolved.normalize()
                : fallbackDirection;
    }

    public record Arrival(Vec3 point, double power) {
        public Arrival {
            power = Double.isFinite(power) ? Math.max(0.0, power) : 0.0;
        }
    }
}
