package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.macaroon.acousticsystem.client.material.AcousticMaterial;

import java.util.List;

public record RoomProbe(
        RoomAcoustics acoustics,
        RoomImpulseResponse impulseResponse,
        List<SurfaceSample> surfaces,
        List<OpeningSample> openings
) {
    public RoomProbe(RoomAcoustics acoustics, List<SurfaceSample> surfaces, List<OpeningSample> openings) {
        this(acoustics, RoomImpulseResponse.SILENT, surfaces, openings);
    }

    public record SurfaceSample(
            Vec3 location,
            Vec3 normal,
            AABB bounds,
            double distance,
            AcousticMaterial material
    ) {
    }

    public record OpeningSample(Vec3 point, Vec3 direction, float weight) {
    }
}
