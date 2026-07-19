package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticTracerDiffractionIntegrationTest {
    private static final Vec3 SOURCE = new Vec3(-4.5, 2.5, 0.5);
    private static final Vec3 LISTENER = new Vec3(4.5, 2.5, 0.5);
    private static final RoomProbe OUTDOOR_PROBE = new RoomProbe(
            RoomAcoustics.OUTDOORS,
            List.of(),
            List.of()
    );

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nearbyOpenEdgeDiffractsFarMoreEnergyThanASealedWall() {
        AcousticResult shortBarrier = trace(position ->
                position.getX() == 0
                        && position.getY() >= 1 && position.getY() <= 3
                        && position.getZ() >= -1 && position.getZ() <= 1
        );
        AcousticResult shortBarrierBasic = trace(position ->
                position.getX() == 0
                        && position.getY() >= 1 && position.getY() <= 3
                        && position.getZ() >= -1 && position.getZ() <= 1,
                AcousticTracer.TraceQuality.BASIC
        );
        AcousticResult sealedWall = trace(position ->
                position.getX() == 0
                        && position.getY() >= -10 && position.getY() <= 10
                        && position.getZ() >= -10 && position.getZ() <= 10
        );

        assertTrue(
                shortBarrier.diffractionContribution() > 0.12F,
                () -> "shortBarrier=" + shortBarrier
        );
        assertTrue(
                shortBarrierBasic.diffractionContribution() > 0.05F,
                () -> "shortBarrierBasic=" + shortBarrierBasic
        );
        assertTrue(
                Math.abs(shortBarrier.diffractionContribution()
                        - shortBarrierBasic.diffractionContribution()) < 1.0E-6F,
                () -> "Sound priority must not change the propagation path: full="
                        + shortBarrier + ", basic=" + shortBarrierBasic
        );
        assertTrue(
                shortBarrier.diffractionContribution()
                        > sealedWall.diffractionContribution() * 3.0F,
                () -> "shortBarrier=" + shortBarrier + ", sealedWall=" + sealedWall
        );
        assertTrue(
                shortBarrier.directGain() > sealedWall.directGain() * 1.5F,
                () -> "shortBarrier=" + shortBarrier + ", sealedWall=" + sealedWall
        );
    }

    @Test
    void firstFrameReverbUsesCurrentTransmissionInsteadOfAFullSendPlaceholder() {
        AcousticResult clear = AcousticTracer.traceImmediate(
                new TestWorld(position -> false),
                SOURCE,
                LISTENER,
                OUTDOOR_PROBE
        );
        AcousticResult sealed = AcousticTracer.traceImmediate(
                new TestWorld(position -> position.getX() == 0
                        && position.getY() >= -10 && position.getY() <= 10
                        && position.getZ() >= -10 && position.getZ() <= 10),
                SOURCE,
                LISTENER,
                OUTDOOR_PROBE
        );

        assertTrue(
                sealed.reverbSend() < clear.reverbSend() * 0.25F,
                () -> "The onset predictor must not start every voice at maximum reverb: clear="
                        + clear + ", sealed=" + sealed
        );
        assertTrue(
                sealed.directGain() < clear.directGain() * 0.25F,
                () -> "The onset predictor must sample the current direct path: clear="
                        + clear + ", sealed=" + sealed
        );
    }

    @Test
    void grazingASolidCornerUsesTheActualChordLength() {
        TestWorld world = new TestWorld(position ->
                position.getX() == 0 && position.getY() == 0 && position.getZ() == 0
        );
        Vec3 clearSource = new Vec3(-1.0, 0.5, 0.01);
        Vec3 clearListener = new Vec3(2.0, 0.5, 3.01);
        Vec3 grazingSource = new Vec3(-1.0, 0.5, -0.01);
        Vec3 grazingListener = new Vec3(2.0, 0.5, 2.99);
        Vec3 centerSource = new Vec3(-1.0, 0.5, -1.0);
        Vec3 centerListener = new Vec3(2.0, 0.5, 2.0);

        AcousticResult clear = AcousticTracer.traceImmediate(
                world, clearSource, clearListener, OUTDOOR_PROBE
        );
        AcousticResult grazing = AcousticTracer.traceImmediate(
                world, grazingSource, grazingListener, OUTDOOR_PROBE
        );
        AcousticResult center = AcousticTracer.traceImmediate(
                world, centerSource, centerListener, OUTDOOR_PROBE
        );

        assertTrue(
                grazing.directGain() > 0.70F,
                () -> "A centimetre-scale chord must not receive a full block of loss: " + grazing
        );
        assertTrue(
                grazing.directGain() > center.directGain() * 2.5F,
                () -> "Transmission loss must grow continuously with distance inside material: grazing="
                        + grazing + ", center=" + center
        );
        assertTrue(
                clear.directGain() >= grazing.directGain(),
                () -> "Adding material path length cannot increase transmitted energy: clear="
                        + clear + ", grazing=" + grazing
        );
    }

    @Test
    void crossingAnOcclusionEdgeDoesNotSwitchToADifferentGainRule() {
        SolidGeometry halfScreen = position -> position.getX() == 0
                && position.getY() >= -10 && position.getY() <= 10
                && position.getZ() <= 0;
        AcousticResult justOccluded = traceAt(halfScreen, 0.99);
        AcousticResult justVisible = traceAt(halfScreen, 1.01);
        float edgeRatio = justVisible.directGain() / justOccluded.directGain();

        assertTrue(
                edgeRatio < 1.45F,
                () -> "The edge transition should be continuous: occluded="
                        + justOccluded + ", visible=" + justVisible
        );
    }

    @Test
    void distantBypassPathPersistsAcrossSmallListenerMovement() {
        SolidGeometry distantFiniteWall = position -> position.getX() == 0
                && position.getY() >= -12 && position.getY() <= 12
                && position.getZ() >= -12 && position.getZ() <= 12;
        SolidGeometry effectivelyInfiniteWall = position -> position.getX() == 0
                && position.getY() >= -64 && position.getY() <= 64
                && position.getZ() >= -64 && position.getZ() <= 64;

        AcousticResult beforeBoundary = traceAt(distantFiniteWall, 0.49);
        AcousticResult afterBoundary = traceAt(distantFiniteWall, 0.51);
        AcousticResult noBypass = traceAt(effectivelyInfiniteWall, 0.50);
        float smaller = Math.min(
                beforeBoundary.diffractionContribution(),
                afterBoundary.diffractionContribution()
        );
        float larger = Math.max(
                beforeBoundary.diffractionContribution(),
                afterBoundary.diffractionContribution()
        );

        assertTrue(
                smaller > noBypass.diffractionContribution() + 1.0E-4F,
                () -> "finiteBefore=" + beforeBoundary + ", finiteAfter="
                        + afterBoundary + ", noBypass=" + noBypass
        );
        assertTrue(
                larger / Math.max(smaller, 1.0E-6F) < 1.35F,
                () -> "A two-centimetre move must not delete the distant route: before="
                        + beforeBoundary + ", after=" + afterBoundary
        );
    }

    @Test
    void diagonalOpeningProducesADiagonalDiffractionArrival() {
        SolidGeometry crossOccluder = position -> position.getX() == 0
                && ((position.getY() == 2
                        && position.getZ() >= -8 && position.getZ() <= 8)
                    || (position.getZ() == 0
                        && position.getY() >= -8 && position.getY() <= 12));

        AcousticResult result = trace(crossOccluder);
        Vec3 arrivalOffset = result.apparentPosition().subtract(SOURCE);

        assertTrue(
                Math.abs(arrivalOffset.y) > 0.20 && Math.abs(arrivalOffset.z) > 0.20,
                () -> "Expected an oblique edge arrival, result=" + result
        );
        assertTrue(
                result.diffractionContribution() > 0.05F,
                () -> "Diagonal opening was treated as sealed, result=" + result
        );
    }

    @Test
    void reflectedFieldSoftensPartialOcclusionWithoutLeakingThroughSealedWall() {
        SolidGeometry roomShell = position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean insideSpan = x >= -6 && x <= 6
                    && y >= 0 && y <= 5
                    && z >= -4 && z <= 4;
            return insideSpan && (x == -6 || x == 6
                    || y == 0 || y == 5
                    || z == -4 || z == 4);
        };
        SolidGeometry shortScreen = position -> roomShell.isSolid(position)
                || position.getX() == 0
                && position.getY() >= 1 && position.getY() <= 3
                && position.getZ() >= -1 && position.getZ() <= 1;
        SolidGeometry sealedDivider = position -> roomShell.isSolid(position)
                || position.getX() == 0
                && position.getY() >= 0 && position.getY() <= 5
                && position.getZ() >= -4 && position.getZ() <= 4;

        AcousticResult open = traceWithLocalRoom(roomShell);
        AcousticResult partial = traceWithLocalRoom(shortScreen);
        AcousticResult sealed = traceWithLocalRoom(sealedDivider);
        assertTrue(
                partial.reverbSend() > sealed.reverbSend() * 1.5F,
                () -> "open=" + open + ", partial=" + partial + ", sealed=" + sealed
        );
        assertTrue(
                audibleEnergy(partial) > partial.directGain() * 1.02F,
                () -> "Reflections should add audible energy around the screen: partial="
                        + partial
        );
        assertTrue(
                audibleEnergy(partial) > audibleEnergy(sealed) * 1.5F,
                () -> "partial=" + partial + ", sealed=" + sealed
        );
    }

    @Test
    void exteriorWallSourceFeedsTheListenerSharedLateField() {
        Vec3 exteriorSource = new Vec3(0.5, 2.5, 6.5);
        Vec3 interiorListener = new Vec3(0.5, 2.5, 0.5);
        SolidGeometry finiteRoom = position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean span = x >= -5 && x <= 5
                    && y >= 0 && y <= 6
                    && z >= -5 && z <= 5;
            boolean shell = span && (x == -5 || x == 5
                    || y == 0 || y == 6
                    || z == -5 || z == 5);
            return shell || position.equals(BlockPos.containing(exteriorSource));
        };
        TestWorld world = new TestWorld(finiteRoom);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, exteriorSource);
        RoomProbe listenerProbe = AcousticTracer.probeRoom(world, interiorListener);

        AcousticResult result = AcousticTracer.trace(
                world,
                exteriorSource,
                interiorListener,
                sourceProbe,
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                sourceProbe.acoustics().decayTime()
                        < listenerProbe.acoustics().decayTime() * 0.65F
                        && sourceProbe.acoustics().density()
                        < listenerProbe.acoustics().density() * 0.50F,
                () -> "source=" + sourceProbe.acoustics()
                        + ", listener=" + listenerProbe.acoustics()
        );
        assertTrue(result.reverbRoom().equals(listenerProbe.acoustics()));
        assertTrue(result.reverbSend() > 0.0F);
        assertTrue(result.impulseResponse().isSilent());
    }

    @Test
    void reflectionContinuesAcrossAdjacentFacesAndQualityChanges() {
        Vec3 source = new Vec3(0.25, 2.0, 0.5);
        Vec3 listener = new Vec3(3.25, 2.0, 0.5);
        TestWorld world = new TestWorld(position ->
                position.getY() == 0
                        && position.getX() >= -8 && position.getX() <= 8
                        && position.getZ() == 0
        );
        RoomProbe sampledFloor = new RoomProbe(
                RoomAcoustics.OUTDOORS,
                List.of(new RoomProbe.SurfaceSample(
                        new Vec3(0.5, 1.0, 0.5),
                        new Vec3(0.0, 1.0, 0.0),
                        new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                        1.0,
                        AcousticMaterialRegistry.find(Blocks.STONE.defaultBlockState())
                )),
                List.of()
        );
        AcousticResult full = AcousticTracer.trace(
                world, source, listener, sampledFloor, AcousticTracer.TraceQuality.FULL
        );
        AcousticResult basic = AcousticTracer.trace(
                world, source, listener, sampledFloor, AcousticTracer.TraceQuality.BASIC
        );
        AcousticTracer.ReflectionResult reflected = AcousticTracer.estimateEarlyReflections(
                world, source, listener, sampledFloor, 4
        );

        assertTrue(
                reflected.gain() > 0.05F,
                () -> "The image point lies on the adjacent floor block and must remain valid: "
                        + reflected
        );
        assertTrue(
                Math.abs(full.reverbSend() - basic.reverbSend()) < 1.0E-5F,
                () -> "A priority/quality transition must not switch the reflection off: full="
                        + full + ", basic=" + basic
        );
    }

    @Test
    void approachingAWallDoesNotDoubleTheSharedRoomEffect() {
        Vec3 source = new Vec3(0.5, 2.5, 0.5);
        Vec3 centralListener = new Vec3(2.5, 2.5, 0.5);
        Vec3 wallListener = new Vec3(8.75, 2.5, 0.5);
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean span = x >= -10 && x <= 10
                    && y >= 0 && y <= 6
                    && z >= -10 && z <= 10;
            return span && (x == -10 || x == 10
                    || y == 0 || y == 6 || z == -10 || z == 10);
        });
        RoomProbe centralProbe = AcousticTracer.probeRoom(world, centralListener);
        RoomProbe wallProbe = AcousticTracer.probeRoom(world, wallListener);
        AcousticResult central = AcousticTracer.trace(
                world, source, centralListener, centralProbe, AcousticTracer.TraceQuality.FULL
        );
        AcousticResult atWall = AcousticTracer.trace(
                world, source, wallListener, wallProbe, AcousticTracer.TraceQuality.FULL
        );
        assertTrue(
                central.reverbSend() > 0.08F,
                () -> "The shared room field must remain audible away from the wall: " + central
        );
        assertTrue(
                atWall.reverbSend() <= central.reverbSend() * 1.25F,
                () -> "A nearby wall must not inject the same reflection twice: center="
                        + central + ", wall=" + atWall
        );
        assertTrue(
                wallProbe.acoustics().reflectionsGain()
                        <= centralProbe.acoustics().reflectionsGain() * 1.05F,
                () -> "A nearby wall may raise physical first-arrival energy but must not double it: center="
                        + centralProbe.acoustics() + ", wall=" + wallProbe.acoustics()
        );
    }

    @Test
    void listenerTouchingASoundBlockStillExcitesTheRoomField() {
        Vec3 source = new Vec3(0.5, 1.5, 0.5);
        Vec3 touchingListener = new Vec3(0.5, 2.62, 0.5);
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean room = x >= -8 && x <= 8
                    && y >= 0 && y <= 6
                    && z >= -8 && z <= 8;
            boolean shell = room && (x == -8 || x == 8
                    || y == 0 || y == 6 || z == -8 || z == 8);
            boolean sourceBlock = x == 0 && y == 1 && z == 0;
            return shell || sourceBlock;
        });
        RoomProbe listenerProbe = AcousticTracer.probeRoom(world, touchingListener);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        AcousticResult result = AcousticTracer.trace(
                world,
                source,
                touchingListener,
                sourceProbe,
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                result.reverbSend() > 0.5F,
                () -> "An emitter's endpoint block must not erase its room excitation: " + result
        );
        assertTrue(
                listenerProbe.acoustics().gain() > 0.05F,
                () -> "The listener touching the source still occupies the surrounding room: "
                        + listenerProbe.acoustics()
        );
    }

    @Test
    void nearbyFreestandingBlockDoesNotBecomeARoomSizedOpening() {
        Vec3 listener = new Vec3(0.5, 2.62, 0.5);
        SolidGeometry shell = position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean room = x >= -8 && x <= 8
                    && y >= 0 && y <= 6
                    && z >= -8 && z <= 8;
            return room && (x == -8 || x == 8
                    || y == 0 || y == 6 || z == -8 || z == 8);
        };
        RoomProbe emptyRoom = AcousticTracer.probeRoom(new TestWorld(shell), listener);
        RoomProbe besideBlock = AcousticTracer.probeRoom(
                new TestWorld(position -> shell.isSolid(position)
                        || position.getX() == 1
                        && position.getY() == 2
                        && position.getZ() == 0),
                listener
        );
        TestWorld obstacleWorld = new TestWorld(position -> shell.isSolid(position)
                || position.getX() == 0
                && position.getY() == 1
                && position.getZ() == 0);
        float minimumGain = Float.POSITIVE_INFINITY;
        Vec3 minimumPosition = Vec3.ZERO;
        for (int x = -8; x <= 8; x += 2) {
            for (int z = -8; z <= 8; z += 2) {
                Vec3 sampled = new Vec3(0.5 + x / 8.0, 2.62, 0.5 + z / 8.0);
                float gain = AcousticTracer.probeRoom(obstacleWorld, sampled).acoustics().gain();
                if (gain < minimumGain) {
                    minimumGain = gain;
                    minimumPosition = sampled;
                }
            }
        }
        float sampledMinimumGain = minimumGain;
        Vec3 sampledMinimumPosition = minimumPosition;

        assertTrue(
                besideBlock.acoustics().gain() > emptyRoom.acoustics().gain() * 0.55F,
                () -> "A nearby solid obstacle must not erase the enclosing room field: empty="
                        + emptyRoom.acoustics() + ", besideBlock=" + besideBlock.acoustics()
                        + ", openings=" + besideBlock.openings().size()
        );
        assertTrue(
                sampledMinimumGain > emptyRoom.acoustics().gain() * 0.55F,
                () -> "No position around a source-sized obstacle may collapse the room field: minimum="
                        + sampledMinimumGain + " at " + sampledMinimumPosition
                        + ", empty=" + emptyRoom.acoustics()
        );
    }

    @Test
    void sourceBelowASealedFloorDoesNotExciteTheListenerRoomAsAnIndoorSource() {
        Vec3 listener = new Vec3(0.5, 2.5, 0.5);
        Vec3 sourceBelow = new Vec3(0.5, -2.5, 0.5);
        Vec3 sourceInside = new Vec3(0.5, 2.5, 4.5);
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean span = x >= -5 && x <= 5
                    && y >= -6 && y <= 6
                    && z >= -5 && z <= 5;
            return span && (x == -5 || x == 5
                    || y == -6 || y == 0 || y == 6
                    || z == -5 || z == 5);
        });
        RoomProbe listenerProbe = AcousticTracer.probeRoom(world, listener);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, sourceBelow);
        AcousticResult below = AcousticTracer.trace(
                world,
                sourceBelow,
                listener,
                sourceProbe,
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );
        AcousticResult inside = AcousticTracer.trace(
                world,
                sourceInside,
                listener,
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                below.reverbSend() < inside.reverbSend() * 0.35F,
                () -> "A sealed floor must preserve the outside-room character: below="
                        + below + ", inside=" + inside
        );
    }

    @Test
    void listenerOutsideLargeOpenFacadeDoesNotInheritTheInteriorLateField() {
        Vec3 interior = new Vec3(0.5, 2.5, 0.5);
        Vec3 exterior = new Vec3(0.5, 2.5, 8.5);
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean floor = y == 0 && x >= -24 && x <= 24 && z >= -24 && z <= 24;
            boolean roomSpan = x >= -6 && x <= 6
                    && y >= 0 && y <= 6
                    && z >= -6 && z <= 6;
            boolean shell = roomSpan && (x == -6 || x == 6
                    || y == 0 || y == 6 || z == -6);
            boolean frontFrame = roomSpan && z == 6
                    && (Math.abs(x) >= 5 || y == 0 || y == 6);
            return floor || shell || frontFrame;
        });

        RoomProbe interiorProbe = AcousticTracer.probeRoom(world, interior);
        RoomProbe exteriorProbe = AcousticTracer.probeRoom(world, exterior);

        assertTrue(
                exteriorProbe.acoustics().gain() < 0.12F
                        && exteriorProbe.acoustics().gain()
                        < interiorProbe.acoustics().gain() * 0.50F,
                () -> "The exterior listener inherited the open-front room: inside="
                        + interiorProbe.acoustics() + ", outside=" + exteriorProbe.acoustics()
        );
        assertTrue(
                exteriorProbe.openings().size() > 0,
                () -> "Unbounded escape directions must be represented as open boundaries: "
                        + exteriorProbe
        );
    }

    @Test
    void interiorSoundEscapesOverLowWallToExteriorListener() {
        Vec3 source = new Vec3(0.5, 2.5, 0.5);
        Vec3 listener = new Vec3(4.5, 2.5, 8.5);
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean floor = y == 0 && x >= -24 && x <= 24 && z >= -24 && z <= 24;
            boolean roomSpan = x >= -6 && x <= 6
                    && y >= 0 && y <= 7
                    && z >= -6 && z <= 6;
            boolean shell = roomSpan && (x == -6 || x == 6
                    || y == 0 || y == 7 || z == -6);
            boolean frontFrame = roomSpan && z == 6 && (Math.abs(x) >= 5 || y == 0 || y == 7);
            boolean lowScreen = z >= 4 && z <= 6
                    && x >= -3 && x <= 4
                    && y >= 1 && y <= 3;
            return floor || shell || frontFrame || lowScreen;
        });

        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        RoomProbe listenerProbe = AcousticTracer.probeRoom(world, listener);
        AcousticResult result = AcousticTracer.trace(
                world, source, listener, sourceProbe, listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                result.diffractionContribution() > 0.035F && result.directGain() > 0.055F,
                () -> "The large upper aperture was treated as sealed: openings="
                        + sourceProbe.openings() + ", result=" + result
        );
        assertTrue(
                result.reverbRoom().gain() < 0.15F,
                () -> "The listener field must remain exterior: " + result.reverbRoom()
        );
        if ("true".equals(System.getenv("ACOUSTICSYSTEM_PERFORMANCE_SMOKE"))) {
            for (int warmup = 0; warmup < 4; warmup++) {
                AcousticTracer.trace(
                        world, source, listener, sourceProbe, listenerProbe,
                        AcousticTracer.TraceQuality.FULL
                );
            }
            double maximumMilliseconds = 0.0;
            for (int sample = 0; sample < 16; sample++) {
                long started = System.nanoTime();
                AcousticTracer.trace(
                        world, source, listener, sourceProbe, listenerProbe,
                        AcousticTracer.TraceQuality.FULL
                );
                maximumMilliseconds = Math.max(
                        maximumMilliseconds,
                        (System.nanoTime() - started) / 1_000_000.0
                );
            }
            double measured = maximumMilliseconds;
            System.out.println("maximumThickScreenOpeningTraceMilliseconds=" + measured);
            assertTrue(measured < 10.0, () ->
                    "thick-screen opening trace exceeded 10 ms: " + measured);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ACOUSTICSYSTEM_PERFORMANCE_SMOKE", matches = "true")
    void cachedRoomAndFullSourceTraceStayInsideTenMillisecondWorkerBudget() {
        Vec3 listener = new Vec3(0.5, 3.5, 0.5);
        Vec3 source = new Vec3(0.5, 3.5, 6.5);
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean span = x >= -10 && x <= 10
                    && y >= 0 && y <= 8
                    && z >= -10 && z <= 10;
            return span && (x == -10 || x == 10
                    || y == 0 || y == 8 || z == -10 || z == 10);
        });

        RoomProbe probe = AcousticTracer.probeRoom(world, listener);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        for (int warmup = 0; warmup < 4; warmup++) {
            AcousticTracer.probeRoom(world, listener);
            AcousticTracer.probeSourceRoom(world, source);
            AcousticTracer.trace(
                    world, source, listener, sourceProbe, probe,
                    AcousticTracer.TraceQuality.FULL
            );
        }

        double maximumProbeMilliseconds = 0.0;
        double maximumSourceProbeMilliseconds = 0.0;
        double maximumTraceMilliseconds = 0.0;
        double maximumTotalMilliseconds = 0.0;
        for (int sample = 0; sample < 16; sample++) {
            long totalStarted = System.nanoTime();
            long probeStarted = System.nanoTime();
            probe = AcousticTracer.probeRoom(world, listener);
            maximumProbeMilliseconds = Math.max(
                    maximumProbeMilliseconds,
                    (System.nanoTime() - probeStarted) / 1_000_000.0
            );
            long sourceProbeStarted = System.nanoTime();
            sourceProbe = AcousticTracer.probeSourceRoom(world, source);
            maximumSourceProbeMilliseconds = Math.max(
                    maximumSourceProbeMilliseconds,
                    (System.nanoTime() - sourceProbeStarted) / 1_000_000.0
            );
            long traceStarted = System.nanoTime();
            AcousticTracer.trace(
                    world, source, listener, sourceProbe, probe,
                    AcousticTracer.TraceQuality.FULL
            );
            maximumTraceMilliseconds = Math.max(
                    maximumTraceMilliseconds,
                    (System.nanoTime() - traceStarted) / 1_000_000.0
            );
            maximumTotalMilliseconds = Math.max(
                    maximumTotalMilliseconds,
                    (System.nanoTime() - totalStarted) / 1_000_000.0
            );
        }
        System.out.println("maximumRoomProbeMilliseconds=" + maximumProbeMilliseconds
                + ", maximumSourceProbeMilliseconds=" + maximumSourceProbeMilliseconds
                + ", maximumFullTraceMilliseconds=" + maximumTraceMilliseconds
                + ", maximumTotalMilliseconds=" + maximumTotalMilliseconds);
        double measuredProbe = maximumProbeMilliseconds;
        double measuredSourceProbe = maximumSourceProbeMilliseconds;
        double measuredTrace = maximumTraceMilliseconds;
        double measuredTotal = maximumTotalMilliseconds;
        assertTrue(measuredProbe < 10.0, () -> "room probe exceeded 10 ms: " + measuredProbe);
        assertTrue(measuredSourceProbe < 10.0, () ->
                "source probe exceeded 10 ms: " + measuredSourceProbe);
        assertTrue(measuredTrace < 10.0, () -> "source trace exceeded 10 ms: " + measuredTrace);
        assertTrue(measuredTotal < 10.0, () ->
                "complete listener + source update exceeded 10 ms: " + measuredTotal);
    }

    private static AcousticResult trace(SolidGeometry geometry) {
        return trace(geometry, AcousticTracer.TraceQuality.FULL);
    }

    private static AcousticResult trace(
            SolidGeometry geometry,
            AcousticTracer.TraceQuality quality
    ) {
        return AcousticTracer.trace(
                new TestWorld(geometry),
                SOURCE,
                LISTENER,
                OUTDOOR_PROBE,
                quality
        );
    }

    private static AcousticResult traceWithLocalRoom(SolidGeometry geometry) {
        TestWorld world = new TestWorld(geometry);
        RoomProbe listenerProbe = AcousticTracer.probeRoom(world, LISTENER);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, SOURCE);
        return AcousticTracer.trace(
                world,
                SOURCE,
                LISTENER,
                sourceProbe,
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );
    }

    private static AcousticResult traceAt(SolidGeometry geometry, double z) {
        return AcousticTracer.trace(
                new TestWorld(geometry),
                new Vec3(SOURCE.x, SOURCE.y, z),
                new Vec3(LISTENER.x, LISTENER.y, z),
                OUTDOOR_PROBE,
                AcousticTracer.TraceQuality.FULL
        );
    }

    private static float audibleEnergy(AcousticResult result) {
        return (float) Math.sqrt(
                result.directGain() * result.directGain()
                        + result.reverbSend() * result.reverbSend()
        );
    }

    @FunctionalInterface
    private interface SolidGeometry {
        boolean isSolid(BlockPos position);
    }

    private record TestWorld(SolidGeometry geometry) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return geometry.isSolid(position)
                    ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getHeight() {
            return 384;
        }
    }
}
