package org.macaroon.acousticsystem.client.simulation;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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
    private static final int[][] AIR_GRAPH_NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };
    private static final double MAX_SHARED_SURFACE_DIRECTION_DOT = 0.94;
    private static final float[] AIR_ABSORPTION_PER_METER = {
            0.000005F, 0.000010F, 0.000025F, 0.000070F,
            0.00018F, 0.00060F, 0.00220F, 0.00800F
    };
    private static final Map<Integer, RoomRayGrid> ROOM_RAY_GRIDS = new ConcurrentHashMap<>();
    private static final Map<AirPathCacheKey, PropagationGraphPath> AIR_PATH_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<StructuralPathCacheKey, StructuralPath> STRUCTURAL_PATH_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<TransmissionCacheKey, Transmission> TRANSMISSION_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<TraceCacheKey, AcousticResult> TRACE_CACHE =
            new ConcurrentHashMap<>();
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
                result.earlyReflection(),
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
        long sceneRevision = level instanceof AcousticScene scene
                ? scene.revision()
                : 0L;
        TraceCacheKey traceCacheKey = new TraceCacheKey(
                level,
                sceneRevision,
                AcousticMaterialRegistry.revision(),
                graphPointKey(source),
                graphPointKey(listener),
                sourceRoomProbe,
                listenerRoomProbe,
                quality
        );
        AcousticResult cachedTrace = TRACE_CACHE.get(traceCacheKey);
        if (cachedTrace != null) {
            return cachedTrace;
        }
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
        FiniteWavefront directWavefront = traceFiniteWavefront(
                level, source, listener, direction, right, up, 8
        );
        Transmission center = directWavefront.center();
        // The offset rays sample the finite first Fresnel zone around the geometric ray.
        // Combine their power continuously on both sides of an edge. Switching from the
        // centre ray to an arithmetic-amplitude average when it first became blocked made
        // an otherwise tiny occluder produce an audible step in level.
        float diffractionContribution = 0.0F;
        DiffractionPath diffractionPath = null;
        float[] diffractionSpectrum = new float[AcousticBands.COUNT];
        if (center.firstBounds() != null) {
            diffractionPath = traceDiffraction(
                    level, source, listener, center.firstBounds(),
                    DIFFRACTION_CANDIDATE_BUDGET, tuning,
                    sourceRoomProbe.openings(), listenerRoomProbe.openings(),
                    true,
                    sourceRoomProbe.acoustics().gain() > 0.0F
                            && listenerRoomProbe.acoustics().gain() > 0.0F
            );
            diffractionSpectrum = diffractionPath.bands().clone();
            // The offset bundle is a finite Fresnel-zone quadrature, while the edge
            // solution reconstructs energy missing from that same incident wavefront.
            // Adding both at full power counts the partially open aperture twice. Give
            // diffraction only the per-band power not already carried by the sampled
            // direct/transmitted bundle. A path therefore grows continuously as the
            // Fresnel zone enters shadow and never creates gain at a grazing edge.
            for (int band = 0; band < diffractionSpectrum.length; band++) {
                float sampledPower = directWavefront.meanPower()[band];
                diffractionSpectrum[band] *= (float) Math.sqrt(
                        Mth.clamp(1.0F - sampledPower, 0.0F, 1.0F)
                );
            }
        }
        StructuralPath structuralPath = traceStructuralPath(
                level, source, listener, tuning
        );
        float[] directSpectrum = directWavefront.bands().clone();
        applyAirAbsorption(directSpectrum, tuning.meters(directDistance));
        applyMediumResponse(directSpectrum, listenerMedium);
        if (diffractionPath != null) {
            applyAirAbsorption(
                    diffractionSpectrum,
                    tuning.meters(diffractionPath.pathDistance())
            );
        }
        applyMediumResponse(diffractionSpectrum, listenerMedium);
        diffractionContribution = weightedEnergy(diffractionSpectrum);
        float[] structuralSpectrum = structuralPath == null
                ? new float[AcousticBands.COUNT]
                : structuralPath.bands().clone();
        // Structural propagation already includes damping through the connected solid
        // and air absorption after re-radiation. Reusing the unrelated direct distance
        // here double-counted one path using information from another path.
        applyMediumResponse(structuralSpectrum, listenerMedium);

        ArrivalFieldAccumulator resolvedField = new ArrivalFieldAccumulator();
        resolvedField.add(
                PathKind.DIRECT_OR_TRANSMITTED,
                directSpectrum,
                1.0F,
                source.subtract(listener),
                directDistance
        );
        if (diffractionPath != null) {
            resolvedField.add(
                    PathKind.DIFFRACTED,
                    diffractionSpectrum,
                    1.0F,
                    diffractionPath.point().subtract(listener),
                    diffractionPath.pathDistance()
            );
        }
        if (structuralPath != null) {
            resolvedField.add(
                    PathKind.STRUCTURAL,
                    structuralSpectrum,
                    1.0F,
                    structuralPath.arrivalPoint().subtract(listener),
                    structuralPath.pathDistance()
                            + structuralPath.arrivalPoint().distanceTo(listener)
            );
        }
        float[] geometricSpectrum = resolvedField.amplitudes();

        ReflectionResult reflections = estimateEarlyReflections(
                level,
                source,
                listener,
                sourceRoomProbe,
                MAX_EARLY_REFLECTION_PATHS_PER_SOUND,
                diffractionPath == null || !diffractionPath.multipleBends()
        );
        // A resolved image reflection is already rendered by the source-specific early
        // reflection channel. Feeding the same path into the shared late-field send
        // counts it twice and lets one image-path topology change spike total loudness.
        // Late-field coupling follows the continuously transported geometric field;
        // its decay and density still come from the listener room response below.
        float[] fieldSpectrum = geometricSpectrum;
        // The dry positional voice contains only the direct/transmitted and diffracted
        // first-arrival field. A specular image path is audible, but routing its power
        // through the dry voice makes a blocked source sound clean and centered behind
        // the wall. It is sent to the source-specific EAX early-reflection cluster below
        // with its calculated delay and arrival direction instead.
        float arrivalEnergy = Mth.clamp(weightedEnergy(fieldSpectrum), 0.0F, 1.0F);
        float lowEnergy = geometricSpectrum[0] * 0.24F + geometricSpectrum[1] * 0.26F
                + geometricSpectrum[2] * 0.27F + geometricSpectrum[3] * 0.23F;
        float highEnergy = geometricSpectrum[4] * 0.28F + geometricSpectrum[5] * 0.27F
                + geometricSpectrum[6] * 0.25F + geometricSpectrum[7] * 0.20F;
        // OpenAL's positional direct path exposes one broadband gain and one HF ratio.
        // Anchor that approximation to the physically traced low-band amplitude, then
        // express the remaining spectral tilt through the direct low-pass filter. This
        // keeps every dry component on the source's HRTF-aware path; no dry band is sent
        // through a positionless auxiliary effect slot.
        float directGain = Mth.clamp(lowEnergy, 0.0F, 1.0F);
        float highFrequencyGain = Mth.clamp(highEnergy / Math.max(lowEnergy, 0.01F), 0.02F, 1.0F);
        Vec3 apparentDirection = resolvedField.apparentDirection(
                source.subtract(listener)
        );
        Vec3 apparentPosition = listener.add(
                apparentDirection.scale(Math.max(1.0, directDistance))
        );
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
        // The EAX network is a listener-owned late field shared by every live source.
        // Source-specific image paths contribute to this voice's send above, but must
        // never overwrite the shared room parameters or pan all other sources toward
        // whichever reflection happened to finish last.
        RoomAcoustics reverbRoom = listenerRoom;
        RoomImpulseResponse reverbImpulse = listenerRoomProbe.impulseResponse();
        float fieldLow = meanBands(fieldSpectrum, 0, 4);
        float fieldHigh = meanBands(fieldSpectrum, 4, 8);
        float fieldHighFrequencyGain = Mth.clamp(
                fieldHigh / Math.max(fieldLow, 0.01F),
                0.05F,
                1.0F
        );
        double propagationDistance = resolvedField.propagationDistance(directDistance);
        boolean sameEnclosedDiffuseField = sourceRoomProbe.openings().isEmpty()
                && listenerRoomProbe.openings().isEmpty()
                && (center.firstBounds() == null
                || diffractionPath != null && diffractionPath.clear());
        AcousticResult result = new AcousticResult(
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
                new EarlyReflection(
                        reflections.gain(),
                        reflections.highFrequencyGain(),
                        reflections.delay(),
                        reflections.pan()
                ),
                diffractionContribution,
                propagationDistance,
                apparentPosition,
                reverbRoom,
                reverbImpulse,
                // Two probes inside one sealed, air-connected volume measure the same
                // diffuse field even though their finite ray samples produce different
                // numeric RT60/gain estimates. A separate source-room send in that case
                // double-counts the room and, on a two-send device, can replace the
                // listener field with a sealed remote field whose portal transmission
                // is correctly zero. Null means this source feeds the listener-owned
                // field directly; disconnected sealed volumes retain their own probe.
                sameEnclosedDiffuseField ? null : sourceRoomProbe,
                source
        );
        if (TRACE_CACHE.size() >= 512) {
            TRACE_CACHE.clear();
        }
        TRACE_CACHE.put(traceCacheKey, result);
        return result;
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
            double localMedian = neighborMedian(distances, grid, ray, neighborBuffer);
            if (hits[ray] == null && localMedian >= probeDistance * 0.75) {
                // A null ray surrounded by other null rays is genuine escape to the
                // control-volume boundary. A null ray surrounded by nearby coherent
                // surfaces instead passed through an aperture in that surface; recover
                // the aperture plane so it can become a graph portal at the doorway,
                // rather than thirty blocks out in open space.
                continue;
            }
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
                    if (!Double.isFinite(boundary)
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
                Vec3.ZERO,
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
                level, source, listener, obstacle, candidateBudget, tuning,
                List.of(), List.of(), false, false
        );
    }

    /**
     * Builds an on-demand hierarchy for one blocked source/listener pair. The immutable
     * {@link AcousticScene} is the chunk graph. Room openings become portal nodes,
     * mutually unobstructed segments become visibility edges, and newly encountered
     * silhouettes contribute diffraction-edge nodes. A* expands this graph by physical
     * path length; the frequency-dependent solver is applied only after complete paths
     * have been found, so search order cannot substitute a situation-specific gain.
     */
    private static DiffractionPath traceDiffraction(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            AABB obstacle,
            int candidateBudget,
            AcousticTuning tuning,
            List<RoomProbe.OpeningSample> sourceOpenings,
            List<RoomProbe.OpeningSample> listenerOpenings,
            boolean allowAirCellGraph,
            boolean preferAirCellGraph
    ) {
        double directDistance = source.distanceTo(listener);
        if (directDistance < 1.0E-6 || candidateBudget <= 0) {
            return new DiffractionPath(
                    new float[AcousticBands.COUNT], source, directDistance, false, false
            );
        }

        boolean hasPortalEvidence = !sourceOpenings.isEmpty()
                || !listenerOpenings.isEmpty();
        // Traverse the hierarchy from its sparse physical representation downward.
        // A block-centred sound is still a boundary emitter: endpoint blocks are already
        // excluded by transmission and visibility segment tests. Starting a full voxel
        // search solely because the coordinate lies in its emitting block flooded the
        // outdoor half-space before an otherwise valid portal/edge path was considered.
        // The air graph remains the complete fallback whenever the sparse graph cannot
        // connect the listener, and remains the primary representation when neither
        // endpoint probe exposes useful portal evidence.
        PropagationGraphPath airPath = allowAirCellGraph
                && preferAirCellGraph && !hasPortalEvidence
                ? searchAirCellGraph(level, source, listener, candidateBudget)
                : null;
        List<PropagationGraphPath> paths = airPath != null
                ? List.of()
                : searchPropagationGraph(
                        level,
                        source,
                        listener,
                        obstacle,
                        sourceOpenings,
                        listenerOpenings,
                        candidateBudget,
                        tuning
                );
        boolean edgeGraphReachedListener = paths.stream().anyMatch(
                PropagationGraphPath::clear
        );
        if (allowAirCellGraph && airPath == null && (!edgeGraphReachedListener
                || hasPortalEvidence)) {
            airPath = searchAirCellGraph(level, source, listener, candidateBudget);
        }
        if (paths.isEmpty() && airPath == null) {
            return new DiffractionPath(
                    new float[AcousticBands.COUNT], source, directDistance, false, false
            );
        }

        List<EvaluatedGraphPath> evaluated = new ArrayList<>(paths.size());
        boolean hasClearPath = airPath != null || edgeGraphReachedListener;
        for (PropagationGraphPath path : paths) {
            if (hasClearPath && !path.clear()) {
                continue;
            }
            EvaluatedGraphPath result = evaluatePropagationPath(
                    level, source, listener, path, tuning
            );
            if (result.score() > 0.0F) {
                evaluated.add(result);
            }
        }
        EvaluatedGraphPath evaluatedAirPath = airPath == null
                ? null
                : evaluatePropagationPath(level, source, listener, airPath, tuning);
        if (evaluatedAirPath != null && evaluatedAirPath.score() <= 0.0F) {
            evaluatedAirPath = null;
        }
        boolean multipleDiffractionBackbone = evaluatedAirPath != null
                && airPath.points().size() > 3;
        if (multipleDiffractionBackbone) {
            // Several consecutive bends are one connected waveguide path, not a set of
            // independent edge rays. The visibility graph may enumerate a different
            // count of correlated corner samples after a centimetre-scale move, which
            // would create a sqrt(N) gain jump. Use the continuously connected air path
            // as the sole energy and direction backbone for higher-order diffraction.
            evaluated.clear();
            evaluated.add(evaluatedAirPath);
            evaluatedAirPath = null;
        }
        if (evaluated.isEmpty() && evaluatedAirPath == null) {
            return new DiffractionPath(
                    new float[AcousticBands.COUNT], source, directDistance, false, false
            );
        }
        if (evaluated.isEmpty()) {
            evaluated.add(evaluatedAirPath);
            evaluatedAirPath = null;
        }
        evaluated.sort(Comparator.comparingDouble(EvaluatedGraphPath::score).reversed());
        evaluated = mergeCorrelatedGraphPaths(evaluated, listener, tuning);

        float[] power = new float[AcousticBands.COUNT];
        Vec3 arrivalDirectionSum = Vec3.ZERO;
        double arrivalDirectionWeight = 0.0;
        double bestDistance = evaluated.getFirst().pathDistance();
        // The air-cell solution is a topology-stable estimate of the same first-arrival
        // field, not another independent wave. Keep it as an energy floor rather than
        // power-summing it with edge paths. Its single A* arrival is deliberately not
        // added to the direction moment: with two equal exits, that route represents
        // only one arbitrary member of a physically symmetric arrival field.
        if (evaluatedAirPath != null) {
            bestDistance = Math.min(bestDistance, evaluatedAirPath.pathDistance());
        }
        int selected = 0;
        for (EvaluatedGraphPath path : evaluated) {
            for (int band = 0; band < power.length; band++) {
                power[band] += path.bands()[band] * path.bands()[band];
            }
            // Localization must resolve from the exact same finite set of paths whose
            // energy is rendered below. Previously every rejected low-energy graph
            // candidate contributed to this moment while only the strongest paths
            // contributed sound power; many weak edges on one side could therefore
            // flip the apparent direction without carrying audible energy.
            Vec3 arrival = path.arrivalPoint().subtract(listener);
            double directionWeight = path.score() * path.score();
            if (directionWeight > 1.0E-10 && arrival.lengthSqr() > 1.0E-10) {
                arrivalDirectionSum = arrivalDirectionSum.add(
                        arrival.normalize().scale(directionWeight)
                );
                arrivalDirectionWeight += directionWeight;
            }
            selected++;
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
            if (evaluatedAirPath != null) {
                combined[band] = Math.max(
                        combined[band],
                        Mth.clamp(
                                evaluatedAirPath.bands()[band]
                                        * tuning.diffractionGainScale(),
                                0.0F,
                                1.0F
                        )
                );
            }
        }
        // OpenAL exposes one position for a source even when the wave reaches the
        // listener through several doors. Represent that directional distribution by
        // its first power-weighted moment instead of selecting the first A* route.
        // The moment magnitude is directional coherence: equal opposing openings
        // cancel to zero, while one dominant opening approaches one. Blending by that
        // coherence keeps symmetric exits centred on the real source and moves the
        // image continuously toward an increasingly dominant exit.
        Vec3 sourceDirection = source.subtract(listener).normalize();
        Vec3 apparentDirection = sourceDirection;
        if (arrivalDirectionWeight > 1.0E-10) {
            Vec3 meanArrivalDirection = arrivalDirectionSum.scale(
                    1.0 / arrivalDirectionWeight
            );
            double directionalCoherence = Math.min(
                    1.0,
                    Math.sqrt(meanArrivalDirection.lengthSqr())
            );
            if (directionalCoherence > 1.0E-6) {
                Vec3 dominantDirection = meanArrivalDirection.normalize();
                Vec3 blendedDirection = sourceDirection.scale(1.0 - directionalCoherence)
                        .add(dominantDirection.scale(directionalCoherence));
                if (blendedDirection.lengthSqr() > 1.0E-10) {
                    apparentDirection = blendedDirection.normalize();
                }
            }
        }
        Vec3 arrivalPoint = listener.add(
                apparentDirection.scale(Math.max(1.0, directDistance))
        );
        return new DiffractionPath(
                combined, arrivalPoint, bestDistance, hasClearPath,
                multipleDiffractionBackbone
        );
    }

    private static List<EvaluatedGraphPath> mergeCorrelatedGraphPaths(
            List<EvaluatedGraphPath> paths,
            Vec3 listener,
            AcousticTuning tuning
    ) {
        if (paths.size() < 2) {
            return paths;
        }
        double pathCoherence = tuning.blocks(
                DiffractionPhysics.SPEED_OF_SOUND_METERS_PER_SECOND / (2.0 * 125.0)
        );
        double angularCoherence = Math.cos(Math.toRadians(20.0));
        List<EvaluatedGraphPath> lobes = new ArrayList<>(paths.size());
        for (EvaluatedGraphPath candidate : paths) {
            Vec3 candidateDirection = candidate.arrivalPoint().subtract(listener);
            if (candidateDirection.lengthSqr() <= 1.0E-10) {
                lobes.add(candidate);
                continue;
            }
            candidateDirection = candidateDirection.normalize();
            int correlated = -1;
            for (int index = 0; index < lobes.size(); index++) {
                EvaluatedGraphPath lobe = lobes.get(index);
                Vec3 lobeDirection = lobe.arrivalPoint().subtract(listener);
                if (lobeDirection.lengthSqr() <= 1.0E-10) {
                    continue;
                }
                if (candidateDirection.dot(lobeDirection.normalize()) >= angularCoherence
                        && Math.abs(candidate.pathDistance() - lobe.pathDistance())
                        <= pathCoherence) {
                    correlated = index;
                    break;
                }
            }
            if (correlated < 0) {
                lobes.add(candidate);
                continue;
            }
            EvaluatedGraphPath lobe = lobes.get(correlated);
            float[] envelope = new float[AcousticBands.COUNT];
            for (int band = 0; band < envelope.length; band++) {
                // These samples lie inside one coherent arrival lobe. They estimate the
                // same wavefront, so summing their power would make gain depend on how
                // many graph edges happened to be enumerated during this frame.
                envelope[band] = Math.max(lobe.bands()[band], candidate.bands()[band]);
            }
            EvaluatedGraphPath representative = candidate.score() > lobe.score()
                    ? candidate
                    : lobe;
            lobes.set(correlated, new EvaluatedGraphPath(
                    envelope,
                    weightedEnergy(envelope),
                    Math.min(lobe.pathDistance(), candidate.pathDistance()),
                    representative.arrivalPoint(),
                    lobe.clear() || candidate.clear()
            ));
        }
        lobes.sort(Comparator.comparingDouble(EvaluatedGraphPath::score).reversed());
        return lobes;
    }

    private static List<PropagationGraphPath> searchPropagationGraph(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            AABB firstObstacle,
            List<RoomProbe.OpeningSample> sourceOpenings,
            List<RoomProbe.OpeningSample> listenerOpenings,
            int candidateBudget,
            AcousticTuning tuning
    ) {
        List<PropagationGraphNode> portals = new ArrayList<>(
                sourceOpenings.size() + listenerOpenings.size()
        );
        appendPortalNodes(
                level, portals, sourceOpenings, source, listener,
                tuning.roomProbeDistance()
        );
        appendPortalNodes(
                level, portals, listenerOpenings, listener, source,
                tuning.roomProbeDistance()
        );

        PriorityQueue<PropagationGraphState> frontier = new PriorityQueue<>(
                Comparator.comparingDouble(PropagationGraphState::priority)
        );
        Map<GraphPointKey, Double> bestDistanceByNode = new HashMap<>();
        PropagationGraphNode start = new PropagationGraphNode(source, 1.0F, GraphNodeKind.SOURCE);
        frontier.add(new PropagationGraphState(
                start, null, 0.0, source.distanceTo(listener), 1.0F
        ));
        bestDistanceByNode.put(graphPointKey(source), 0.0);

        List<PropagationGraphPath> clearPaths = new ArrayList<>();
        int expanded = 0;
        int maximumExpansions = Math.max(16, candidateBudget * 5);
        int maximumCompletePaths = Math.max(4, tuning.diffractionMaxPaths() * 2);
        while (!frontier.isEmpty() && expanded < maximumExpansions) {
            PropagationGraphState state = frontier.poll();
            GraphPointKey stateKey = graphPointKey(state.node().point());
            if (state.distance() > bestDistanceByNode.getOrDefault(
                    stateKey, Double.POSITIVE_INFINITY
            ) + 1.0E-7) {
                continue;
            }
            expanded++;

            AABB listenerObstacle = firstCollisionBounds(
                    level, state.node().point(), listener
            );
            if (listenerObstacle == null) {
                clearPaths.add(reconstructGraphPath(state, listener, true));
                // A* pops states by travelled distance plus the remaining straight-line
                // lower bound. Once twice the renderer's independent path capacity has
                // reached the listener, later states cannot displace those shortest
                // arrivals and only repeat the same aperture field at extra cost.
                if (clearPaths.size() >= maximumCompletePaths) {
                    break;
                }
                continue;
            }
            if (state.node().kind() == GraphNodeKind.LOCAL_EDGE) {
                continue;
            }

            // Portal graph: the room probes supply measured aperture planes. Visibility
            // edges are accepted only when the complete segment is unobstructed.
            for (PropagationGraphNode portal : portals) {
                enqueueVisibleGraphNode(
                        level, listener, state, portal, frontier, bestDistanceByNode
                );
            }

            AABB obstacle = listenerObstacle;
            if (state.previous() == null && firstObstacle != null) {
                obstacle = firstObstacle;
            }
            if (obstacle == null) {
                continue;
            }
            // Edge graph: only actual source-visible silhouette points are inserted.
            // Another blocked listener leg discovers the next edge on a later A* step,
            // so staggered corridors are not limited to a fixed number of turns.
            List<PropagationGraphNode> edgeNodes = sampleVisibleDiffractionEdges(
                    level,
                    state.node().point(),
                    listener,
                    obstacle,
                    candidateBudget,
                    tuning,
                    state.previous() == null
            );
            for (PropagationGraphNode edgeNode : edgeNodes) {
                enqueueVisibleGraphNode(
                        level,
                        listener,
                        state,
                        edgeNode,
                        frontier,
                        bestDistanceByNode
                );
            }
        }
        return clearPaths;
    }

    /**
     * Completes the hierarchy for passages whose sequence of finite openings is too
     * complex to recover from endpoint silhouettes alone. Full air voxels form the
     * lowest-level navigation graph; A* finds a connected route and visibility string
     * pulling removes the voxel staircase before the acoustic solver sees it. This is a
     * connectivity fallback, not an alternate gain model: the resulting segments still
     * pass through the same material, fluid, distance and diffraction evaluation.
     */
    private static PropagationGraphPath searchAirCellGraph(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            int candidateBudget
    ) {
        AirPathCacheKey cacheKey = airPathCacheKey(level, source, listener);
        PropagationGraphPath cached = AIR_PATH_CACHE.get(cacheKey);
        if (cached != null) {
            PropagationGraphPath adapted = adaptCachedAirPath(
                    level, cached, source, listener
            );
            if (adapted != null) {
                return adapted;
            }
            AIR_PATH_CACHE.remove(cacheKey, cached);
        }
        PropagationGraphPath neighboring = adaptNeighboringCachedAirPath(
                level, cacheKey, source, listener
        );
        if (neighboring != null) {
            AIR_PATH_CACHE.put(cacheKey, neighboring);
            return neighboring;
        }
        List<AirCellKey> startKeys = airEndpointCells(level, source);
        List<AirCellKey> goalKeys = airEndpointCells(level, listener);
        if (startKeys.isEmpty() || goalKeys.isEmpty()) {
            return null;
        }

        PriorityQueue<AirGraphState> forwardFrontier = new PriorityQueue<>(
                Comparator.comparingDouble(AirGraphState::priority)
        );
        PriorityQueue<AirGraphState> backwardFrontier = new PriorityQueue<>(
                Comparator.comparingDouble(AirGraphState::priority)
        );
        Long2DoubleOpenHashMap forwardDistance = new Long2DoubleOpenHashMap(
                Math.max(1024, candidateBudget * 128)
        );
        Long2DoubleOpenHashMap backwardDistance = new Long2DoubleOpenHashMap(
                Math.max(1024, candidateBudget * 128)
        );
        forwardDistance.defaultReturnValue(Double.POSITIVE_INFINITY);
        backwardDistance.defaultReturnValue(Double.POSITIVE_INFINITY);
        Long2ObjectOpenHashMap<AirGraphState> forwardStates =
                new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<AirGraphState> backwardStates =
                new Long2ObjectOpenHashMap<>();
        for (AirCellKey startKey : startKeys) {
            Vec3 startCenter = airCellCenter(startKey);
            AirGraphState start = new AirGraphState(
                    startKey,
                    null,
                    source.distanceTo(startCenter),
                    source.distanceTo(startCenter) + startCenter.distanceTo(listener)
            );
            long packedStart = packAirCell(startKey);
            if (start.distance() < forwardDistance.get(packedStart)) {
                forwardFrontier.add(start);
                forwardDistance.put(packedStart, start.distance());
                forwardStates.put(packedStart, start);
            }
        }
        for (AirCellKey goalKey : goalKeys) {
            Vec3 goalCenter = airCellCenter(goalKey);
            AirGraphState end = new AirGraphState(
                    goalKey,
                    null,
                    listener.distanceTo(goalCenter),
                    listener.distanceTo(goalCenter) + goalCenter.distanceTo(source)
            );
            long packedGoal = packAirCell(goalKey);
            if (end.distance() < backwardDistance.get(packedGoal)) {
                backwardFrontier.add(end);
                backwardDistance.put(packedGoal, end.distance());
                backwardStates.put(packedGoal, end);
            }
        }

        int expanded = 0;
        int maximumExpansions = Mth.clamp(
                (int) Math.ceil(source.distanceTo(listener)) * candidateBudget * 32,
                8192,
                65536
        );
        double bestMeetingDistance = Double.POSITIVE_INFINITY;
        AirGraphState forwardMeeting = null;
        AirGraphState backwardMeeting = null;
        boolean expandForward = true;
        while (!forwardFrontier.isEmpty()
                && !backwardFrontier.isEmpty()
                && expanded < maximumExpansions) {
            if (bestMeetingDistance < Double.POSITIVE_INFINITY
                    && forwardFrontier.peek().priority() >= bestMeetingDistance
                    && backwardFrontier.peek().priority() >= bestMeetingDistance) {
                break;
            }
            // Alternate the two admissible A* frontiers. Picking only the globally
            // smaller f-score lets a listener standing in open terrain fan out over
            // thousands of equally good exterior cells while the compact indoor side
            // never reaches its doorway. Balanced bidirectional expansion preserves
            // the same edge costs and shortest-path criterion without increasing the
            // search budget or biasing the physical route.
            boolean forward = expandForward;
            expandForward = !expandForward;
            PriorityQueue<AirGraphState> frontier = forward
                    ? forwardFrontier
                    : backwardFrontier;
            Long2DoubleOpenHashMap ownDistance = forward
                    ? forwardDistance
                    : backwardDistance;
            Long2DoubleOpenHashMap otherDistance = forward
                    ? backwardDistance
                    : forwardDistance;
            Long2ObjectOpenHashMap<AirGraphState> ownStates = forward
                    ? forwardStates
                    : backwardStates;
            Long2ObjectOpenHashMap<AirGraphState> otherStates = forward
                    ? backwardStates
                    : forwardStates;
            Vec3 oppositeCenter = forward ? listener : source;
            AirGraphState state = frontier.poll();
            long packedState = packAirCell(state.key());
            if (state.distance() > ownDistance.get(packedState) + 1.0E-7) {
                continue;
            }
            AirGraphState oppositeState = otherStates.get(packedState);
            if (oppositeState != null) {
                double meetingDistance = state.distance() + oppositeState.distance();
                if (meetingDistance < bestMeetingDistance) {
                    bestMeetingDistance = meetingDistance;
                    forwardMeeting = forward ? state : oppositeState;
                    backwardMeeting = forward ? oppositeState : state;
                }
            }
            expanded++;
            Vec3 from = airCellCenter(state.key());
            BlockState fromState = level.getBlockState(new BlockPos(
                    state.key().x(), state.key().y(), state.key().z()
            ));
            for (int[] offset : AIR_GRAPH_NEIGHBORS) {
                AirCellKey nextKey = new AirCellKey(
                        state.key().x() + offset[0],
                        state.key().y() + offset[1],
                        state.key().z() + offset[2]
                );
                Vec3 next = airCellCenter(nextKey);
                BlockState nextState = level.getBlockState(new BlockPos(
                        nextKey.x(), nextKey.y(), nextKey.z()
                ));
                // Two face-neighboring air voxels have an unobstructed segment by
                // construction. Only partial collision cells need the more expensive
                // shape query; this is the common-case hot path in long caves.
                if (!fromState.isAir() || !nextState.isAir()) {
                    if (pointInsideCollision(level, next)
                            || firstCollisionBounds(level, from, next) != null) {
                        continue;
                    }
                }
                double distance = state.distance() + 1.0;
                long packedNext = packAirCell(nextKey);
                if (distance + 1.0E-7 >= ownDistance.get(packedNext)) {
                    continue;
                }
                AirGraphState nextStateNode = new AirGraphState(
                        nextKey,
                        state,
                        distance,
                        distance + next.distanceTo(oppositeCenter)
                );
                ownDistance.put(packedNext, distance);
                ownStates.put(packedNext, nextStateNode);
                frontier.add(nextStateNode);
                double oppositeDistance = otherDistance.get(packedNext);
                if (oppositeDistance < Double.POSITIVE_INFINITY) {
                    double meetingDistance = distance + oppositeDistance;
                    if (meetingDistance < bestMeetingDistance) {
                        AirGraphState otherState = otherStates.get(packedNext);
                        bestMeetingDistance = meetingDistance;
                        forwardMeeting = forward ? nextStateNode : otherState;
                        backwardMeeting = forward ? otherState : nextStateNode;
                    }
                }
            }
        }
        if (forwardMeeting == null || backwardMeeting == null) {
            return null;
        }
        ArrayDeque<Vec3> reversed = new ArrayDeque<>();
        for (AirGraphState state = forwardMeeting;
             state != null;
             state = state.previous()) {
            reversed.addFirst(airCellCenter(state.key()));
        }
        List<Vec3> raw = new ArrayList<>(reversed.size() + 2);
        raw.add(source);
        raw.addAll(reversed);
        for (AirGraphState state = backwardMeeting.previous();
             state != null;
             state = state.previous()) {
            raw.add(airCellCenter(state.key()));
        }
        raw.add(listener);
        int firstArrivalSample = Math.max(1, raw.size() - 14);
        List<Vec3> arrivalSamples = new ArrayList<>(raw.size() - firstArrivalSample);
        for (int index = firstArrivalSample; index < raw.size() - 1; index++) {
            arrivalSamples.add(raw.get(index));
        }
        List<Vec3> pulled = new ArrayList<>();
        pulled.add(source);
        int anchor = 0;
        while (anchor < raw.size() - 1) {
            int visible = raw.size() - 1;
            while (visible > anchor + 1
                    && firstCollisionBounds(level, raw.get(anchor), raw.get(visible)) != null) {
                visible--;
            }
            if (visible <= anchor) {
                return null;
            }
            pulled.add(raw.get(visible));
            anchor = visible;
        }
        Vec3 arrivalPoint = pulled.size() > 2
                ? pulled.get(pulled.size() - 2)
                : source;
        arrivalPoint = arrivalFieldCentroid(
                level, arrivalSamples, listener, arrivalPoint
        );
        PropagationGraphPath result = new PropagationGraphPath(
                List.copyOf(pulled),
                1.0F,
                true,
                arrivalPoint,
                List.copyOf(arrivalSamples)
        );
        if (AIR_PATH_CACHE.size() >= 256) {
            AIR_PATH_CACHE.clear();
        }
        AIR_PATH_CACHE.put(cacheKey, result);
        return result;
    }

    private static PropagationGraphPath adaptCachedAirPath(
            BlockGetter level,
            PropagationGraphPath cached,
            Vec3 source,
            Vec3 listener
    ) {
        List<Vec3> cachedPoints = cached.points();
        if (cachedPoints.size() < 2) {
            return null;
        }
        Vec3 firstInterior = cachedPoints.size() > 2
                ? cachedPoints.get(1)
                : listener;
        Vec3 lastInterior = cachedPoints.size() > 2
                ? cachedPoints.get(cachedPoints.size() - 2)
                : source;
        List<Vec3> adapted = new ArrayList<>(cachedPoints);
        if (airEndpointConnectorClear(level, source, firstInterior)) {
            adapted.set(0, source);
        } else if (firstCollisionBounds(level, source, cachedPoints.getFirst()) == null) {
            adapted.addFirst(source);
        } else {
            return null;
        }
        List<Vec3> arrivalSamples = new ArrayList<>(cached.arrivalSamples());
        if (airEndpointConnectorClear(level, listener, lastInterior)) {
            adapted.set(adapted.size() - 1, listener);
        } else if (firstCollisionBounds(level, cachedPoints.getLast(), listener) == null) {
            // Incremental graph repair: an adjacent listener cell can remain connected
            // through the previous endpoint even when the newly drawn shortcut clips a
            // corner. Keeping that one valid edge avoids a full A* restart at every
            // voxel boundary and preserves the same arrival lobe while moving.
            adapted.add(listener);
            arrivalSamples.add(cachedPoints.getLast());
        } else {
            return null;
        }
        Vec3 arrivalPoint = arrivalFieldCentroid(
                level,
                arrivalSamples,
                listener,
                lastInterior
        );
        return new PropagationGraphPath(
                List.copyOf(adapted),
                cached.apertureAmplitude(),
                cached.clear(),
                arrivalPoint,
                List.copyOf(arrivalSamples)
        );
    }

    private static Vec3 arrivalFieldCentroid(
            BlockGetter level,
            List<Vec3> samples,
            Vec3 listener,
            Vec3 fallback
    ) {
        Vec3 moment = Vec3.ZERO;
        double totalWeight = 0.0;
        for (Vec3 point : samples) {
            double distance = point.distanceTo(listener);
            if (distance <= 1.0E-6) {
                continue;
            }
            float amplitude = weightedEnergy(
                    traceTransmission(level, point, listener).bands()
            );
            double power = amplitude * amplitude;
            if (power <= 1.0E-8) {
                continue;
            }
            // This is the first spatial moment of the listener-visible wavefront.
            // Transmission power makes a point enter or leave the field continuously
            // as an edge crosses the line of sight; distance reproduces the radial
            // moment used by the propagation solver without normalizing away spread.
            double weight = distance * power;
            moment = moment.add(point.scale(weight));
            totalWeight += weight;
        }
        return totalWeight > 1.0E-10
                ? moment.scale(1.0 / totalWeight)
                : fallback;
    }

    private static PropagationGraphPath adaptNeighboringCachedAirPath(
            BlockGetter level,
            AirPathCacheKey requested,
            Vec3 source,
            Vec3 listener
    ) {
        PropagationGraphPath best = null;
        double bestEndpointDistance = Double.POSITIVE_INFINITY;
        AirCellKey goal = requested.listener();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    PropagationGraphPath candidate = AIR_PATH_CACHE.get(
                            new AirPathCacheKey(
                                    requested.level(),
                                    requested.sceneRevision(),
                                    requested.source(),
                                    new AirCellKey(
                                            goal.x() + x,
                                            goal.y() + y,
                                            goal.z() + z
                                    )
                            )
                    );
                    if (candidate == null) {
                        continue;
                    }
                    PropagationGraphPath adapted = adaptCachedAirPath(
                            level, candidate, source, listener
                    );
                    if (adapted == null) {
                        continue;
                    }
                    double endpointDistance = candidate.points().getLast()
                            .distanceToSqr(listener);
                    if (endpointDistance < bestEndpointDistance) {
                        bestEndpointDistance = endpointDistance;
                        best = adapted;
                    }
                }
            }
        }
        return best;
    }

    private static AirCellKey airCellKey(Vec3 point) {
        return new AirCellKey(
                Mth.floor(point.x),
                Mth.floor(point.y),
                Mth.floor(point.z)
        );
    }

    /**
     * A block sound is normally positioned at the centre of the block which emits it.
     * That point may be inside the block's collision shape (jukeboxes are the common
     * example), while its wave is coupled to every adjacent air volume. Seed all six
     * physical faces instead of declaring the global air graph disconnected.
     */
    private static List<AirCellKey> airEndpointCells(BlockGetter level, Vec3 endpoint) {
        AirCellKey containing = airCellKey(endpoint);
        if (!pointInsideCollision(level, endpoint)
                && !pointInsideCollision(level, airCellCenter(containing))) {
            return List.of(containing);
        }
        List<AirCellKey> result = new ArrayList<>(AIR_GRAPH_NEIGHBORS.length);
        for (int[] offset : AIR_GRAPH_NEIGHBORS) {
            AirCellKey adjacent = new AirCellKey(
                    containing.x() + offset[0],
                    containing.y() + offset[1],
                    containing.z() + offset[2]
            );
            if (!pointInsideCollision(level, airCellCenter(adjacent))) {
                result.add(adjacent);
            }
        }
        return List.copyOf(result);
    }

    private static boolean airEndpointConnectorClear(
            BlockGetter level,
            Vec3 endpoint,
            Vec3 interior
    ) {
        if (firstCollisionBounds(level, endpoint, interior) == null) {
            return true;
        }
        AirCellKey containing = airCellKey(endpoint);
        if (!pointInsideCollision(level, endpoint)
                && !pointInsideCollision(level, airCellCenter(containing))) {
            return false;
        }
        for (AirCellKey seed : airEndpointCells(level, endpoint)) {
            if (firstCollisionBounds(level, airCellCenter(seed), interior) == null) {
                return true;
            }
        }
        return false;
    }

    private static AirPathCacheKey airPathCacheKey(
            BlockGetter level,
            Vec3 source,
            Vec3 listener
    ) {
        long sceneRevision = level instanceof AcousticScene scene
                ? scene.revision()
                : 0L;
        return new AirPathCacheKey(
                level,
                sceneRevision,
                airCellKey(source),
                airCellKey(listener)
        );
    }

    private static Vec3 airCellCenter(AirCellKey key) {
        return new Vec3(key.x() + 0.5, key.y() + 0.5, key.z() + 0.5);
    }

    private static long packAirCell(AirCellKey key) {
        return BlockPos.asLong(key.x(), key.y(), key.z());
    }

    private static void appendPortalNodes(
            BlockGetter level,
            List<PropagationGraphNode> target,
            List<RoomProbe.OpeningSample> openings,
            Vec3 anchor,
            Vec3 oppositeEndpoint,
            double controlVolumeDistance
    ) {
        for (RoomProbe.OpeningSample opening : openings) {
            Vec3 direction = opening.direction();
            if (direction.lengthSqr() < 1.0E-10) {
                continue;
            }
            // Move only enough to choose the air side of an exact block face. This is a
            // numerical boundary convention, not an acoustic clearance or gain.
            Vec3 normalizedDirection = direction.normalize();
            double measuredDistance = anchor.distanceTo(opening.point());
            Vec3 point = opening.point().add(normalizedDirection.scale(1.0E-3));
            if (measuredDistance <= 0.02
                    || pointInsideCollision(level, point)
                    || firstCollisionBounds(level, anchor, point) != null) {
                continue;
            }
            boolean oppositeVisible = firstCollisionBounds(
                    level, point, oppositeEndpoint
            ) == null;
            boolean measuredAperture = measuredDistance
                    < controlVolumeDistance - 0.02;
            if (!oppositeVisible && !measuredAperture) {
                // A control-volume boundary is not a physical portal. Keep it only when
                // it already closes a valid visibility edge; otherwise outdoor sky
                // samples would crowd real doors out of the bounded A* frontier.
                continue;
            }
            if (oppositeVisible) {
                // For a control-volume ray, find the first mutually visible point on the
                // same measured line. This preserves the shortest physical path through
                // a large facade while measured local apertures already start at their
                // reconstructed plane.
                double blockedDistance = 0.0;
                double clearDistance = measuredDistance;
                int checks = 0;
                while (clearDistance - blockedDistance > 0.02 && checks++ < 12) {
                    double testDistance = (blockedDistance + clearDistance) * 0.5;
                    Vec3 testPoint = anchor.add(
                            normalizedDirection.scale(testDistance)
                    );
                    boolean visible = !pointInsideCollision(level, testPoint)
                            && firstCollisionBounds(level, anchor, testPoint) == null
                            && firstCollisionBounds(level, testPoint, oppositeEndpoint) == null;
                    if (visible) {
                        clearDistance = testDistance;
                    } else {
                        blockedDistance = testDistance;
                    }
                }
                point = anchor.add(normalizedDirection.scale(clearDistance + 1.0E-3));
            }
            // A portal belongs to the local region graph even when the opposite endpoint
            // cannot see it. Requiring endpoint-to-portal visibility here reduced every
            // room to a one-turn shortcut and discarded the first door of an L-shaped
            // corridor or maze before A* had a chance to expand it.
            float apertureAmplitude = (float) Math.sqrt(
                    Mth.clamp(opening.weight(), 0.0F, 1.0F)
            );
            target.add(new PropagationGraphNode(
                    point, apertureAmplitude, GraphNodeKind.PORTAL
            ));
        }
    }

    private static void enqueueVisibleGraphNode(
            BlockGetter level,
            Vec3 listener,
            PropagationGraphState state,
            PropagationGraphNode node,
            PriorityQueue<PropagationGraphState> frontier,
            Map<GraphPointKey, Double> bestDistanceByNode
    ) {
        double segmentDistance = state.node().point().distanceTo(node.point());
        if (segmentDistance < 1.0E-4 || pointInsideCollision(level, node.point())) {
            return;
        }
        if (firstCollisionBounds(level, state.node().point(), node.point()) != null) {
            return;
        }
        double distance = state.distance() + segmentDistance;
        GraphPointKey key = graphPointKey(node.point());
        if (distance + 1.0E-7 >= bestDistanceByNode.getOrDefault(
                key, Double.POSITIVE_INFINITY
        )) {
            return;
        }
        bestDistanceByNode.put(key, distance);
        float apertureAmplitude = state.apertureAmplitude()
                * (node.kind() == GraphNodeKind.PORTAL ? node.apertureAmplitude() : 1.0F);
        frontier.add(new PropagationGraphState(
                node,
                state,
                distance,
                distance + node.point().distanceTo(listener),
                apertureAmplitude
        ));
    }

    private static List<PropagationGraphNode> sampleVisibleDiffractionEdges(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            AABB obstacle,
            int candidateBudget,
            AcousticTuning tuning,
            boolean includeLocalEdges
    ) {
        double directDistance = source.distanceTo(listener);
        if (directDistance < 1.0E-6) {
            return List.of();
        }
        Vec3 direction = listener.subtract(source).scale(1.0 / directDistance);
        Vec3 right = direction.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0E-8) {
            right = new Vec3(1.0, 0.0, 0.0);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(direction).normalize();
        Vec3[] corners = aabbCorners(obstacle);
        double obstacleExitDistance = 0.0;
        for (Vec3 corner : corners) {
            obstacleExitDistance = Math.max(
                    obstacleExitDistance,
                    corner.subtract(source).dot(direction)
            );
        }
        // Put edge nodes just beyond the obstacle's far support plane. A node on the
        // centre or source-facing plane can be visible only because the voxel walker
        // excludes its endpoint block, leaving A* stuck rediscovering the same wall.
        double planeDistance = Mth.clamp(
                obstacleExitDistance + DIFFRACTION_MARGIN,
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
        double searchRadius = Math.max(fresnel125, tuning.roomProbeDistance());
        int radialChecks = Math.max(6, Math.min(8, candidateBudget / 2));
        List<PropagationGraphNode> result = new ArrayList<>(20);
        if (includeLocalEdges) {
            appendVisibleObstacleEdges(level, source, obstacle, result);
        }
        int radialNodeCount = 0;
        for (int angularStep = 0; angularStep < 8; angularStep++) {
            double angle = angularStep * Math.PI * 0.25;
            Vec3 radial = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            double nearRadius = projectedSilhouetteRadius(planeCenter, radial, corners)
                    + DIFFRACTION_MARGIN;
            double maximumRadius = Math.max(searchRadius, nearRadius);
            Vec3 nearPoint = planeCenter.add(radial.scale(nearRadius));
            boolean previousClear = isAdvancingVisibilityNode(
                    level, source, listener, nearPoint, direction,
                    obstacle, obstacleExitDistance
            );
            if (previousClear && radialNodeCount < candidateBudget) {
                result.add(new PropagationGraphNode(
                        nearPoint, 1.0F, GraphNodeKind.EDGE
                ));
                radialNodeCount++;
            }

            // Visibility is not monotonic across a facade: several finite doors may be
            // separated by jambs, and another corridor wall can close the radial line
            // again. Record every blocked->clear interval instead of choosing either the
            // nearest or farthest one. A* then decides which portal sequence is physical.
            double previousRadius = nearRadius;
            int sweepSamples = (int) Math.ceil(
                    (maximumRadius - nearRadius) / 0.5
            );
            for (int sweep = 1; sweep <= sweepSamples; sweep++) {
                double radius = Math.min(
                        nearRadius + sweep * 0.5,
                        maximumRadius
                );
                Vec3 point = planeCenter.add(radial.scale(radius));
                boolean clear = isAdvancingVisibilityNode(
                        level, source, listener, point,
                        direction, obstacle, obstacleExitDistance
                );
                if (clear && !previousClear && radialNodeCount < candidateBudget) {
                    double clearBoundary = refineAdvancingBoundary(
                            level, source, listener, planeCenter, radial,
                            direction, obstacle, obstacleExitDistance,
                            previousRadius, radius, radialChecks
                    );
                    result.add(new PropagationGraphNode(
                            planeCenter.add(radial.scale(clearBoundary)),
                            1.0F,
                            GraphNodeKind.EDGE
                    ));
                    radialNodeCount++;
                }
                previousClear = clear;
                previousRadius = radius;
            }
        }
        return result;
    }

    private static double refineAdvancingBoundary(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            Vec3 planeCenter,
            Vec3 radial,
            Vec3 searchDirection,
            AABB currentObstacle,
            double obstacleExitDistance,
            double blockedRadius,
            double clearRadius,
            int checks
    ) {
        for (int check = 0; check < checks; check++) {
            double radius = (blockedRadius + clearRadius) * 0.5;
            Vec3 point = planeCenter.add(radial.scale(radius));
            if (isAdvancingVisibilityNode(
                    level, source, listener, point,
                    searchDirection, currentObstacle, obstacleExitDistance
            )) {
                clearRadius = radius;
            } else {
                blockedRadius = radius;
            }
        }
        return clearRadius;
    }

    private static void appendVisibleObstacleEdges(
            BlockGetter level,
            Vec3 source,
            AABB obstacle,
            List<PropagationGraphNode> target
    ) {
        double centerX = (obstacle.minX + obstacle.maxX) * 0.5;
        double centerY = (obstacle.minY + obstacle.maxY) * 0.5;
        double centerZ = (obstacle.minZ + obstacle.maxZ) * 0.5;
        double diagonal = DIFFRACTION_MARGIN / Math.sqrt(2.0);
        double dx = Math.abs(source.x - centerX);
        double dy = Math.abs(source.y - centerY);
        double dz = Math.abs(source.z - centerZ);
        // Only the eight edges belonging to the source- and listener-facing support
        // faces can participate in the current bend. Emitting all twelve edges of every
        // touched voxel flooded A* with internal wall seams and broke symmetry.
        for (int faceSign : new int[]{-1, 1}) {
            for (int edgeSign : new int[]{-1, 1}) {
                Vec3 first;
                Vec3 second;
                if (dx >= dy && dx >= dz) {
                    double faceX = faceSign < 0 ? obstacle.minX : obstacle.maxX;
                    first = new Vec3(
                            faceX + faceSign * diagonal,
                            edgeSign < 0 ? obstacle.minY - diagonal : obstacle.maxY + diagonal,
                            centerZ
                    );
                    second = new Vec3(
                            faceX + faceSign * diagonal,
                            centerY,
                            edgeSign < 0 ? obstacle.minZ - diagonal : obstacle.maxZ + diagonal
                    );
                } else if (dy >= dx && dy >= dz) {
                    double faceY = faceSign < 0 ? obstacle.minY : obstacle.maxY;
                    first = new Vec3(
                            edgeSign < 0 ? obstacle.minX - diagonal : obstacle.maxX + diagonal,
                            faceY + faceSign * diagonal,
                            centerZ
                    );
                    second = new Vec3(
                            centerX,
                            faceY + faceSign * diagonal,
                            edgeSign < 0 ? obstacle.minZ - diagonal : obstacle.maxZ + diagonal
                    );
                } else {
                    double faceZ = faceSign < 0 ? obstacle.minZ : obstacle.maxZ;
                    first = new Vec3(
                            edgeSign < 0 ? obstacle.minX - diagonal : obstacle.maxX + diagonal,
                            centerY,
                            faceZ + faceSign * diagonal
                    );
                    second = new Vec3(
                            centerX,
                            edgeSign < 0 ? obstacle.minY - diagonal : obstacle.maxY + diagonal,
                            faceZ + faceSign * diagonal
                    );
                }
                addVisibleEdgePoint(level, source, first, target);
                addVisibleEdgePoint(level, source, second, target);
            }
        }
    }

    private static void addVisibleEdgePoint(
            BlockGetter level,
            Vec3 source,
            Vec3 point,
            List<PropagationGraphNode> target
    ) {
        if (!pointInsideCollision(level, point)
                && firstCollisionBounds(level, source, point) == null) {
            target.add(new PropagationGraphNode(
                    point, 1.0F, GraphNodeKind.LOCAL_EDGE
            ));
        }
    }

    private static boolean isAdvancingVisibilityNode(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            Vec3 point,
            Vec3 searchDirection,
            AABB currentObstacle,
            double currentObstacleExit
    ) {
        if (pointInsideCollision(level, point)
                || firstCollisionBounds(level, source, point) != null) {
            return false;
        }
        AABB outgoingObstacle = firstCollisionBounds(level, point, listener);
        if (outgoingObstacle == null) {
            return true;
        }
        if (sameObstacleLayer(currentObstacle, outgoingObstacle, searchDirection)) {
            return false;
        }
        // A visibility node must advance past the current screen, not merely land on a
        // source-facing point whose outgoing ray immediately hits another voxel of that
        // same screen. Compare support planes along the current propagation direction;
        // a genuinely later wall has a strictly later entry plane.
        double nextObstacleEntry = Double.POSITIVE_INFINITY;
        for (Vec3 corner : aabbCorners(outgoingObstacle)) {
            nextObstacleEntry = Math.min(
                    nextObstacleEntry,
                    corner.subtract(source).dot(searchDirection)
            );
        }
        return nextObstacleEntry > currentObstacleExit + 1.0E-4;
    }

    private static boolean sameObstacleLayer(
            AABB current,
            AABB next,
            Vec3 propagationDirection
    ) {
        double absoluteX = Math.abs(propagationDirection.x);
        double absoluteY = Math.abs(propagationDirection.y);
        double absoluteZ = Math.abs(propagationDirection.z);
        if (absoluteX >= absoluteY && absoluteX >= absoluteZ) {
            return Math.abs(current.minX - next.minX) < 1.0E-6
                    && Math.abs(current.maxX - next.maxX) < 1.0E-6;
        }
        if (absoluteY >= absoluteZ) {
            return Math.abs(current.minY - next.minY) < 1.0E-6
                    && Math.abs(current.maxY - next.maxY) < 1.0E-6;
        }
        return Math.abs(current.minZ - next.minZ) < 1.0E-6
                && Math.abs(current.maxZ - next.maxZ) < 1.0E-6;
    }

    private static PropagationGraphPath reconstructGraphPath(
            PropagationGraphState state,
            Vec3 listener,
            boolean clear
    ) {
        ArrayDeque<Vec3> reversed = new ArrayDeque<>();
        for (PropagationGraphState cursor = state; cursor != null; cursor = cursor.previous()) {
            reversed.addFirst(cursor.node().point());
        }
        List<Vec3> points = new ArrayList<>(reversed.size() + 1);
        points.addAll(reversed);
        points.add(listener);
        return new PropagationGraphPath(
                List.copyOf(points), state.apertureAmplitude(), clear,
                points.size() > 2
                        ? points.get(points.size() - 2)
                        : state.node().point(),
                points.size() > 2
                        ? List.of(points.get(points.size() - 2))
                        : List.of(state.node().point())
        );
    }

    private static EvaluatedGraphPath evaluatePropagationPath(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            PropagationGraphPath path,
            AcousticTuning tuning
    ) {
        float[] bands = new float[AcousticBands.COUNT];
        Arrays.fill(bands, path.apertureAmplitude());
        double pathDistance = 0.0;
        List<Vec3> points = path.points();
        for (int index = 1; index < points.size(); index++) {
            Vec3 from = points.get(index - 1);
            Vec3 to = points.get(index);
            pathDistance += from.distanceTo(to);
            float[] segmentBands = traceTransmission(level, from, to).bands();
            for (int band = 0; band < bands.length; band++) {
                bands[band] *= segmentBands[band];
            }
        }
        double directDistance = source.distanceTo(listener);
        double excessMeters = tuning.meters(
                Math.max(0.0, pathDistance - directDistance)
        );
        float distanceLoss = (float) (directDistance / Math.max(pathDistance, 0.1));
        for (int band = 0; band < bands.length; band++) {
            bands[band] *= DiffractionPhysics.knifeEdgeAmplitude(
                    AcousticBands.CENTERS_HZ[band], excessMeters
            );
            bands[band] *= distanceLoss * airAbsorption(band, excessMeters);
        }
        Vec3 arrivalPoint = path.arrivalPoint() != null
                ? path.arrivalPoint()
                : points.size() > 2
                ? points.get(points.size() - 2)
                : source;
        return new EvaluatedGraphPath(
                bands,
                weightedEnergy(bands),
                pathDistance,
                arrivalPoint,
                path.clear()
        );
    }

    private static GraphPointKey graphPointKey(Vec3 point) {
        // 1/1024 block is below collision and audible spatial resolution, while removing
        // floating-point duplicates generated from the same geometric edge.
        return new GraphPointKey(
                Math.round(point.x * 1024.0),
                Math.round(point.y * 1024.0),
                Math.round(point.z * 1024.0)
        );
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

    private static double occlusionSampleRadius(double distance, AcousticTuning tuning) {
        return Math.max(
                tuning.occlusionSampleRadiusMin(),
                Math.min(
                        tuning.occlusionSampleRadiusMax(),
                        distance * tuning.occlusionSampleAngularSpread()
                )
        );
    }

    private static FiniteWavefront traceFiniteWavefront(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            Vec3 direction,
            Vec3 right,
            Vec3 up,
            int pairCount
    ) {
        Transmission center = traceTransmission(level, source, listener);
        double distance = source.distanceTo(listener);
        float[] meanPower = new float[AcousticBands.COUNT];
        float[] bands = new float[AcousticBands.COUNT];
        if (distance < 1.0E-6) {
            for (int band = 0; band < bands.length; band++) {
                bands[band] = center.bands()[band];
                meanPower[band] = bands[band] * bands[band];
            }
            return new FiniteWavefront(center, bands, meanPower);
        }

        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        double sourceDistance = center.firstBounds() == null
                ? distance * 0.5
                : Mth.clamp(
                        firstObstacleDistance(center, source, listener),
                        0.01,
                        Math.max(0.01, distance - 0.01)
                );
        double listenerDistance = Math.max(0.01, distance - sourceDistance);
        double geometricRadius = occlusionSampleRadius(distance, tuning);
        Vec3 wavefrontCenter = source.add(direction.scale(sourceDistance));
        int samplesPerBand = 1 + pairCount * 2;
        for (int firstBand = 0; firstBand < AcousticBands.COUNT; firstBand += 2) {
            double representativeFrequency = Math.sqrt(
                    AcousticBands.CENTERS_HZ[firstBand]
                            * AcousticBands.CENTERS_HZ[firstBand + 1]
            );
            double physicalRadius = tuning.blocks(fresnelRadius(
                    representativeFrequency,
                    tuning.meters(sourceDistance),
                    tuning.meters(listenerDistance)
            ));
            double radius = Math.max(geometricRadius, physicalRadius);
            float firstPower = center.bands()[firstBand]
                    * center.bands()[firstBand];
            float secondPower = center.bands()[firstBand + 1]
                    * center.bands()[firstBand + 1];
            for (Vec3 offset : fresnelAreaOffsets(
                    right, up, radius, pairCount, false
            )) {
                Transmission sample = traceBentTransmission(
                        level, source, wavefrontCenter.add(offset), listener
                );
                float firstAmplitude = sample.bands()[firstBand];
                float secondAmplitude = sample.bands()[firstBand + 1];
                firstPower += firstAmplitude * firstAmplitude;
                secondPower += secondAmplitude * secondAmplitude;
            }
            meanPower[firstBand] = firstPower / samplesPerBand;
            meanPower[firstBand + 1] = secondPower / samplesPerBand;
            bands[firstBand] = (float) Math.sqrt(meanPower[firstBand]);
            bands[firstBand + 1] = (float) Math.sqrt(meanPower[firstBand + 1]);
        }
        return new FiniteWavefront(center, bands, meanPower);
    }

    private static Vec3[] fresnelAreaOffsets(
            Vec3 right,
            Vec3 up,
            double radius,
            int pairCount,
            boolean includeCenter
    ) {
        // Equal-area antipodal low-discrepancy quadrature. Every ray has its opposite,
        // while golden-angle rotation prevents an entire ring from crossing one voxel
        // edge in the same frame. This keeps motion continuous without giving any
        // cardinal or diagonal world direction a privileged sample pattern.
        int first = includeCenter ? 1 : 0;
        Vec3[] offsets = new Vec3[first + pairCount * 2];
        if (includeCenter) {
            offsets[0] = Vec3.ZERO;
        }
        int index = first;
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int pair = 0; pair < pairCount; pair++) {
            double sampleRadius = radius * Math.sqrt((pair + 0.5) / pairCount);
            double angle = pair * goldenAngle;
            Vec3 offset = right.scale(Math.cos(angle) * sampleRadius)
                    .add(up.scale(Math.sin(angle) * sampleRadius));
            offsets[index++] = offset;
            offsets[index++] = offset.scale(-1.0);
        }
        return offsets;
    }

    /**
     * Traces mechanical energy through connected collision solids. Source coupling is
     * continuous with distance to the supporting surface, so contact sounds strongly
     * excite the structure while airborne sounds a block away contribute essentially
     * nothing. A* follows actual face-connected solids and the material model supplies
     * frequency-dependent coupling and damping; no sound-id or footstep special case is
     * involved.
     */
    static StructuralPath traceStructuralPath(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            AcousticTuning tuning
    ) {
        long sceneRevision = level instanceof AcousticScene scene
                ? scene.revision()
                : 0L;
        StructuralPathCacheKey cacheKey = new StructuralPathCacheKey(
                level,
                sceneRevision,
                graphPointKey(source),
                graphPointKey(listener),
                tuning
        );
        StructuralPath cached = STRUCTURAL_PATH_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<StructuralStart> starts = structuralStarts(level, source);
        if (starts.isEmpty()) {
            return null;
        }

        PriorityQueue<StructuralState> frontier = new PriorityQueue<>(
                Comparator.comparingDouble(StructuralState::priority)
        );
        Map<BlockPos, Double> bestCost = new HashMap<>();
        for (StructuralStart start : starts) {
            BlockPos key = start.position().immutable();
            double priority = start.contactDistance()
                    + Vec3.atCenterOf(key).distanceTo(listener);
            StructuralState state = new StructuralState(
                    key,
                    null,
                    0.0,
                    priority,
                    start.contactDistance()
            );
            if (priority < bestCost.getOrDefault(key, Double.POSITIVE_INFINITY)) {
                bestCost.put(key, 0.0);
                frontier.add(state);
            }
        }

        StructuralState goal = null;
        Vec3 radiationPoint = null;
        int expanded = 0;
        int maximumExpansions = 192;
        while (!frontier.isEmpty() && expanded++ < maximumExpansions) {
            StructuralState state = frontier.poll();
            if (state.cost() > bestCost.getOrDefault(
                    state.position(), Double.POSITIVE_INFINITY
            ) + 1.0E-7) {
                continue;
            }
            Vec3 candidateRadiator = visibleRadiationPoint(
                    level, state.position(), listener
            );
            if (candidateRadiator != null) {
                goal = state;
                radiationPoint = candidateRadiator;
                break;
            }
            BlockState currentState = level.getBlockState(state.position());
            AcousticMaterial currentMaterial = AcousticMaterialRegistry.find(currentState);
            for (int[] offset : AIR_GRAPH_NEIGHBORS) {
                BlockPos nextPosition = state.position().offset(
                        offset[0], offset[1], offset[2]
                );
                BlockState nextState = level.getBlockState(nextPosition);
                if (nextState.isAir()
                        || nextState.getCollisionShape(level, nextPosition).isEmpty()
                        || !mechanicallyConnected(
                                level,
                                state.position(), currentState,
                                nextPosition, nextState
                        )) {
                    continue;
                }
                AcousticMaterial nextMaterial = AcousticMaterialRegistry.find(nextState);
                double dampingCost = -Math.log(Math.max(
                        nextMaterial.structuralGain(1, tuning.metersPerBlock()),
                        1.0E-8F
                ));
                double nextCost = state.cost() + 1.0 + dampingCost;
                BlockPos key = nextPosition.immutable();
                if (nextCost + 1.0E-7 >= bestCost.getOrDefault(
                        key, Double.POSITIVE_INFINITY
                )) {
                    continue;
                }
                bestCost.put(key, nextCost);
                frontier.add(new StructuralState(
                        key,
                        state,
                        nextCost,
                        nextCost + Vec3.atCenterOf(key).distanceTo(listener),
                        state.contactDistance()
                ));
            }
        }
        if (goal == null || radiationPoint == null) {
            return null;
        }

        ArrayDeque<BlockPos> reversed = new ArrayDeque<>();
        for (StructuralState cursor = goal; cursor != null; cursor = cursor.previous()) {
            reversed.addFirst(cursor.position());
        }
        List<BlockPos> path = List.copyOf(reversed);
        if (path.isEmpty()) {
            return null;
        }
        float contactWeight = (float) Math.exp(
                -tuning.meters(goal.contactDistance()) / 0.06
        );
        if (contactWeight < 1.0E-4F) {
            return null;
        }

        float[] bands = new float[AcousticBands.COUNT];
        AcousticMaterial firstMaterial = AcousticMaterialRegistry.find(
                level.getBlockState(path.getFirst())
        );
        AcousticMaterial previousMaterial = null;
        for (int band = 0; band < bands.length; band++) {
            bands[band] = contactWeight
                    * (float) Math.sqrt(firstMaterial.structuralCoupling(band));
        }
        for (BlockPos position : path) {
            AcousticMaterial material = AcousticMaterialRegistry.find(
                    level.getBlockState(position)
            );
            for (int band = 0; band < bands.length; band++) {
                if (previousMaterial != null && previousMaterial != material) {
                    float lower = Math.min(
                            previousMaterial.structuralCoupling(band),
                            material.structuralCoupling(band)
                    );
                    float higher = Math.max(
                            previousMaterial.structuralCoupling(band),
                            material.structuralCoupling(band)
                    );
                    bands[band] *= (float) Math.sqrt(
                            lower / Math.max(higher, 1.0E-6F)
                    );
                }
                bands[band] *= material.structuralGain(
                        band, tuning.metersPerBlock()
                );
            }
            previousMaterial = material;
        }
        AcousticMaterial finalMaterial = AcousticMaterialRegistry.find(
                level.getBlockState(path.getLast())
        );
        double structuralDistance = path.size() * tuning.metersPerBlock();
        double radiatedAirDistance = radiationPoint.distanceTo(listener);
        double directDistance = Math.max(source.distanceTo(listener), 0.1);
        float routeDistanceGain = (float) Math.min(
                1.0,
                directDistance / Math.max(
                        directDistance,
                        structuralDistance + tuning.meters(radiatedAirDistance)
                )
        );
        for (int band = 0; band < bands.length; band++) {
            bands[band] *= (float) Math.sqrt(
                    finalMaterial.structuralCoupling(band)
            );
            bands[band] *= routeDistanceGain
                    * airAbsorption(band, tuning.meters(radiatedAirDistance));
            bands[band] = Mth.clamp(bands[band], 0.0F, 1.0F);
        }
        if (weightedEnergy(bands) <= 1.0E-5F) {
            return null;
        }
        StructuralPath result = new StructuralPath(
                bands, radiationPoint, structuralDistance
        );
        if (STRUCTURAL_PATH_CACHE.size() >= 256) {
            STRUCTURAL_PATH_CACHE.clear();
        }
        STRUCTURAL_PATH_CACHE.put(cacheKey, result);
        return result;
    }

    private static List<StructuralStart> structuralStarts(
            BlockGetter level,
            Vec3 source
    ) {
        BlockPos origin = BlockPos.containing(source);
        List<StructuralStart> starts = new ArrayList<>(8);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos position = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(position);
                    VoxelShape shape = state.getCollisionShape(level, position);
                    if (state.isAir() || shape.isEmpty()) {
                        continue;
                    }
                    double distance = Double.POSITIVE_INFINITY;
                    for (AABB local : shape.toAabbs()) {
                        distance = Math.min(
                                distance,
                                pointToAabbSurfaceDistance(source, local.move(position))
                        );
                    }
                    // Beyond 25 cm the exponential contact term is already below 1.6%;
                    // discarding it is the numerical energy cutoff used by the solver.
                    if (distance <= 0.25) {
                        starts.add(new StructuralStart(position.immutable(), distance));
                    }
                }
            }
        }
        starts.sort(Comparator.comparingDouble(StructuralStart::contactDistance));
        return starts.size() <= 8 ? starts : List.copyOf(starts.subList(0, 8));
    }

    private static double pointToAabbSurfaceDistance(Vec3 point, AABB bounds) {
        double dx = Math.max(bounds.minX - point.x, Math.max(0.0, point.x - bounds.maxX));
        double dy = Math.max(bounds.minY - point.y, Math.max(0.0, point.y - bounds.maxY));
        double dz = Math.max(bounds.minZ - point.z, Math.max(0.0, point.z - bounds.maxZ));
        double outsideDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (outsideDistance > 1.0E-10) {
            return outsideDistance;
        }
        // Structural coupling models contact with a material surface. Treating every
        // point inside a collision box as distance zero made a block-centred airborne
        // source (for example a jukebox) excite the structure as strongly as a footstep.
        return Math.min(
                Math.min(point.x - bounds.minX, bounds.maxX - point.x),
                Math.min(
                        Math.min(point.y - bounds.minY, bounds.maxY - point.y),
                        Math.min(point.z - bounds.minZ, bounds.maxZ - point.z)
                )
        );
    }

    private static Vec3 visibleRadiationPoint(
            BlockGetter level,
            BlockPos position,
            Vec3 listener
    ) {
        VoxelShape shape = level.getBlockState(position).getCollisionShape(level, position);
        Vec3 best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (AABB local : shape.toAabbs()) {
            AABB bounds = local.move(position);
            Vec3 surface = new Vec3(
                    Mth.clamp(listener.x, bounds.minX, bounds.maxX),
                    Mth.clamp(listener.y, bounds.minY, bounds.maxY),
                    Mth.clamp(listener.z, bounds.minZ, bounds.maxZ)
            );
            Vec3 outward = listener.subtract(surface);
            if (outward.lengthSqr() <= 1.0E-10) {
                continue;
            }
            Vec3 outside = surface.add(outward.normalize().scale(0.035));
            if (pointInsideCollision(level, outside)
                    || firstCollisionBounds(level, outside, listener) != null) {
                continue;
            }
            double distance = surface.distanceToSqr(listener);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = surface;
            }
        }
        return best;
    }

    private static boolean mechanicallyConnected(
            BlockGetter level,
            BlockPos firstPosition,
            BlockState firstState,
            BlockPos secondPosition,
            BlockState secondState
    ) {
        double epsilon = 1.0E-6;
        for (AABB firstLocal : firstState.getCollisionShape(level, firstPosition).toAabbs()) {
            AABB first = firstLocal.move(firstPosition);
            for (AABB secondLocal : secondState.getCollisionShape(level, secondPosition).toAabbs()) {
                AABB second = secondLocal.move(secondPosition);
                boolean xFace = (Math.abs(first.maxX - second.minX) <= epsilon
                        || Math.abs(second.maxX - first.minX) <= epsilon)
                        && overlap(first.minY, first.maxY, second.minY, second.maxY) > epsilon
                        && overlap(first.minZ, first.maxZ, second.minZ, second.maxZ) > epsilon;
                boolean yFace = (Math.abs(first.maxY - second.minY) <= epsilon
                        || Math.abs(second.maxY - first.minY) <= epsilon)
                        && overlap(first.minX, first.maxX, second.minX, second.maxX) > epsilon
                        && overlap(first.minZ, first.maxZ, second.minZ, second.maxZ) > epsilon;
                boolean zFace = (Math.abs(first.maxZ - second.minZ) <= epsilon
                        || Math.abs(second.maxZ - first.minZ) <= epsilon)
                        && overlap(first.minX, first.maxX, second.minX, second.maxX) > epsilon
                        && overlap(first.minY, first.maxY, second.minY, second.maxY) > epsilon;
                if (xFace || yFace || zFace) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double overlap(
            double firstMin,
            double firstMax,
            double secondMin,
            double secondMax
    ) {
        return Math.min(firstMax, secondMax) - Math.max(firstMin, secondMin);
    }

    static ReflectionResult estimateEarlyReflections(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe roomProbe,
            int pathBudget
    ) {
        return estimateEarlyReflections(
                level, source, listener, roomProbe, pathBudget, true
        );
    }

    private static ReflectionResult estimateEarlyReflections(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe sourceRoomProbe,
            int pathBudget,
            boolean allowDiffractedLegs
    ) {
        List<RoomProbe.SurfaceSample> candidates = reflectionCandidates(
                source, listener, sourceRoomProbe
        );
        if (candidates.isEmpty() || pathBudget <= 0) {
            return ReflectionResult.silent();
        }

        double directDistance = Math.max(source.distanceTo(listener), 0.5);
        float[] accumulated = new float[AcousticBands.COUNT];
        int remainingDiffractedLegs = allowDiffractedLegs
                ? Math.min(2, pathBudget)
                : 0;
        int acceptedPaths = 0;
        List<Vec3> acceptedReflectionPoints = new ArrayList<>(pathBudget);
        SurfaceHit[] validationHit = new SurfaceHit[1];
        double delayPowerSum = 0.0;
        double directionPower = 0.0;
        Vec3 arrivalDirectionSum = Vec3.ZERO;
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
            float[] pathBands = new float[AcousticBands.COUNT];
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
                pathBands[band] = contribution;
                accumulated[band] += contribution * contribution;
            }
            double pathPower = weightedEnergy(pathBands);
            pathPower *= pathPower;
            if (pathPower > 1.0E-12) {
                double excessMeters = AcousticMaterialRegistry.tuning().meters(
                        Math.max(0.0, pathDistance - directDistance)
                );
                delayPowerSum += pathPower
                        * excessMeters / DiffractionPhysics.SPEED_OF_SOUND_METERS_PER_SECOND;
                Vec3 arrivalDirection = reflectionPoint.subtract(listener);
                if (arrivalDirection.lengthSqr() > 1.0E-10) {
                    arrivalDirectionSum = arrivalDirectionSum.add(
                            arrivalDirection.normalize().scale(pathPower)
                    );
                    directionPower += pathPower;
                }
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
        Vec3 reflectionPan = directionPower > 1.0E-12
                && arrivalDirectionSum.lengthSqr() > 1.0E-10
                ? arrivalDirectionSum.normalize()
                : Vec3.ZERO;
        return new ReflectionResult(
                accumulated,
                Mth.clamp(
                        weightedEnergy(accumulated),
                        0.0F,
                        0.90F
                ),
                Mth.clamp(high / Math.max(low, 0.01F), 0.05F, 1.0F),
                (float) (directionPower > 1.0E-12
                        ? delayPowerSum / directionPower
                        : 0.0),
                reflectionPan
        );
    }

    private static List<RoomProbe.SurfaceSample> reflectionCandidates(
            Vec3 source,
            Vec3 listener,
            RoomProbe sourceRoomProbe
    ) {
        // A first-order image path must strike a surface reached by the incident field
        // from the source. Listener-probe surfaces can sit behind an unrelated wall; a
        // diffracted/transmitted source leg may then make one of those discrete samples
        // appear as a very strong reflection for a single listener position. Source-field
        // surfaces remain fixed while the listener moves and the two physical legs below
        // still decide continuously whether each image path reaches the listener.
        List<RoomProbe.SurfaceSample> candidates = new ArrayList<>(
                sourceRoomProbe.surfaces()
        );
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
        return traceTransmission(level, from, to, true, true);
    }

    private static Transmission traceTransmission(
            BlockGetter level,
            Vec3 from,
            Vec3 to,
            boolean ignoreFromBlock,
            boolean ignoreToBlock
    ) {
        long sceneRevision = level instanceof AcousticScene scene
                ? scene.revision()
                : 0L;
        TransmissionCacheKey cacheKey = new TransmissionCacheKey(
                level,
                sceneRevision,
                AcousticMaterialRegistry.revision(),
                from,
                to,
                ignoreFromBlock,
                ignoreToBlock
        );
        Transmission cached = TRANSMISSION_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        float[] bands = unityBands();
        TransmissionScratch scratch = TRANSMISSION_SCRATCH.get();
        scratch.reset();
        BlockPos fromBlock = BlockPos.containing(from);
        BlockPos toBlock = BlockPos.containing(to);
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

            if ((ignoreFromBlock && pos.equals(fromBlock))
                    || (ignoreToBlock && pos.equals(toBlock))) {
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
        Transmission result = new Transmission(
                bands,
                scratch.firstBounds,
                scratch.blockers,
                fluidStats.distance(),
                fluidStats.scattering()
        );
        if (TRANSMISSION_CACHE.size() >= 4096) {
            TRANSMISSION_CACHE.clear();
        }
        TRANSMISSION_CACHE.put(cacheKey, result);
        return result;
    }

    /**
     * Collision-only segment query for visibility-graph construction. Material spectra,
     * fluid intervals and transmission thickness are intentionally deferred until A*
     * has produced complete paths; doing that work for every rejected graph edge made a
     * finite-aperture sweep much more expensive without changing graph connectivity.
     */
    private static AABB firstCollisionBounds(BlockGetter level, Vec3 from, Vec3 to) {
        AABB[] result = new AABB[1];
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
                SegmentInterval interval = intersectSegment(bounds, from, to);
                if (interval != null && interval.end() - interval.start() > 1.0E-7) {
                    result[0] = bounds;
                    return false;
                }
                return true;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty() || shape.clip(from, to, pos) == null) {
                return true;
            }
            SolidIntersection intersection = intersectShape(shape, pos, from, to);
            if (intersection.distance() <= 1.0E-7) {
                return true;
            }
            result[0] = intersection.firstBounds() != null
                    ? intersection.firstBounds()
                    : shape.bounds().move(pos);
            return false;
        });
        return result[0];
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
                SegmentInterval interval = intersectSegment(bounds, from, to);
                if (interval == null || interval.end() - interval.start() <= 1.0E-7) {
                    return true;
                }
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
            SolidIntersection intersection = intersectShape(shape, pos, from, to);
            if (intersection.distance() <= 1.0E-7) {
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
            boolean endpoint = (x == startX && y == startY && z == startZ)
                    || (x == endX && y == endY && z == endZ);
            if (!visitor.visit(mutablePos.set(x, y, z), endpoint)) {
                return;
            }
            if (x == endX && y == endY && z == endZ) {
                return;
            }

            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            // Only genuinely simultaneous crossings may advance more than one axis.
            // The previous fixed 1e-10 tolerance grew into a measurable world-space
            // gap on long rays and could skip a thin positive chord next to a diagonal
            // edge. ULP-scaled equality keeps exact corner contacts zero-width while a
            // real material segment, however short, is visited and measured below.
            boolean crossX = stepX != 0 && sameGridBoundary(tMaxX, next);
            boolean crossY = stepY != 0 && sameGridBoundary(tMaxY, next);
            boolean crossZ = stepZ != 0 && sameGridBoundary(tMaxZ, next);

            if (crossX) {
                x += stepX;
                tMaxX += tDeltaX;
            }
            if (crossY) {
                y += stepY;
                tMaxY += tDeltaY;
            }
            if (crossZ) {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
    }

    private static boolean sameGridBoundary(double candidate, double minimum) {
        if (candidate == minimum) {
            return true;
        }
        double scale = Math.max(Math.abs(candidate), Math.abs(minimum));
        return Math.abs(candidate - minimum) <= Math.ulp(scale) * 4.0;
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

    /**
     * One Huygens/Fresnel sample with fixed physical endpoints. The internal wavefront
     * point is included in both legs, so a point inside an obstacle measures the entry
     * and exit material lengths instead of turning that obstacle into an ignored
     * endpoint cell. Band amplitudes are multiplied because both legs belong to the
     * same connected arrival path.
     */
    private static Transmission traceBentTransmission(
            BlockGetter level,
            Vec3 source,
            Vec3 wavefrontPoint,
            Vec3 listener
    ) {
        Transmission sourceLeg = traceTransmission(
                level, source, wavefrontPoint, true, false
        );
        Transmission listenerLeg = traceTransmission(
                level, wavefrontPoint, listener, false, true
        );
        float[] bands = new float[AcousticBands.COUNT];
        for (int band = 0; band < bands.length; band++) {
            bands[band] = sourceLeg.bands()[band] * listenerLeg.bands()[band];
        }
        return new Transmission(
                bands,
                sourceLeg.firstBounds() != null
                        ? sourceLeg.firstBounds()
                        : listenerLeg.firstBounds(),
                sourceLeg.blockers() + listenerLeg.blockers(),
                sourceLeg.fluidDistance() + listenerLeg.fluidDistance(),
                Math.max(sourceLeg.fluidScattering(), listenerLeg.fluidScattering())
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

    private static double firstObstacleDistance(
            Transmission transmission,
            Vec3 source,
            Vec3 listener
    ) {
        AABB bounds = transmission.firstBounds();
        double rayDistance = source.distanceTo(listener);
        SegmentInterval interval = bounds == null
                ? null
                : intersectSegment(bounds, source, listener);
        if (interval != null) {
            return interval.start() * rayDistance;
        }
        // A partial collision shape may supply a conservative enclosing box which the
        // centre line only touches numerically. Keep a continuous projection fallback
        // instead of failing the finite-wavefront estimate.
        Vec3 direction = rayDistance > 1.0E-10
                ? listener.subtract(source).scale(1.0 / rayDistance)
                : Vec3.ZERO;
        return bounds == null
                ? 0.0
                : bounds.getCenter().subtract(source).dot(direction);
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

    private record FiniteWavefront(
            Transmission center,
            float[] bands,
            float[] meanPower
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
            boolean clear,
            boolean multipleBends
    ) {
    }

    private enum GraphNodeKind {
        SOURCE,
        PORTAL,
        LOCAL_EDGE,
        EDGE
    }

    private record GraphPointKey(long x, long y, long z) {
    }

    private record AirCellKey(int x, int y, int z) {
    }

    private record AirPathCacheKey(
            BlockGetter level,
            long sceneRevision,
            AirCellKey source,
            AirCellKey listener
    ) {
    }

    private record StructuralPathCacheKey(
            BlockGetter level,
            long sceneRevision,
            GraphPointKey source,
            GraphPointKey listener,
            AcousticTuning tuning
    ) {
    }

    private static final class TransmissionCacheKey {
        private final BlockGetter level;
        private final long sceneRevision;
        private final long materialRevision;
        private final Vec3 from;
        private final Vec3 to;
        private final boolean ignoreFromBlock;
        private final boolean ignoreToBlock;
        private final int hash;

        private TransmissionCacheKey(
                BlockGetter level,
                long sceneRevision,
                long materialRevision,
                Vec3 from,
                Vec3 to,
                boolean ignoreFromBlock,
                boolean ignoreToBlock
        ) {
            this.level = level;
            this.sceneRevision = sceneRevision;
            this.materialRevision = materialRevision;
            this.from = from;
            this.to = to;
            this.ignoreFromBlock = ignoreFromBlock;
            this.ignoreToBlock = ignoreToBlock;
            int value = System.identityHashCode(level);
            value = 31 * value + Long.hashCode(sceneRevision);
            value = 31 * value + Long.hashCode(materialRevision);
            value = 31 * value + from.hashCode();
            value = 31 * value + to.hashCode();
            value = 31 * value + Boolean.hashCode(ignoreFromBlock);
            value = 31 * value + Boolean.hashCode(ignoreToBlock);
            this.hash = value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TransmissionCacheKey key)) {
                return false;
            }
            return level == key.level
                    && sceneRevision == key.sceneRevision
                    && materialRevision == key.materialRevision
                    && ignoreFromBlock == key.ignoreFromBlock
                    && ignoreToBlock == key.ignoreToBlock
                    && from.equals(key.from)
                    && to.equals(key.to);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class TraceCacheKey {
        private final BlockGetter level;
        private final long sceneRevision;
        private final long materialRevision;
        private final GraphPointKey source;
        private final GraphPointKey listener;
        private final RoomProbe sourceProbe;
        private final RoomProbe listenerProbe;
        private final TraceQuality quality;
        private final int hash;

        private TraceCacheKey(
                BlockGetter level,
                long sceneRevision,
                long materialRevision,
                GraphPointKey source,
                GraphPointKey listener,
                RoomProbe sourceProbe,
                RoomProbe listenerProbe,
                TraceQuality quality
        ) {
            this.level = level;
            this.sceneRevision = sceneRevision;
            this.materialRevision = materialRevision;
            this.source = source;
            this.listener = listener;
            this.sourceProbe = sourceProbe;
            this.listenerProbe = listenerProbe;
            this.quality = quality;
            int value = System.identityHashCode(level);
            value = 31 * value + Long.hashCode(sceneRevision);
            value = 31 * value + Long.hashCode(materialRevision);
            value = 31 * value + source.hashCode();
            value = 31 * value + listener.hashCode();
            value = 31 * value + System.identityHashCode(sourceProbe);
            value = 31 * value + System.identityHashCode(listenerProbe);
            value = 31 * value + quality.hashCode();
            this.hash = value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TraceCacheKey key)) {
                return false;
            }
            return level == key.level
                    && sceneRevision == key.sceneRevision
                    && materialRevision == key.materialRevision
                    && source.equals(key.source)
                    && listener.equals(key.listener)
                    && sourceProbe == key.sourceProbe
                    && listenerProbe == key.listenerProbe
                    && quality == key.quality;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private record AirGraphState(
            AirCellKey key,
            AirGraphState previous,
            double distance,
            double priority
    ) {
    }

    private record StructuralStart(BlockPos position, double contactDistance) {
    }

    private record StructuralState(
            BlockPos position,
            StructuralState previous,
            double cost,
            double priority,
            double contactDistance
    ) {
    }

    record StructuralPath(
            float[] bands,
            Vec3 arrivalPoint,
            double pathDistance
    ) {
    }

    private record PropagationGraphNode(
            Vec3 point,
            float apertureAmplitude,
            GraphNodeKind kind
    ) {
    }

    private record PropagationGraphState(
            PropagationGraphNode node,
            PropagationGraphState previous,
            double distance,
            double priority,
            float apertureAmplitude
    ) {
    }

    private record PropagationGraphPath(
            List<Vec3> points,
            float apertureAmplitude,
            boolean clear,
            Vec3 arrivalPoint,
            List<Vec3> arrivalSamples
    ) {
    }

    private record EvaluatedGraphPath(
            float[] bands,
            float score,
            double pathDistance,
            Vec3 arrivalPoint,
            boolean clear
    ) {
    }

    private enum PathKind {
        DIRECT_OR_TRANSMITTED,
        DIFFRACTED,
        STRUCTURAL
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

    record ReflectionResult(
            float[] bands,
            float gain,
            float highFrequencyGain,
            float delay,
            Vec3 pan
    ) {
        private static ReflectionResult silent() {
            return new ReflectionResult(
                    new float[AcousticBands.COUNT],
                    0.0F,
                    1.0F,
                    0.0F,
                    Vec3.ZERO
            );
        }
    }

    /**
     * Unified first-arrival field. Every propagation mode contributes one spectral
     * power, arrival direction and travelled distance together. Output gain, filtering,
     * localization and delay therefore resolve from the same set of physical paths
     * rather than from independently selected direct/diffraction/structure results.
     */
    private static final class ArrivalFieldAccumulator {
        private final PathEnergyAccumulator energy = new PathEnergyAccumulator();
        private Vec3 directionMoment = Vec3.ZERO;
        private double directionalPower;
        private double distancePower;

        private void add(
                PathKind kind,
                float[] amplitudes,
                float powerWeight,
                Vec3 arrivalDirection,
                double pathDistance
        ) {
            energy.add(kind, amplitudes, powerWeight);
            double amplitude = weightedEnergy(amplitudes);
            double power = amplitude * amplitude * Math.max(0.0F, powerWeight);
            if (power <= 1.0E-12) {
                return;
            }
            if (arrivalDirection.lengthSqr() > 1.0E-10) {
                directionMoment = directionMoment.add(
                        arrivalDirection.normalize().scale(power)
                );
            }
            directionalPower += power;
            distancePower += Math.max(0.0, pathDistance) * power;
        }

        private float[] amplitudes() {
            return energy.amplitudes();
        }

        private Vec3 apparentDirection(Vec3 fallback) {
            Vec3 fallbackDirection = fallback.lengthSqr() > 1.0E-10
                    ? fallback.normalize()
                    : new Vec3(0.0, 0.0, 1.0);
            if (directionalPower <= 1.0E-12 || directionMoment.lengthSqr() <= 1.0E-10) {
                return fallbackDirection;
            }
            // The length of the normalized first angular moment is directional
            // coherence. Opposing or broadly distributed arrivals must not have their
            // tiny residual normalized into a seemingly certain, frame-dependent
            // direction. Blend that unresolved part toward the real source direction;
            // a single coherent arrival still retains its full calculated direction.
            Vec3 meanDirection = directionMoment.scale(1.0 / directionalPower);
            double coherence = Mth.clamp(meanDirection.length(), 0.0, 1.0);
            Vec3 resolved = fallbackDirection.scale(1.0 - coherence)
                    .add(meanDirection);
            return resolved.lengthSqr() > 1.0E-10
                    ? resolved.normalize()
                    : fallbackDirection;
        }

        private double propagationDistance(double fallback) {
            return directionalPower > 1.0E-12
                    ? distancePower / directionalPower
                    : fallback;
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
            // The onset estimate samples the same finite incident wavefront as the full
            // solver. That prevents a point-like centre ray from producing a different
            // first-frame gain when a source starts beside an edge or diagonal seam.
            float[] transmission = traceImmediateWavefront(
                    level, source, listener
            );
            for (int band = 0; band < transmission.length; band++) {
                transmission[band] *= airAbsorption(band, distanceMeters);
                transmission[band] *= switch (band / 2) {
                    case 0 -> lowBandGain;
                    case 1 -> midLowBandGain;
                    case 2 -> midHighBandGain;
                    default -> highBandGain;
                };
            }
            float[] airborne = transmission.clone();
            StructuralPath structuralPath = traceStructuralPath(
                    level, source, listener, tuning
            );
            if (structuralPath != null) {
                for (int band = 0; band < transmission.length; band++) {
                    transmission[band] = Mth.clamp(
                            (float) Math.sqrt(
                                    transmission[band] * transmission[band]
                                            + structuralPath.bands()[band]
                                            * structuralPath.bands()[band]
                            ),
                            0.0F,
                            1.0F
                    );
                }
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
            Vec3 apparentPosition = source;
            if (structuralPath != null) {
                float airborneEnergy = weightedEnergy(airborne);
                float structuralEnergy = weightedEnergy(structuralPath.bands());
                Vec3 moment = Vec3.ZERO;
                if (airborneEnergy > 1.0E-6F
                        && source.subtract(listener).lengthSqr() > 1.0E-10) {
                    moment = moment.add(source.subtract(listener).normalize().scale(
                            airborneEnergy * airborneEnergy
                    ));
                }
                if (structuralEnergy > 1.0E-6F
                        && structuralPath.arrivalPoint().subtract(listener).lengthSqr() > 1.0E-10) {
                    moment = moment.add(
                            structuralPath.arrivalPoint().subtract(listener).normalize().scale(
                                    structuralEnergy * structuralEnergy
                            )
                    );
                }
                if (moment.lengthSqr() > 1.0E-10) {
                    apparentPosition = listener.add(
                            moment.normalize().scale(Math.max(1.0, distance))
                    );
                }
            }
            return new AcousticResult(
                    Mth.clamp(
                            transmission[0] * 0.24F + transmission[1] * 0.26F
                                    + transmission[2] * 0.27F + transmission[3] * 0.23F,
                            0.0F,
                            1.0F
                    ),
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
                    EarlyReflection.SILENT,
                    0.0F,
                    distance,
                    apparentPosition,
                    room,
                    RoomImpulseResponse.SILENT
            );
        }
    }

    private static float[] traceImmediateWavefront(
            BlockGetter level,
            Vec3 source,
            Vec3 listener
    ) {
        Vec3 delta = listener.subtract(source);
        double distance = delta.length();
        if (distance < 1.0E-6) {
            return traceTransmission(level, source, listener).bands().clone();
        }

        Vec3 direction = delta.scale(1.0 / distance);
        Vec3 right = direction.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0E-6) {
            right = new Vec3(1.0, 0.0, 0.0);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(direction).normalize();
        return traceFiniteWavefront(
                level, source, listener, direction, right, up, 8
        ).bands().clone();
    }
}
