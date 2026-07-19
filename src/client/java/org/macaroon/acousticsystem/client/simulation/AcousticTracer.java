package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.macaroon.acousticsystem.client.material.AcousticMaterial;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.macaroon.acousticsystem.client.material.AcousticTuning;
import org.macaroon.acousticsystem.client.material.AcousticBands;
import org.macaroon.acousticsystem.client.material.MediumProfile;
import org.macaroon.acousticsystem.client.scene.AcousticScene;
import org.macaroon.acousticsystem.physics.DiffractionPhysics;
import org.macaroon.acousticsystem.physics.RoomLeakagePhysics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AcousticTracer {
    private static final double DIFFRACTION_MARGIN = 0.075;
    private static final Vec3 NEGATIVE_X_NORMAL = new Vec3(-1.0, 0.0, 0.0);
    private static final Vec3 POSITIVE_X_NORMAL = new Vec3(1.0, 0.0, 0.0);
    private static final Vec3 NEGATIVE_Y_NORMAL = new Vec3(0.0, -1.0, 0.0);
    private static final Vec3 POSITIVE_Y_NORMAL = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3 NEGATIVE_Z_NORMAL = new Vec3(0.0, 0.0, -1.0);
    private static final Vec3 POSITIVE_Z_NORMAL = new Vec3(0.0, 0.0, 1.0);
    // EAX exposes one aggregate early-reflection cluster, so four diverse physical
    // image paths retain the useful directional/energy estimate without tracing extra
    // paths that the output stage cannot represent separately.
    private static final int MAX_EARLY_REFLECTION_PATHS_PER_SOUND = 4;
    private static final int DIFFRACTION_CANDIDATE_BUDGET = 20;
    private static final int MAX_ROOM_REFLECTION_SURFACES = 32;
    private static final int MAX_ROOM_OPENINGS = 16;
    private static final double MAX_SHARED_SURFACE_DIRECTION_DOT = 0.94;
    private static final float[] AIR_ABSORPTION_PER_METER = {
            0.000005F, 0.000010F, 0.000025F, 0.000070F,
            0.00018F, 0.00060F, 0.00220F, 0.00800F
    };
    private static final Map<Integer, RoomRayGrid> ROOM_RAY_GRIDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<TransmissionScratch> TRANSMISSION_SCRATCH = ThreadLocal.withInitial(
            TransmissionScratch::new
    );
    private static final ThreadLocal<BlockPos.MutableBlockPos> WALK_POSITION = ThreadLocal.withInitial(
            BlockPos.MutableBlockPos::new
    );

    private AcousticTracer() {
    }

    public static AcousticResult traceImmediate(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomAcoustics room
    ) {
        return captureImmediateContext(level, listener, room).resultFor(source);
    }

    public static AcousticResult traceImmediate(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe roomProbe
    ) {
        AcousticResult result = traceImmediate(level, source, listener, roomProbe.acoustics());
        return new AcousticResult(
                result.directGain(),
                result.highFrequencyGain(),
                result.lowBandGain(),
                result.midLowBandGain(),
                result.midHighBandGain(),
                result.highBandGain(),
                result.reverbSend(),
                result.reverbHighFrequencyGain(),
                result.diffractionContribution(),
                result.propagationDistance(),
                result.apparentPosition(),
                result.reverbRoom(),
                roomProbe.impulseResponse()
        );
    }

    public static ImmediateContext captureImmediateContext(
            BlockGetter level,
            Vec3 listener,
            RoomAcoustics room
    ) {
        MediumSample listenerMedium = sampleMedium(level, listener);
        float mediumSend = listenerMedium.profile().reverbSend() * listenerMedium.weight();
        return new ImmediateContext(
                level,
                listener,
                mixedMediumPair(listenerMedium, 0),
                mixedMediumPair(listenerMedium, 2),
                mixedMediumPair(listenerMedium, 4),
                mixedMediumPair(listenerMedium, 6),
                mediumSend,
                mix(
                        1.0F,
                        listenerMedium.profile().reverbHighFrequencyGain(),
                        listenerMedium.weight()
                ),
                room
        );
    }

    public static AcousticResult trace(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe listenerRoomProbe,
            TraceQuality quality
    ) {
        return trace(
                level, source, listener,
                listenerRoomProbe, listenerRoomProbe,
                quality
        );
    }

    public static AcousticResult trace(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe sourceRoomProbe,
            RoomProbe listenerRoomProbe,
            TraceQuality quality
    ) {
        MediumSample listenerMedium = sampleMedium(level, listener);
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        Vec3 delta = listener.subtract(source);
        double directDistance = source.distanceTo(listener);
        Vec3 direction = delta.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : delta.normalize();
        Vec3 right = direction.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) {
            right = new Vec3(1, 0, 0);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(direction).normalize();
        double rayOffset = occlusionSampleRadius(directDistance, tuning);
        double diagonalOffset = rayOffset / Math.sqrt(2.0);
        Vec3[] offsets = {
                Vec3.ZERO,
                right.add(up).scale(diagonalOffset),
                right.subtract(up).scale(diagonalOffset),
                up.subtract(right).scale(diagonalOffset),
                right.add(up).scale(-diagonalOffset)
        };

        PathEnergyAccumulator geometricPaths = new PathEnergyAccumulator();
        Transmission[] transmissions = new Transmission[offsets.length];
        for (int ray = 0; ray < offsets.length; ray++) {
            Vec3 offset = offsets[ray];
            transmissions[ray] = traceTransmission(level, source.add(offset), listener.add(offset));
            geometricPaths.add(
                    PathKind.DIRECT_OR_TRANSMITTED,
                    transmissions[ray].bands(),
                    1.0F / transmissions.length
            );
        }
        Transmission center = transmissions[0];
        // The offset rays sample the finite first Fresnel zone around the geometric ray.
        // Combine their power continuously on both sides of an edge. Switching from the
        // centre ray to an arithmetic-amplitude average when it first became blocked made
        // an otherwise tiny occluder produce an audible step in level.
        float diffractionContribution = 0.0F;
        DiffractionPath diffractionPath = null;
        if (center.firstBounds() != null) {
            diffractionPath = traceDiffraction(
                    level, source, listener, center.firstBounds(),
                    DIFFRACTION_CANDIDATE_BUDGET, tuning
            );
            DiffractionPath openingBypass = traceOpeningBypass(
                    level, source, listener, sourceRoomProbe, tuning
            );
            if (openingBypass.clear()
                    && weightedEnergy(openingBypass.bands())
                    > weightedEnergy(diffractionPath.bands())) {
                diffractionPath = openingBypass;
            }
            float[] diffraction = diffractionPath.bands();
            geometricPaths.add(PathKind.DIFFRACTED, diffraction, 1.0F);
        }
        float[] geometricSpectrum = geometricPaths.amplitudes();
        applyAirAbsorption(geometricSpectrum, tuning.meters(directDistance));
        applyMediumResponse(geometricSpectrum, listenerMedium);
        float[] diffractionSpectrum = geometricPaths.amplitudes(PathKind.DIFFRACTED);
        applyAirAbsorption(diffractionSpectrum, tuning.meters(directDistance));
        applyMediumResponse(diffractionSpectrum, listenerMedium);
        diffractionContribution = weightedEnergy(diffractionSpectrum);

        float lowEnergy = geometricSpectrum[0] * 0.24F + geometricSpectrum[1] * 0.26F
                + geometricSpectrum[2] * 0.27F + geometricSpectrum[3] * 0.23F;
        float highEnergy = geometricSpectrum[4] * 0.28F + geometricSpectrum[5] * 0.27F
                + geometricSpectrum[6] * 0.25F + geometricSpectrum[7] * 0.20F;
        float arrivalEnergy = Mth.clamp(weightedEnergy(geometricSpectrum), 0.0F, 1.0F);
        float directGain = Mth.clamp(arrivalEnergy, 0.0F, 1.0F);
        float highFrequencyGain = Mth.clamp(highEnergy / Math.max(lowEnergy, 0.01F), 0.02F, 1.0F);
        ReflectionResult reflections = estimateEarlyReflections(
                level,
                source,
                listener,
                sourceRoomProbe,
                listenerRoomProbe,
                MAX_EARLY_REFLECTION_PATHS_PER_SOUND
        );
        float[] reflectedSpectrum = reflections.bands().clone();
        applyMediumResponse(reflectedSpectrum, listenerMedium);
        PathEnergyAccumulator allArrivals = new PathEnergyAccumulator();
        allArrivals.add(PathKind.DIRECT_OR_TRANSMITTED, geometricSpectrum, 1.0F);
        allArrivals.add(PathKind.SPECULAR_REFLECTION, reflectedSpectrum, 1.0F);
        float[] fieldSpectrum = allArrivals.amplitudes();
        Vec3 apparentPosition = source;
        if (diffractionPath != null) {
            Vec3 directDirection = source.subtract(listener);
            Vec3 diffractedDirection = diffractionPath.point().subtract(listener);
            float directionMix = Mth.clamp(
                    diffractionContribution / Math.max(arrivalEnergy, 0.01F),
                    0.0F,
                    1.0F
            );
            if (directDirection.lengthSqr() > 1.0E-6 && diffractedDirection.lengthSqr() > 1.0E-6) {
                Vec3 arrivalDirection = directDirection.normalize().scale(1.0F - directionMix)
                        .add(diffractedDirection.normalize().scale(directionMix));
                if (arrivalDirection.lengthSqr() > 1.0E-6) {
                    apparentPosition = listener.add(
                            arrivalDirection.normalize().scale(Math.max(1.0, source.distanceTo(listener)))
                    );
                }
            }
        }

        float mediumReverb = listenerMedium.profile().reverbSend() * listenerMedium.weight();
        float mediumReverbHighFrequency = mix(
                1.0F,
                listenerMedium.profile().reverbHighFrequencyGain(),
                listenerMedium.weight()
        );
        RoomAcoustics listenerRoom = listenerRoomProbe.acoustics();
        // The late field is driven by the energy which physically reaches the listener
        // region through any path. There is deliberately no same-room/different-room
        // classification: closing a door, moving past an edge, or adding absorption only
        // changes the path spectra and therefore changes the send continuously.
        float pathCoupling = Mth.clamp(weightedEnergy(fieldSpectrum), 0.0F, 1.0F);
        float mediumDiffuseCoupling = mediumReverb * arrivalEnergy;
        float reflectedSend = Mth.clamp(
                (float) Math.sqrt(
                        pathCoupling * pathCoupling
                                + mediumDiffuseCoupling * mediumDiffuseCoupling
                ),
                0.0F,
                1.0F
        );
        RoomAcoustics reverbRoom = listenerRoom;
        RoomImpulseResponse reverbImpulse = listenerRoomProbe.impulseResponse();
        float fieldLow = meanBands(fieldSpectrum, 0, 4);
        float fieldHigh = meanBands(fieldSpectrum, 4, 8);
        float fieldHighFrequencyGain = Mth.clamp(
                fieldHigh / Math.max(fieldLow, 0.01F),
                0.05F,
                1.0F
        );
        double propagationDistance = source.distanceTo(listener);
        return new AcousticResult(
                directGain,
                highFrequencyGain,
                pairEnergy(geometricSpectrum, 0),
                pairEnergy(geometricSpectrum, 2),
                pairEnergy(geometricSpectrum, 4),
                pairEnergy(geometricSpectrum, 6),
                Mth.clamp(
                        reflectedSend * tuning.reverbSendScale(),
                        0.0F,
                        0.95F
                ),
                Math.min(
                        fieldHighFrequencyGain,
                        mediumReverbHighFrequency
                ),
                diffractionContribution,
                propagationDistance,
                apparentPosition,
                reverbRoom,
                reverbImpulse
        );
    }

    public static RoomProbe probeRoom(BlockGetter level, Vec3 listener) {
        return probeRoom(level, listener, AcousticMaterialRegistry.tuning().roomRayCount());
    }

    static RoomProbe probeSourceRoom(BlockGetter level, Vec3 source) {
        return probeRoom(
                level,
                source,
                AcousticMaterialRegistry.tuning().sourceRoomRayCount()
        );
    }

    private static RoomProbe probeRoom(BlockGetter level, Vec3 listener, int requestedRayCount) {
        MediumSample listenerMedium = sampleMedium(level, listener);
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        RoomRayGrid grid = roomRayGrid(requestedRayCount);
        double probeDistance = tuning.roomProbeDistance();
        SurfaceHit[] hits = new SurfaceHit[grid.directions().length];
        double[] distances = new double[hits.length];
        double[] openingBoundaries = new double[hits.length];
        float[] openingWeights = new float[hits.length];
        OpeningBoundary[] openingModels = new OpeningBoundary[hits.length];
        Arrays.fill(openingBoundaries, Double.POSITIVE_INFINITY);
        SurfaceHit[] firstHitHolder = new SurfaceHit[1];

        for (int ray = 0; ray < hits.length; ray++) {
            Vec3 direction = grid.directions()[ray];
            SurfaceHit hit = firstSurface(
                    level,
                    listener,
                    listener.add(direction.scale(probeDistance)),
                    firstHitHolder
            );
            hits[ray] = hit;
            distances[ray] = hit == null ? probeDistance : hit.distance();
            if (hit == null) {
                // A ray leaving the simulation control volume is an absorbing open
                // boundary, not missing data. Previously it contributed no surface and
                // no escape area, so a listener outside a large open facade inherited
                // the walls inside the opening as if they enclosed the listener.
                openingBoundaries[ray] = probeDistance;
                openingWeights[ray] = 1.0F;
            }
        }

        double[] neighborBuffer = new double[8];
        ArrayDeque<Integer> openingQueue = new ArrayDeque<>();
        double openingContrast = tuning.roomOpeningContrast();
        for (int ray = 0; ray < hits.length; ray++) {
            if (hits[ray] == null) {
                // Preserve genuine escape to the control-volume boundary. Local plane
                // reconstruction is only for a ray that passes a nearby aperture and
                // later hits another surface; applying it to open sky folds the sky back
                // onto a nearby facade or ground plane.
                continue;
            }
            double localMedian = neighborMedian(distances, grid, ray, neighborBuffer);
            OpeningBoundary opening = openingBoundary(
                    hits,
                    distances,
                    grid,
                    ray,
                    listener,
                    localMedian
            );
            if (opening != null
                    && distances[ray] > opening.distance() * openingContrast) {
                openingBoundaries[ray] = opening.distance();
                openingWeights[ray] = Math.max(
                        openingWeights[ray],
                        openingWeight(distances[ray], opening.distance(), openingContrast)
                );
                openingModels[ray] = opening;
                openingQueue.addLast(ray);
            }
        }

        while (!openingQueue.isEmpty()) {
            int ray = openingQueue.removeFirst();
            int row = ray / grid.azimuthSamples();
            int column = ray % grid.azimuthSamples();
            OpeningBoundary model = openingModels[ray];
            if (model == null) {
                continue;
            }
            for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
                int neighborRow = row + rowOffset;
                if (neighborRow < 0 || neighborRow >= grid.elevationSamples()) {
                    continue;
                }
                for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                    if (rowOffset == 0 && columnOffset == 0) {
                        continue;
                    }
                    int neighborColumn = Math.floorMod(column + columnOffset, grid.azimuthSamples());
                    int neighbor = neighborRow * grid.azimuthSamples() + neighborColumn;
                    double boundary = model.intersectionDistance(
                            listener,
                            grid.directions()[neighbor]
                    );
                    if (hits[neighbor] == null
                            || !Double.isFinite(boundary)
                            || distances[neighbor] <= boundary * openingContrast
                            || boundary + 1.0E-6 >= openingBoundaries[neighbor]) {
                        continue;
                    }
                    openingBoundaries[neighbor] = boundary;
                    openingWeights[neighbor] = Math.max(
                            openingWeights[neighbor],
                            openingWeight(distances[neighbor], boundary, openingContrast)
                    );
                    openingModels[neighbor] = model;
                    openingQueue.addLast(neighbor);
                }
            }
        }

        List<RoomProbe.SurfaceSample> surfaces = new ArrayList<>(hits.length);
        double returnedWeight = 0.0;
        double totalDistance = 0.0;
        double absorption = 0.0;
        double lowAbsorption = 0.0;
        double scattering = 0.0;
        double roomVolume = 0.0;
        double solidSurfaceArea = 0.0;
        double openingArea = 0.0;
        double openingAbsorptionArea = 0.0;
        double solidAngle = Math.PI * 4.0 / hits.length;
        Vec3 reflectionDirection = Vec3.ZERO;
        for (int ray = 0; ray < hits.length; ray++) {
            Vec3 rayDirection = grid.directions()[ray];
            SurfaceHit hit = hits[ray];
            float openingWeight = openingWeights[ray];
            double boundaryDistance = openingBoundaries[ray];
            double solidFraction = 1.0;
            if (Double.isFinite(boundaryDistance) && openingWeight > 1.0E-4F) {
                double rayOpeningArea = RoomLeakagePhysics.projectedOpeningArea(
                        solidAngle,
                        boundaryDistance
                );
                openingArea += rayOpeningArea;
                openingAbsorptionArea += rayOpeningArea
                        * tuning.roomOpeningAreaScale();
                roomVolume += solidAngle
                        * boundaryDistance * boundaryDistance * boundaryDistance / 3.0;
                solidFraction = 0.0;
            }

            if (hit == null) {
                continue;
            }
            surfaces.add(new RoomProbe.SurfaceSample(
                    hit.location(), hit.normal(), hit.bounds(), hit.distance(), hit.material()
            ));
            if (solidFraction <= 1.0E-4) {
                continue;
            }

            double incidence = Math.max(Math.abs(rayDirection.dot(hit.normal())), 0.20);
            double raySurfaceArea = solidAngle * solidFraction
                    * hit.distance() * hit.distance() / incidence;
            solidSurfaceArea += raySurfaceArea;
            roomVolume += solidAngle * solidFraction
                    * hit.distance() * hit.distance() * hit.distance() / 3.0;
            returnedWeight += raySurfaceArea;
            totalDistance += hit.distance() * raySurfaceArea;
            absorption += meanMaterialAbsorption(hit.material(), 2, 6) * raySurfaceArea;
            lowAbsorption += meanMaterialAbsorption(hit.material(), 0, 2) * raySurfaceArea;
            scattering += hit.material().scattering() * raySurfaceArea;

            Vec3 direction = hit.location().subtract(listener);
            if (direction.lengthSqr() > 1.0E-6) {
                float reflectivity = 1.0F
                        - meanMaterialAbsorption(hit.material(), 2, 6);
                reflectionDirection = reflectionDirection.add(direction.normalize().scale(
                        solidFraction * reflectivity
                ));
            }
        }

        if (returnedWeight < 1.0E-4) {
            RoomAcoustics acoustics = applyTuning(
                    applyMediumRoom(RoomAcoustics.OUTDOORS, listenerMedium),
                    tuning
            );
            return new RoomProbe(acoustics, RoomImpulseResponse.SILENT, List.of(), List.of());
        }

        double metersPerBlock = tuning.metersPerBlock();
        double squareMetersPerBlockFace = metersPerBlock * metersPerBlock;
        RoomLeakagePhysics.Result leakage = RoomLeakagePhysics.evaluate(
                roomVolume * squareMetersPerBlockFace * metersPerBlock,
                solidSurfaceArea * squareMetersPerBlockFace,
                absorption * squareMetersPerBlockFace,
                openingArea * squareMetersPerBlockFace,
                openingAbsorptionArea * squareMetersPerBlockFace
        );
        float meanDistance = (float) (totalDistance / returnedWeight);
        float meanAbsorption = (float) (absorption / returnedWeight);
        float meanLowAbsorption = (float) (lowAbsorption / returnedWeight);
        float meanScattering = (float) (scattering / returnedWeight);
        float reflective = 1.0F - meanAbsorption;
        LateReverbTracer.Estimate lateReverb = LateReverbTracer.trace(
                level,
                listener,
                grid.directions(),
                hits,
                tuning,
                Mth.clamp(leakage.reverberationTimeSeconds(), 0.12F, 4.0F)
        );
        float reflectionDelay = Mth.clamp(lateReverb.earlyDelay(), 0.0F, 0.3F);
        float lateDelay = Mth.clamp(lateReverb.lateDelay(), 0.0F, 0.1F);
        if (reflectionDirection.lengthSqr() > 1.0) {
            reflectionDirection = reflectionDirection.normalize();
        }
        float totalReturnedEnergy = lateReverb.earlyEnergy() + lateReverb.lateEnergy();
        // Diffuse-field theory gives I_reverb/I_direct(1 m) = 16*pi/A for an
        // omnidirectional source, where A is the equivalent absorption area. EAX takes
        // amplitude, so use its square root and leave source coupling to the send filter.
        double equivalentAbsorptionArea = (absorption + openingAbsorptionArea)
                * squareMetersPerBlockFace;
        float roomGain = Mth.clamp(
                (float) Math.sqrt(
                        16.0 * Math.PI / Math.max(equivalentAbsorptionArea, 1.0E-6)
                                * lateReverb.fieldStrength()
                ),
                0.0F,
                1.0F
        );
        float earlyShare = totalReturnedEnergy <= 1.0E-8F
                ? 0.0F
                : lateReverb.earlyEnergy() / totalReturnedEnergy;
        float lateShare = totalReturnedEnergy <= 1.0E-8F
                ? 0.0F
                : lateReverb.lateEnergy() / totalReturnedEnergy;
        float midReflectivity = Math.max(1.0E-6F, 1.0F - meanAbsorption);
        float lowFrequencyGain = Mth.clamp(
                (float) Math.sqrt(Math.max(0.0F, 1.0F - meanLowAbsorption) / midReflectivity),
                0.1F,
                1.0F
        );

        RoomAcoustics acoustics = new RoomAcoustics(
                lateReverb.density(),
                lateReverb.diffusion(),
                roomGain,
                lateReverb.highFrequencyGain(),
                lowFrequencyGain,
                Mth.clamp(lateReverb.decayTime(), 0.12F, 4.0F),
                lateReverb.decayHighFrequencyRatio(),
                lateReverb.decayLowFrequencyRatio(),
                Mth.clamp(
                        (float) Math.sqrt(Math.max(0.0F, earlyShare)),
                        0.0F,
                        3.16F
                ),
                reflectionDelay,
                reflectionDirection,
                Mth.clamp((float) Math.sqrt(Math.max(0.0F, lateShare)), 0.0F, 10.0F),
                lateDelay,
                Vec3.ZERO,
                0.25F,
                0.0F,
                Mth.clamp(airAbsorption(6, tuning.meters(meanDistance)), 0.892F, 1.0F)
        );
        acoustics = applyMediumRoom(acoustics, listenerMedium);
        acoustics = applyTuning(acoustics, tuning);
        List<RoomProbe.OpeningSample> openings = new ArrayList<>();
        for (int ray = 0; ray < hits.length; ray++) {
            if (!Double.isFinite(openingBoundaries[ray]) || openingWeights[ray] <= 0.01F) {
                continue;
            }
            Vec3 direction = grid.directions()[ray];
            openings.add(new RoomProbe.OpeningSample(
                    listener.add(direction.scale(openingBoundaries[ray])),
                    direction,
                    openingWeights[ray]
            ));
        }
        return new RoomProbe(
                acoustics,
                lateReverb.impulseResponse(),
                selectReflectionSurfaces(surfaces, listener),
                selectOpenings(openings)
        );
    }

    private static RoomRayGrid roomRayGrid(int requestedRayCount) {
        return ROOM_RAY_GRIDS.computeIfAbsent(requestedRayCount, count -> {
            int elevationSamples = Math.max(4, (int) Math.round(Math.sqrt(count / 4.0)));
            int azimuthSamples = Math.max(8, (int) Math.ceil(count / (double) elevationSamples));
            Vec3[] directions = new Vec3[elevationSamples * azimuthSamples];
            for (int row = 0; row < elevationSamples; row++) {
                double y = -1.0 + (row + 0.5) * 2.0 / elevationSamples;
                double horizontal = Math.sqrt(Math.max(0.0, 1.0 - y * y));
                double phase = (row & 1) == 0 ? 0.0 : Math.PI / azimuthSamples;
                for (int column = 0; column < azimuthSamples; column++) {
                    double azimuth = column * Math.PI * 2.0 / azimuthSamples + phase;
                    directions[row * azimuthSamples + column] = new Vec3(
                            Math.cos(azimuth) * horizontal,
                            y,
                            Math.sin(azimuth) * horizontal
                    );
                }
            }
            return new RoomRayGrid(azimuthSamples, elevationSamples, directions);
        });
    }

    private static double neighborMedian(
            double[] distances,
            RoomRayGrid grid,
            int ray,
            double[] buffer
    ) {
        int row = ray / grid.azimuthSamples();
        int column = ray % grid.azimuthSamples();
        int count = 0;
        for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
            int neighborRow = row + rowOffset;
            if (neighborRow < 0 || neighborRow >= grid.elevationSamples()) {
                continue;
            }
            for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                if (rowOffset == 0 && columnOffset == 0) {
                    continue;
                }
                int neighborColumn = Math.floorMod(column + columnOffset, grid.azimuthSamples());
                buffer[count++] = distances[neighborRow * grid.azimuthSamples() + neighborColumn];
            }
        }
        Arrays.sort(buffer, 0, count);
        int middle = count / 2;
        return (count & 1) == 0
                ? (buffer[middle - 1] + buffer[middle]) * 0.5
                : buffer[middle];
    }

    private static float openingWeight(double distance, double boundary, double contrast) {
        return Mth.clamp((float) (1.0 - boundary * contrast / Math.max(distance, 1.0E-6)), 0.0F, 1.0F);
    }

    private static OpeningBoundary openingBoundary(
            SurfaceHit[] hits,
            double[] distances,
            RoomRayGrid grid,
            int ray,
            Vec3 listener,
            double localMedian
    ) {
        int row = ray / grid.azimuthSamples();
        int column = ray % grid.azimuthSamples();
        SurfaceHit nearest = null;
        for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
            int neighborRow = row + rowOffset;
            if (neighborRow < 0 || neighborRow >= grid.elevationSamples()) {
                continue;
            }
            for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                if (rowOffset == 0 && columnOffset == 0) {
                    continue;
                }
                int neighborColumn = Math.floorMod(column + columnOffset, grid.azimuthSamples());
                int neighbor = neighborRow * grid.azimuthSamples() + neighborColumn;
                SurfaceHit hit = hits[neighbor];
                if (hit != null && (nearest == null || hit.distance() < nearest.distance())) {
                    nearest = hit;
                }
            }
        }
        if (nearest == null) {
            return null;
        }

        Vec3 referenceNormal = nearest.normal();
        Vec3 normalSum = Vec3.ZERO;
        Vec3 pointSum = Vec3.ZERO;
        int coherentSurfaces = 0;
        double supportMinX = Double.POSITIVE_INFINITY;
        double supportMinY = Double.POSITIVE_INFINITY;
        double supportMinZ = Double.POSITIVE_INFINITY;
        double supportMaxX = Double.NEGATIVE_INFINITY;
        double supportMaxY = Double.NEGATIVE_INFINITY;
        double supportMaxZ = Double.NEGATIVE_INFINITY;
        for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
            int neighborRow = row + rowOffset;
            if (neighborRow < 0 || neighborRow >= grid.elevationSamples()) {
                continue;
            }
            for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                if (rowOffset == 0 && columnOffset == 0) {
                    continue;
                }
                int neighborColumn = Math.floorMod(column + columnOffset, grid.azimuthSamples());
                int neighbor = neighborRow * grid.azimuthSamples() + neighborColumn;
                SurfaceHit hit = hits[neighbor];
                if (hit == null
                        || distances[neighbor] > localMedian * 1.5
                        || hit.normal().dot(referenceNormal) < 0.88) {
                    continue;
                }
                normalSum = normalSum.add(hit.normal());
                pointSum = pointSum.add(hit.location());
                coherentSurfaces++;
                AABB bounds = hit.bounds();
                supportMinX = Math.min(supportMinX, bounds.minX);
                supportMinY = Math.min(supportMinY, bounds.minY);
                supportMinZ = Math.min(supportMinZ, bounds.minZ);
                supportMaxX = Math.max(supportMaxX, bounds.maxX);
                supportMaxY = Math.max(supportMaxY, bounds.maxY);
                supportMaxZ = Math.max(supportMaxZ, bounds.maxZ);
            }
        }
        if (coherentSurfaces < 2 || normalSum.lengthSqr() < 1.0E-6) {
            return null;
        }

        Vec3 planeNormal = normalSum.normalize();
        Vec3 planePoint = pointSum.scale(1.0 / coherentSurfaces);
        Vec3 direction = grid.directions()[ray];
        double denominator = direction.dot(planeNormal);
        if (Math.abs(denominator) < 0.12) {
            return null;
        }
        double intersection = planePoint.subtract(listener).dot(planeNormal) / denominator;
        if (intersection <= 0.0
                || intersection < localMedian * 0.35
                || intersection > localMedian * 2.5) {
            return null;
        }
        OpeningBoundary opening = new OpeningBoundary(
                intersection,
                planeNormal,
                planePoint,
                new AABB(
                        supportMinX, supportMinY, supportMinZ,
                        supportMaxX, supportMaxY, supportMaxZ
                )
        );
        return Double.isFinite(opening.intersectionDistance(listener, direction))
                ? opening
                : null;
    }

    private static List<RoomProbe.SurfaceSample> selectReflectionSurfaces(
            List<RoomProbe.SurfaceSample> surfaces,
            Vec3 listener
    ) {
        surfaces.sort(Comparator.comparingDouble(RoomProbe.SurfaceSample::distance));
        List<RoomProbe.SurfaceSample> selected = new ArrayList<>(
                Math.min(MAX_ROOM_REFLECTION_SURFACES, surfaces.size())
        );
        List<Vec3> directions = new ArrayList<>(MAX_ROOM_REFLECTION_SURFACES);
        for (RoomProbe.SurfaceSample candidate : surfaces) {
            Vec3 direction = candidate.location().subtract(listener).normalize();
            boolean separated = true;
            for (Vec3 accepted : directions) {
                if (direction.dot(accepted) > MAX_SHARED_SURFACE_DIRECTION_DOT) {
                    separated = false;
                    break;
                }
            }
            if (!separated) {
                continue;
            }
            selected.add(candidate);
            directions.add(direction);
            if (selected.size() >= MAX_ROOM_REFLECTION_SURFACES) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private static List<RoomProbe.OpeningSample> selectOpenings(List<RoomProbe.OpeningSample> openings) {
        openings.sort(Comparator.comparingDouble(RoomProbe.OpeningSample::weight).reversed());
        List<RoomProbe.OpeningSample> selected = new ArrayList<>(Math.min(MAX_ROOM_OPENINGS, openings.size()));
        for (RoomProbe.OpeningSample candidate : openings) {
            boolean separated = true;
            for (RoomProbe.OpeningSample accepted : selected) {
                if (candidate.direction().dot(accepted.direction()) > 0.975) {
                    separated = false;
                    break;
                }
            }
            if (!separated) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() >= MAX_ROOM_OPENINGS) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private static DiffractionPath traceDiffraction(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            AABB obstacle,
            int candidateBudget,
            AcousticTuning tuning
    ) {
        return traceDiffraction(
                level, source, listener, obstacle, candidateBudget, tuning, 0
        );
    }

    private static DiffractionPath traceDiffraction(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            AABB obstacle,
            int candidateBudget,
            AcousticTuning tuning,
            int diffractionDepth
    ) {
        double directDistance = source.distanceTo(listener);
        if (directDistance < 1.0E-6 || candidateBudget <= 0) {
            return new DiffractionPath(
                    new float[AcousticBands.COUNT], source, directDistance, false
            );
        }

        Vec3 direction = listener.subtract(source).scale(1.0 / directDistance);
        Vec3 right = direction.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0E-8) {
            right = new Vec3(1.0, 0.0, 0.0);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(direction).normalize();
        Vec3 obstacleCenter = obstacle.getCenter();
        double planeDistance = Mth.clamp(
                obstacleCenter.subtract(source).dot(direction),
                0.01,
                directDistance - 0.01
        );
        Vec3 planeCenter = source.add(direction.scale(planeDistance));
        double sourceDistance = Math.max(0.01, planeDistance);
        double listenerDistance = Math.max(0.01, directDistance - planeDistance);
        double fresnel125 = tuning.blocks(fresnelRadius(
                125.0,
                tuning.meters(sourceDistance),
                tuning.meters(listenerDistance)
        ));
        // Geometry search is not cut off at the first Fresnel boundary. A remote edge
        // still carries finite (usually very small) energy; the extra path length and
        // knife-edge loss decide its importance. The room-probe span is also the radius
        // for which the immutable worker scene contains complete surrounding geometry.
        double searchRadius = Math.max(
                fresnel125,
                Math.min(
                        tuning.roomProbeDistance(),
                        directDistance + fresnel125
                )
        );
        Vec3[] corners = aabbCorners(obstacle);
        int checksPerDirection = Math.max(1, (int) Math.ceil(candidateBudget / 8.0));
        int angularSamples = 8;
        List<DiffractionCandidate> viable = new ArrayList<>(angularSamples);
        for (int angularStep = 0; angularStep < angularSamples; angularStep++) {
            double angle = angularStep * Math.PI * 0.25;
            Vec3 radial = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            double nearRadius = projectedSilhouetteRadius(planeCenter, radial, corners)
                    + DIFFRACTION_MARGIN;
            double maximumRadius = Math.max(searchRadius, nearRadius);
            double localRadius = Math.min(
                    maximumRadius,
                    Math.max(fresnel125, nearRadius * 2.5)
            );
            double initialRadius = checksPerDirection == 1 ? localRadius : nearRadius;
            DiffractionCandidate near = evaluateDiffractionCandidate(
                    level, source, listener, planeCenter.add(radial.scale(initialRadius)),
                    directDistance, tuning
            );
            if (near.clear()) {
                viable.add(near);
                continue;
            }
            double blockedRadius = initialRadius;
            double clearRadius = Double.NaN;
            DiffractionCandidate bestBlocked = near;
            DiffractionCandidate nearestClear = null;
            for (int check = 1; check < checksPerDirection; check++) {
                double testRadius;
                if (nearestClear == null) {
                    if (check == 1) {
                        // Resolve the acoustically important local edge first.
                        testRadius = localRadius;
                    } else {
                        // Only a still-blocked direction expands outward. The final scan
                        // reaches the scene boundary, while any spare checks refine the
                        // first open bracket instead of jumping between fixed radii.
                        int remainingChecks = checksPerDirection - check;
                        double expansion = Math.pow(
                                maximumRadius / Math.max(blockedRadius, 1.0E-4),
                                1.0 / Math.max(1, remainingChecks)
                        );
                        testRadius = check == checksPerDirection - 1
                                ? maximumRadius
                                : Math.min(maximumRadius, blockedRadius * expansion);
                    }
                } else {
                    testRadius = (blockedRadius + clearRadius) * 0.5;
                }
                DiffractionCandidate tested = evaluateDiffractionCandidate(
                        level, source, listener, planeCenter.add(radial.scale(testRadius)),
                        directDistance, tuning
                );
                if (tested.clear()) {
                    clearRadius = testRadius;
                    nearestClear = tested;
                } else {
                    blockedRadius = testRadius;
                    if (tested.score() > bestBlocked.score()) {
                        bestBlocked = tested;
                    }
                }
            }
            // Never turn a direction into a Boolean "sealed" result. If no open bend was
            // found, preserve the least-loss grazing/transmitted path; it remains subject
            // to its measured material, distance and diffraction losses.
            viable.add(nearestClear == null ? bestBlocked : nearestClear);
        }

        viable.sort(Comparator.comparingDouble(DiffractionCandidate::score).reversed());
        if (diffractionDepth < 2) {
            int refinements = Math.min(2, viable.size());
            for (int index = 0; index < refinements; index++) {
                DiffractionCandidate candidate = viable.get(index);
                Transmission sourceLeg = candidate.sourceLeg();
                Transmission listenerLeg = candidate.listenerLeg();
                if (candidate.clear()
                        || sourceLeg == null || listenerLeg == null
                        || sourceLeg.blockers() != 0
                        || listenerLeg.firstBounds() == null) {
                    continue;
                }
                // A thick or stepped screen has successive geometric edges. A one-bend
                // knife-edge path can clear the near edge and still intersect the next
                // block, which previously fell back to wall transmission and sounded
                // fully sealed. Resolve a bounded visibility chain and apply every edge.
                DiffractionPath secondEdge = traceDiffraction(
                        level,
                        candidate.point(),
                        listener,
                        listenerLeg.firstBounds(),
                        12,
                        tuning,
                        diffractionDepth + 1
                );
                double twoEdgeDistance = source.distanceTo(candidate.point())
                        + secondEdge.pathDistance();
                float[] twoEdgeBands = diffractionEnergy(
                        sourceLeg.bands(),
                        secondEdge.bands(),
                        null,
                        twoEdgeDistance,
                        directDistance,
                        tuning
                );
                float twoEdgeScore = weightedEnergy(twoEdgeBands);
                if (twoEdgeScore > candidate.score()) {
                    viable.set(index, new DiffractionCandidate(
                            twoEdgeBands,
                            secondEdge.point(),
                            twoEdgeScore,
                            secondEdge.clear(),
                            sourceLeg,
                            listenerLeg,
                            twoEdgeDistance
                    ));
                }
            }
            viable.sort(Comparator.comparingDouble(DiffractionCandidate::score).reversed());
        }
        float[] power = new float[AcousticBands.COUNT];
        Vec3 bestPoint = source;
        double bestPathDistance = directDistance;
        boolean bestClear = false;
        int selected = 0;
        for (DiffractionCandidate candidate : viable) {
            if (selected == 0) {
                bestPoint = candidate.point();
                bestPathDistance = candidate.pathDistance();
                bestClear = candidate.clear();
            }
            selected++;
            for (int band = 0; band < power.length; band++) {
                power[band] += candidate.bands()[band] * candidate.bands()[band];
            }
            if (selected >= tuning.diffractionMaxPaths()) {
                break;
            }
        }

        float[] combined = new float[AcousticBands.COUNT];
        for (int band = 0; band < combined.length; band++) {
            combined[band] = Mth.clamp(
                    (float) Math.sqrt(power[band]) * tuning.diffractionGainScale(),
                    0.0F,
                    1.0F
            );
        }
        return new DiffractionPath(combined, bestPoint, bestPathDistance, bestClear);
    }

    private static DiffractionCandidate evaluateDiffractionCandidate(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            Vec3 point,
            double directDistance,
            AcousticTuning tuning
    ) {
        if (isSolidAt(level, point)) {
            return new DiffractionCandidate(
                    new float[AcousticBands.COUNT], point, 0.0F, false,
                    null, null, directDistance
            );
        }
        Transmission sourceLeg = traceTransmission(level, source, point);
        Transmission listenerLeg = traceTransmission(level, point, listener);
        double pathDistance = source.distanceTo(point) + point.distanceTo(listener);
        float[] energy = diffractionEnergy(
                sourceLeg.bands(), listenerLeg.bands(), null,
                pathDistance, directDistance, tuning
        );
        return new DiffractionCandidate(
                energy,
                point,
                weightedEnergy(energy),
                sourceLeg.blockers() == 0
                        && listenerLeg.blockers() == 0,
                sourceLeg,
                listenerLeg,
                pathDistance
        );
    }

    /**
     * Connects the source probe's measured escape directions back to the listener. This
     * is a geometric broken-ray solver for thick screens and stepped apertures: both legs
     * must be genuinely unobstructed, so it cannot turn a sealed room into a portal.
     */
    private static DiffractionPath traceOpeningBypass(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe sourceRoomProbe,
            AcousticTuning tuning
    ) {
        double directDistance = source.distanceTo(listener);
        if (directDistance < 1.0E-6 || sourceRoomProbe.openings().isEmpty()) {
            return new DiffractionPath(
                    new float[AcousticBands.COUNT], source, directDistance, false
            );
        }

        float bestScore = 0.0F;
        float[] bestBands = new float[AcousticBands.COUNT];
        Vec3 bestPoint = source;
        double bestDistance = directDistance;
        double maximumDistance = Math.max(directDistance, tuning.roomProbeDistance());
        double step = 0.5;
        for (RoomProbe.OpeningSample opening : sourceRoomProbe.openings()) {
            Vec3 direction = opening.direction().normalize();
            for (double distance = step; distance <= maximumDistance; distance += step) {
                Vec3 point = source.add(direction.scale(distance));
                if (pointInsideCollision(level, point)) {
                    continue;
                }
                Transmission sourceLeg = traceTransmission(level, source, point);
                if (sourceLeg.blockers() != 0) {
                    break;
                }
                Transmission listenerLeg = traceTransmission(level, point, listener);
                if (listenerLeg.blockers() != 0) {
                    continue;
                }
                double pathDistance = source.distanceTo(point) + point.distanceTo(listener);
                float[] bands = diffractionEnergy(
                        sourceLeg.bands(),
                        listenerLeg.bands(),
                        null,
                        pathDistance,
                        directDistance,
                        tuning
                );
                float apertureAmplitude = (float) Math.sqrt(
                        Mth.clamp(opening.weight(), 0.0F, 1.0F)
                );
                for (int band = 0; band < bands.length; band++) {
                    bands[band] *= apertureAmplitude;
                }
                float score = weightedEnergy(bands);
                if (score > bestScore) {
                    bestScore = score;
                    bestBands = bands;
                    bestPoint = point;
                    bestDistance = pathDistance;
                }
                // Along one escape ray the first mutually visible point minimizes its
                // broken-path length; later points only add propagation distance.
                break;
            }
        }
        return new DiffractionPath(
                bestBands, bestPoint, bestDistance, bestScore > 0.0F
        );
    }

    private static boolean isSolidAt(BlockGetter level, Vec3 point) {
        BlockPos position = BlockPos.containing(point);
        BlockState state = level.getBlockState(position);
        if (state.isAir()) {
            return false;
        }
        if (isCollisionShapeFullBlock(level, position, state)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, position);
        for (AABB localBounds : shape.toAabbs()) {
            if (localBounds.move(position).contains(point)) {
                return true;
            }
        }
        return false;
    }

    private static double fresnelRadius(
            double frequencyHz,
            double sourceDistance,
            double listenerDistance
    ) {
        double wavelength = DiffractionPhysics.SPEED_OF_SOUND_METERS_PER_SECOND / frequencyHz;
        return Math.sqrt(
                wavelength * sourceDistance * listenerDistance
                        / (sourceDistance + listenerDistance)
        );
    }

    private static double projectedSilhouetteRadius(
            Vec3 planeCenter,
            Vec3 radial,
            Vec3[] corners
    ) {
        double radius = 0.0;
        for (Vec3 corner : corners) {
            radius = Math.max(radius, corner.subtract(planeCenter).dot(radial));
        }
        return Math.max(0.0, radius);
    }

    private static Vec3[] aabbCorners(AABB box) {
        return new Vec3[]{
                new Vec3(box.minX, box.minY, box.minZ),
                new Vec3(box.minX, box.minY, box.maxZ),
                new Vec3(box.minX, box.maxY, box.minZ),
                new Vec3(box.minX, box.maxY, box.maxZ),
                new Vec3(box.maxX, box.minY, box.minZ),
                new Vec3(box.maxX, box.minY, box.maxZ),
                new Vec3(box.maxX, box.maxY, box.minZ),
                new Vec3(box.maxX, box.maxY, box.maxZ)
        };
    }

    private static float[] diffractionEnergy(
            float[] first,
            float[] second,
            float[] third,
            double pathDistance,
            double directDistance,
            AcousticTuning tuning
    ) {
        double excessDistance = tuning.meters(Math.max(0.0, pathDistance - directDistance));
        float distanceLoss = (float) (directDistance / Math.max(pathDistance, 0.1));
        float[] energy = new float[AcousticBands.COUNT];
        for (int band = 0; band < energy.length; band++) {
            float thirdLeg = third == null ? 1.0F : third[band];
            float diffraction = DiffractionPhysics.knifeEdgeAmplitude(
                    AcousticBands.CENTERS_HZ[band],
                    excessDistance
            );
            energy[band] = first[band] * second[band] * thirdLeg * diffraction
                    * distanceLoss * airAbsorption(band, excessDistance);
        }
        return energy;
    }

    private static double occlusionSampleRadius(double distance, AcousticTuning tuning) {
        return Math.max(
                tuning.occlusionSampleRadiusMin(),
                Math.min(
                        tuning.occlusionSampleRadiusMax(),
                        distance * tuning.occlusionSampleAngularSpread()
                )
        );
    }

    static ReflectionResult estimateEarlyReflections(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe roomProbe,
            int pathBudget
    ) {
        return estimateEarlyReflections(
                level, source, listener, roomProbe, roomProbe, pathBudget
        );
    }

    private static ReflectionResult estimateEarlyReflections(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe sourceRoomProbe,
            RoomProbe listenerRoomProbe,
            int pathBudget
    ) {
        List<RoomProbe.SurfaceSample> candidates = reflectionCandidates(
                source, listener, sourceRoomProbe, listenerRoomProbe
        );
        if (candidates.isEmpty() || pathBudget <= 0) {
            return ReflectionResult.silent();
        }

        double directDistance = Math.max(source.distanceTo(listener), 0.5);
        float[] accumulated = new float[AcousticBands.COUNT];
        int remainingDiffractedLegs = Math.min(2, pathBudget);
        int acceptedPaths = 0;
        List<Vec3> acceptedReflectionPoints = new ArrayList<>(pathBudget);
        SurfaceHit[] validationHit = new SurfaceHit[1];
        // pathBudget limits accepted, audible paths. It must not limit how many cheap
        // image-plane candidates are inspected: the nearest few probe rays can be
        // geometrically invalid even though a wall or ceiling immediately after them
        // supplies the dominant reflection.
        for (RoomProbe.SurfaceSample surface : candidates) {
            Vec3 normal = surface.normal().normalize();
            double sourceSide = source.subtract(surface.location()).dot(normal);
            double listenerSide = listener.subtract(surface.location()).dot(normal);
            if (sourceSide * listenerSide <= 1.0E-5) {
                continue;
            }

            Vec3 mirroredSource = source.subtract(normal.scale(2.0 * sourceSide));
            Vec3 mirrorToListener = listener.subtract(mirroredSource);
            double denominator = mirrorToListener.dot(normal);
            if (Math.abs(denominator) < 1.0E-7) {
                continue;
            }
            double interpolation = surface.location().subtract(mirroredSource).dot(normal) / denominator;
            if (interpolation <= 0.0 || interpolation >= 1.0) {
                continue;
            }
            Vec3 reflectionPoint = mirroredSource.add(mirrorToListener.scale(interpolation));
            Vec3 facingNormal = sourceSide >= 0.0 ? normal : normal.scale(-1.0);
            // The sampled face only identifies a physical plane. A Minecraft wall is
            // made from many one-block faces, so requiring the image-source point to
            // remain inside that particular sample made a valid reflection disappear
            // whenever it crossed into the neighbouring block. Probe the calculated
            // point against the current world instead, preserving real gaps and shapes.
            AcousticMaterial reflectionMaterial = surface.material();
            // Always validate the calculated point, including points inside the sampled
            // block bounds. At block edges a probe ray can report a floor/side normal for
            // the adjoining closed divider. Trusting that ambiguous normal creates a
            // zero-loss reflection through a sealed wall.
            SurfaceHit actualSurface = firstSurface(
                    level,
                    reflectionPoint.add(facingNormal.scale(0.12)),
                    reflectionPoint.subtract(facingNormal.scale(1.25)),
                    validationHit
            );
            if (actualSurface == null
                    || actualSurface.location().distanceToSqr(reflectionPoint) > 0.05 * 0.05
                    || actualSurface.normal().normalize().dot(facingNormal) < 0.98) {
                continue;
            }
            reflectionPoint = actualSurface.location();
            reflectionMaterial = actualSurface.material();
            boolean duplicate = false;
            for (Vec3 accepted : acceptedReflectionPoints) {
                if (accepted.distanceToSqr(reflectionPoint) < 0.12 * 0.12) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            acceptedReflectionPoints.add(reflectionPoint);

            Vec3 pathPoint = reflectionPoint.add(facingNormal.scale(0.035));
            // The offset point must actually lie in air. At the intersection of two
            // closed walls an image path can otherwise touch the mathematical edge, with
            // each leg starting in the adjoining solid block and the voxel walker
            // (correctly) ignoring its endpoint block. That is not an open acoustic path.
            if (pointInsideCollision(level, pathPoint)) {
                acceptedReflectionPoints.remove(acceptedReflectionPoints.size() - 1);
                continue;
            }
            acceptedPaths++;
            Transmission sourceLeg = traceTransmission(level, source, pathPoint);
            Transmission listenerLeg = traceTransmission(level, pathPoint, listener);
            float[] sourceBands = sourceLeg.bands();
            float[] listenerBands = listenerLeg.bands();
            // A first-order reflection can remain audible even when either of its legs
            // bends around the same screen that blocks the geometric direct ray. Trace a
            // bounded number of the nearest (therefore strongest) diffraction-reflection
            // paths instead of treating every blocked leg as wall transmission.
            if (remainingDiffractedLegs > 0 && sourceLeg.firstBounds() != null) {
                sourceBands = combineIndependentPaths(
                        sourceBands,
                        traceDiffraction(
                                level, source, pathPoint, sourceLeg.firstBounds(), 12,
                                AcousticMaterialRegistry.tuning()
                        ).bands()
                );
                remainingDiffractedLegs--;
            }
            if (remainingDiffractedLegs > 0 && listenerLeg.firstBounds() != null) {
                listenerBands = combineIndependentPaths(
                        listenerBands,
                        traceDiffraction(
                                level, pathPoint, listener, listenerLeg.firstBounds(), 12,
                                AcousticMaterialRegistry.tuning()
                        ).bands()
                );
                remainingDiffractedLegs--;
            }
            double pathDistance = source.distanceTo(reflectionPoint) + reflectionPoint.distanceTo(listener);
            float distanceGain = (float) (directDistance / Math.max(pathDistance, directDistance));
            float incidence = (float) Math.abs(
                    source.subtract(reflectionPoint).normalize().dot(facingNormal)
            );
            for (int band = 0; band < accumulated.length; band++) {
                float transmission = reflectionMaterial.surfaceTransmission(
                        band,
                        AcousticMaterialRegistry.tuning().metersPerBlock()
                );
                float reflectedPower = Mth.clamp(
                        1.0F - reflectionMaterial.absorption(band) - transmission * transmission,
                        0.0F,
                        1.0F
                );
                float specularPower = reflectedPower * (1.0F - reflectionMaterial.scattering(band));
                float reflection = (float) Math.sqrt(Math.max(0.0F, specularPower));
                float contribution = sourceBands[band]
                        * listenerBands[band]
                        * reflection
                        * distanceGain
                        * airAbsorption(band, AcousticMaterialRegistry.tuning().meters(pathDistance));
                accumulated[band] += contribution * contribution;
            }
            if (acceptedPaths >= pathBudget) {
                break;
            }
        }

        for (int band = 0; band < accumulated.length; band++) {
            accumulated[band] = Mth.clamp(
                    (float) Math.sqrt(accumulated[band])
                            * AcousticMaterialRegistry.tuning().reflectionGainScale(),
                    0.0F,
                    1.0F
            );
        }
        float low = meanBands(accumulated, 0, 4);
        float high = meanBands(accumulated, 4, 8);
        return new ReflectionResult(
                accumulated,
                Mth.clamp(
                        weightedEnergy(accumulated),
                        0.0F,
                        0.90F
                ),
                Mth.clamp(high / Math.max(low, 0.01F), 0.05F, 1.0F)
        );
    }

    private static List<RoomProbe.SurfaceSample> reflectionCandidates(
            Vec3 source,
            Vec3 listener,
            RoomProbe sourceRoomProbe,
            RoomProbe listenerRoomProbe
    ) {
        int sourceCount = sourceRoomProbe == listenerRoomProbe
                ? 0
                : sourceRoomProbe.surfaces().size();
        List<RoomProbe.SurfaceSample> candidates = new ArrayList<>(
                listenerRoomProbe.surfaces().size() + sourceCount
        );
        candidates.addAll(listenerRoomProbe.surfaces());
        if (sourceRoomProbe != listenerRoomProbe) {
            candidates.addAll(sourceRoomProbe.surfaces());
        }
        // Nearest total image path first is a numerical ordering only. Every surface is
        // still eligible; accepted path count is bounded because the OpenAL output has a
        // single early-reflection cluster and cannot render arbitrary path counts.
        candidates.sort(Comparator.comparingDouble(surface ->
                source.distanceTo(surface.location()) + listener.distanceTo(surface.location())
        ));
        return candidates;
    }

    private static float[] combineIndependentPaths(float[] first, float[] second) {
        float[] combined = new float[AcousticBands.COUNT];
        for (int band = 0; band < combined.length; band++) {
            combined[band] = Mth.clamp(
                    (float) Math.sqrt(
                            first[band] * first[band]
                                    + second[band] * second[band]
                    ),
                    0.0F,
                    1.0F
            );
        }
        return combined;
    }

    private static Transmission traceTransmission(BlockGetter level, Vec3 from, Vec3 to) {
        float[] bands = unityBands();
        TransmissionScratch scratch = TRANSMISSION_SCRATCH.get();
        scratch.reset();
        walkBlocks(from, to, (pos, endpoint) -> {
            FluidState fluidState = level.getFluidState(pos);
            if (!fluidState.isEmpty()) {
                AABB fluidBounds = fluidState.getAABB(level, pos);
                SegmentInterval interval = fluidBounds == null ? null : intersectSegment(fluidBounds, from, to);
                if (interval != null && interval.end() - interval.start() > 1.0E-7) {
                    scratch.fluidSegments.add(new FluidSegment(
                            fluidState.getType(),
                            AcousticMaterialRegistry.findFluid(fluidState),
                            interval.start(),
                            interval.end()
                    ));
                }
            }

            if (endpoint) {
                return true;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return true;
            }
            SolidIntersection solid;
            if (isCollisionShapeFullBlock(level, pos, state)) {
                AABB bounds = new AABB(pos);
                SegmentInterval interval = intersectSegment(bounds, from, to);
                solid = interval == null
                        ? new SolidIntersection(0.0, null)
                        : new SolidIntersection(
                                (interval.end() - interval.start()) * from.distanceTo(to), bounds
                        );
            } else {
                VoxelShape shape = state.getCollisionShape(level, pos);
                if (shape.isEmpty() || shape.clip(from, to, pos) == null) {
                    return true;
                }
                solid = intersectShape(shape, pos, from, to);
            }
            if (solid.distance() <= 1.0E-7) {
                return true;
            }
            if (scratch.firstBounds == null) {
                scratch.firstBounds = solid.firstBounds();
            }
            scratch.blockers++;
            AcousticMaterial material = AcousticMaterialRegistry.find(state);
            double pathLengthMeters = AcousticMaterialRegistry.tuning().meters(solid.distance());
            for (int band = 0; band < bands.length; band++) {
                bands[band] *= material.transmissionGain(band, pathLengthMeters);
            }
            for (float band : bands) {
                if (band > 0.001F) {
                    return true;
                }
            }
            return false;
        });
        FluidStats fluidStats = applyFluidSegments(
                bands,
                scratch.fluidSegments,
                from.distanceTo(to)
        );
        return new Transmission(
                bands,
                scratch.firstBounds,
                scratch.blockers,
                fluidStats.distance(),
                fluidStats.scattering()
        );
    }

    private static boolean pointInsideCollision(BlockGetter level, Vec3 point) {
        BlockPos position = BlockPos.containing(point);
        BlockState state = level.getBlockState(position);
        if (state.isAir()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, position);
        if (shape.isEmpty()) {
            return false;
        }
        for (AABB bounds : shape.toAabbs()) {
            if (bounds.move(position).contains(point)) {
                return true;
            }
        }
        return false;
    }

    static SurfaceHit firstSurface(BlockGetter level, Vec3 from, Vec3 to) {
        return firstSurface(level, from, to, new SurfaceHit[1]);
    }

    static SurfaceHit firstSurface(BlockGetter level, Vec3 from, Vec3 to, SurfaceHit[] result) {
        result[0] = null;
        walkBlocks(from, to, (pos, endpoint) -> {
            if (endpoint) {
                return true;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return true;
            }
            if (isCollisionShapeFullBlock(level, pos, state)) {
                AABB bounds = new AABB(pos);
                SurfaceHit hit = intersectFullBlockSurface(
                        bounds,
                        from,
                        to,
                        AcousticMaterialRegistry.find(state)
                );
                if (hit == null) {
                    return true;
                }
                result[0] = hit;
                return false;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                return true;
            }
            BlockHitResult hit = shape.clip(from, to, pos);
            if (hit == null) {
                return true;
            }
            result[0] = new SurfaceHit(
                    hit.getLocation(),
                    hit.getDirection().getUnitVec3(),
                    shape.bounds().move(pos),
                    from.distanceTo(hit.getLocation()),
                    AcousticMaterialRegistry.find(state)
            );
            return false;
        });
        return result[0];
    }

    private static void walkBlocks(Vec3 from, Vec3 to, BlockVisitor visitor) {
        int x = Mth.floor(from.x);
        int y = Mth.floor(from.y);
        int z = Mth.floor(from.z);
        int endX = Mth.floor(to.x);
        int endY = Mth.floor(to.y);
        int endZ = Mth.floor(to.z);
        int startX = x;
        int startY = y;
        int startZ = z;

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        int stepX = Integer.compare(endX, x);
        int stepY = Integer.compare(endY, y);
        int stepZ = Integer.compare(endZ, z);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = initialT(from.x, dx, stepX);
        double tMaxY = initialT(from.y, dy, stepY);
        double tMaxZ = initialT(from.z, dz, stepZ);
        int maxSteps = Math.min(1536, Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z) + 8);
        BlockPos.MutableBlockPos mutablePos = WALK_POSITION.get();

        for (int step = 0; step < maxSteps; step++) {
            boolean endpoint = (x == startX && y == startY && z == startZ) || (x == endX && y == endY && z == endZ);
            if (!visitor.visit(mutablePos.set(x, y, z), endpoint)) {
                return;
            }
            if (x == endX && y == endY && z == endZ) {
                return;
            }

            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (tMaxX <= next + 1.0E-10) {
                x += stepX;
                tMaxX += tDeltaX;
            }
            if (tMaxY <= next + 1.0E-10) {
                y += stepY;
                tMaxY += tDeltaY;
            }
            if (tMaxZ <= next + 1.0E-10) {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
    }

    private static boolean isCollisionShapeFullBlock(
            BlockGetter level,
            BlockPos pos,
            BlockState state
    ) {
        return level instanceof AcousticScene scene
                ? scene.isCollisionShapeFullBlock(pos, state)
                : state.isCollisionShapeFullBlock(level, pos);
    }

    private static double initialT(double coordinate, double delta, int step) {
        if (step > 0) {
            return (Math.floor(coordinate) + 1.0 - coordinate) / delta;
        }
        if (step < 0) {
            return (coordinate - Math.floor(coordinate)) / -delta;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static float weightedEnergy(float[] bands) {
        if (bands.length == 4) {
            return bands[0] * 0.30F + bands[1] * 0.32F + bands[2] * 0.23F + bands[3] * 0.15F;
        }
        return bands[0] * 0.10F + bands[1] * 0.13F
                + bands[2] * 0.15F + bands[3] * 0.16F
                + bands[4] * 0.16F + bands[5] * 0.13F
                + bands[6] * 0.10F + bands[7] * 0.07F;
    }

    private static float pairEnergy(float[] bands, int firstBand) {
        return bands[firstBand] * 0.5F + bands[firstBand + 1] * 0.5F;
    }

    private static float meanBands(float[] bands, int fromInclusive, int toExclusive) {
        float sum = 0.0F;
        for (int band = fromInclusive; band < toExclusive; band++) {
            sum += bands[band];
        }
        return sum / (toExclusive - fromInclusive);
    }

    private static float meanMaterialAbsorption(
            AcousticMaterial material,
            int fromInclusive,
            int toExclusive
    ) {
        float sum = 0.0F;
        for (int band = fromInclusive; band < toExclusive; band++) {
            sum += material.absorption(band);
        }
        return sum / (toExclusive - fromInclusive);
    }

    private static float mixedMediumPair(MediumSample medium, int firstBand) {
        return mix(
                1.0F,
                (medium.profile().gain(firstBand) + medium.profile().gain(firstBand + 1)) * 0.5F,
                medium.weight()
        );
    }

    private static float[] unityBands() {
        float[] bands = new float[AcousticBands.COUNT];
        Arrays.fill(bands, 1.0F);
        return bands;
    }

    private static void applyAirAbsorption(float[] bands, double distance) {
        for (int band = 0; band < bands.length; band++) {
            bands[band] *= airAbsorption(band, distance);
        }
    }

    private static void applyMediumResponse(float[] bands, MediumSample medium) {
        for (int band = 0; band < bands.length; band++) {
            bands[band] *= mix(1.0F, medium.profile().gain(band), medium.weight());
        }
    }

    private static MediumSample sampleMedium(BlockGetter level, Vec3 point) {
        BlockPos pos = new BlockPos(Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z));
        FluidState fluidState = level.getFluidState(pos);
        if (fluidState.isEmpty()) {
            return new MediumSample(MediumProfile.AIR, 0.0F);
        }
        AABB bounds = fluidState.getAABB(level, pos);
        if (bounds == null || !bounds.contains(point)) {
            return new MediumSample(MediumProfile.AIR, 0.0F);
        }
        MediumProfile profile = AcousticMaterialRegistry.findFluid(fluidState).medium();
        float depth = (float) Math.max(0.0, bounds.maxY - point.y);
        return new MediumSample(profile, Mth.clamp(depth / profile.transitionDepth(), 0.0F, 1.0F));
    }

    private static RoomAcoustics applyMediumRoom(RoomAcoustics base, MediumSample medium) {
        MediumProfile profile = medium.profile();
        float weight = medium.weight();
        return new RoomAcoustics(
                mix(base.density(), profile.density(), weight),
                mix(base.diffusion(), profile.diffusion(), weight),
                mix(base.gain(), profile.roomGain(), weight),
                mix(base.gainHighFrequency(), profile.roomGainHighFrequency(), weight),
                mix(base.gainLowFrequency(), profile.roomGainLowFrequency(), weight),
                mix(base.decayTime(), profile.decayTime(), weight),
                mix(base.decayHighFrequencyRatio(), profile.decayHighFrequencyRatio(), weight),
                mix(base.decayLowFrequencyRatio(), profile.decayLowFrequencyRatio(), weight),
                mix(base.reflectionsGain(), profile.reflectionsGain(), weight),
                mix(base.reflectionsDelay(), profile.reflectionsDelay(), weight),
                base.reflectionsPan().scale(1.0F - weight),
                mix(base.lateReverbGain(), profile.lateReverbGain(), weight),
                mix(base.lateReverbDelay(), profile.lateReverbDelay(), weight),
                base.lateReverbPan().scale(1.0F - weight),
                mix(base.modulationTime(), profile.modulationTime(), weight),
                mix(base.modulationDepth(), profile.modulationDepth(), weight),
                mix(base.airAbsorptionGainHighFrequency(), profile.airAbsorptionGainHighFrequency(), weight)
        );
    }

    private static RoomAcoustics applyTuning(RoomAcoustics room, AcousticTuning tuning) {
        return new RoomAcoustics(
                room.density(),
                room.diffusion(),
                Mth.clamp(room.gain() * tuning.roomGainScale(), 0.0F, 1.0F),
                room.gainHighFrequency(),
                room.gainLowFrequency(),
                Mth.clamp(room.decayTime() * tuning.decayTimeScale(), 0.1F, tuning.maxDecayTime()),
                room.decayHighFrequencyRatio(),
                room.decayLowFrequencyRatio(),
                Mth.clamp(room.reflectionsGain() * tuning.reflectionGainScale(), 0.0F, 3.16F),
                room.reflectionsDelay(),
                room.reflectionsPan(),
                Mth.clamp(room.lateReverbGain() * tuning.lateReverbGainScale(), 0.0F, 10.0F),
                room.lateReverbDelay(),
                room.lateReverbPan(),
                room.modulationTime(),
                room.modulationDepth(),
                room.airAbsorptionGainHighFrequency()
        );
    }

    private static float mix(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    static float airAbsorption(int band, double distance) {
        return (float) Math.exp(-AIR_ABSORPTION_PER_METER[band] * Math.max(0.0, distance));
    }

    @FunctionalInterface
    private interface BlockVisitor {
        boolean visit(BlockPos pos, boolean endpoint);
    }

    private static FluidStats applyFluidSegments(float[] bands, List<FluidSegment> segments, double rayDistance) {
        if (segments.isEmpty() || rayDistance <= 1.0E-7) {
            return new FluidStats(0.0F, 0.0F);
        }

        segments.sort(Comparator.comparingDouble(FluidSegment::start));
        List<FluidSegment> merged = new ArrayList<>(segments.size());
        for (FluidSegment segment : segments) {
            if (!merged.isEmpty()) {
                FluidSegment previous = merged.getLast();
                if (previous.type().isSame(segment.type()) && segment.start() <= previous.end() + 1.0E-6) {
                    merged.set(merged.size() - 1, new FluidSegment(
                            previous.type(),
                            previous.material(),
                            previous.start(),
                            Math.max(previous.end(), segment.end())
                    ));
                    continue;
                }
            }
            merged.add(segment);
        }

        float totalDistance = 0.0F;
        float scatteringDistance = 0.0F;
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        for (FluidSegment segment : merged) {
            double normalizedLength = Math.max(0.0, segment.end() - segment.start());
            float distance = (float) (normalizedLength * rayDistance);
            if (distance <= 1.0E-6F) {
                continue;
            }
            totalDistance += distance;
            scatteringDistance += distance * segment.material().scattering();
            int boundaries = (segment.start() > 1.0E-5 ? 1 : 0) + (segment.end() < 1.0 - 1.0E-5 ? 1 : 0);
            for (int band = 0; band < bands.length; band++) {
                bands[band] *= segment.material().transmissionGain(
                        band,
                        tuning.meters(distance)
                );
                if (boundaries > 0) {
                    bands[band] *= (float) Math.pow(segment.material().boundaryTransmission(band), boundaries);
                }
            }
        }
        return new FluidStats(
                totalDistance,
                totalDistance <= 1.0E-6F ? 0.0F : scatteringDistance / totalDistance
        );
    }

    private static SegmentInterval intersectSegment(AABB box, Vec3 from, Vec3 to) {
        double[] interval = {0.0, 1.0};
        if (!clipAxis(from.x, to.x - from.x, box.minX, box.maxX, interval)
                || !clipAxis(from.y, to.y - from.y, box.minY, box.maxY, interval)
                || !clipAxis(from.z, to.z - from.z, box.minZ, box.maxZ, interval)) {
            return null;
        }
        return interval[1] >= interval[0] ? new SegmentInterval(interval[0], interval[1]) : null;
    }

    private static SurfaceHit intersectFullBlockSurface(
            AABB box,
            Vec3 from,
            Vec3 to,
            AcousticMaterial material
    ) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double enter = 0.0;
        double exit = 1.0;
        Vec3 normal = Vec3.ZERO;

        if (Math.abs(dx) < 1.0E-12) {
            if (from.x < box.minX || from.x > box.maxX) return null;
        } else {
            double first = (box.minX - from.x) / dx;
            double second = (box.maxX - from.x) / dx;
            Vec3 entryNormal = NEGATIVE_X_NORMAL;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
                entryNormal = POSITIVE_X_NORMAL;
            }
            if (first > enter) {
                enter = first;
                normal = entryNormal;
            }
            exit = Math.min(exit, second);
            if (exit < enter) return null;
        }

        if (Math.abs(dy) < 1.0E-12) {
            if (from.y < box.minY || from.y > box.maxY) return null;
        } else {
            double first = (box.minY - from.y) / dy;
            double second = (box.maxY - from.y) / dy;
            Vec3 entryNormal = NEGATIVE_Y_NORMAL;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
                entryNormal = POSITIVE_Y_NORMAL;
            }
            if (first > enter) {
                enter = first;
                normal = entryNormal;
            }
            exit = Math.min(exit, second);
            if (exit < enter) return null;
        }

        if (Math.abs(dz) < 1.0E-12) {
            if (from.z < box.minZ || from.z > box.maxZ) return null;
        } else {
            double first = (box.minZ - from.z) / dz;
            double second = (box.maxZ - from.z) / dz;
            Vec3 entryNormal = NEGATIVE_Z_NORMAL;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
                entryNormal = POSITIVE_Z_NORMAL;
            }
            if (first > enter) {
                enter = first;
                normal = entryNormal;
            }
            exit = Math.min(exit, second);
            if (exit < enter) return null;
        }

        if (enter < 0.0 || enter > 1.0 || normal == Vec3.ZERO) {
            return null;
        }
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz) * enter;
        return new SurfaceHit(
                new Vec3(from.x + dx * enter, from.y + dy * enter, from.z + dz * enter),
                normal,
                box,
                distance,
                material
        );
    }

    private static SolidIntersection intersectShape(
            VoxelShape shape,
            BlockPos position,
            Vec3 from,
            Vec3 to
    ) {
        List<ShapeInterval> intervals = new ArrayList<>();
        for (AABB localBox : shape.toAabbs()) {
            AABB worldBox = localBox.move(position);
            SegmentInterval interval = intersectSegment(worldBox, from, to);
            if (interval != null && interval.end() - interval.start() > 1.0E-7) {
                intervals.add(new ShapeInterval(interval.start(), interval.end(), worldBox));
            }
        }
        if (intervals.isEmpty()) {
            return new SolidIntersection(0.0, null);
        }

        intervals.sort(Comparator.comparingDouble(ShapeInterval::start));
        double totalNormalizedLength = 0.0;
        double mergedStart = intervals.getFirst().start();
        double mergedEnd = intervals.getFirst().end();
        AABB firstBounds = intervals.getFirst().bounds();
        for (int index = 1; index < intervals.size(); index++) {
            ShapeInterval interval = intervals.get(index);
            if (interval.start() <= mergedEnd + 1.0E-7) {
                mergedEnd = Math.max(mergedEnd, interval.end());
            } else {
                totalNormalizedLength += mergedEnd - mergedStart;
                mergedStart = interval.start();
                mergedEnd = interval.end();
            }
        }
        totalNormalizedLength += mergedEnd - mergedStart;
        return new SolidIntersection(totalNormalizedLength * from.distanceTo(to), firstBounds);
    }

    private static boolean clipAxis(double origin, double delta, double min, double max, double[] interval) {
        if (Math.abs(delta) < 1.0E-12) {
            return origin >= min && origin <= max;
        }
        double first = (min - origin) / delta;
        double second = (max - origin) / delta;
        if (first > second) {
            double swap = first;
            first = second;
            second = swap;
        }
        interval[0] = Math.max(interval[0], first);
        interval[1] = Math.min(interval[1], second);
        return interval[1] >= interval[0];
    }

    private record RoomRayGrid(int azimuthSamples, int elevationSamples, Vec3[] directions) {
    }

    /**
     * Finite plane support reconstructed from neighboring surface hits. Propagation of
     * an aperture sample is allowed only through the projected extent actually spanned
     * by those surfaces. This prevents a nearby freestanding block from acting like an
     * infinite wall whose silhouette flood-fills the entire room as an opening.
     */
    private record OpeningBoundary(
            double distance,
            Vec3 normal,
            Vec3 point,
            AABB support
    ) {
        private double intersectionDistance(Vec3 listener, Vec3 direction) {
            double denominator = direction.dot(normal);
            if (Math.abs(denominator) < 0.12) {
                return Double.NaN;
            }
            double intersection = point.subtract(listener).dot(normal) / denominator;
            if (intersection <= 0.0) {
                return Double.NaN;
            }
            Vec3 projected = listener.add(direction.scale(intersection));
            double epsilon = 1.0E-5;
            double absoluteX = Math.abs(normal.x);
            double absoluteY = Math.abs(normal.y);
            double absoluteZ = Math.abs(normal.z);
            boolean inside;
            if (absoluteX >= absoluteY && absoluteX >= absoluteZ) {
                inside = projected.y >= support.minY - epsilon
                        && projected.y <= support.maxY + epsilon
                        && projected.z >= support.minZ - epsilon
                        && projected.z <= support.maxZ + epsilon;
            } else if (absoluteY >= absoluteZ) {
                inside = projected.x >= support.minX - epsilon
                        && projected.x <= support.maxX + epsilon
                        && projected.z >= support.minZ - epsilon
                        && projected.z <= support.maxZ + epsilon;
            } else {
                inside = projected.x >= support.minX - epsilon
                        && projected.x <= support.maxX + epsilon
                        && projected.y >= support.minY - epsilon
                        && projected.y <= support.maxY + epsilon;
            }
            return inside ? intersection : Double.NaN;
        }
    }

    private record Transmission(
            float[] bands,
            AABB firstBounds,
            int blockers,
            float fluidDistance,
            float fluidScattering
    ) {
    }

    private record FluidSegment(Fluid type, AcousticMaterial material, double start, double end) {
    }

    private record FluidStats(float distance, float scattering) {
    }

    private record SegmentInterval(double start, double end) {
    }

    private record ShapeInterval(double start, double end, AABB bounds) {
    }

    private record SolidIntersection(double distance, AABB firstBounds) {
    }

    record SurfaceHit(
            Vec3 location,
            Vec3 normal,
            AABB bounds,
            double distance,
            AcousticMaterial material
    ) {
    }

    private record DiffractionPath(
            float[] bands,
            Vec3 point,
            double pathDistance,
            boolean clear
    ) {
    }

    private record DiffractionCandidate(
            float[] bands,
            Vec3 point,
            float score,
            boolean clear,
            Transmission sourceLeg,
            Transmission listenerLeg,
            double pathDistance
    ) {
    }

    private enum PathKind {
        DIRECT_OR_TRANSMITTED,
        DIFFRACTED,
        SPECULAR_REFLECTION
    }

    /**
     * Incoherent, band-limited transport accumulator shared by every propagation mode.
     * The solver adds path power, never room labels or situation-specific gains. This is
     * also why a path can fade continuously into another one at an occlusion boundary.
     */
    private static final class PathEnergyAccumulator {
        private final float[][] powerByKind = new float[PathKind.values().length][AcousticBands.COUNT];

        private void add(PathKind kind, float[] amplitudes, float powerWeight) {
            float[] power = powerByKind[kind.ordinal()];
            float nonNegativeWeight = Math.max(0.0F, powerWeight);
            for (int band = 0; band < AcousticBands.COUNT; band++) {
                float amplitude = Mth.clamp(amplitudes[band], 0.0F, 1.0F);
                power[band] += amplitude * amplitude * nonNegativeWeight;
            }
        }

        private float[] amplitudes() {
            float[] amplitudes = new float[AcousticBands.COUNT];
            for (PathKind kind : PathKind.values()) {
                float[] power = powerByKind[kind.ordinal()];
                for (int band = 0; band < AcousticBands.COUNT; band++) {
                    amplitudes[band] += power[band];
                }
            }
            powerToAmplitude(amplitudes);
            return amplitudes;
        }

        private float[] amplitudes(PathKind kind) {
            float[] amplitudes = powerByKind[kind.ordinal()].clone();
            powerToAmplitude(amplitudes);
            return amplitudes;
        }

        private static void powerToAmplitude(float[] power) {
            for (int band = 0; band < power.length; band++) {
                power[band] = Mth.clamp((float) Math.sqrt(power[band]), 0.0F, 1.0F);
            }
        }
    }

    record ReflectionResult(float[] bands, float gain, float highFrequencyGain) {
        private static ReflectionResult silent() {
            return new ReflectionResult(new float[AcousticBands.COUNT], 0.0F, 1.0F);
        }
    }

    private static final class TransmissionScratch {
        private final List<FluidSegment> fluidSegments = new ArrayList<>();
        private AABB firstBounds;
        private int blockers;

        private void reset() {
            fluidSegments.clear();
            firstBounds = null;
            blockers = 0;
        }
    }

    private record MediumSample(MediumProfile profile, float weight) {
    }

    public enum TraceQuality {
        FULL,
        MEDIUM,
        BASIC
    }

    public record ImmediateContext(
            BlockGetter level,
            Vec3 listener,
            float lowBandGain,
            float midLowBandGain,
            float midHighBandGain,
            float highBandGain,
            float mediumReverbSend,
            float reverbHighFrequencyGain,
            RoomAcoustics room
    ) {
        public AcousticResult resultFor(Vec3 source) {
            double distance = source.distanceTo(listener);
            AcousticTuning tuning = AcousticMaterialRegistry.tuning();
            double distanceMeters = tuning.meters(distance);
            float[] transmission = traceTransmission(level, source, listener).bands().clone();
            for (int band = 0; band < transmission.length; band++) {
                transmission[band] *= airAbsorption(band, distanceMeters);
                transmission[band] *= switch (band / 2) {
                    case 0 -> lowBandGain;
                    case 1 -> midLowBandGain;
                    case 2 -> midHighBandGain;
                    default -> highBandGain;
                };
            }
            float pathCoupling = Mth.clamp(weightedEnergy(transmission), 0.0F, 1.0F);
            float mediumDiffuseCoupling = mediumReverbSend * pathCoupling;
            float initialReverbSend = Mth.clamp(
                    (float) Math.sqrt(
                            pathCoupling * pathCoupling
                                    + mediumDiffuseCoupling * mediumDiffuseCoupling
                    ) * tuning.reverbSendScale(),
                    0.0F,
                    0.95F
            );
            float low = meanBands(transmission, 0, 4);
            float high = meanBands(transmission, 4, 8);
            return new AcousticResult(
                    pathCoupling,
                    Mth.clamp(high / Math.max(low, 0.01F), 0.02F, 1.0F),
                    pairEnergy(transmission, 0),
                    pairEnergy(transmission, 2),
                    pairEnergy(transmission, 4),
                    pairEnergy(transmission, 6),
                    initialReverbSend,
                    Math.min(
                            Mth.clamp(high / Math.max(low, 0.01F), 0.05F, 1.0F),
                            reverbHighFrequencyGain
                    ),
                    0.0F,
                    distance,
                    source,
                    room,
                    RoomImpulseResponse.SILENT
            );
        }
    }
}
