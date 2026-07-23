package org.macaroon.acousticsystem.client.simulation;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
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
import org.macaroon.acousticsystem.physics.AtmosphericAbsorption;
import org.macaroon.acousticsystem.physics.FluidAcoustics;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntConsumer;

public final class AcousticTracer {
    private static final boolean PROFILE_TRACE = Boolean.getBoolean("acousticsystem.profileTrace")
            || "true".equalsIgnoreCase(System.getenv("ACOUSTICSYSTEM_PROFILE_TRACE"));
    private static final double DIFFRACTION_MARGIN = 0.075;
    private static final Vec3 NEGATIVE_X_NORMAL = new Vec3(-1.0, 0.0, 0.0);
    private static final Vec3 POSITIVE_X_NORMAL = new Vec3(1.0, 0.0, 0.0);
    private static final Vec3 NEGATIVE_Y_NORMAL = new Vec3(0.0, -1.0, 0.0);
    private static final Vec3 POSITIVE_Y_NORMAL = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3 NEGATIVE_Z_NORMAL = new Vec3(0.0, 0.0, -1.0);
    private static final Vec3 POSITIVE_Z_NORMAL = new Vec3(0.0, 0.0, 1.0);

    private static AABB fluidBounds(FluidState state, BlockGetter level, BlockPos position) {
        if (state.isEmpty()) {
            return null;
        }
        // FluidState#getShape follows Minecraft's lowered visual surface. Using it as
        // an acoustic volume leaves a thin air slit above every full water voxel, so a
        // vertical ray in continuous water encounters a fake reflecting interface once
        // per block. Acoustic occupancy follows conserved fluid amount instead: source
        // and falling cells are full, while flowing levels retain a continuous height.
        double height = Mth.clamp(state.getAmount() / 8.0, 0.0, 1.0);
        return new AABB(
                position.getX(),
                position.getY(),
                position.getZ(),
                position.getX() + 1.0,
                position.getY() + height,
                position.getZ() + 1.0
        );
    }
    // EAX exposes one aggregate early-reflection cluster. The tracer still examines all
    // sampled image paths and retains the strongest independent contributions; stopping
    // at the first four valid planes made an echo appear or disappear when candidate
    // ordering changed during small movements.
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
    private static volatile AtmosphereCache atmosphereCache;
    private static final Map<Integer, RoomRayGrid> ROOM_RAY_GRIDS = new ConcurrentHashMap<>();
    private static final Map<AirPathCacheKey, PropagationGraphPath> AIR_PATH_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<AirPathMissCacheKey, Boolean> AIR_PATH_MISS_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<ListenerAirFieldKey, ListenerAirRouteField> LISTENER_AIR_FIELDS =
            new ConcurrentHashMap<>();
    private static final Map<SparsePathCacheKey, List<PropagationGraphPath>> SPARSE_PATH_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<StructuralPathCacheKey, StructuralPath> STRUCTURAL_PATH_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<TransmissionCacheKey, Transmission> TRANSMISSION_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<TraceCacheKey, AcousticResult> TRACE_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<AirPathCacheKey> AIR_PATH_CACHE_ORDER =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<AirPathMissCacheKey> AIR_PATH_MISS_CACHE_ORDER =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ListenerAirFieldKey> LISTENER_AIR_FIELD_ORDER =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<SparsePathCacheKey> SPARSE_PATH_CACHE_ORDER =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<StructuralPathCacheKey> STRUCTURAL_PATH_CACHE_ORDER =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<TransmissionCacheKey> TRANSMISSION_CACHE_ORDER =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<TraceCacheKey> TRACE_CACHE_ORDER =
            new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<TransmissionScratch> TRANSMISSION_SCRATCH = ThreadLocal.withInitial(
            TransmissionScratch::new
    );
    private static final ThreadLocal<BlockPos.MutableBlockPos> WALK_POSITION = ThreadLocal.withInitial(
            BlockPos.MutableBlockPos::new
    );
    private static final ThreadLocal<CollisionQuery> COLLISION_QUERY = ThreadLocal.withInitial(
            CollisionQuery::new
    );
    private static final ThreadLocal<TraceTiming> LAST_TRACE_TIMING = ThreadLocal.withInitial(
            TraceTiming::empty
    );
    private static final ThreadLocal<DiffractionTiming> LAST_DIFFRACTION_TIMING =
            ThreadLocal.withInitial(DiffractionTiming::empty);
    private static final ThreadLocal<AirSearchStats> LAST_AIR_SEARCH =
            ThreadLocal.withInitial(AirSearchStats::none);
    private static final ThreadLocal<DenseAirSearchScratch> DENSE_AIR_SEARCH =
            ThreadLocal.withInitial(DenseAirSearchScratch::new);
    private static final ThreadLocal<SurfaceHit[]> ROOM_SURFACE_HOLDER =
            ThreadLocal.withInitial(() -> new SurfaceHit[1]);

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
                roomProbe.impulseResponse(),
                null,
                null,
                result.propagationGain(),
                result.directionalField()
        );
    }

    public static ImmediateContext captureImmediateContext(
            BlockGetter level,
            Vec3 listener,
            RoomAcoustics room
    ) {
        MediumSample listenerMedium = sampleMedium(level, listener);
        return new ImmediateContext(
                level,
                listener,
                mixedMediumPair(listenerMedium, 0),
                mixedMediumPair(listenerMedium, 2),
                mixedMediumPair(listenerMedium, 4),
                mixedMediumPair(listenerMedium, 6),
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
        long traceStarted = System.nanoTime();
        LAST_DIFFRACTION_TIMING.set(DiffractionTiming.empty());
        long sceneRevision = level instanceof AcousticScene scene
                ? scene.revision()
                : 0L;
        TraceCacheKey traceCacheKey = new TraceCacheKey(
                cacheIdentity(level),
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
            LAST_TRACE_TIMING.set(TraceTiming.cacheHit(System.nanoTime() - traceStarted));
            return cachedTrace;
        }
        EmitterMediumTransfer sourceMedium = solveEmitterBoundary(level, source);
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
        long directFinished = System.nanoTime();
        Transmission center = directWavefront.center();
        // The offset rays sample the finite first Fresnel zone around the geometric ray.
        // Combine their power continuously on both sides of an edge. Switching from the
        // centre ray to an arithmetic-amplitude average when it first became blocked made
        // an otherwise tiny occluder produce an audible step in level.
        float diffractionContribution = 0.0F;
        DiffractionPath diffractionPath = null;
        DiffractionTiming primaryDiffractionTiming = DiffractionTiming.empty();
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
            // Reflection legs may run their own diffraction query later in this trace.
            // Preserve the primary propagation timing now so diagnostics do not report
            // a cheap reflected connector in place of the expensive source path.
            primaryDiffractionTiming = LAST_DIFFRACTION_TIMING.get();
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
        long diffractionFinished = System.nanoTime();
        StructuralPath structuralPath = traceStructuralPath(
                level, source, listener, tuning
        );
        long structureFinished = System.nanoTime();
        float[] directSpectrum = directWavefront.bands().clone();
        applyEmitterMediumResponse(directSpectrum, sourceMedium);
        applyMediumResponse(directSpectrum, listenerMedium);
        applyEmitterMediumResponse(diffractionSpectrum, sourceMedium);
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
                List.of(new DirectionalArrivalField.Arrival(source, 1.0)),
                directDistance
        );
        if (diffractionPath != null) {
            resolvedField.add(
                    PathKind.DIFFRACTED,
                    diffractionSpectrum,
                    1.0F,
                    diffractionPath.arrivals(),
                    diffractionPath.pathDistance()
            );
        }
        if (structuralPath != null) {
            resolvedField.add(
                    PathKind.STRUCTURAL,
                    structuralSpectrum,
                    1.0F,
                    List.of(new DirectionalArrivalField.Arrival(
                            structuralPath.arrivalPoint(), 1.0
                    )),
                    structuralPath.pathDistance()
                            + structuralPath.arrivalPoint().distanceTo(listener)
            );
        }
        float[] geometricSpectrum = resolvedField.amplitudes();
        double propagationDistance = resolvedField.propagationDistance(directDistance);

        ReflectionResult reflections = tuning.reflectionGainScale() <= 1.0E-6F
                ? ReflectionResult.silent()
                : estimateEarlyReflections(
                        level,
                        source,
                        listener,
                        sourceRoomProbe,
                        MAX_EARLY_REFLECTION_PATHS_PER_SOUND,
                        diffractionPath == null || !diffractionPath.multipleBends(),
                        center.travelTimeSeconds()
                );
        long reflectionsFinished = System.nanoTime();
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
        float lowEnergy = geometricSpectrum[0] * 0.24F + geometricSpectrum[1] * 0.26F
                + geometricSpectrum[2] * 0.27F + geometricSpectrum[3] * 0.23F;
        float highEnergy = geometricSpectrum[4] * 0.28F + geometricSpectrum[5] * 0.27F
                + geometricSpectrum[6] * 0.25F + geometricSpectrum[7] * 0.20F;
        // OpenAL's positional direct path exposes one broadband gain and one HF ratio.
        // Anchor that approximation to the physically traced low-band amplitude, then
        // express the remaining spectral tilt through the direct low-pass filter. This
        // keeps every dry component on the source's HRTF-aware path; no dry band is sent
        // through a positionless auxiliary effect slot.
        float directGain = spectralAnchor(geometricSpectrum);
        float highFrequencyGain = Mth.clamp(highEnergy / Math.max(directGain, 0.01F), 0.02F, 1.0F);
        Vec3 apparentDirection = resolvedField.apparentDirection(
                listener,
                source
        );
        Vec3 apparentPosition = listener.add(
                apparentDirection.scale(Math.max(1.0, directDistance))
        );
        RoomAcoustics listenerRoom = listenerRoomProbe.acoustics();
        // The late field is driven by the energy which physically reaches the listener
        // region through any path. There is deliberately no same-room/different-room
        // classification: closing a door, moving past an edge, or adding absorption only
        // changes the path spectra and therefore changes the send continuously.
        float pathCoupling = Mth.clamp(weightedEnergy(fieldSpectrum), 0.0F, 1.0F);
        float reflectedSend = pathCoupling;
        // The listener probe supplies this voice's receiving-field boundary. DSP state
        // is still private to the playback occurrence; only the immutable geometric
        // measurement is reused, so another source cannot reset or redirect this tail.
        RoomAcoustics reverbRoom = listenerRoom;
        RoomImpulseResponse reverbImpulse = listenerRoomProbe.impulseResponse();
        float fieldLow = meanBands(fieldSpectrum, 0, 4);
        float fieldHigh = meanBands(fieldSpectrum, 4, 8);
        float fieldHighFrequencyGain = Mth.clamp(
                fieldHigh / Math.max(fieldLow, 0.01F),
                0.05F,
                1.0F
        );
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
                fieldHighFrequencyGain,
                new EarlyReflection(
                        reflections.gain(),
                        reflections.lowFrequencyGain(),
                        reflections.highFrequencyGain(),
                        reflections.delay(),
                        reflections.pan(),
                        reflections.directionalField()
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
                source,
                sphericalSpreadingGain(tuning.meters(directDistance), tuning),
                resolvedField.directionalField(source)
        );
        AcousticResult published = putBounded(
                TRACE_CACHE, TRACE_CACHE_ORDER, traceCacheKey, result, 512
        );
        long finished = System.nanoTime();
        TraceTiming timing = new TraceTiming(
                false,
                directFinished - traceStarted,
                diffractionFinished - directFinished,
                primaryDiffractionTiming.airNanoseconds(),
                primaryDiffractionTiming.sparseNanoseconds(),
                primaryDiffractionTiming.evaluationNanoseconds(),
                primaryDiffractionTiming.airSearch().mode(),
                primaryDiffractionTiming.airSearch().expanded(),
                structureFinished - diffractionFinished,
                reflectionsFinished - structureFinished,
                finished - reflectionsFinished,
                finished - traceStarted
        );
        LAST_TRACE_TIMING.set(timing);
        if (PROFILE_TRACE) {
            System.out.printf(
                    "acousticTraceMs direct=%.3f diffraction=%.3f structure=%.3f reflections=%.3f assembly=%.3f total=%.3f%n",
                    (directFinished - traceStarted) / 1_000_000.0,
                    (diffractionFinished - directFinished) / 1_000_000.0,
                    (structureFinished - diffractionFinished) / 1_000_000.0,
                    (reflectionsFinished - structureFinished) / 1_000_000.0,
                    (finished - reflectionsFinished) / 1_000_000.0,
                    (finished - traceStarted) / 1_000_000.0
            );
        }
        return published;
    }

    /** Timing for the most recent trace on the calling worker thread. */
    static TraceTiming lastTraceTiming() {
        return LAST_TRACE_TIMING.get();
    }

    record TraceTiming(
            boolean cacheHit,
            long directNanoseconds,
            long diffractionNanoseconds,
            long diffractionAirNanoseconds,
            long diffractionSparseNanoseconds,
            long diffractionEvaluationNanoseconds,
            String diffractionAirMode,
            int diffractionAirExpanded,
            long structureNanoseconds,
            long reflectionsNanoseconds,
            long assemblyNanoseconds,
            long totalNanoseconds
    ) {
        private static TraceTiming empty() {
            return new TraceTiming(
                    false, 0L, 0L, 0L, 0L, 0L, "none", 0,
                    0L, 0L, 0L, 0L
            );
        }

        private static TraceTiming cacheHit(long totalNanoseconds) {
            return new TraceTiming(
                    true, 0L, 0L, 0L, 0L, 0L, "cache", 0,
                    0L, 0L, 0L,
                    totalNanoseconds
            );
        }
    }

    private record DiffractionTiming(
            long airNanoseconds,
            long sparseNanoseconds,
            long evaluationNanoseconds,
            AirSearchStats airSearch
    ) {
        private static DiffractionTiming empty() {
            return new DiffractionTiming(0L, 0L, 0L, AirSearchStats.none());
        }
    }

    private record AirSearchStats(String mode, int expanded) {
        private static AirSearchStats none() {
            return new AirSearchStats("none", 0);
        }
    }

    public static RoomProbe probeRoom(BlockGetter level, Vec3 listener) {
        return probeRoom(
                level, listener,
                AcousticMaterialRegistry.tuning().roomRayCount(),
                true
        );
    }

    static RoomProbe probeSourceRoom(BlockGetter level, Vec3 source) {
        return probeRoom(
                level,
                source,
                AcousticMaterialRegistry.tuning().sourceRoomRayCount(),
                false
        );
    }

    private static RoomProbe probeRoom(
            BlockGetter level,
            Vec3 listener,
            int requestedRayCount,
            boolean parallelRays
    ) {
        MediumSample listenerMedium = sampleMedium(level, listener);
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        RoomRayGrid grid = roomRayGrid(requestedRayCount);
        double probeDistance = tuning.adaptiveRoomProbeDistance();
        SurfaceHit[] hits = new SurfaceHit[grid.directions().length];
        double[] distances = new double[hits.length];
        double[] openingBoundaries = new double[hits.length];
        float[] openingWeights = new float[hits.length];
        OpeningBoundary[] openingModels = new OpeningBoundary[hits.length];
        Arrays.fill(openingBoundaries, Double.POSITIVE_INFINITY);
        IntConsumer castRay = ray -> {
            Vec3 direction = grid.directions()[ray];
            SurfaceHit[] firstHitHolder = ROOM_SURFACE_HOLDER.get();
            firstHitHolder[0] = null;
            SurfaceHit hit = firstAcousticSurface(
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
        };
        if (parallelRays) {
            AcousticWorkerPool.parallelFor(hits.length, castRay);
        } else {
            for (int ray = 0; ray < hits.length; ray++) {
                castRay.accept(ray);
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
            absorption += meanSurfaceAbsorption(hit, 2, 6) * raySurfaceArea;
            lowAbsorption += meanSurfaceAbsorption(hit, 0, 2) * raySurfaceArea;
            scattering += hit.material().scattering() * raySurfaceArea;

        }

        if (returnedWeight < 1.0E-4) {
            // With no returning boundary energy, water is still an open field rather
            // than a synthetic enclosed room. Medium propagation is handled by the
            // path solver, not by replacing outdoors with a canned reverb preset.
            RoomAcoustics acoustics = applyTuning(RoomAcoustics.OUTDOORS, tuning);
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
        LateReverbTracer.Estimate lateReverb = tuning.reverbSendScale() <= 1.0E-6F
                ? LateReverbTracer.Estimate.silent()
                : LateReverbTracer.trace(
                        level,
                        listener,
                        grid.directions(),
                        hits,
                        tuning,
                        listenerMedium.profile(),
                        listenerMedium.material(),
                        listenerMedium.weight(),
                        Mth.clamp(
                                leakage.reverberationTimeSeconds(),
                                0.12F,
                                tuning.maxDecayTime()
                        ),
                        parallelRays
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
                Mth.clamp(
                        lateReverb.decayTime(),
                        0.12F,
                        tuning.maxDecayTime()
                ),
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
        // LateReverbTracer already used the fluid sound speed and per-band propagation
        // loss. Applying a second medium preset here used to shorten the measured
        // delays again and overwrite the traced decay/diffusion, producing a cave-like
        // underwater tail.
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
        long diffractionStarted = System.nanoTime();
        LAST_AIR_SEARCH.set(AirSearchStats.none());
        double directDistance = source.distanceTo(listener);
        if (directDistance < 1.0E-6 || candidateBudget <= 0
                || tuning.diffractionGainScale() <= 1.0E-6F) {
            LAST_DIFFRACTION_TIMING.set(DiffractionTiming.empty());
            return new DiffractionPath(
                    new float[AcousticBands.COUNT], directDistance, directDistance,
                    List.of(), false, false
            );
        }

        // Traverse the hierarchy in spatial-complexity order.  Portals, visible edges
        // and their A* links are an exact sparse representation whenever they prove a
        // clear path.  Expanding the full air-voxel volume first made a simple doorway
        // pay O(r^3) work before this O(edges log nodes) proof was even attempted.
        //
        // The air graph is still the complete connectivity fallback for mazes and
        // irregular caves that the finite sparse graph cannot prove.  Both routes feed
        // the same frequency-dependent diffraction evaluator, so this changes search
        // representation and cost, not the propagation law or an audible cutoff.
        boolean hasPortalEvidence = !sourceOpenings.isEmpty()
                || !listenerOpenings.isEmpty();
        long airGraphNanoseconds = 0L;
        long sparseGraphNanoseconds = 0L;
        long phaseStarted = System.nanoTime();
        VerifiedAirBackbone verifiedAir = allowAirCellGraph
                ? verifiedCachedAirPath(
                        level, source, listener, hasPortalEvidence
                )
                : null;
        airGraphNanoseconds += System.nanoTime() - phaseStarted;
        PropagationGraphPath verifiedAirBackbone = verifiedAir == null
                ? null : verifiedAir.path();
        // Portal samples carry the multi-exit arrival field used for localization.  In
        // that case recompute the tiny sparse graph and retain the air route only as a
        // topology backbone; using one cached voxel path alone would arbitrarily pick
        // a side and make the apparent direction jump while walking past an opening.
        boolean reusableVerifiedBackbone = verifiedAirBackbone != null
                && (!hasPortalEvidence
                        || verifiedAir.listenerUnchanged()
                        || verifiedAirBackbone.points().size() > 3);
        List<PropagationGraphPath> paths = List.of();
        phaseStarted = System.nanoTime();
        if (!reusableVerifiedBackbone) {
            List<PropagationGraphPath> hierarchicalPaths = List.of();
            if (allowAirCellGraph && level instanceof AcousticScene scene) {
                hierarchicalPaths = searchScenePortalGraph(
                        scene, source, listener,
                        Math.max(1, tuning.diffractionMaxPaths())
                );
            }
            // The portal hierarchy proves long-range connectivity, but its voxel
            // corridor is not a substitute for the actual diffraction silhouette.
            // Always refine the first arrivals through the visibility/edge graph;
            // otherwise even a single wall corner is localized at an arbitrary voxel
            // turn and can lose its UTD edge contribution.
            boolean firstOrderRefinement = hierarchicalPaths.stream().anyMatch(
                    path -> path.points().size() <= 3
            );
            List<PropagationGraphPath> edgePaths =
                    hierarchicalPaths.isEmpty() || firstOrderRefinement
                            ? searchPropagationGraph(
                            level,
                            source,
                            listener,
                            obstacle,
                            sourceOpenings,
                            listenerOpenings,
                            candidateBudget,
                            tuning,
                            !hierarchicalPaths.isEmpty()
                    ) : List.of();
            if (hierarchicalPaths.isEmpty()) {
                paths = edgePaths;
            } else if (edgePaths.isEmpty()) {
                paths = hierarchicalPaths;
            } else {
                ArrayList<PropagationGraphPath> combined = new ArrayList<>(
                        edgePaths.size() + hierarchicalPaths.size()
                );
                combined.addAll(edgePaths);
                combined.addAll(hierarchicalPaths);
                paths = List.copyOf(combined);
            }
        }
        sparseGraphNanoseconds += System.nanoTime() - phaseStarted;
        boolean edgeGraphReachedListener = paths.stream().anyMatch(
                PropagationGraphPath::clear
        );
        phaseStarted = System.nanoTime();
        PropagationGraphPath airPath = reusableVerifiedBackbone
                ? verifiedAirBackbone
                : allowAirCellGraph && !edgeGraphReachedListener
                        ? searchAirCellGraph(level, source, listener, candidateBudget)
                        : null;
        airGraphNanoseconds += System.nanoTime() - phaseStarted;
        long evaluationStarted = System.nanoTime();
        if (paths.isEmpty() && airPath == null) {
            LAST_DIFFRACTION_TIMING.set(new DiffractionTiming(
                    airGraphNanoseconds,
                    sparseGraphNanoseconds,
                    0L,
                    LAST_AIR_SEARCH.get()
            ));
            return new DiffractionPath(
                    new float[AcousticBands.COUNT], directDistance, directDistance,
                    List.of(), false, false
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
            long finished = System.nanoTime();
            LAST_DIFFRACTION_TIMING.set(new DiffractionTiming(
                    airGraphNanoseconds,
                    sparseGraphNanoseconds,
                    finished - evaluationStarted,
                    LAST_AIR_SEARCH.get()
            ));
            return new DiffractionPath(
                    new float[AcousticBands.COUNT], directDistance, directDistance,
                    List.of(), false, false
            );
        }
        if (evaluated.isEmpty()) {
            evaluated.add(evaluatedAirPath);
            evaluatedAirPath = null;
        }
        evaluated.sort(Comparator.comparingDouble(EvaluatedGraphPath::score).reversed());
        evaluated = mergeCorrelatedGraphPaths(evaluated, listener, tuning);

        float[] power = new float[AcousticBands.COUNT];
        List<DirectionalArrivalField.Arrival> directionalArrivals = new ArrayList<>();
        double bestDistance = evaluated.get(0).pathDistance();
        double bestAirDistance = evaluated.get(0).airDistance();
        // The air-cell solution is a topology-stable estimate of the same first-arrival
        // field, not another independent wave. Keep it as an energy floor rather than
        // power-summing it with edge paths. Its single A* arrival is deliberately not
        // added to the direction moment: with two equal exits, that route represents
        // only one arbitrary member of a physically symmetric arrival field.
        if (evaluatedAirPath != null) {
            if (evaluatedAirPath.pathDistance() < bestDistance) {
                bestDistance = evaluatedAirPath.pathDistance();
                bestAirDistance = evaluatedAirPath.airDistance();
            }
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
            double directionWeight = path.score() * path.score();
            if (directionWeight > 1.0E-10
                    && path.arrivalPoint().subtract(listener).lengthSqr() > 1.0E-10) {
                directionalArrivals.add(new DirectionalArrivalField.Arrival(
                        path.arrivalPoint(), directionWeight
                ));
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
        DiffractionPath result = new DiffractionPath(
                combined, bestDistance, bestAirDistance,
                List.copyOf(directionalArrivals), hasClearPath,
                multipleDiffractionBackbone
        );
        long finished = System.nanoTime();
        LAST_DIFFRACTION_TIMING.set(new DiffractionTiming(
                airGraphNanoseconds,
                sparseGraphNanoseconds,
                finished - evaluationStarted,
                LAST_AIR_SEARCH.get()
        ));
        if (PROFILE_TRACE) {
            System.out.printf(
                    "  diffractionMs air=%.3f sparse=%.3f evaluate=%.3f total=%.3f%n",
                    airGraphNanoseconds / 1_000_000.0,
                    sparseGraphNanoseconds / 1_000_000.0,
                    (finished - evaluationStarted) / 1_000_000.0,
                    (finished - diffractionStarted) / 1_000_000.0
            );
        }
        return result;
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
            double lobePower = lobe.score() * lobe.score();
            double candidatePower = candidate.score() * candidate.score();
            Vec3 lobeArrival = lobe.arrivalPoint().subtract(listener);
            Vec3 candidateArrival = candidate.arrivalPoint().subtract(listener);
            Vec3 arrivalMoment = lobeArrival.normalize().scale(lobePower).add(
                    candidateArrival.normalize().scale(candidatePower)
            );
            double combinedArrivalPower = lobePower + candidatePower;
            Vec3 mergedArrival = representative.arrivalPoint();
            if (combinedArrivalPower > 1.0E-12
                    && arrivalMoment.lengthSqr() > 1.0E-12) {
                double radius = (lobeArrival.length() * lobePower
                        + candidateArrival.length() * candidatePower)
                        / combinedArrivalPower;
                mergedArrival = listener.add(arrivalMoment.normalize().scale(radius));
            }
            lobes.set(correlated, new EvaluatedGraphPath(
                    envelope,
                    weightedEnergy(envelope),
                    Math.min(lobe.pathDistance(), candidate.pathDistance()),
                    representative.airDistance(),
                    mergedArrival,
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
            AcousticTuning tuning,
            boolean hierarchyAlreadyConnected
    ) {
        SparsePathCacheKey cacheKey = new SparsePathCacheKey(
                cacheIdentity(level),
                level instanceof AcousticScene scene ? scene.revision() : 0L,
                airCellKey(source),
                airCellKey(listener),
                candidateBudget,
                tuning
        );
        List<PropagationGraphPath> cached = SPARSE_PATH_CACHE.get(cacheKey);
        if (cached != null) {
            List<PropagationGraphPath> adapted = adaptCachedSparsePaths(
                    level, cached, source, listener
            );
            if (!adapted.isEmpty()) {
                return adapted;
            }
            SPARSE_PATH_CACHE.remove(cacheKey, cached);
        }
        List<PropagationGraphPath> neighboring = adaptNeighboringCachedSparsePaths(
                level, cacheKey, source, listener
        );
        if (!neighboring.isEmpty()) {
            return putBounded(
                    SPARSE_PATH_CACHE,
                    SPARSE_PATH_CACHE_ORDER,
                    cacheKey,
                    neighboring,
                    512
            );
        }
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
        Object2DoubleOpenHashMap<GraphPointKey> bestDistanceByNode =
                new Object2DoubleOpenHashMap<>();
        bestDistanceByNode.defaultReturnValue(Double.POSITIVE_INFINITY);
        PropagationGraphNode start = new PropagationGraphNode(source, 1.0F, GraphNodeKind.SOURCE);
        frontier.add(new PropagationGraphState(
                start, null, 0.0, source.distanceTo(listener), 1.0F
        ));
        bestDistanceByNode.put(graphPointKey(source), 0.0);

        List<PropagationGraphPath> clearPaths = new ArrayList<>();
        int expanded = 0;
        // When the persistent portal graph has already proved connectivity, this graph
        // is a local silhouette refinement layer rather than a second global pathfinder.
        // Its failure cannot delete the hierarchical path, so bounded local expansion
        // removes duplicate maze traversal without imposing an acoustic distance limit.
        int maximumExpansions = hierarchyAlreadyConnected
                ? 4
                : Math.max(16, candidateBudget * 5);
        int maximumCompletePaths = Math.max(4, tuning.diffractionMaxPaths() * 2);
        while (!frontier.isEmpty() && expanded < maximumExpansions) {
            PropagationGraphState state = frontier.poll();
            GraphPointKey stateKey = graphPointKey(state.node().point());
            if (state.distance() > bestDistanceByNode.getDouble(stateKey) + 1.0E-7) {
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
        if (clearPaths.isEmpty()) {
            return clearPaths;
        }
        return putBounded(
                SPARSE_PATH_CACHE,
                SPARSE_PATH_CACHE_ORDER,
                cacheKey,
                List.copyOf(clearPaths),
                512
        );
    }

    /**
     * Repairs only the source/listener connectors of an unchanged interior graph path.
     * Every connector is tested against the current immutable scene and the exact
     * sub-block endpoint. A blocked connector rejects the cached route and triggers the
     * full portal/visibility/edge A* above; no stale path is accepted by age or by a
     * movement threshold.
     */
    private static List<PropagationGraphPath> adaptCachedSparsePaths(
            BlockGetter level,
            List<PropagationGraphPath> cached,
            Vec3 source,
            Vec3 listener
    ) {
        List<PropagationGraphPath> adapted = new ArrayList<>(cached.size());
        for (PropagationGraphPath path : cached) {
            List<Vec3> points = path.points();
            if (points.size() < 2) {
                continue;
            }
            Vec3 firstInterior = points.size() > 2 ? points.get(1) : listener;
            Vec3 lastInterior = points.size() > 2
                    ? points.get(points.size() - 2)
                    : source;
            if (firstCollisionBounds(level, source, firstInterior) != null
                    || firstCollisionBounds(level, lastInterior, listener) != null) {
                continue;
            }
            List<Vec3> repaired = new ArrayList<>(points);
            repaired.set(0, source);
            repaired.set(repaired.size() - 1, listener);
            List<Vec3> arrivalSamples = points.size() > 2
                    ? List.of(lastInterior)
                    : List.of(source);
            adapted.add(new PropagationGraphPath(
                    List.copyOf(repaired),
                    path.apertureAmplitude(),
                    path.clear(),
                    lastInterior,
                    arrivalSamples
            ));
        }
        return adapted;
    }

    /**
     * Repairs one changed endpoint of a neighboring sparse graph cell.  The unchanged
     * portal/edge sequence is retained only when both new connectors remain visible in
     * the current immutable scene.
     */
    private static List<PropagationGraphPath> adaptNeighboringCachedSparsePaths(
            BlockGetter level,
            SparsePathCacheKey requested,
            Vec3 source,
            Vec3 listener
    ) {
        List<PropagationGraphPath> best = List.of();
        double bestEndpointDistance = Double.POSITIVE_INFINITY;
        AirCellKey start = requested.source();
        AirCellKey goal = requested.listener();
        for (int endpoint = 0; endpoint < 2; endpoint++) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) {
                            continue;
                        }
                        AirCellKey candidateSource = endpoint == 0
                                ? new AirCellKey(
                                        start.x() + x,
                                        start.y() + y,
                                        start.z() + z
                                )
                                : start;
                        AirCellKey candidateListener = endpoint == 1
                                ? new AirCellKey(
                                        goal.x() + x,
                                        goal.y() + y,
                                        goal.z() + z
                                )
                                : goal;
                        List<PropagationGraphPath> candidate = SPARSE_PATH_CACHE.get(
                                new SparsePathCacheKey(
                                        requested.level(),
                                        requested.sceneRevision(),
                                        candidateSource,
                                        candidateListener,
                                        requested.candidateBudget(),
                                        requested.tuning()
                                )
                        );
                        if (candidate == null) {
                            continue;
                        }
                        List<PropagationGraphPath> adapted = adaptCachedSparsePaths(
                                level, candidate, source, listener
                        );
                        if (adapted.isEmpty()) {
                            continue;
                        }
                        PropagationGraphPath representative = candidate.get(0);
                        Vec3 priorEndpoint = endpoint == 0
                                ? representative.points().get(0)
                                : representative.points().get(
                                        representative.points().size() - 1
                                );
                        double endpointDistance = priorEndpoint.distanceToSqr(
                                endpoint == 0 ? source : listener
                        );
                        if (endpointDistance + 1.0E-9 < bestEndpointDistance
                                || Math.abs(endpointDistance - bestEndpointDistance)
                                        <= 1.0E-9
                                        && adapted.size() > best.size()) {
                            bestEndpointDistance = endpointDistance;
                            best = adapted;
                        }
                    }
                }
            }
        }
        return best.isEmpty() ? best : List.copyOf(best);
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
        LAST_AIR_SEARCH.set(new AirSearchStats("cold", 0));
        AirPathCacheKey cacheKey = airPathCacheKey(level, source, listener);
        PropagationGraphPath cached = AIR_PATH_CACHE.get(cacheKey);
        if (cached != null) {
            PropagationGraphPath adapted = adaptCachedAirPath(
                    level, cached, source, listener
            );
            if (adapted != null) {
                LAST_AIR_SEARCH.set(new AirSearchStats("pair-cache", 0));
                return adapted;
            }
            AIR_PATH_CACHE.remove(cacheKey, cached);
        }
        List<AirCellKey> startKeys = airEndpointCells(level, source);
        List<AirCellKey> goalKeys = airEndpointCells(level, listener);
        if (startKeys.isEmpty() || goalKeys.isEmpty()) {
            LAST_AIR_SEARCH.set(new AirSearchStats("no-endpoint", 0));
            return null;
        }
        if (airComponentsProveDisconnection(level, startKeys, goalKeys)) {
            LAST_AIR_SEARCH.set(new AirSearchStats("component-disconnected", 0));
            return null;
        }
        AirPathMissCacheKey missCacheKey = new AirPathMissCacheKey(
                cacheKey,
                airSeedSignature(airCellKey(source), startKeys),
                airSeedSignature(airCellKey(listener), goalKeys)
        );
        if (AIR_PATH_MISS_CACHE.containsKey(missCacheKey)) {
            LAST_AIR_SEARCH.set(new AirSearchStats("miss-cache", 0));
            return null;
        }

        // A moving emitter changes only the first connector.  Repair that proven
        // endpoint before expanding the listener field; reverse flood expansion is
        // useful for genuinely new branches but is needless work for the common
        // entity/block-sound movement case.  Listener-side repairs remain below the
        // shared-field lookup because arrival-direction continuity belongs to the
        // listener-rooted field.
        VerifiedAirBackbone sourceRepair = adaptNeighboringSourceCachedAirPath(
                level, cacheKey, source, listener
        );
        if (sourceRepair != null) {
            putBounded(
                    AIR_PATH_CACHE, AIR_PATH_CACHE_ORDER,
                    cacheKey, sourceRepair.path(), 2048
            );
            LAST_AIR_SEARCH.set(new AirSearchStats("source-repair", 0));
            return sourceRepair.path();
        }

        ListenerAirFieldKey fieldKey = new ListenerAirFieldKey(
                cacheKey.level(),
                cacheKey.sceneRevision(),
                cacheKey.listener(),
                airSeedSignature(cacheKey.listener(), goalKeys)
        );
        ListenerAirRouteField listenerField = LISTENER_AIR_FIELDS.get(fieldKey);
        if (listenerField != null && listenerField.hasBackbone()) {
            long sharedStarted = System.nanoTime();
            List<Long> sharedCells = listenerField.connect(
                    level, source, startKeys, candidateBudget
            );
            long sharedConnected = System.nanoTime();
            if (sharedCells != null) {
                PropagationGraphPath shared = buildAirPropagationPath(
                        level, source, listener, sharedCells, listenerField
                );
                if (PROFILE_TRACE) {
                    System.out.printf(
                            "    sharedAir connect=%.4f ms build=%.4f ms cells=%d%n",
                            (sharedConnected - sharedStarted) / 1_000_000.0,
                            (System.nanoTime() - sharedConnected) / 1_000_000.0,
                            sharedCells.size()
                    );
                }
                if (shared != null) {
                    putBounded(
                            AIR_PATH_CACHE, AIR_PATH_CACHE_ORDER,
                            cacheKey, shared, 2048
                    );
                    return shared;
                }
            }
        }
        // A one-cell endpoint move already has a fully validated interior route in the
        // per-pair cache. Repair that connector before waiting for a newly rooted shared
        // field; otherwise every voice stalls behind the first cold field build whenever
        // the listener crosses a voxel boundary.
        PropagationGraphPath neighboring = adaptNeighboringCachedAirPath(
                level, cacheKey, source, listener
        );
        if (neighboring != null) {
            putBounded(
                    AIR_PATH_CACHE, AIR_PATH_CACHE_ORDER,
                    cacheKey, neighboring, 2048
            );
            LAST_AIR_SEARCH.set(new AirSearchStats("neighbor-repair", 0));
            return neighboring;
        }
        if (listenerField == null) {
            ListenerAirRouteField inherited = neighboringListenerAirField(fieldKey);
            ListenerAirRouteField created = new ListenerAirRouteField(
                    listener, goalKeys, inherited
            );
            ListenerAirRouteField raced = LISTENER_AIR_FIELDS.putIfAbsent(fieldKey, created);
            listenerField = raced == null ? created : raced;
            if (raced == null) {
                LISTENER_AIR_FIELD_ORDER.add(fieldKey);
                while (LISTENER_AIR_FIELDS.size() > 8) {
                    ListenerAirFieldKey oldest = LISTENER_AIR_FIELD_ORDER.poll();
                    if (oldest == null) {
                        break;
                    }
                    LISTENER_AIR_FIELDS.remove(oldest);
                }
            }
        }
        boolean initialFieldBuilder = false;
        if (!listenerField.hasBackbone()) {
            initialFieldBuilder = listenerField.claimBackboneBuild();
            if (!initialFieldBuilder) {
                listenerField.awaitBackboneBuild();
                if (listenerField.hasBackbone()) {
                    List<Long> sharedCells = listenerField.connect(
                            level, source, startKeys, candidateBudget
                    );
                    if (sharedCells != null) {
                        PropagationGraphPath shared = buildAirPropagationPath(
                                level, source, listener, sharedCells, listenerField
                        );
                        if (shared != null) {
                            putBounded(
                                    AIR_PATH_CACHE, AIR_PATH_CACHE_ORDER,
                                    cacheKey, shared, 2048
                            );
                            return shared;
                        }
                    }
                }
                initialFieldBuilder = listenerField.claimBackboneBuild();
            }
        }
        if (level instanceof AcousticScene scene) {
            DenseAirSearchResult dense = searchDenseAirGraph(
                    scene, source, listener, startKeys, goalKeys,
                    candidateBudget
            );
            LAST_AIR_SEARCH.set(new AirSearchStats(
                    dense.path() == null ? "dense-inconclusive" : "dense-connected",
                    dense.expanded()
            ));
            if (dense.path() != null) {
                listenerField.register(dense.path());
                PropagationGraphPath densePath = buildAirPropagationPath(
                        level, source, listener, dense.path(), listenerField
                );
                if (densePath != null) {
                    AIR_PATH_MISS_CACHE.remove(missCacheKey);
                    return putBounded(
                            AIR_PATH_CACHE, AIR_PATH_CACHE_ORDER,
                            cacheKey, densePath, 2048
                    );
                }
            }
        }
        AirGraphHeap forwardFrontier = new AirGraphHeap(1024);
        AirGraphHeap backwardFrontier = new AirGraphHeap(1024);
        Long2DoubleOpenHashMap forwardDistance = new Long2DoubleOpenHashMap(
                Math.max(1024, candidateBudget * 128)
        );
        Long2DoubleOpenHashMap backwardDistance = new Long2DoubleOpenHashMap(
                Math.max(1024, candidateBudget * 128)
        );
        forwardDistance.defaultReturnValue(Double.POSITIVE_INFINITY);
        backwardDistance.defaultReturnValue(Double.POSITIVE_INFINITY);
        Long2LongOpenHashMap forwardPrevious = new Long2LongOpenHashMap();
        Long2LongOpenHashMap backwardPrevious = new Long2LongOpenHashMap();
        forwardPrevious.defaultReturnValue(Long.MIN_VALUE);
        backwardPrevious.defaultReturnValue(Long.MIN_VALUE);
        for (AirCellKey startKey : startKeys) {
            long packedStart = packAirCell(startKey);
            double distance = distanceFromCellCenter(
                    startKey.x(), startKey.y(), startKey.z(), source
            );
            if (distance < forwardDistance.get(packedStart)) {
                forwardFrontier.add(
                        packedStart,
                        distance,
                        distance + endpointGraphHeuristic(
                                startKey.x(), startKey.y(), startKey.z(),
                                goalKeys, listener
                        )
                );
                forwardDistance.put(packedStart, distance);
                forwardPrevious.put(packedStart, Long.MIN_VALUE);
            }
        }
        for (AirCellKey goalKey : goalKeys) {
            long packedGoal = packAirCell(goalKey);
            double distance = distanceFromCellCenter(
                    goalKey.x(), goalKey.y(), goalKey.z(), listener
            );
            if (distance < backwardDistance.get(packedGoal)) {
                backwardFrontier.add(
                        packedGoal,
                        distance,
                        distance + endpointGraphHeuristic(
                                goalKey.x(), goalKey.y(), goalKey.z(),
                                startKeys, source
                        )
                );
                backwardDistance.put(packedGoal, distance);
                backwardPrevious.put(packedGoal, Long.MIN_VALUE);
            }
        }

        int expanded = 0;
        int maximumExpansions = Mth.clamp(
                (int) Math.ceil(source.distanceTo(listener)) * candidateBudget * 32,
                8192,
                65536
        );
        double bestMeetingDistance = Double.POSITIVE_INFINITY;
        long meetingCell = Long.MIN_VALUE;
        boolean expandForward = true;
        while (!forwardFrontier.isEmpty()
                && !backwardFrontier.isEmpty()
                && expanded < maximumExpansions) {
            if (bestMeetingDistance < Double.POSITIVE_INFINITY
                    && (forwardFrontier.peekPriority() >= bestMeetingDistance
                    || backwardFrontier.peekPriority() >= bestMeetingDistance)) {
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
            AirGraphHeap frontier = forward
                    ? forwardFrontier
                    : backwardFrontier;
            Long2DoubleOpenHashMap ownDistance = forward
                    ? forwardDistance
                    : backwardDistance;
            Long2DoubleOpenHashMap otherDistance = forward
                    ? backwardDistance
                    : forwardDistance;
            Long2LongOpenHashMap ownPrevious = forward
                    ? forwardPrevious
                    : backwardPrevious;
            List<AirCellKey> oppositeSeeds = forward ? goalKeys : startKeys;
            Vec3 oppositeEndpoint = forward ? listener : source;
            long packedState = frontier.poll();
            double stateDistance = frontier.lastDistance();
            if (stateDistance > ownDistance.get(packedState) + 1.0E-7) {
                continue;
            }
            double oppositeStateDistance = otherDistance.get(packedState);
            if (oppositeStateDistance < Double.POSITIVE_INFINITY) {
                double meetingDistance = stateDistance + oppositeStateDistance;
                if (meetingDistance < bestMeetingDistance) {
                    bestMeetingDistance = meetingDistance;
                    meetingCell = packedState;
                }
            }
            expanded++;
            int stateX = BlockPos.getX(packedState);
            int stateY = BlockPos.getY(packedState);
            int stateZ = BlockPos.getZ(packedState);
            boolean fromAir = isAirCell(level, stateX, stateY, stateZ);
            for (int[] offset : AIR_GRAPH_NEIGHBORS) {
                int nextX = stateX + offset[0];
                int nextY = stateY + offset[1];
                int nextZ = stateZ + offset[2];
                boolean nextAir = isAirCell(level, nextX, nextY, nextZ);
                // Two face-neighboring air voxels have an unobstructed segment by
                // construction. Only partial collision cells need the more expensive
                // shape query; this is the common-case hot path in long caves.
                if (!fromAir || !nextAir) {
                    Vec3 from = new Vec3(
                            stateX + 0.5, stateY + 0.5, stateZ + 0.5
                    );
                    Vec3 next = new Vec3(
                            nextX + 0.5, nextY + 0.5, nextZ + 0.5
                    );
                    if (pointInsideCollision(level, next)
                            || firstCollisionBounds(level, from, next) != null) {
                        continue;
                    }
                }
                double distance = stateDistance + 1.0;
                long packedNext = BlockPos.asLong(nextX, nextY, nextZ);
                if (distance + 1.0E-7 >= ownDistance.get(packedNext)) {
                    continue;
                }
                ownDistance.put(packedNext, distance);
                ownPrevious.put(packedNext, packedState);
                frontier.add(
                        packedNext,
                        distance,
                        distance + endpointGraphHeuristic(
                                nextX, nextY, nextZ,
                                oppositeSeeds, oppositeEndpoint
                        )
                );
                double oppositeDistance = otherDistance.get(packedNext);
                if (oppositeDistance < Double.POSITIVE_INFINITY) {
                    double meetingDistance = distance + oppositeDistance;
                    if (meetingDistance < bestMeetingDistance) {
                        bestMeetingDistance = meetingDistance;
                        meetingCell = packedNext;
                    }
                }
            }
        }
        if (PROFILE_TRACE) {
            System.out.printf(
                    "    airGraph expanded=%d limit=%d connected=%s%n",
                    expanded, maximumExpansions, meetingCell != Long.MIN_VALUE
            );
        }
        LAST_AIR_SEARCH.set(new AirSearchStats(
                meetingCell != Long.MIN_VALUE
                        ? "full-connected"
                        : expanded >= maximumExpansions
                        ? "full-limit"
                        : "full-exhausted",
                expanded
        ));
        if (meetingCell == Long.MIN_VALUE) {
            // Only an exhausted frontier proves disconnection. Reaching the bounded
            // expansion capacity is inconclusive and must remain eligible for another
            // exact endpoint/scene query.
            if (forwardFrontier.isEmpty() || backwardFrontier.isEmpty()) {
                putBounded(
                        AIR_PATH_MISS_CACHE,
                        AIR_PATH_MISS_CACHE_ORDER,
                        missCacheKey,
                        Boolean.TRUE,
                        512
                );
            }
            if (initialFieldBuilder) {
                listenerField.finishBackboneBuild();
            }
            return null;
        }
        ArrayDeque<Long> reversed = new ArrayDeque<>();
        for (long cell = meetingCell;
             cell != Long.MIN_VALUE;
             cell = forwardPrevious.get(cell)) {
            reversed.addFirst(cell);
        }
        List<Long> cellPath = new ArrayList<>(reversed.size() + 8);
        cellPath.addAll(reversed);
        for (long cell = backwardPrevious.get(meetingCell);
             cell != Long.MIN_VALUE;
             cell = backwardPrevious.get(cell)) {
            cellPath.add(cell);
        }
        listenerField.register(cellPath);
        PropagationGraphPath result = buildAirPropagationPath(
                level, source, listener, cellPath, listenerField
        );
        if (result == null) {
            if (initialFieldBuilder) {
                listenerField.finishBackboneBuild();
            }
            return null;
        }
        AIR_PATH_MISS_CACHE.remove(missCacheKey);
        return putBounded(
                AIR_PATH_CACHE, AIR_PATH_CACHE_ORDER, cacheKey, result, 2048
        );
    }

    private static List<PropagationGraphPath> searchScenePortalGraph(
            AcousticScene scene,
            Vec3 source,
            Vec3 listener,
            int maximumPaths
    ) {
        List<AirCellKey> starts = airEndpointCells(scene, source);
        List<AirCellKey> goals = airEndpointCells(scene, listener);
        if (starts.isEmpty() || goals.isEmpty()) {
            return List.of();
        }
        List<PropagationGraphPath> result = new ArrayList<>();
        for (AirCellKey start : starts) {
            for (AirCellKey goal : goals) {
                List<List<Long>> routes = scene.portalPaths(
                        start.x(), start.y(), start.z(),
                        goal.x(), goal.y(), goal.z(),
                        maximumPaths
                );
                for (List<Long> route : routes) {
                    PropagationGraphPath path = buildAirPropagationPath(
                            scene, source, listener, route, null
                    );
                    if (path != null) {
                        result.add(path);
                        if (result.size() >= maximumPaths) {
                            return List.copyOf(result);
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static DenseAirSearchResult searchDenseAirGraph(
            AcousticScene scene,
            Vec3 source,
            Vec3 listener,
            List<AirCellKey> startKeys,
            List<AirCellKey> goalKeys,
            int candidateBudget
    ) {
        DenseAirSearchScratch scratch = DENSE_AIR_SEARCH.get();
        boolean singleSeed = startKeys.size() == 1 && goalKeys.size() == 1;
        scratch.begin(scene.cellCapacity(), !singleSeed);
        try {
            return searchDenseAirGraphInitialized(
                    scene, source, listener, startKeys, goalKeys,
                    candidateBudget, scratch, singleSeed
            );
        } finally {
            scratch.end();
        }
    }

    private static DenseAirSearchResult searchDenseAirGraphInitialized(
            AcousticScene scene,
            Vec3 source,
            Vec3 listener,
            List<AirCellKey> startKeys,
            List<AirCellKey> goalKeys,
            int candidateBudget,
            DenseAirSearchScratch scratch,
            boolean singleSeed
    ) {
        DenseEndpointHeuristic goalHeuristic = DenseEndpointHeuristic.from(
                goalKeys, listener
        );
        DenseEndpointHeuristic startHeuristic = DenseEndpointHeuristic.from(
                startKeys, source
        );
        if (singleSeed) {
            return searchDenseSingleSeed(
                    scene, source, listener,
                    startKeys.get(0), goalKeys.get(0),
                    candidateBudget, scratch
            );
        }
        for (AirCellKey start : startKeys) {
            int id = scene.airCellIndex(start.x(), start.y(), start.z());
            if (id < 0) {
                continue;
            }
            double distance = distanceFromCellCenter(
                    start.x(), start.y(), start.z(), source
            );
            if (distance < scratch.forwardDistance(id)) {
                scratch.setForward(id, distance, -1);
                scratch.forwardFrontier.add(
                        id, distance,
                        distance + goalHeuristic.distance(
                                start.x(), start.y(), start.z()
                        )
                );
            }
        }
        for (AirCellKey goal : goalKeys) {
            int id = scene.airCellIndex(goal.x(), goal.y(), goal.z());
            if (id < 0) {
                continue;
            }
            double distance = distanceFromCellCenter(
                    goal.x(), goal.y(), goal.z(), listener
            );
            if (distance < scratch.backwardDistance(id)) {
                scratch.setBackward(id, distance, -1);
                scratch.backwardFrontier.add(
                        id, distance,
                        distance + startHeuristic.distance(
                                goal.x(), goal.y(), goal.z()
                        )
                );
            }
        }
        if (scratch.forwardFrontier.isEmpty()
                || scratch.backwardFrontier.isEmpty()) {
            return new DenseAirSearchResult(null, 0);
        }
        int maximumExpansions = Mth.clamp(
                (int) Math.ceil(source.distanceTo(listener))
                        * candidateBudget * 32,
                8192,
                65536
        );
        double bestMeetingDistance = Double.POSITIVE_INFINITY;
        int meeting = -1;
        int expanded = 0;
        boolean expandForward = true;
        while (!scratch.forwardFrontier.isEmpty()
                && !scratch.backwardFrontier.isEmpty()
                && expanded < maximumExpansions) {
            if (bestMeetingDistance < Double.POSITIVE_INFINITY
                    && (scratch.forwardFrontier.peekPriority() >= bestMeetingDistance
                    || scratch.backwardFrontier.peekPriority() >= bestMeetingDistance)) {
                break;
            }
            boolean forward = expandForward;
            expandForward = !expandForward;
            IntAirGraphHeap frontier = forward
                    ? scratch.forwardFrontier : scratch.backwardFrontier;
            int state = frontier.poll();
            double stateDistance = frontier.lastDistance();
            double knownDistance = forward
                    ? scratch.forwardDistance(state)
                    : scratch.backwardDistance(state);
            if (stateDistance > knownDistance + 1.0E-7) {
                continue;
            }
            double opposite = forward
                    ? scratch.backwardDistance(state)
                    : scratch.forwardDistance(state);
            if (opposite < Double.POSITIVE_INFINITY
                    && stateDistance + opposite < bestMeetingDistance) {
                bestMeetingDistance = stateDistance + opposite;
                meeting = state;
            }
            expanded++;
            int stateX = scene.cellX(state);
            int stateY = scene.cellY(state);
            int stateZ = scene.cellZ(state);
            for (int[] offset : AIR_GRAPH_NEIGHBORS) {
                int nextX = stateX + offset[0];
                int nextY = stateY + offset[1];
                int nextZ = stateZ + offset[2];
                int next = scene.neighborCellIndex(
                        state, offset[0], offset[1], offset[2]
                );
                if (next < 0) {
                    continue;
                }
                if (!scene.isAirCellIndex(next)) {
                    // Partial collision cells are uncommon but remain part of the same
                    // exact graph when their centre-to-centre segment is physically
                    // clear. Cells outside the immutable capture are left to the old
                    // complete fallback, never guessed as open or closed here.
                    Vec3 from = new Vec3(
                            stateX + 0.5, stateY + 0.5, stateZ + 0.5
                    );
                    Vec3 to = new Vec3(
                            nextX + 0.5, nextY + 0.5, nextZ + 0.5
                    );
                    if (pointInsideCollision(scene, to)
                            || firstCollisionBounds(scene, from, to) != null) {
                        continue;
                    }
                }
                double nextDistance = stateDistance + 1.0;
                double prior = forward
                        ? scratch.forwardDistance(next)
                        : scratch.backwardDistance(next);
                if (nextDistance + 1.0E-7 >= prior) {
                    continue;
                }
                if (forward) {
                    scratch.setForward(next, nextDistance, state);
                } else {
                    scratch.setBackward(next, nextDistance, state);
                }
                frontier.add(
                        next,
                        nextDistance,
                        nextDistance + (forward
                                ? goalHeuristic.distance(nextX, nextY, nextZ)
                                : startHeuristic.distance(nextX, nextY, nextZ))
                );
                double nextOpposite = forward
                        ? scratch.backwardDistance(next)
                        : scratch.forwardDistance(next);
                if (nextOpposite < Double.POSITIVE_INFINITY
                        && nextDistance + nextOpposite < bestMeetingDistance) {
                    bestMeetingDistance = nextDistance + nextOpposite;
                    meeting = next;
                }
            }
        }
        if (meeting < 0) {
            return new DenseAirSearchResult(null, expanded);
        }
        ArrayDeque<Long> reversed = new ArrayDeque<>();
        for (int cell = meeting; cell >= 0; cell = scratch.forwardPrevious(cell)) {
            reversed.addFirst(BlockPos.asLong(
                    scene.cellX(cell), scene.cellY(cell), scene.cellZ(cell)
            ));
        }
        List<Long> path = new ArrayList<>(reversed.size() + 8);
        path.addAll(reversed);
        for (int cell = scratch.backwardPrevious(meeting);
             cell >= 0;
             cell = scratch.backwardPrevious(cell)) {
            path.add(BlockPos.asLong(
                    scene.cellX(cell), scene.cellY(cell), scene.cellZ(cell)
            ));
        }
        return new DenseAirSearchResult(List.copyOf(path), expanded);
    }

    private static DenseAirSearchResult searchDenseSingleSeed(
            AcousticScene scene,
            Vec3 source,
            Vec3 listener,
            AirCellKey startKey,
            AirCellKey goalKey,
            int candidateBudget,
            DenseAirSearchScratch scratch
    ) {
        int start = scene.airCellIndex(startKey.x(), startKey.y(), startKey.z());
        int goal = scene.airCellIndex(goalKey.x(), goalKey.y(), goalKey.z());
        if (start < 0 || goal < 0) {
            return new DenseAirSearchResult(null, 0);
        }
        double startConnector = distanceFromCellCenter(
                startKey.x(), startKey.y(), startKey.z(), source
        );
        double goalConnector = distanceFromCellCenter(
                goalKey.x(), goalKey.y(), goalKey.z(), listener
        );
        double priorityBase = startConnector + goalConnector;
        scratch.setForward(start, startConnector, -1);
        scratch.setBackward(goal, goalConnector, -1);
        scratch.forwardBuckets.add(
                start, startConnector,
                manhattan(startKey.x(), startKey.y(), startKey.z(), goalKey)
        );
        scratch.backwardBuckets.add(
                goal, goalConnector,
                manhattan(goalKey.x(), goalKey.y(), goalKey.z(), startKey)
        );
        int maximumExpansions = Mth.clamp(
                (int) Math.ceil(source.distanceTo(listener))
                        * candidateBudget * 32,
                8192,
                65536
        );
        double bestMeetingDistance = Double.POSITIVE_INFINITY;
        int meeting = -1;
        int expanded = 0;
        boolean expandForward = true;
        while (!scratch.forwardBuckets.isEmpty()
                && !scratch.backwardBuckets.isEmpty()
                && expanded < maximumExpansions) {
            if (bestMeetingDistance < Double.POSITIVE_INFINITY
                    && (priorityBase + scratch.forwardBuckets.peekPriority()
                    >= bestMeetingDistance
                    || priorityBase + scratch.backwardBuckets.peekPriority()
                    >= bestMeetingDistance)) {
                break;
            }
            boolean forward = expandForward;
            expandForward = !expandForward;
            IntBucketFrontier frontier = forward
                    ? scratch.forwardBuckets : scratch.backwardBuckets;
            int state = frontier.poll();
            double stateDistance = frontier.lastDistance();
            double knownDistance = forward
                    ? scratch.forwardDistance(state)
                    : scratch.backwardDistance(state);
            if (stateDistance > knownDistance + 1.0E-7) {
                continue;
            }
            double opposite = forward
                    ? scratch.backwardDistance(state)
                    : scratch.forwardDistance(state);
            if (opposite < Double.POSITIVE_INFINITY
                    && stateDistance + opposite < bestMeetingDistance) {
                bestMeetingDistance = stateDistance + opposite;
                meeting = state;
            }
            expanded++;
            int stateX = scene.cellX(state);
            int stateY = scene.cellY(state);
            int stateZ = scene.cellZ(state);
            for (int[] offset : AIR_GRAPH_NEIGHBORS) {
                int next = scene.neighborCellIndex(
                        state, offset[0], offset[1], offset[2]
                );
                if (next < 0 || !scene.isAirCellIndex(next)) {
                    continue;
                }
                double nextDistance = stateDistance + 1.0;
                double prior = forward
                        ? scratch.forwardDistance(next)
                        : scratch.backwardDistance(next);
                if (nextDistance + 1.0E-7 >= prior) {
                    continue;
                }
                if (forward) {
                    scratch.setForward(next, nextDistance, state);
                } else {
                    scratch.setBackward(next, nextDistance, state);
                }
                int nextX = scene.cellX(next);
                int nextY = scene.cellY(next);
                int nextZ = scene.cellZ(next);
                int steps = (int) Math.round(nextDistance
                        - (forward ? startConnector : goalConnector));
                int heuristic = forward
                        ? manhattan(nextX, nextY, nextZ, goalKey)
                        : manhattan(nextX, nextY, nextZ, startKey);
                frontier.add(next, nextDistance, steps + heuristic);
                double nextOpposite = forward
                        ? scratch.backwardDistance(next)
                        : scratch.forwardDistance(next);
                if (nextOpposite < Double.POSITIVE_INFINITY
                        && nextDistance + nextOpposite < bestMeetingDistance) {
                    bestMeetingDistance = nextDistance + nextOpposite;
                    meeting = next;
                }
            }
        }
        if (meeting < 0) {
            return new DenseAirSearchResult(null, expanded);
        }
        ArrayDeque<Long> reversed = new ArrayDeque<>();
        for (int cell = meeting; cell >= 0; cell = scratch.forwardPrevious(cell)) {
            reversed.addFirst(BlockPos.asLong(
                    scene.cellX(cell), scene.cellY(cell), scene.cellZ(cell)
            ));
        }
        List<Long> path = new ArrayList<>(reversed.size() + 8);
        path.addAll(reversed);
        for (int cell = scratch.backwardPrevious(meeting);
             cell >= 0;
             cell = scratch.backwardPrevious(cell)) {
            path.add(BlockPos.asLong(
                    scene.cellX(cell), scene.cellY(cell), scene.cellZ(cell)
            ));
        }
        return new DenseAirSearchResult(List.copyOf(path), expanded);
    }

    private static int manhattan(int x, int y, int z, AirCellKey target) {
        return Math.abs(x - target.x())
                + Math.abs(y - target.y())
                + Math.abs(z - target.z());
    }


    static DenseAirSearchMetrics measureDenseAirSearch(
            AcousticScene scene,
            Vec3 source,
            Vec3 listener
    ) {
        List<AirCellKey> starts = airEndpointCells(scene, source);
        List<AirCellKey> goals = airEndpointCells(scene, listener);
        long started = System.nanoTime();
        DenseAirSearchResult result = searchDenseAirGraph(
                scene, source, listener, starts, goals,
                DIFFRACTION_CANDIDATE_BUDGET
        );
        return new DenseAirSearchMetrics(
                result.path() != null,
                result.expanded(),
                System.nanoTime() - started
        );
    }

    static DenseAirSearchStorageMetrics measureDenseAirSearchStorage(
            int sceneCellCapacity
    ) {
        DenseAirSearchScratch scratch = DENSE_AIR_SEARCH.get();
        scratch.begin(sceneCellCapacity, false);
        scratch.end();
        return scratch.storageMetrics(sceneCellCapacity);
    }

    private static PropagationGraphPath buildAirPropagationPath(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            List<Long> cellPath,
            ListenerAirRouteField listenerField
    ) {
        if (cellPath.isEmpty()) {
            return null;
        }
        List<Vec3> raw = new ArrayList<>(cellPath.size() + 2);
        raw.add(source);
        for (long cell : cellPath) {
            raw.add(airCellCenter(cell));
        }
        raw.add(listener);
        List<Vec3> corridor = collapseCollinearCorridor(raw);
        List<Vec3> pulled = new ArrayList<>();
        pulled.add(source);
        int anchor = 0;
        while (anchor < corridor.size() - 1) {
            int visible = anchor + 1;
            while (visible + 1 < corridor.size()
                    && firstCollisionBounds(
                    level, corridor.get(anchor), corridor.get(visible + 1)
            ) == null) {
                visible++;
            }
            if (visible <= anchor) {
                return null;
            }
            pulled.add(corridor.get(visible));
            anchor = visible;
        }
        Vec3 arrivalPoint = pulled.size() > 2
                ? pulled.get(pulled.size() - 2)
                : source;
        arrivalPoint = resolveFinalHuygensAperture(level, pulled, listener, arrivalPoint);
        return new PropagationGraphPath(
                List.copyOf(pulled),
                1.0F,
                true,
                arrivalPoint,
                List.of(arrivalPoint)
        );
    }

    private static List<Vec3> collapseCollinearCorridor(List<Vec3> points) {
        if (points.size() <= 2) {
            return points;
        }
        ArrayList<Vec3> result = new ArrayList<>(points.size());
        result.add(points.get(0));
        for (int index = 1; index < points.size() - 1; index++) {
            Vec3 previous = points.get(index - 1);
            Vec3 current = points.get(index);
            Vec3 next = points.get(index + 1);
            Vec3 incoming = current.subtract(previous);
            Vec3 outgoing = next.subtract(current);
            double crossSquared = incoming.cross(outgoing).lengthSqr();
            boolean sameDirection = incoming.dot(outgoing) > 0.0;
            if (crossSquared > 1.0E-12 || !sameDirection) {
                result.add(current);
            }
        }
        result.add(points.get(points.size() - 1));
        return List.copyOf(result);
    }

    /**
     * Resolves the last diffraction lobe from the locally visible aperture instead of
     * from one arbitrary voxel chosen by A*.  Every accepted sample is propagation-open
     * and visible from both adjacent path legs, so the centroid is the discrete
     * Huygens aperture through which this route actually reaches the listener.
     */
    private static Vec3 resolveFinalHuygensAperture(
            BlockGetter level,
            List<Vec3> path,
            Vec3 listener,
            Vec3 fallback
    ) {
        if (path.size() < 3) {
            return fallback;
        }
        Vec3 previous = path.get(path.size() - 3);
        BlockPos center = BlockPos.containing(fallback);
        Vec3 moment = Vec3.ZERO;
        double totalPower = 0.0;
        for (int x = center.getX() - 2; x <= center.getX() + 2; x++) {
            for (int y = center.getY() - 2; y <= center.getY() + 2; y++) {
                for (int z = center.getZ() - 2; z <= center.getZ() + 2; z++) {
                    if (!isAirCell(level, x, y, z)) {
                        continue;
                    }
                    Vec3 sample = new Vec3(x + 0.5, y + 0.5, z + 0.5);
                    double listenerDistance = sample.distanceTo(listener);
                    if (listenerDistance < 0.50
                            || firstCollisionBounds(level, previous, sample) != null
                            || firstCollisionBounds(level, sample, listener) != null) {
                        continue;
                    }
                    double previousDistance = Math.max(0.50, previous.distanceTo(sample));
                    double geometricAmplitude = 1.0
                            / (previousDistance * Math.max(0.50, listenerDistance));
                    double power = geometricAmplitude * geometricAmplitude;
                    moment = moment.add(sample.scale(power));
                    totalPower += power;
                }
            }
        }
        return totalPower > 1.0E-12
                ? moment.scale(1.0 / totalPower)
                : fallback;
    }

    /**
     * Reprojects a previously solved voxel route onto the current sub-block endpoints.
     * adaptCachedAirPath validates both changed connectors against the immutable scene;
     * failure returns null and the normal sparse/A* hierarchy is executed.
     */
    private static VerifiedAirBackbone verifiedCachedAirPath(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            boolean preservePortalDirection
    ) {
        AirPathCacheKey cacheKey = airPathCacheKey(level, source, listener);
        PropagationGraphPath cached = AIR_PATH_CACHE.get(cacheKey);
        if (cached != null) {
            PropagationGraphPath adapted = adaptCachedAirPath(
                    level, cached, source, listener
            );
            if (adapted != null) {
                Vec3 cachedListener = cached.points().get(cached.points().size() - 1);
                return new VerifiedAirBackbone(
                        adapted,
                        cachedListener.distanceToSqr(listener) < 1.0E-12
                );
            }
            AIR_PATH_CACHE.remove(cacheKey, cached);
        }
        if (!preservePortalDirection) {
            ListenerAirRouteField listenerField = listenerAirRouteField(level, listener);
            if (listenerField != null && listenerField.hasBackbone()) {
                List<Long> knownCells = listenerField.knownRoute(
                        source, airEndpointCells(level, source)
                );
                if (knownCells != null) {
                    PropagationGraphPath known = buildAirPropagationPath(
                            level, source, listener, knownCells, listenerField
                    );
                    if (known != null) {
                        putBounded(
                                AIR_PATH_CACHE, AIR_PATH_CACHE_ORDER,
                                cacheKey, known, 2048
                        );
                        return new VerifiedAirBackbone(known, false);
                    }
                }
            }
        }
        if (preservePortalDirection) {
            VerifiedAirBackbone repaired = adaptNeighboringSourceCachedAirPath(
                    level, cacheKey, source, listener
            );
            if (repaired == null) {
                PropagationGraphPath endpointRepair = adaptNeighboringCachedAirPath(
                        level, cacheKey, source, listener
                );
                if (endpointRepair != null) {
                    repaired = new VerifiedAirBackbone(endpointRepair, false);
                }
            }
            if (repaired != null) {
                putBounded(
                        AIR_PATH_CACHE, AIR_PATH_CACHE_ORDER,
                        cacheKey, repaired.path(), 2048
                );
            }
            return repaired;
        }
        PropagationGraphPath neighboring = adaptNeighboringCachedAirPath(
                level, cacheKey, source, listener
        );
        return neighboring == null
                ? null : new VerifiedAirBackbone(neighboring, false);
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
        } else if (firstCollisionBounds(level, source, cachedPoints.get(0)) == null) {
            adapted.add(0, source);
        } else {
            return null;
        }
        if (airEndpointConnectorClear(level, listener, lastInterior)) {
            adapted.set(adapted.size() - 1, listener);
        } else if (firstCollisionBounds(level, cachedPoints.get(cachedPoints.size() - 1), listener) == null) {
            // Incremental graph repair: an adjacent listener cell can remain connected
            // through the previous endpoint even when the newly drawn shortcut clips a
            // corner. Keeping that one valid edge avoids a full A* restart at every
            // voxel boundary and preserves the same arrival lobe while moving.
            adapted.add(listener);
        } else {
            return null;
        }
        Vec3 arrivalPoint = adapted.size() > 2
                ? adapted.get(adapted.size() - 2)
                : adapted.get(0);
        arrivalPoint = resolveFinalHuygensAperture(
                level, adapted, listener, arrivalPoint
        );
        return new PropagationGraphPath(
                List.copyOf(adapted),
                cached.apertureAmplitude(),
                cached.clear(),
                arrivalPoint,
                List.of(arrivalPoint)
        );
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
        AirCellKey start = requested.source();
        // Incremental Phi*/D* Lite principle: retain the unchanged interior route and
        // repair only the endpoint connector after a one-cell source movement. The
        // listener-side variant below does the symmetric operation. Both connectors are
        // collision-tested against the current immutable scene before reuse.
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
                                    new AirCellKey(
                                            start.x() + x,
                                            start.y() + y,
                                            start.z() + z
                                    ),
                                    goal
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
                    double endpointDistance = candidate.points().get(0)
                            .distanceToSqr(source);
                    if (endpointDistance < bestEndpointDistance) {
                        bestEndpointDistance = endpointDistance;
                        best = adapted;
                    }
                }
            }
        }
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
                    double endpointDistance = candidate.points().get(candidate.points().size() - 1)
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

    /** Repairs only a moving source endpoint, leaving the listener arrival field intact. */
    private static VerifiedAirBackbone adaptNeighboringSourceCachedAirPath(
            BlockGetter level,
            AirPathCacheKey requested,
            Vec3 source,
            Vec3 listener
    ) {
        PropagationGraphPath best = null;
        double bestEndpointDistance = Double.POSITIVE_INFINITY;
        boolean bestListenerUnchanged = false;
        AirCellKey start = requested.source();
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
                                    new AirCellKey(
                                            start.x() + x,
                                            start.y() + y,
                                            start.z() + z
                                    ),
                                    requested.listener()
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
                    double endpointDistance = candidate.points().get(0)
                            .distanceToSqr(source);
                    if (endpointDistance < bestEndpointDistance) {
                        bestEndpointDistance = endpointDistance;
                        best = adapted;
                        Vec3 candidateListener = candidate.points().get(
                                candidate.points().size() - 1
                        );
                        bestListenerUnchanged = candidateListener.distanceToSqr(listener)
                                < 1.0E-12;
                    }
                }
            }
        }
        if (best == null) {
            return null;
        }
        return new VerifiedAirBackbone(best, bestListenerUnchanged);
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
                cacheIdentity(level),
                sceneRevision,
                airCellKey(source),
                airCellKey(listener)
        );
    }

    private static ListenerAirRouteField listenerAirRouteField(
            BlockGetter level,
            Vec3 listener
    ) {
        List<AirCellKey> seeds = airEndpointCells(level, listener);
        if (seeds.isEmpty()) {
            return null;
        }
        AirCellKey cell = airCellKey(listener);
        long sceneRevision = level instanceof AcousticScene scene
                ? scene.revision()
                : 0L;
        return LISTENER_AIR_FIELDS.get(new ListenerAirFieldKey(
                cacheIdentity(level),
                sceneRevision,
                cell,
                airSeedSignature(cell, seeds)
        ));
    }

    private static ListenerAirRouteField neighboringListenerAirField(
            ListenerAirFieldKey requested
    ) {
        ListenerAirRouteField best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<ListenerAirFieldKey, ListenerAirRouteField> entry
                : LISTENER_AIR_FIELDS.entrySet()) {
            ListenerAirFieldKey candidate = entry.getKey();
            if (candidate.level() != requested.level()
                    || candidate.sceneRevision() != requested.sceneRevision()
                    || !entry.getValue().hasBackbone()) {
                continue;
            }
            int dx = Math.abs(candidate.listener().x() - requested.listener().x());
            int dy = Math.abs(candidate.listener().y() - requested.listener().y());
            int dz = Math.abs(candidate.listener().z() - requested.listener().z());
            int distance = dx + dy + dz;
            // Distance is not a validity rule. Import the nearest retained field and
            // let buildAirPropagationPath collision-test its terminal connector. This
            // also breaks the latency feedback loop where a slow calculation lets the
            // listener move more than two voxels and therefore forces every voice to
            // start another cold search.
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
            }
        }
        return best;
    }

    private static Vec3 airCellCenter(AirCellKey key) {
        return new Vec3(key.x() + 0.5, key.y() + 0.5, key.z() + 0.5);
    }

    private static Vec3 airCellCenter(long packed) {
        return new Vec3(
                BlockPos.getX(packed) + 0.5,
                BlockPos.getY(packed) + 0.5,
                BlockPos.getZ(packed) + 0.5
        );
    }

    private static double distanceFromCellCenter(
            int x,
            int y,
            int z,
            Vec3 point
    ) {
        double dx = x + 0.5 - point.x;
        double dy = y + 0.5 - point.y;
        double dz = z + 0.5 - point.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int airSeedSignature(
            AirCellKey containing,
            List<AirCellKey> seeds
    ) {
        int signature = 0;
        for (AirCellKey seed : seeds) {
            int dx = seed.x() - containing.x();
            int dy = seed.y() - containing.y();
            int dz = seed.z() - containing.z();
            if (dx == 0 && dy == 0 && dz == 0) signature |= 1;
            else if (dx == 1 && dy == 0 && dz == 0) signature |= 1 << 1;
            else if (dx == -1 && dy == 0 && dz == 0) signature |= 1 << 2;
            else if (dx == 0 && dy == 1 && dz == 0) signature |= 1 << 3;
            else if (dx == 0 && dy == -1 && dz == 0) signature |= 1 << 4;
            else if (dx == 0 && dy == 0 && dz == 1) signature |= 1 << 5;
            else if (dx == 0 && dy == 0 && dz == -1) signature |= 1 << 6;
        }
        return signature;
    }

    /**
     * Exact obstacle-free lower bound for the six-neighbour air graph. A route must pay
     * one unit for every face transition to one of the endpoint's valid seed cells and
     * then the measured seed-centre-to-endpoint connector. Unlike a raw Euclidean
     * heuristic this uses the graph's actual metric, while taking the minimum across
     * all boundary-emitter seeds keeps it admissible for block-centred sounds.
     */
    private static double endpointGraphHeuristic(
            int x,
            int y,
            int z,
            List<AirCellKey> endpointSeeds,
            Vec3 endpoint
    ) {
        double best = Double.POSITIVE_INFINITY;
        for (AirCellKey seed : endpointSeeds) {
            int faceSteps = Math.abs(x - seed.x())
                    + Math.abs(y - seed.y())
                    + Math.abs(z - seed.z());
            double connector = distanceFromCellCenter(
                    seed.x(), seed.y(), seed.z(), endpoint
            );
            best = Math.min(best, faceSteps + connector);
        }
        return best;
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
            Object2DoubleOpenHashMap<GraphPointKey> bestDistanceByNode
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
        if (distance + 1.0E-7 >= bestDistanceByNode.getDouble(key)) {
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
        radialDirections:
        for (int angularStep = 0; angularStep < 8; angularStep++) {
            if (radialNodeCount >= candidateBudget) {
                break;
            }
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
                if (radialNodeCount >= candidateBudget) {
                    break radialDirections;
                }
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
                    if (radialNodeCount >= candidateBudget) {
                        break radialDirections;
                    }
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
        double supportX = searchDirection.x >= 0.0
                ? outgoingObstacle.minX : outgoingObstacle.maxX;
        double supportY = searchDirection.y >= 0.0
                ? outgoingObstacle.minY : outgoingObstacle.maxY;
        double supportZ = searchDirection.z >= 0.0
                ? outgoingObstacle.minZ : outgoingObstacle.maxZ;
        double nextObstacleEntry = (supportX - source.x) * searchDirection.x
                + (supportY - source.y) * searchDirection.y
                + (supportZ - source.z) * searchDirection.z;
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
        double airDistance = 0.0;
        AcousticMaterial previousEndFluid = null;
        boolean hasPreviousSegment = false;
        List<Vec3> points = path.points();
        for (int index = 1; index < points.size(); index++) {
            Vec3 from = points.get(index - 1);
            Vec3 to = points.get(index);
            pathDistance += from.distanceTo(to);
            Transmission segment = traceTransmission(level, from, to);
            float[] segmentBands = segment.bands();
            if (hasPreviousSegment) {
                applyFluidBoundary(
                        bands,
                        previousEndFluid,
                        segment.startFluid()
                );
            }
            airDistance += segment.airDistance();
            for (int band = 0; band < bands.length; band++) {
                bands[band] *= segmentBands[band];
            }
            previousEndFluid = segment.endFluid();
            hasPreviousSegment = true;
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
            bands[band] *= distanceLoss;
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
                airDistance,
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

    private static Object cacheIdentity(BlockGetter level) {
        return level instanceof AcousticScene scene
                ? scene.cacheIdentity()
                : level;
    }

    private static boolean isAirCell(BlockGetter level, int x, int y, int z) {
        if (level instanceof AcousticScene scene) {
            return scene.isPropagationOpen(x, y, z);
        }
        BlockPos position = WALK_POSITION.get().set(x, y, z);
        BlockState state = level.getBlockState(position);
        return state.isAir() || state.getCollisionShape(level, position).isEmpty();
    }

    private static <K, V> V putBounded(
            Map<K, V> cache,
            ConcurrentLinkedQueue<K> insertionOrder,
            K key,
            V value,
            int maximumSize
    ) {
        V existing = cache.putIfAbsent(key, value);
        if (existing != null) {
            return existing;
        }
        insertionOrder.add(key);
        while (cache.size() > maximumSize) {
            K oldest = insertionOrder.poll();
            if (oldest == null) {
                break;
            }
            cache.remove(oldest);
        }
        return value;
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
                cacheIdentity(level),
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
                level.getBlockState(path.get(0))
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
                level.getBlockState(path.get(path.size() - 1))
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
        return putBounded(
                STRUCTURAL_PATH_CACHE, STRUCTURAL_PATH_CACHE_ORDER,
                cacheKey, result, 256
        );
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
                level,
                source,
                listener,
                roomProbe,
                pathBudget,
                true,
                traceTransmission(level, source, listener).travelTimeSeconds()
        );
    }

    private static ReflectionResult estimateEarlyReflections(
            BlockGetter level,
            Vec3 source,
            Vec3 listener,
            RoomProbe sourceRoomProbe,
            int pathBudget,
            boolean allowDiffractedLegs,
            double firstArrivalTimeSeconds
    ) {
        EmitterMediumTransfer sourceMedium = solveEmitterBoundary(level, source);
        MediumSample listenerMedium = sampleMedium(level, listener);
        List<RoomProbe.SurfaceSample> candidates = reflectionCandidates(
                source, listener, sourceRoomProbe
        );
        if (candidates.isEmpty() || pathBudget <= 0) {
            return ReflectionResult.silent();
        }

        double directDistance = Math.max(source.distanceTo(listener), 0.5);
        int remainingDiffractedLegs = allowDiffractedLegs
                ? Math.min(2, pathBudget)
                : 0;
        List<Vec3> acceptedReflectionPoints = new ArrayList<>(pathBudget);
        PriorityQueue<ReflectionPathSample> strongestPaths = new PriorityQueue<>(
                Comparator.comparingDouble(ReflectionPathSample::power)
        );
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
            Vec3 pathPoint = reflectionPoint.add(facingNormal.scale(0.035));
            // The offset point must actually lie in air. At the intersection of two
            // closed walls an image path can otherwise touch the mathematical edge, with
            // each leg starting in the adjoining solid block and the voxel walker
            // (correctly) ignoring its endpoint block. That is not an open acoustic path.
            if (pointInsideCollision(level, pathPoint)) {
                continue;
            }
            Transmission sourceLeg = traceTransmission(level, source, pathPoint);
            Transmission listenerLeg = traceTransmission(level, pathPoint, listener);
            float[] sourceBands = sourceLeg.bands().clone();
            float[] listenerBands = listenerLeg.bands().clone();
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
            // Acoustic transfer is reciprocal: an air-calibrated source coupled into
            // water must acquire the same endpoint spectrum as the inverse path where
            // the listener's ear is submerged. Applying these at the physical endpoints
            // (and not at the image point) avoids double-filtering a reflected leg.
            applyEmitterMediumResponse(sourceBands, sourceMedium);
            applyMediumResponse(listenerBands, listenerMedium);
            double pathDistance = source.distanceTo(reflectionPoint) + reflectionPoint.distanceTo(listener);
            float distanceGain = (float) (directDistance / Math.max(pathDistance, directDistance));
            float[] pathBands = new float[AcousticBands.COUNT];
            for (int band = 0; band < AcousticBands.COUNT; band++) {
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
                        * distanceGain;
                pathBands[band] = contribution;
            }
            double pathPower = weightedEnergy(pathBands);
            pathPower *= pathPower;
            if (pathPower > 1.0E-12) {
                // The budget is for physically contributing arrivals. Counting a
                // geometrically valid but fully blocked/absorbed candidate let several
                // silent planes consume the whole budget, making the echo disappear
                // when the listener moved by a fraction of a block.
                acceptedReflectionPoints.add(reflectionPoint);
                double reflectionTravelTime = sourceLeg.travelTimeSeconds()
                        + listenerLeg.travelTimeSeconds();
                Vec3 arrivalDirection = reflectionPoint.subtract(listener);
                ReflectionPathSample sample = new ReflectionPathSample(
                        pathBands,
                        pathPower,
                        Math.max(0.0, reflectionTravelTime - firstArrivalTimeSeconds),
                        reflectionPoint,
                        arrivalDirection.lengthSqr() > 1.0E-10
                                ? arrivalDirection.normalize()
                                : Vec3.ZERO
                );
                if (strongestPaths.size() < pathBudget) {
                    strongestPaths.add(sample);
                } else if (sample.power() > strongestPaths.peek().power()) {
                    strongestPaths.poll();
                    strongestPaths.add(sample);
                }
            }
        }

        float[] accumulated = new float[AcousticBands.COUNT];
        double delayPowerSum = 0.0;
        double directionPower = 0.0;
        Vec3 arrivalDirectionSum = Vec3.ZERO;
        List<DirectionalArrivalField.Arrival> reflectionArrivals = new ArrayList<>(
                strongestPaths.size()
        );
        for (ReflectionPathSample path : strongestPaths) {
            for (int band = 0; band < accumulated.length; band++) {
                accumulated[band] += path.bands()[band] * path.bands()[band];
            }
            delayPowerSum += path.power() * path.delaySeconds();
            if (path.arrivalDirection().lengthSqr() > 1.0E-10) {
                reflectionArrivals.add(new DirectionalArrivalField.Arrival(
                        path.arrivalPoint(), path.power()
                ));
                arrivalDirectionSum = arrivalDirectionSum.add(
                        path.arrivalDirection().scale(path.power())
                );
                directionPower += path.power();
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
        float low = pairEnergy(accumulated, 0);
        float high = pairEnergy(accumulated, 6);
        // OpenAL's band-pass shelves can only attenuate around one broadband anchor.
        // Preserve the strongest traced pair as that anchor, then retain the actual
        // low/high ratios instead of discarding material-dependent bass transmission.
        float reflectionAnchor = spectralAnchor(accumulated);
        Vec3 reflectionPan = directionPower > 1.0E-12
                && arrivalDirectionSum.lengthSqr() > 1.0E-10
                ? arrivalDirectionSum.normalize()
                : Vec3.ZERO;
        return new ReflectionResult(
                accumulated,
                Mth.clamp(reflectionAnchor, 0.0F, 0.90F),
                Mth.clamp(low / Math.max(reflectionAnchor, 0.01F), 0.02F, 1.0F),
                Mth.clamp(high / Math.max(reflectionAnchor, 0.01F), 0.02F, 1.0F),
                (float) (directionPower > 1.0E-12
                        ? delayPowerSum / directionPower
                        : 0.0),
                reflectionPan,
                new DirectionalArrivalField(
                        reflectionArrivals,
                        reflectionArrivals.isEmpty() || reflectionPan.lengthSqr() <= 1.0E-10
                                ? null
                                : listener.add(reflectionPan.scale(Math.max(1.0, directDistance)))
                )
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

    private record ReflectionPathSample(
            float[] bands,
            double power,
            double delaySeconds,
            Vec3 arrivalPoint,
            Vec3 arrivalDirection
    ) {
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
                cacheIdentity(level),
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
                AABB fluidBounds = fluidBounds(fluidState, level, pos);
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
            int sceneCollisionClass = level instanceof AcousticScene scene
                    ? scene.collisionClass(pos)
                    : -1;
            if (sceneCollisionClass == 0) {
                return true;
            }
            BlockState state = level.getBlockState(pos);
            if (sceneCollisionClass < 0 && state.isAir()) {
                return true;
            }
            SolidIntersection solid;
            if (sceneCollisionClass == 1
                    || sceneCollisionClass < 0 && isCollisionShapeFullBlock(level, pos, state)) {
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
            // Keep walking after the first strongly insulating block. Stopping at an
            // arbitrary amplitude floor made every additional wall disappear from the
            // physical path, so one layer and a thick sealed shell produced the same
            // transmission. Float underflow already provides the only numerical floor
            // needed for a genuinely inaudible path.
            return true;
        });
        MediumSample startMedium = sampleMedium(level, from);
        MediumSample endMedium = sampleMedium(level, to);
        FluidStats fluidStats = applyFluidSegments(
                bands,
                scratch.fluidSegments,
                from.distanceTo(to),
                startMedium.weight(),
                endMedium.weight()
        );
        // Atmospheric loss belongs to a transport segment, just like solid and fluid
        // loss. Applying it here keeps direct, bent, reflected and graph paths on the
        // same pipeline and guarantees that only the actually airborne portion is used.
        applyAirAbsorption(
                bands,
                AcousticMaterialRegistry.tuning().meters(fluidStats.airDistance())
        );
        Transmission result = new Transmission(
                bands,
                scratch.firstBounds,
                scratch.blockers,
                fluidStats.distance(),
                fluidStats.airDistance(),
                fluidStats.travelTimeSeconds(),
                fluidStats.scattering(),
                fluidStats.startFluid(),
                fluidStats.endFluid()
        );
        return putBounded(
                TRANSMISSION_CACHE, TRANSMISSION_CACHE_ORDER,
                cacheKey, result, 4096
        );
    }

    /**
     * Collision-only segment query for visibility-graph construction. Material spectra,
     * fluid intervals and transmission thickness are intentionally deferred until A*
     * has produced complete paths; doing that work for every rejected graph edge made a
     * finite-aperture sweep much more expensive without changing graph connectivity.
     */
    private static AABB firstCollisionBounds(BlockGetter level, Vec3 from, Vec3 to) {
        if (level instanceof AcousticScene scene
                && !scene.mayIntersectPotentialCollision(from, to)) {
            return null;
        }
        CollisionQuery query = COLLISION_QUERY.get();
        query.prepare(level, from, to);
        walkBlocks(from, to, query);
        return query.result;
    }

    private static final class CollisionQuery implements BlockVisitor {
        private BlockGetter level;
        private Vec3 from;
        private Vec3 to;
        private AABB result;

        private void prepare(BlockGetter level, Vec3 from, Vec3 to) {
            this.level = level;
            this.from = from;
            this.to = to;
            result = null;
        }

        @Override
        public boolean visit(BlockPos pos, boolean endpoint) {
            if (endpoint) {
                return true;
            }
            if (level instanceof AcousticScene scene) {
                int collisionClass = scene.collisionClass(pos);
                if (collisionClass == 0) {
                    return true;
                }
                if (collisionClass == 1) {
                    if (intersectsUnitBlockPositive(pos, from, to)) {
                        result = new AABB(pos);
                        return false;
                    }
                    return true;
                }
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return true;
            }
            if (isCollisionShapeFullBlock(level, pos, state)) {
                AABB bounds = new AABB(pos);
                SegmentInterval interval = intersectSegment(bounds, from, to);
                if (interval != null && interval.end() - interval.start() > 1.0E-7) {
                    result = bounds;
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
            result = intersection.firstBounds() != null
                    ? intersection.firstBounds()
                    : shape.bounds().move(pos);
            return false;
        }
    }

    private static boolean intersectsUnitBlockPositive(
            BlockPos pos,
            Vec3 from,
            Vec3 to
    ) {
        double minimum = 0.0;
        double maximum = 1.0;
        double dx = to.x - from.x;
        if (Math.abs(dx) < 1.0E-15) {
            if (from.x < pos.getX() || from.x > pos.getX() + 1.0) {
                return false;
            }
        } else {
            double first = (pos.getX() - from.x) / dx;
            double second = (pos.getX() + 1.0 - from.x) / dx;
            if (first > second) {
                double swap = first; first = second; second = swap;
            }
            minimum = Math.max(minimum, first);
            maximum = Math.min(maximum, second);
            if (maximum - minimum <= 1.0E-7) {
                return false;
            }
        }
        double dy = to.y - from.y;
        if (Math.abs(dy) < 1.0E-15) {
            if (from.y < pos.getY() || from.y > pos.getY() + 1.0) {
                return false;
            }
        } else {
            double first = (pos.getY() - from.y) / dy;
            double second = (pos.getY() + 1.0 - from.y) / dy;
            if (first > second) {
                double swap = first; first = second; second = swap;
            }
            minimum = Math.max(minimum, first);
            maximum = Math.min(maximum, second);
            if (maximum - minimum <= 1.0E-7) {
                return false;
            }
        }
        double dz = to.z - from.z;
        if (Math.abs(dz) < 1.0E-15) {
            if (from.z < pos.getZ() || from.z > pos.getZ() + 1.0) {
                return false;
            }
        } else {
            double first = (pos.getZ() - from.z) / dz;
            double second = (pos.getZ() + 1.0 - from.z) / dz;
            if (first > second) {
                double swap = first; first = second; second = swap;
            }
            minimum = Math.max(minimum, first);
            maximum = Math.min(maximum, second);
        }
        return maximum - minimum > 1.0E-7;
    }

    private static boolean pointInsideCollision(BlockGetter level, Vec3 point) {
        BlockPos position = BlockPos.containing(point);
        if (level instanceof AcousticScene scene) {
            int collisionClass = scene.collisionClass(position);
            if (collisionClass == 0) {
                return false;
            }
            if (collisionClass == 1) {
                return true;
            }
        }
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

    static SurfaceHit firstAcousticSurface(
            BlockGetter level,
            Vec3 from,
            Vec3 to,
            SurfaceHit[] result
    ) {
        SurfaceHit solid = firstSurface(level, from, to, result);
        SurfaceHit fluid = firstFluidBoundary(level, from, to);
        if (fluid != null && (solid == null || fluid.distance() < solid.distance())) {
            result[0] = fluid;
            return fluid;
        }
        result[0] = solid;
        return solid;
    }

    private static SurfaceHit firstFluidBoundary(BlockGetter level, Vec3 from, Vec3 to) {
        BlockPos startPosition = BlockPos.containing(from);
        FluidState startState = level.getFluidState(startPosition);
        if (startState.isEmpty()) {
            return null;
        }
        AABB startBounds = fluidBounds(startState, level, startPosition);
        if (startBounds == null || !startBounds.contains(from)) {
            return null;
        }

        double[] contiguousEnd = {0.0};
        AABB[] boundaryBounds = {startBounds};
        AcousticMaterial[] boundaryOtherMaterial = {null};
        walkBlocks(from, to, (position, endpoint) -> {
            FluidState state = level.getFluidState(position);
            if (state.isEmpty() || !state.getType().isSame(startState.getType())) {
                if (!state.isEmpty()) {
                    boundaryOtherMaterial[0] = AcousticMaterialRegistry.findFluid(state);
                }
                return false;
            }
            AABB bounds = fluidBounds(state, level, position);
            SegmentInterval interval = bounds == null ? null : intersectSegment(bounds, from, to);
            if (interval == null || interval.start() > contiguousEnd[0] + 1.0E-6) {
                return false;
            }
            if (interval.end() > contiguousEnd[0]) {
                contiguousEnd[0] = interval.end();
                boundaryBounds[0] = bounds;
            }
            return contiguousEnd[0] < 1.0 - 1.0E-7;
        });
        if (contiguousEnd[0] <= 1.0E-7 || contiguousEnd[0] >= 1.0 - 1.0E-7) {
            return null;
        }
        Vec3 delta = to.subtract(from);
        Vec3 location = from.add(delta.scale(contiguousEnd[0]));
        Vec3 normal = exitNormal(boundaryBounds[0], location, delta);
        return new SurfaceHit(
                location,
                normal,
                boundaryBounds[0],
                from.distanceTo(location),
                AcousticMaterialRegistry.findFluid(startState),
                boundaryOtherMaterial[0],
                true
        );
    }

    private static Vec3 exitNormal(AABB bounds, Vec3 point, Vec3 direction) {
        double best = Double.POSITIVE_INFINITY;
        Vec3 normal = Vec3.ZERO;
        if (direction.x < 0.0 && Math.abs(point.x - bounds.minX) < best) {
            best = Math.abs(point.x - bounds.minX);
            normal = NEGATIVE_X_NORMAL;
        } else if (direction.x > 0.0 && Math.abs(point.x - bounds.maxX) < best) {
            best = Math.abs(point.x - bounds.maxX);
            normal = POSITIVE_X_NORMAL;
        }
        double yDistance = direction.y < 0.0
                ? Math.abs(point.y - bounds.minY)
                : Math.abs(point.y - bounds.maxY);
        if (direction.y != 0.0 && yDistance < best) {
            best = yDistance;
            normal = direction.y < 0.0 ? NEGATIVE_Y_NORMAL : POSITIVE_Y_NORMAL;
        }
        double zDistance = direction.z < 0.0
                ? Math.abs(point.z - bounds.minZ)
                : Math.abs(point.z - bounds.maxZ);
        if (direction.z != 0.0 && zDistance < best) {
            normal = direction.z < 0.0 ? NEGATIVE_Z_NORMAL : POSITIVE_Z_NORMAL;
        }
        return normal == Vec3.ZERO ? POSITIVE_Y_NORMAL : normal;
    }

    static SurfaceHit firstSurface(BlockGetter level, Vec3 from, Vec3 to, SurfaceHit[] result) {
        result[0] = null;
        walkBlocks(from, to, (pos, endpoint) -> {
            if (endpoint) {
                return true;
            }
            int sceneCollisionClass = level instanceof AcousticScene scene
                    ? scene.collisionClass(pos)
                    : -1;
            if (sceneCollisionClass == 0) {
                return true;
            }
            BlockState state = level.getBlockState(pos);
            if (sceneCollisionClass < 0 && state.isAir()) {
                return true;
            }
            if (sceneCollisionClass == 1
                    || sceneCollisionClass < 0 && isCollisionShapeFullBlock(level, pos, state)) {
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
                    new Vec3(hit.getDirection().getStepX(), hit.getDirection().getStepY(), hit.getDirection().getStepZ()),
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

    private static float spectralAnchor(float[] bands) {
        return Mth.clamp(
                Math.max(
                        Math.max(pairEnergy(bands, 0), pairEnergy(bands, 2)),
                        Math.max(pairEnergy(bands, 4), pairEnergy(bands, 6))
                ),
                0.0F,
                1.0F
        );
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

    private static float meanSurfaceAbsorption(
            SurfaceHit hit,
            int fromInclusive,
            int toExclusive
    ) {
        float sum = 0.0F;
        for (int band = fromInclusive; band < toExclusive; band++) {
            sum += 1.0F - surfaceReflectedPower(
                    hit,
                    band,
                    AcousticMaterialRegistry.tuning().metersPerBlock()
            );
        }
        return sum / (toExclusive - fromInclusive);
    }

    static float surfaceReflectedPower(
            SurfaceHit hit,
            int band,
            double metersPerBlock
    ) {
        if (hit.fluidBoundary()) {
            float reflection = FluidAcoustics.interfaceReflectionAmplitude(
                    hit.material().medium().acousticImpedanceRayl(),
                    hit.boundaryOtherMaterial() == null
                            ? MediumProfile.AIR.acousticImpedanceRayl()
                            : hit.boundaryOtherMaterial().medium().acousticImpedanceRayl()
            );
            return reflection * reflection;
        }
        float transmission = hit.material().surfaceTransmission(band, metersPerBlock);
        return Mth.clamp(
                1.0F - hit.material().absorption(band) - transmission * transmission,
                0.0F,
                1.0F
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
        applyFluidBoundary(bands, sourceLeg.endFluid(), listenerLeg.startFluid());
        return new Transmission(
                bands,
                sourceLeg.firstBounds() != null
                        ? sourceLeg.firstBounds()
                        : listenerLeg.firstBounds(),
                sourceLeg.blockers() + listenerLeg.blockers(),
                sourceLeg.fluidDistance() + listenerLeg.fluidDistance(),
                sourceLeg.airDistance() + listenerLeg.airDistance(),
                sourceLeg.travelTimeSeconds() + listenerLeg.travelTimeSeconds(),
                Math.max(sourceLeg.fluidScattering(), listenerLeg.fluidScattering()),
                sourceLeg.startFluid(),
                listenerLeg.endFluid()
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

    private static void applyEmitterMediumResponse(
            float[] bands,
            EmitterMediumTransfer transfer
    ) {
        for (int band = 0; band < bands.length; band++) {
            bands[band] *= transfer.amplitude()[band];
        }
    }

    /**
     * Integrates near-field pressure energy on a finite voxel control surface.
     * Minecraft may expose an empty centre cell during the update which starts an
     * immersed block sound; a point sample would mistake that for a one-cubic-metre
     * bubble. The physical radiator is the block boundary, so its outward flux is what
     * determines coupling to air and fluid.
     */
    static EmitterMediumTransfer solveEmitterBoundary(
            BlockGetter level,
            Vec3 source
    ) {
        MediumSample local = sampleMedium(level, source);
        if (local.weight() > 1.0E-6F) {
            float[] amplitude = new float[AcousticBands.COUNT];
            for (int band = 0; band < amplitude.length; band++) {
                amplitude[band] = mix(
                        1.0F,
                        local.profile().gain(band),
                        local.weight()
                );
            }
            return new EmitterMediumTransfer(amplitude);
        }

        BlockPos cell = BlockPos.containing(source);
        float[] pressureEnergy = new float[AcousticBands.COUNT];
        double totalRadiationAdmittance = 0.0;
        final double patchSize = 0.5;
        final double outsideOffset = 1.0E-4;

        // Four exact-solid-angle patches per face are enough to resolve partial fluid
        // contact while keeping this boundary solve far cheaper than one propagation
        // ray bundle. Solid patches belong to the structure-borne solver and are not
        // counted as imaginary air apertures.
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                double plane = coordinate(cell, axis) + side;
                double distance = Math.max(
                        Math.abs(plane - coordinate(source, axis)),
                        1.0E-7
                );
                int firstTangent = (axis + 1) % 3;
                int secondTangent = (axis + 2) % 3;
                for (int firstPatch = 0; firstPatch < 2; firstPatch++) {
                    for (int secondPatch = 0; secondPatch < 2; secondPatch++) {
                        double firstMin = coordinate(cell, firstTangent)
                                + firstPatch * patchSize
                                - coordinate(source, firstTangent);
                        double firstMax = firstMin + patchSize;
                        double secondMin = coordinate(cell, secondTangent)
                                + secondPatch * patchSize
                                - coordinate(source, secondTangent);
                        double secondMax = secondMin + patchSize;
                        double solidAngle = rectangleSolidAngle(
                                distance,
                                firstMin,
                                firstMax,
                                secondMin,
                                secondMax
                        );
                        if (solidAngle <= 1.0E-12) {
                            continue;
                        }

                        double[] point = {source.x, source.y, source.z};
                        point[axis] = plane + (side == 0 ? -outsideOffset : outsideOffset);
                        point[firstTangent] = coordinate(cell, firstTangent)
                                + (firstPatch + 0.5) * patchSize;
                        point[secondTangent] = coordinate(cell, secondTangent)
                                + (secondPatch + 0.5) * patchSize;
                        Vec3 boundaryPoint = new Vec3(point[0], point[1], point[2]);
                        if (pointInsideCollision(level, boundaryPoint)) {
                            continue;
                        }

                        MediumSample boundaryMedium = sampleBoundaryMedium(level, boundaryPoint);
                        // Pressure and normal particle velocity are continuous at an
                        // open fluid boundary. For the pressure-calibrated Minecraft
                        // sample, u_n = p / Z and the outward intensity is p*u_n;
                        // therefore each patch is weighted by its acoustic admittance.
                        // This also prevents a tiny wetted corner from acting like a
                        // wholly submerged source merely because water has a large Z.
                        double patchAdmittance = solidAngle
                                / boundaryMedium.profile().acousticImpedanceRayl();
                        totalRadiationAdmittance += patchAdmittance;
                        for (int band = 0; band < pressureEnergy.length; band++) {
                            float pressure = mix(
                                    1.0F,
                                    boundaryMedium.profile().gain(band),
                                    boundaryMedium.weight()
                            );
                            pressureEnergy[band] += (float) (
                                    patchAdmittance * pressure * pressure
                            );
                        }
                    }
                }
            }
        }

        if (totalRadiationAdmittance <= 1.0E-12) {
            return EmitterMediumTransfer.AIR;
        }
        float[] amplitude = new float[AcousticBands.COUNT];
        for (int band = 0; band < amplitude.length; band++) {
            amplitude[band] = Mth.clamp(
                    (float) Math.sqrt(
                            pressureEnergy[band] / totalRadiationAdmittance
                    ),
                    0.0F,
                    1.0F
            );
        }
        return new EmitterMediumTransfer(amplitude);
    }

    private static double rectangleSolidAngle(
            double distance,
            double firstMin,
            double firstMax,
            double secondMin,
            double secondMax
    ) {
        return Math.abs(
                solidAnglePrimitive(firstMax, secondMax, distance)
                        - solidAnglePrimitive(firstMin, secondMax, distance)
                        - solidAnglePrimitive(firstMax, secondMin, distance)
                        + solidAnglePrimitive(firstMin, secondMin, distance)
        );
    }

    private static double solidAnglePrimitive(double first, double second, double distance) {
        return Math.atan2(
                first * second,
                distance * Math.sqrt(
                        distance * distance + first * first + second * second
                )
        );
    }

    private static double coordinate(BlockPos position, int axis) {
        return switch (axis) {
            case 0 -> position.getX();
            case 1 -> position.getY();
            default -> position.getZ();
        };
    }

    private static double coordinate(Vec3 position, int axis) {
        return switch (axis) {
            case 0 -> position.x;
            case 1 -> position.y;
            default -> position.z;
        };
    }

    private static MediumSample sampleMedium(BlockGetter level, Vec3 point) {
        BlockPos pos = new BlockPos(Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z));
        FluidState fluidState = level.getFluidState(pos);
        if (fluidState.isEmpty()) {
            return new MediumSample(MediumProfile.AIR, null, 0.0F);
        }
        AABB bounds = fluidBounds(fluidState, level, pos);
        if (bounds == null || !bounds.contains(point)) {
            return new MediumSample(MediumProfile.AIR, null, 0.0F);
        }
        AcousticMaterial material = AcousticMaterialRegistry.findFluid(fluidState);
        MediumProfile profile = material.medium();
        float depth = (float) Math.max(0.0, bounds.maxY - point.y);
        return new MediumSample(
                profile,
                material,
                Mth.clamp(depth / profile.transitionDepth(), 0.0F, 1.0F)
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
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        AtmosphereCache cache = atmosphereCache;
        if (cache == null || !cache.matches(tuning)) {
            cache = AtmosphereCache.from(tuning);
            atmosphereCache = cache;
        }
        return AtmosphericAbsorption.amplitudeGain(
                cache.nepersPerMeter()[band], distance
        );
    }

    private static MediumSample sampleBoundaryMedium(BlockGetter level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        FluidState fluidState = level.getFluidState(pos);
        if (fluidState.isEmpty()) {
            return new MediumSample(MediumProfile.AIR, null, 0.0F);
        }
        // FluidState's visual/collision shape deliberately leaves a small gap above a
        // full source block. That gap is a rendering convention, not an acoustic air
        // aperture. Boundary wetting follows conserved fluid amount; flowing levels
        // still expose the corresponding unfilled part of a vertical face.
        float fill = Mth.clamp(fluidState.getAmount() / 8.0F, 0.0F, 1.0F);
        if (point.y - pos.getY() > fill + 1.0E-6) {
            return new MediumSample(MediumProfile.AIR, null, 0.0F);
        }
        AcousticMaterial material = AcousticMaterialRegistry.findFluid(fluidState);
        return new MediumSample(material.medium(), material, 1.0F);
    }

    static float sphericalSpreadingGain(double distanceMeters, AcousticTuning tuning) {
        double reference = tuning.distanceReferenceMeters();
        double distance = Math.max(reference, Math.max(0.0, distanceMeters));
        double denominator = reference + tuning.distanceRolloffFactor()
                * (distance - reference);
        return (float) Math.min(1.0, reference / Math.max(reference, denominator));
    }

    private record AtmosphereCache(
            float temperatureCelsius,
            float humidityPercent,
            float pressureKilopascals,
            float absorptionScale,
            double[] nepersPerMeter
    ) {
        private static AtmosphereCache from(AcousticTuning tuning) {
            double[] coefficients = new double[AcousticBands.COUNT];
            for (int band = 0; band < coefficients.length; band++) {
                coefficients[band] = AtmosphericAbsorption.amplitudeNepersPerMeter(
                        AcousticBands.CENTERS_HZ[band],
                        tuning.airTemperatureCelsius(),
                        tuning.relativeHumidityPercent(),
                        tuning.airPressureKilopascals()
                ) * tuning.airAbsorptionScale();
            }
            return new AtmosphereCache(
                    tuning.airTemperatureCelsius(),
                    tuning.relativeHumidityPercent(),
                    tuning.airPressureKilopascals(),
                    tuning.airAbsorptionScale(),
                    coefficients
            );
        }

        private boolean matches(AcousticTuning tuning) {
            return Float.compare(temperatureCelsius, tuning.airTemperatureCelsius()) == 0
                    && Float.compare(humidityPercent, tuning.relativeHumidityPercent()) == 0
                    && Float.compare(pressureKilopascals, tuning.airPressureKilopascals()) == 0
                    && Float.compare(absorptionScale, tuning.airAbsorptionScale()) == 0;
        }
    }

    @FunctionalInterface
    private interface BlockVisitor {
        boolean visit(BlockPos pos, boolean endpoint);
    }

    private static FluidStats applyFluidSegments(
            float[] bands,
            List<FluidSegment> segments,
            double rayDistance,
            float startMediumWeight,
            float endMediumWeight
    ) {
        if (segments.isEmpty() || rayDistance <= 1.0E-7) {
            double airMeters = AcousticMaterialRegistry.tuning().meters(rayDistance);
            return new FluidStats(
                    0.0F,
                    (float) rayDistance,
                    FluidAcoustics.travelTimeSeconds(
                            airMeters,
                            MediumProfile.AIR.soundSpeedMetersPerSecond()
                    ),
                    0.0F,
                    null,
                    null
            );
        }

        segments.sort(Comparator.comparingDouble(FluidSegment::start));
        List<FluidSegment> merged = new ArrayList<>(segments.size());
        for (FluidSegment segment : segments) {
            if (!merged.isEmpty()) {
                FluidSegment previous = merged.get(merged.size() - 1);
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
        for (int index = 0; index < merged.size(); index++) {
            FluidSegment segment = merged.get(index);
            double normalizedLength = Math.max(0.0, segment.end() - segment.start());
            float distance = (float) (normalizedLength * rayDistance);
            if (distance <= 1.0E-6F) {
                continue;
            }
            totalDistance += distance;
            scatteringDistance += distance * segment.material().scattering();
            for (int band = 0; band < bands.length; band++) {
                bands[band] *= segment.material().transmissionGain(
                        band,
                        tuning.meters(distance)
                );
            }

            // Each physical interface is evaluated exactly once. Adjacent unlike
            // fluids transition directly; a real air gap produces the two separate
            // interfaces that actually exist. Endpoints already inside a fluid do not
            // invent an air/fluid boundary at the emitter or listener.
            if (segment.start() > 1.0E-5
                    && (index == 0 || merged.get(index - 1).end() < segment.start() - 1.0E-6)) {
                float coverage = segment.end() >= 1.0 - 1.0E-5
                        ? endMediumWeight
                        : 1.0F;
                applyFluidBoundary(bands, null, segment, coverage);
            }
            if (segment.end() < 1.0 - 1.0E-5) {
                FluidSegment next = index + 1 < merged.size()
                        && merged.get(index + 1).start() <= segment.end() + 1.0E-6
                        ? merged.get(index + 1)
                        : null;
                float coverage = segment.start() <= 1.0E-5
                        ? startMediumWeight
                        : 1.0F;
                applyFluidBoundary(bands, segment, next, coverage);
            }
        }
        float airDistance = Math.max(0.0F, (float) rayDistance - totalDistance);
        double travelTime = FluidAcoustics.travelTimeSeconds(
                tuning.meters(airDistance),
                MediumProfile.AIR.soundSpeedMetersPerSecond()
        );
        for (FluidSegment segment : merged) {
            double distance = Math.max(0.0, segment.end() - segment.start()) * rayDistance;
            travelTime += FluidAcoustics.travelTimeSeconds(
                    tuning.meters(distance),
                    segment.material().medium().soundSpeedMetersPerSecond()
            );
        }
        return new FluidStats(
                totalDistance,
                airDistance,
                travelTime,
                totalDistance <= 1.0E-6F ? 0.0F : scatteringDistance / totalDistance,
                merged.get(0).start() <= 1.0E-5
                        ? merged.get(0).material()
                        : null,
                merged.get(merged.size() - 1).end() >= 1.0 - 1.0E-5
                        ? merged.get(merged.size() - 1).material()
                        : null
        );
    }

    private static void applyFluidBoundary(
            float[] bands,
            FluidSegment first,
            FluidSegment second
    ) {
        applyFluidBoundary(bands, first, second, 1.0F);
    }

    private static void applyFluidBoundary(
            float[] bands,
            FluidSegment first,
            FluidSegment second,
            float coverage
    ) {
        applyFluidBoundary(
                bands,
                first == null ? null : first.material(),
                second == null ? null : second.material(),
                coverage
        );
    }

    private static void applyFluidBoundary(
            float[] bands,
            AcousticMaterial first,
            AcousticMaterial second
    ) {
        applyFluidBoundary(bands, first, second, 1.0F);
    }

    private static void applyFluidBoundary(
            float[] bands,
            AcousticMaterial first,
            AcousticMaterial second,
            float coverage
    ) {
        float submergedFraction = Mth.clamp(coverage, 0.0F, 1.0F);
        if (submergedFraction <= 1.0E-6F) {
            return;
        }
        MediumProfile firstProfile = first == null ? MediumProfile.AIR : first.medium();
        MediumProfile secondProfile = second == null ? MediumProfile.AIR : second.medium();
        if (firstProfile == secondProfile
                || Float.compare(
                        firstProfile.acousticImpedanceRayl(),
                        secondProfile.acousticImpedanceRayl()
                ) == 0) {
            return;
        }
        float physicalTransmission = FluidAcoustics.interfaceTransmissionAmplitude(
                firstProfile.acousticImpedanceRayl(),
                secondProfile.acousticImpedanceRayl()
        );
        if (physicalTransmission >= 0.999999F) {
            return;
        }
        for (int band = 0; band < bands.length; band++) {
            float firstSurface = first == null ? 1.0F : first.boundaryTransmission(band);
            float secondSurface = second == null ? 1.0F : second.boundaryTransmission(band);
            float surfaceCoherence = (float) Math.sqrt(firstSurface * secondSurface);
            float submergedAmplitude = physicalTransmission * surfaceCoherence;
            // A source or ear intersecting the surface radiates through its air-exposed
            // and submerged fractions in parallel. Sum those incoherent areas as power
            // so an infinitesimal contact approaches the dry response continuously,
            // while a fully submerged endpoint retains the exact interface solution.
            bands[band] *= (float) Math.sqrt(
                    (1.0F - submergedFraction)
                            + submergedFraction * submergedAmplitude * submergedAmplitude
            );
        }
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
        double mergedStart = intervals.get(0).start();
        double mergedEnd = intervals.get(0).end();
        AABB firstBounds = intervals.get(0).bounds();
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
            float airDistance,
            double travelTimeSeconds,
            float fluidScattering,
            AcousticMaterial startFluid,
            AcousticMaterial endFluid
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

    private record FluidStats(
            float distance,
            float airDistance,
            double travelTimeSeconds,
            float scattering,
            AcousticMaterial startFluid,
            AcousticMaterial endFluid
    ) {
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
            AcousticMaterial material,
            AcousticMaterial boundaryOtherMaterial,
            boolean fluidBoundary
    ) {
        SurfaceHit(
                Vec3 location,
                Vec3 normal,
                AABB bounds,
                double distance,
                AcousticMaterial material
        ) {
            this(location, normal, bounds, distance, material, null, false);
        }
    }

    private record DiffractionPath(
            float[] bands,
            double pathDistance,
            double airDistance,
            List<DirectionalArrivalField.Arrival> arrivals,
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

    /**
     * Uses the immutable section/component graph only for a mathematical no-path
     * proof. A component touching uncaptured space remains open and therefore falls
     * through to the complete voxel search; distant routes are never rejected merely
     * because they leave the current snapshot boundary.
     */
    private static boolean airComponentsProveDisconnection(
            BlockGetter level,
            List<AirCellKey> starts,
            List<AirCellKey> goals
    ) {
        if (!(level instanceof AcousticScene scene)) {
            return false;
        }
        int[] startComponents = new int[starts.size()];
        int startCount = 0;
        boolean allStartsClosed = true;
        for (AirCellKey start : starts) {
            int component = scene.airComponentId(start.x(), start.y(), start.z());
            if (component < 0) {
                return false;
            }
            boolean duplicate = false;
            for (int index = 0; index < startCount; index++) {
                duplicate |= startComponents[index] == component;
            }
            if (!duplicate) {
                startComponents[startCount++] = component;
                allStartsClosed &= scene.isAirComponentClosed(component);
            }
        }
        int[] goalComponents = new int[goals.size()];
        int goalCount = 0;
        boolean allGoalsClosed = true;
        for (AirCellKey goal : goals) {
            int component = scene.airComponentId(goal.x(), goal.y(), goal.z());
            if (component < 0) {
                return false;
            }
            for (int startIndex = 0; startIndex < startCount; startIndex++) {
                if (startComponents[startIndex] == component) {
                    return false;
                }
            }
            boolean duplicate = false;
            for (int index = 0; index < goalCount; index++) {
                duplicate |= goalComponents[index] == component;
            }
            if (!duplicate) {
                goalComponents[goalCount++] = component;
                allGoalsClosed &= scene.isAirComponentClosed(component);
            }
        }
        return startCount > 0 && goalCount > 0
                && (allStartsClosed || allGoalsClosed);
    }

    private record VerifiedAirBackbone(
            PropagationGraphPath path,
            boolean listenerUnchanged
    ) {
    }

    private record AirPathCacheKey(
            Object level,
            long sceneRevision,
            AirCellKey source,
            AirCellKey listener
    ) {
    }

    private record AirPathMissCacheKey(
            AirPathCacheKey topology,
            int sourceSeeds,
            int listenerSeeds
    ) {
    }

    private record ListenerAirFieldKey(
            Object level,
            long sceneRevision,
            AirCellKey listener,
            int listenerSeeds
    ) {
    }

    private record ArrivalTransferKey(
            GraphPointKey listener,
            AirCellKey sample
    ) {
    }

    private record SparsePathCacheKey(
            Object level,
            long sceneRevision,
            AirCellKey source,
            AirCellKey listener,
            int candidateBudget,
            AcousticTuning tuning
    ) {
    }

    private record StructuralPathCacheKey(
            Object level,
            long sceneRevision,
            GraphPointKey source,
            GraphPointKey listener,
            AcousticTuning tuning
    ) {
    }

    private static final class TransmissionCacheKey {
        private final Object level;
        private final long sceneRevision;
        private final long materialRevision;
        private final Vec3 from;
        private final Vec3 to;
        private final boolean ignoreFromBlock;
        private final boolean ignoreToBlock;
        private final int hash;

        private TransmissionCacheKey(
                Object level,
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
        private final Object level;
        private final long sceneRevision;
        private final long materialRevision;
        private final GraphPointKey source;
        private final GraphPointKey listener;
        private final RoomProbe sourceProbe;
        private final RoomProbe listenerProbe;
        private final TraceQuality quality;
        private final int hash;

        private TraceCacheKey(
                Object level,
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

    private record DenseAirSearchResult(List<Long> path, int expanded) {
    }

    record DenseAirSearchMetrics(
            boolean connected,
            int expanded,
            long elapsedNanoseconds
    ) {
    }

    record DenseAirSearchStorageMetrics(
            int sceneCellCapacity,
            int sectionTableSlots,
            int retainedPages,
            long estimatedStateBytes
    ) {
    }

    private record DenseEndpointHeuristic(
            int[] xs,
            int[] ys,
            int[] zs,
            double[] connectors
    ) {
        private static DenseEndpointHeuristic from(
                List<AirCellKey> cells,
                Vec3 endpoint
        ) {
            int[] xs = new int[cells.size()];
            int[] ys = new int[cells.size()];
            int[] zs = new int[cells.size()];
            double[] connectors = new double[cells.size()];
            for (int index = 0; index < cells.size(); index++) {
                AirCellKey cell = cells.get(index);
                xs[index] = cell.x();
                ys[index] = cell.y();
                zs[index] = cell.z();
                connectors[index] = distanceFromCellCenter(
                        cell.x(), cell.y(), cell.z(), endpoint
                );
            }
            return new DenseEndpointHeuristic(xs, ys, zs, connectors);
        }

        private double distance(int x, int y, int z) {
            double best = Double.POSITIVE_INFINITY;
            for (int index = 0; index < xs.length; index++) {
                double candidate = Math.abs(x - xs[index])
                        + Math.abs(y - ys[index])
                        + Math.abs(z - zs[index])
                        + connectors[index];
                if (candidate < best) {
                    best = candidate;
                }
            }
            return best;
        }
    }

    /**
     * Search state indexed by the scene's stable dense cell ids. Compact scenes use
     * stamped contiguous arrays for cache locality. Large scenes switch to bounded
     * 16^3 pages, because multiplying six world-sized arrays by every propagation
     * worker can exhaust the client heap.
     */
    private static final class DenseAirSearchScratch {
        private static final int PAGE_SHIFT = 12;
        private static final int PAGE_MASK = (1 << PAGE_SHIFT) - 1;
        private static final int MAX_RETAINED_PAGES = 8;
        private static final int MAX_FLAT_CELLS = 131072;

        private DenseAirSearchPage[] pagesBySection = new DenseAirSearchPage[0];
        private DenseAirSearchPage[] retainedPages =
                new DenseAirSearchPage[MAX_RETAINED_PAGES];
        private final int[] hotSections = new int[16];
        private final DenseAirSearchPage[] hotPages = new DenseAirSearchPage[16];
        private int retainedPageCount;
        private int[] touchedSections = new int[32];
        private int touchedSectionCount;
        private int stamp;
        private boolean flatMode;
        private double[] flatForwardDistances = new double[0];
        private double[] flatBackwardDistances = new double[0];
        private int[] flatForwardPrevious = new int[0];
        private int[] flatBackwardPrevious = new int[0];
        private int[] flatForwardStamps = new int[0];
        private int[] flatBackwardStamps = new int[0];
        private final IntAirGraphHeap forwardFrontier = new IntAirGraphHeap(1024);
        private final IntAirGraphHeap backwardFrontier = new IntAirGraphHeap(1024);
        private final IntBucketFrontier forwardBuckets = new IntBucketFrontier();
        private final IntBucketFrontier backwardBuckets = new IntBucketFrontier();

        private void begin(int capacity, boolean prepareHeaps) {
            end();
            flatMode = capacity <= MAX_FLAT_CELLS;
            if (flatMode && flatForwardDistances.length < capacity) {
                flatForwardDistances = new double[capacity];
                flatBackwardDistances = new double[capacity];
                flatForwardPrevious = new int[capacity];
                flatBackwardPrevious = new int[capacity];
                flatForwardStamps = new int[capacity];
                flatBackwardStamps = new int[capacity];
            }
            int sectionCapacity = (capacity + PAGE_MASK) >>> PAGE_SHIFT;
            if (!flatMode && pagesBySection.length < sectionCapacity) {
                pagesBySection = Arrays.copyOf(pagesBySection, sectionCapacity);
            } else if (!flatMode
                    && pagesBySection.length > Math.max(256, sectionCapacity * 4)) {
                pagesBySection = new DenseAirSearchPage[sectionCapacity];
            }
            if (++stamp == 0) {
                Arrays.fill(flatForwardStamps, 0);
                Arrays.fill(flatBackwardStamps, 0);
                for (int index = 0; index < retainedPageCount; index++) {
                    retainedPages[index].clearStamps();
                }
                stamp = 1;
            }
            Arrays.fill(hotSections, -1);
            Arrays.fill(hotPages, null);
            if (prepareHeaps) {
                forwardFrontier.begin();
                backwardFrontier.begin();
            }
            forwardBuckets.clear();
            backwardBuckets.clear();
        }

        private void end() {
            for (int index = 0; index < touchedSectionCount; index++) {
                int section = touchedSections[index];
                DenseAirSearchPage page = pagesBySection[section];
                pagesBySection[section] = null;
                if (page != null && retainedPageCount < MAX_RETAINED_PAGES) {
                    retainedPages[retainedPageCount++] = page;
                }
            }
            touchedSectionCount = 0;
            Arrays.fill(hotSections, -1);
            Arrays.fill(hotPages, null);
            flatMode = false;
        }

        private DenseAirSearchPage pageForWrite(int cell) {
            int section = cell >>> PAGE_SHIFT;
            int hotSlot = section & 15;
            if (hotSections[hotSlot] == section) {
                return hotPages[hotSlot];
            }
            DenseAirSearchPage page = pagesBySection[section];
            if (page != null) {
                hotSections[hotSlot] = section;
                hotPages[hotSlot] = page;
                return page;
            }
            if (retainedPageCount > 0) {
                page = retainedPages[--retainedPageCount];
                retainedPages[retainedPageCount] = null;
            } else {
                page = new DenseAirSearchPage();
            }
            pagesBySection[section] = page;
            hotSections[hotSlot] = section;
            hotPages[hotSlot] = page;
            if (touchedSectionCount == touchedSections.length) {
                touchedSections = Arrays.copyOf(
                        touchedSections, touchedSections.length << 1
                );
            }
            touchedSections[touchedSectionCount++] = section;
            return page;
        }

        private DenseAirSearchPage page(int cell) {
            int section = cell >>> PAGE_SHIFT;
            int hotSlot = section & 15;
            if (hotSections[hotSlot] == section) {
                return hotPages[hotSlot];
            }
            DenseAirSearchPage page = section < pagesBySection.length
                    ? pagesBySection[section] : null;
            if (page != null) {
                hotSections[hotSlot] = section;
                hotPages[hotSlot] = page;
            }
            return page;
        }

        private double forwardDistance(int cell) {
            if (flatMode) {
                return flatForwardStamps[cell] == stamp
                        ? flatForwardDistances[cell]
                        : Double.POSITIVE_INFINITY;
            }
            DenseAirSearchPage page = page(cell);
            int local = cell & PAGE_MASK;
            return page != null && page.forwardStamps[local] == stamp
                    ? page.forwardDistances[local] : Double.POSITIVE_INFINITY;
        }

        private double backwardDistance(int cell) {
            if (flatMode) {
                return flatBackwardStamps[cell] == stamp
                        ? flatBackwardDistances[cell]
                        : Double.POSITIVE_INFINITY;
            }
            DenseAirSearchPage page = page(cell);
            int local = cell & PAGE_MASK;
            return page != null && page.backwardStamps[local] == stamp
                    ? page.backwardDistances[local] : Double.POSITIVE_INFINITY;
        }

        private void setForward(int cell, double distance, int previous) {
            if (flatMode) {
                flatForwardStamps[cell] = stamp;
                flatForwardDistances[cell] = distance;
                flatForwardPrevious[cell] = previous;
                return;
            }
            DenseAirSearchPage page = pageForWrite(cell);
            int local = cell & PAGE_MASK;
            page.forwardStamps[local] = stamp;
            page.forwardDistances[local] = distance;
            page.forwardPrevious[local] = previous;
        }

        private void setBackward(int cell, double distance, int previous) {
            if (flatMode) {
                flatBackwardStamps[cell] = stamp;
                flatBackwardDistances[cell] = distance;
                flatBackwardPrevious[cell] = previous;
                return;
            }
            DenseAirSearchPage page = pageForWrite(cell);
            int local = cell & PAGE_MASK;
            page.backwardStamps[local] = stamp;
            page.backwardDistances[local] = distance;
            page.backwardPrevious[local] = previous;
        }

        private int forwardPrevious(int cell) {
            if (flatMode) {
                return flatForwardPrevious[cell];
            }
            return page(cell).forwardPrevious[cell & PAGE_MASK];
        }

        private int backwardPrevious(int cell) {
            if (flatMode) {
                return flatBackwardPrevious[cell];
            }
            return page(cell).backwardPrevious[cell & PAGE_MASK];
        }

        private DenseAirSearchStorageMetrics storageMetrics(int sceneCellCapacity) {
            long pageBytes = 2L * Double.BYTES * 4096
                    + 4L * Integer.BYTES * 4096;
            long estimated = (long) pagesBySection.length * Long.BYTES
                    + (long) touchedSections.length * Integer.BYTES
                    + (long) retainedPageCount * pageBytes
                    + 2L * Double.BYTES * flatForwardDistances.length
                    + 4L * Integer.BYTES * flatForwardDistances.length;
            return new DenseAirSearchStorageMetrics(
                    sceneCellCapacity,
                    pagesBySection.length,
                    retainedPageCount,
                    estimated
            );
        }
    }

    private static final class DenseAirSearchPage {
        private final double[] forwardDistances = new double[4096];
        private final double[] backwardDistances = new double[4096];
        private final int[] forwardPrevious = new int[4096];
        private final int[] backwardPrevious = new int[4096];
        private final int[] forwardStamps = new int[4096];
        private final int[] backwardStamps = new int[4096];

        private void clearStamps() {
            Arrays.fill(forwardStamps, 0);
            Arrays.fill(backwardStamps, 0);
        }
    }

    /** Dial-style monotone frontier for the integer f-cost of a six-neighbour grid. */
    private static final class IntBucketFrontier {
        private int[] heads = new int[256];
        private int[] cells = new int[2048];
        private int[] next = new int[2048];
        private double[] distances = new double[2048];
        private int entries;
        private int size;
        private int minimumPriority;
        private double lastDistance;

        private IntBucketFrontier() {
            Arrays.fill(heads, -1);
        }

        private void clear() {
            Arrays.fill(heads, -1);
            entries = 0;
            size = 0;
            minimumPriority = Integer.MAX_VALUE;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private int peekPriority() {
            advance();
            return minimumPriority;
        }

        private double lastDistance() {
            return lastDistance;
        }

        private void add(int cell, double distance, int priority) {
            ensureBucket(priority);
            ensureEntry(entries + 1);
            cells[entries] = cell;
            distances[entries] = distance;
            next[entries] = heads[priority];
            heads[priority] = entries++;
            size++;
            if (priority < minimumPriority) {
                minimumPriority = priority;
            }
        }

        private int poll() {
            advance();
            int entry = heads[minimumPriority];
            heads[minimumPriority] = next[entry];
            size--;
            lastDistance = distances[entry];
            return cells[entry];
        }

        private void advance() {
            if (size == 0) {
                minimumPriority = Integer.MAX_VALUE;
                return;
            }
            while (minimumPriority < heads.length
                    && heads[minimumPriority] < 0) {
                minimumPriority++;
            }
        }

        private void ensureBucket(int priority) {
            if (priority < heads.length) {
                return;
            }
            int oldLength = heads.length;
            int nextLength = oldLength;
            while (nextLength <= priority) {
                nextLength <<= 1;
            }
            heads = Arrays.copyOf(heads, nextLength);
            Arrays.fill(heads, oldLength, nextLength, -1);
        }

        private void ensureEntry(int required) {
            if (required <= cells.length) {
                return;
            }
            int nextLength = Math.max(required, cells.length + (cells.length >>> 1));
            cells = Arrays.copyOf(cells, nextLength);
            next = Arrays.copyOf(next, nextLength);
            distances = Arrays.copyOf(distances, nextLength);
        }
    }

    /** Allocation-free integer heap for dense scene cell ids. */
    private static final class IntAirGraphHeap {
        private int[] cells;
        private double[] distances;
        private double[] priorities;
        private int size;
        private double lastDistance;

        private IntAirGraphHeap(int initialCapacity) {
            int capacity = Math.max(16, initialCapacity);
            cells = new int[capacity];
            distances = new double[capacity];
            priorities = new double[capacity];
        }

        private void begin() {
            size = 0;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private double peekPriority() {
            return size == 0 ? Double.POSITIVE_INFINITY : priorities[0];
        }

        private double lastDistance() {
            return lastDistance;
        }

        private void add(int cell, double distance, double priority) {
            ensureCapacity(size + 1);
            int index = size++;
            siftUp(index, cell, distance, priority);
        }

        private void siftUp(
                int index,
                int cell,
                double distance,
                double priority
        ) {
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (!AirGraphHeap.comesBefore(
                        priority, distance,
                        priorities[parent], distances[parent]
                )) {
                    break;
                }
                cells[index] = cells[parent];
                distances[index] = distances[parent];
                priorities[index] = priorities[parent];
                index = parent;
            }
            cells[index] = cell;
            distances[index] = distance;
            priorities[index] = priority;
        }

        private int poll() {
            int result = cells[0];
            lastDistance = distances[0];
            int last = --size;
            if (last == 0) {
                return result;
            }
            int movedCell = cells[last];
            double movedDistance = distances[last];
            double movedPriority = priorities[last];
            int index = 0;
            int half = size >>> 1;
            while (index < half) {
                int child = (index << 1) + 1;
                int right = child + 1;
                if (right < size && AirGraphHeap.comesBefore(
                        priorities[right], distances[right],
                        priorities[child], distances[child]
                )) {
                    child = right;
                }
                if (!AirGraphHeap.comesBefore(
                        priorities[child], distances[child],
                        movedPriority, movedDistance
                )) {
                    break;
                }
                cells[index] = cells[child];
                distances[index] = distances[child];
                priorities[index] = priorities[child];
                index = child;
            }
            cells[index] = movedCell;
            distances[index] = movedDistance;
            priorities[index] = movedPriority;
            return result;
        }

        private void ensureCapacity(int required) {
            if (required <= cells.length) {
                return;
            }
            int next = Math.max(required, cells.length + (cells.length >>> 1));
            cells = Arrays.copyOf(cells, next);
            distances = Arrays.copyOf(distances, next);
            priorities = Arrays.copyOf(priorities, next);
        }
    }

    /** Allocation-free binary heap for the voxel A* frontier. */
    private static final class AirGraphHeap {
        private long[] cells;
        private double[] distances;
        private double[] priorities;
        private int size;
        private double lastDistance;

        private AirGraphHeap(int initialCapacity) {
            int capacity = Math.max(16, initialCapacity);
            cells = new long[capacity];
            distances = new double[capacity];
            priorities = new double[capacity];
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private double peekPriority() {
            return size == 0 ? Double.POSITIVE_INFINITY : priorities[0];
        }

        private double lastDistance() {
            return lastDistance;
        }

        private void add(long cell, double distance, double priority) {
            ensureCapacity(size + 1);
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (!comesBefore(
                        priority, distance,
                        priorities[parent], distances[parent]
                )) {
                    break;
                }
                cells[index] = cells[parent];
                distances[index] = distances[parent];
                priorities[index] = priorities[parent];
                index = parent;
            }
            cells[index] = cell;
            distances[index] = distance;
            priorities[index] = priority;
        }

        private long poll() {
            long result = cells[0];
            lastDistance = distances[0];
            int last = --size;
            if (last == 0) {
                return result;
            }
            long movedCell = cells[last];
            double movedDistance = distances[last];
            double movedPriority = priorities[last];
            int index = 0;
            int half = size >>> 1;
            while (index < half) {
                int child = (index << 1) + 1;
                int right = child + 1;
                if (right < size && comesBefore(
                        priorities[right], distances[right],
                        priorities[child], distances[child]
                )) {
                    child = right;
                }
                if (!comesBefore(
                        priorities[child], distances[child],
                        movedPriority, movedDistance
                )) {
                    break;
                }
                cells[index] = cells[child];
                distances[index] = distances[child];
                priorities[index] = priorities[child];
                index = child;
            }
            cells[index] = movedCell;
            distances[index] = movedDistance;
            priorities[index] = movedPriority;
            return result;
        }

        private static boolean comesBefore(
                double leftPriority,
                double leftDistance,
                double rightPriority,
                double rightDistance
        ) {
            int priorityOrder = Double.compare(leftPriority, rightPriority);
            if (priorityOrder != 0) {
                return priorityOrder < 0;
            }
            // All monotone routes in open space have the same admissible f score.
            // Preferring the deeper state follows one shortest route instead of
            // breadth-expanding the entire equal-cost Manhattan volume.
            return leftDistance > rightDistance;
        }

        private void ensureCapacity(int required) {
            if (required <= cells.length) {
                return;
            }
            int next = Math.max(required, cells.length + (cells.length >>> 1));
            cells = Arrays.copyOf(cells, next);
            distances = Arrays.copyOf(distances, next);
            priorities = Arrays.copyOf(priorities, next);
        }
    }

    /**
     * Exact listener-rooted route forest shared by every primary source in one immutable
     * scene. Each registered branch is a suffix of a shortest six-neighbour air path, so
     * a later A* can terminate on that branch with its measured remaining cost instead of
     * solving the same listener half of the graph again. A bounded connector search is
     * only an acceleration layer: if it cannot prove a connection, the original complete
     * bidirectional search still runs and then grows this field.
     */
    private static final class ListenerAirRouteField {
        private final Vec3 anchorListener;
        private final List<AirCellKey> goalCells;
        private final Map<Long, RouteStep> routes = new ConcurrentHashMap<>();
        private final Map<ArrivalTransferKey, Float> arrivalTransfers = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<ArrivalTransferKey> arrivalTransferOrder =
                new ConcurrentLinkedQueue<>();
        private volatile boolean hasBackbone;
        private boolean buildingBackbone;

        private ListenerAirRouteField(
                Vec3 anchorListener,
                List<AirCellKey> goalCells,
                ListenerAirRouteField inherited
        ) {
            this.anchorListener = anchorListener;
            this.goalCells = List.copyOf(goalCells);
            if (inherited != null) {
                for (Map.Entry<Long, RouteStep> entry : inherited.routes.entrySet()) {
                    long cell = entry.getKey();
                    RouteStep step = entry.getValue();
                    routes.put(cell, step);
                }
                hasBackbone = inherited.hasBackbone();
            }
            for (AirCellKey goal : goalCells) {
                long packed = packAirCell(goal);
                double distance = distanceFromCellCenter(
                        goal.x(), goal.y(), goal.z(), anchorListener
                );
                routes.merge(
                        packed,
                        new RouteStep(distance, Long.MIN_VALUE),
                        (known, candidate) -> known.distance() <= candidate.distance()
                                ? known : candidate
                );
            }
        }

        private boolean hasBackbone() {
            return hasBackbone;
        }

        private synchronized boolean claimBackboneBuild() {
            if (hasBackbone || buildingBackbone) {
                return false;
            }
            buildingBackbone = true;
            return true;
        }

        private synchronized void awaitBackboneBuild() {
            while (buildingBackbone && !hasBackbone) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private synchronized void finishBackboneBuild() {
            buildingBackbone = false;
            notifyAll();
        }

        private void register(List<Long> cells) {
            if (cells.isEmpty()) {
                return;
            }
            int last = cells.size() - 1;
            long finalCell = cells.get(last);
            double remaining = distanceFromCellCenter(
                    BlockPos.getX(finalCell),
                    BlockPos.getY(finalCell),
                    BlockPos.getZ(finalCell),
                    anchorListener
            );
            long next = Long.MIN_VALUE;
            for (int index = last; index >= 0; index--) {
                long cell = cells.get(index);
                RouteStep known = routes.get(cell);
                if (known == null || remaining + 1.0E-7 < known.distance()) {
                    RouteStep candidate = new RouteStep(remaining, next);
                    routes.merge(
                            cell,
                            candidate,
                            (current, replacement) -> current.distance()
                                    <= replacement.distance() + 1.0E-7
                                    ? current : replacement
                    );
                } else {
                    // Join the already shorter suffix. Upstream cells must inherit its
                    // measured remaining cost instead of retaining the longer branch
                    // that happened to discover the intersection later.
                    remaining = known.distance();
                }
                next = cell;
                remaining += 1.0;
            }
            hasBackbone = true;
            finishBackboneBuild();
        }

        private List<Long> connect(
                BlockGetter level,
                Vec3 source,
                List<AirCellKey> startCells,
                int candidateBudget
        ) {
            List<Long> known = knownRoute(source, startCells);
            if (known != null) {
                LAST_AIR_SEARCH.set(new AirSearchStats("tree-hit", 0));
                return known;
            }

            // The registered listener tree is an exact set of proven suffixes. Search
            // only the new source connector and terminate when A* proves that its best
            // tree intersection cannot be displaced. This preserves the same grid
            // metric and collision tests as the full search; it merely reuses work
            // shared by every voice around this listener.
            AirGraphHeap frontier = new AirGraphHeap(256);
            Long2DoubleOpenHashMap distances = new Long2DoubleOpenHashMap(
                    Math.max(512, candidateBudget * 64)
            );
            distances.defaultReturnValue(Double.POSITIVE_INFINITY);
            Long2LongOpenHashMap previous = new Long2LongOpenHashMap();
            previous.defaultReturnValue(Long.MIN_VALUE);
            for (AirCellKey start : startCells) {
                long packed = packAirCell(start);
                double distance = distanceFromCellCenter(
                        start.x(), start.y(), start.z(), source
                );
                if (distance >= distances.get(packed)) {
                    continue;
                }
                distances.put(packed, distance);
                previous.put(packed, Long.MIN_VALUE);
                frontier.add(
                        packed,
                        distance,
                        distance + endpointGraphHeuristic(
                                start.x(), start.y(), start.z(),
                                goalCells, anchorListener
                        )
                );
            }

            double bestDistance = Double.POSITIVE_INFINITY;
            long meetingCell = Long.MIN_VALUE;
            int expanded = 0;
            int maximumExpansions = Mth.clamp(
                    (int) Math.ceil(source.distanceTo(anchorListener))
                            * candidateBudget * 32,
                    8192,
                    65536
            );
            while (!frontier.isEmpty() && expanded < maximumExpansions) {
                if (frontier.peekPriority() >= bestDistance) {
                    break;
                }
                long packedState = frontier.poll();
                double stateDistance = frontier.lastDistance();
                if (stateDistance > distances.get(packedState) + 1.0E-7) {
                    continue;
                }
                RouteStep suffix = routes.get(packedState);
                if (suffix != null) {
                    double candidate = stateDistance + suffix.distance();
                    if (candidate < bestDistance) {
                        bestDistance = candidate;
                        meetingCell = packedState;
                    }
                }
                expanded++;
                int stateX = BlockPos.getX(packedState);
                int stateY = BlockPos.getY(packedState);
                int stateZ = BlockPos.getZ(packedState);
                boolean fromAir = isAirCell(level, stateX, stateY, stateZ);
                for (int[] offset : AIR_GRAPH_NEIGHBORS) {
                    int nextX = stateX + offset[0];
                    int nextY = stateY + offset[1];
                    int nextZ = stateZ + offset[2];
                    boolean nextAir = isAirCell(level, nextX, nextY, nextZ);
                    if (!fromAir || !nextAir) {
                        Vec3 from = new Vec3(
                                stateX + 0.5, stateY + 0.5, stateZ + 0.5
                        );
                        Vec3 next = new Vec3(
                                nextX + 0.5, nextY + 0.5, nextZ + 0.5
                        );
                        if (pointInsideCollision(level, next)
                                || firstCollisionBounds(level, from, next) != null) {
                            continue;
                        }
                    }
                    double distance = stateDistance + 1.0;
                    long packedNext = BlockPos.asLong(nextX, nextY, nextZ);
                    if (distance + 1.0E-7 >= distances.get(packedNext)) {
                        continue;
                    }
                    distances.put(packedNext, distance);
                    previous.put(packedNext, packedState);
                    frontier.add(
                            packedNext,
                            distance,
                            distance + endpointGraphHeuristic(
                                    nextX, nextY, nextZ,
                                    goalCells, anchorListener
                            )
                    );
                }
            }
            LAST_AIR_SEARCH.set(new AirSearchStats(
                    meetingCell != Long.MIN_VALUE
                            ? "tree-connect"
                            : expanded >= maximumExpansions
                            ? "tree-limit"
                            : "tree-exhausted",
                    expanded
            ));
            if (meetingCell == Long.MIN_VALUE) {
                return null;
            }
            ArrayDeque<Long> reversed = new ArrayDeque<>();
            for (long cell = meetingCell;
                 cell != Long.MIN_VALUE;
                 cell = previous.get(cell)) {
                reversed.addFirst(cell);
            }
            List<Long> connected = completeRoute(
                    new ArrayList<>(reversed), meetingCell
            );
            if (connected != null) {
                register(connected);
            }
            return connected;
        }

        private List<Long> knownRoute(
                Vec3 source,
                List<AirCellKey> startCells
        ) {
            long directMeeting = Long.MIN_VALUE;
            double directDistance = Double.POSITIVE_INFINITY;
            for (AirCellKey start : startCells) {
                long packed = packAirCell(start);
                RouteStep suffix = routes.get(packed);
                if (suffix == null) {
                    continue;
                }
                double candidate = distanceFromCellCenter(
                        start.x(), start.y(), start.z(), source
                ) + suffix.distance();
                if (candidate < directDistance) {
                    directDistance = candidate;
                    directMeeting = packed;
                }
            }
            if (directMeeting != Long.MIN_VALUE) {
                return completeRoute(List.of(directMeeting), directMeeting);
            }
            return null;
        }

        private List<Long> completeRoute(List<Long> prefix, long meetingCell) {
            List<Long> result = new ArrayList<>(prefix.size() + 16);
            result.addAll(prefix);
            LongOpenHashSet cycleGuard = new LongOpenHashSet();
            cycleGuard.add(meetingCell);
            RouteStep meeting = routes.get(meetingCell);
            for (long cell = meeting == null ? Long.MIN_VALUE : meeting.next();
                 cell != Long.MIN_VALUE;) {
                if (!cycleGuard.add(cell)) {
                    return null;
                }
                result.add(cell);
                RouteStep step = routes.get(cell);
                if (step == null) {
                    return null;
                }
                cell = step.next();
            }
            return List.copyOf(result);
        }

        private float arrivalAmplitude(BlockGetter level, Vec3 sample, Vec3 listener) {
            ArrivalTransferKey key = new ArrivalTransferKey(
                    graphPointKey(listener), airCellKey(sample)
            );
            boolean[] created = {false};
            float value = arrivalTransfers.computeIfAbsent(key, ignored -> {
                created[0] = true;
                return weightedEnergy(
                        traceTransmission(level, sample, listener).bands()
                );
            });
            if (created[0]) {
                arrivalTransferOrder.add(key);
            }
            while (arrivalTransfers.size() > 4096) {
                ArrivalTransferKey oldest = arrivalTransferOrder.poll();
                if (oldest == null) {
                    break;
                }
                arrivalTransfers.remove(oldest);
            }
            return value;
        }

        private record RouteStep(double distance, long next) {
        }
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
            double airDistance,
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
            float lowFrequencyGain,
            float highFrequencyGain,
            float delay,
            Vec3 pan,
            DirectionalArrivalField directionalField
    ) {
        private static ReflectionResult silent() {
            return new ReflectionResult(
                    new float[AcousticBands.COUNT],
                    0.0F,
                    1.0F,
                    1.0F,
                    0.0F,
                    Vec3.ZERO,
                    DirectionalArrivalField.EMPTY
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
        private final List<DirectionalArrivalField.Arrival> arrivals = new ArrayList<>(6);
        private double directionalPower;
        private double distancePower;

        private void add(
                PathKind kind,
                float[] amplitudes,
                float powerWeight,
                List<DirectionalArrivalField.Arrival> pathArrivals,
                double pathDistance
        ) {
            energy.add(kind, amplitudes, powerWeight);
            double amplitude = weightedEnergy(amplitudes);
            double power = amplitude * amplitude * Math.max(0.0F, powerWeight);
            if (power <= 1.0E-12) {
                return;
            }
            double pathArrivalPower = 0.0;
            for (DirectionalArrivalField.Arrival arrival : pathArrivals) {
                pathArrivalPower += arrival.power();
            }
            if (pathArrivalPower > 1.0E-12) {
                for (DirectionalArrivalField.Arrival arrival : pathArrivals) {
                    arrivals.add(new DirectionalArrivalField.Arrival(
                            arrival.point(),
                            power * arrival.power() / pathArrivalPower
                    ));
                }
            }
            directionalPower += power;
            distancePower += Math.max(0.0, pathDistance) * power;
        }

        private float[] amplitudes() {
            return energy.amplitudes();
        }

        private Vec3 apparentDirection(Vec3 listener, Vec3 fallbackSource) {
            return directionalField(fallbackSource).apparentDirection(listener);
        }

        private DirectionalArrivalField directionalField(Vec3 fallbackSource) {
            return new DirectionalArrivalField(arrivals, fallbackSource);
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

    private record MediumSample(
            MediumProfile profile,
            AcousticMaterial material,
            float weight
    ) {
    }

    record EmitterMediumTransfer(float[] amplitude) {
        private static final EmitterMediumTransfer AIR = new EmitterMediumTransfer(
                unityBands()
        );
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
            RoomAcoustics room
    ) {
        public AcousticResult resultFor(Vec3 source) {
            double distance = source.distanceTo(listener);
            AcousticTuning tuning = AcousticMaterialRegistry.tuning();
            double distanceMeters = tuning.meters(distance);
            // The onset estimate samples the same finite incident wavefront as the full
            // solver. That prevents a point-like centre ray from producing a different
            // first-frame gain when a source starts beside an edge or diagonal seam.
            FiniteWavefront immediateWavefront = traceImmediateWavefront(
                    level, source, listener
            );
            float[] transmission = immediateWavefront.bands().clone();
            for (int band = 0; band < transmission.length; band++) {
                transmission[band] *= switch (band / 2) {
                    case 0 -> lowBandGain;
                    case 1 -> midLowBandGain;
                    case 2 -> midHighBandGain;
                    default -> highBandGain;
                };
            }
            // The listener endpoint response is precomputed above; sample the source
            // endpoint from the same immutable scene so a short water-to-air sound is
            // already coloured on its first callback buffer.
            applyEmitterMediumResponse(transmission, solveEmitterBoundary(level, source));
            float[] airborne = transmission.clone();
            StructuralPath structuralPath = traceStructuralPath(
                    level, source, listener, tuning
            );
            if (structuralPath != null) {
                for (int band = 0; band < transmission.length; band++) {
                    float listenerResponse = switch (band / 2) {
                        case 0 -> lowBandGain;
                        case 1 -> midLowBandGain;
                        case 2 -> midHighBandGain;
                        default -> highBandGain;
                    };
                    float structuralAmplitude = structuralPath.bands()[band]
                            * listenerResponse;
                    transmission[band] = Mth.clamp(
                            (float) Math.sqrt(
                                    transmission[band] * transmission[band]
                                            + structuralAmplitude * structuralAmplitude
                            ),
                            0.0F,
                            1.0F
                    );
                }
            }
            float pathCoupling = Mth.clamp(weightedEnergy(transmission), 0.0F, 1.0F);
            float initialReverbSend = Mth.clamp(
                    pathCoupling * tuning.reverbSendScale(),
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
                    spectralAnchor(transmission),
                    Mth.clamp(high / Math.max(spectralAnchor(transmission), 0.01F), 0.02F, 1.0F),
                    pairEnergy(transmission, 0),
                    pairEnergy(transmission, 2),
                    pairEnergy(transmission, 4),
                    pairEnergy(transmission, 6),
                    initialReverbSend,
                    Mth.clamp(high / Math.max(low, 0.01F), 0.05F, 1.0F),
                    EarlyReflection.SILENT,
                    0.0F,
                    distance,
                    apparentPosition,
                    room,
                    RoomImpulseResponse.SILENT,
                    sphericalSpreadingGain(distanceMeters, tuning)
            );
        }
    }

    private static FiniteWavefront traceImmediateWavefront(
            BlockGetter level,
            Vec3 source,
            Vec3 listener
    ) {
        Vec3 delta = listener.subtract(source);
        double distance = delta.length();
        if (distance < 1.0E-6) {
            Transmission transmission = traceTransmission(level, source, listener);
            float[] bands = transmission.bands().clone();
            float[] power = new float[AcousticBands.COUNT];
            for (int band = 0; band < power.length; band++) {
                power[band] = bands[band] * bands[band];
            }
            return new FiniteWavefront(transmission, bands, power);
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
        );
    }
}
