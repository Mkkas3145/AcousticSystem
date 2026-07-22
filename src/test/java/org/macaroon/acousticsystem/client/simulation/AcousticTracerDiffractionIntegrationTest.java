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
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Random;

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
                grazing.directGain() > center.directGain() * 1.03F,
                () -> "Finite-wave transmission must still decrease as more of the wavefront "
                        + "crosses material: grazing="
                        + grazing + ", center=" + center
        );
        assertTrue(
                clear.directGain() >= grazing.directGain() * 0.95F,
                () -> "Moving two centimetres across an edge must keep finite-wave "
                        + "transmission continuous: clear="
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
                && position.getY() >= -8 && position.getY() <= 12
                && position.getZ() >= -8 && position.getZ() <= 8
                && !(position.getY() >= 3 && position.getZ() >= 1);

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
    void everyLayerOfASealedWallContributesTransmissionLoss() {
        SolidGeometry oneLayer = position -> position.getX() == 0
                && Math.abs(position.getY()) <= 8
                && Math.abs(position.getZ()) <= 8;
        SolidGeometry threeLayers = position -> position.getX() >= 0
                && position.getX() <= 2
                && Math.abs(position.getY()) <= 8
                && Math.abs(position.getZ()) <= 8;
        Vec3 source = new Vec3(-4.5, 0.5, 0.5);
        Vec3 listener = new Vec3(6.5, 0.5, 0.5);

        AcousticResult thin = AcousticTracer.traceImmediate(
                new TestWorld(oneLayer), source, listener, OUTDOOR_PROBE
        );
        AcousticResult thick = AcousticTracer.traceImmediate(
                new TestWorld(threeLayers), source, listener, OUTDOOR_PROBE
        );

        assertTrue(
                thick.directGain() < thin.directGain() * 0.10F,
                () -> "Every crossed metre of a sealed wall must accumulate material loss: thin="
                        + thin + ", thick=" + thick
        );
    }

    @Test
    void aThickSealedShellAttenuatesTheCompleteArrivalField() {
        SolidGeometry thinShell = position -> {
            int radius = Math.max(
                    Math.abs(position.getX()),
                    Math.max(Math.abs(position.getY()), Math.abs(position.getZ()))
            );
            return radius == 2;
        };
        SolidGeometry thickShell = position -> {
            int radius = Math.max(
                    Math.abs(position.getX()),
                    Math.max(Math.abs(position.getY()), Math.abs(position.getZ()))
            );
            return radius >= 2 && radius <= 4;
        };
        Vec3 source = new Vec3(0.5, 0.5, 0.5);
        Vec3 listener = new Vec3(8.5, 0.5, 0.5);
        TestWorld thinWorld = new TestWorld(thinShell);
        TestWorld thickWorld = new TestWorld(thickShell);

        AcousticResult thin = AcousticTracer.trace(
                thinWorld,
                source,
                listener,
                AcousticTracer.probeRoom(thinWorld, source),
                OUTDOOR_PROBE,
                AcousticTracer.TraceQuality.FULL
        );
        AcousticResult thick = AcousticTracer.trace(
                thickWorld,
                source,
                listener,
                AcousticTracer.probeRoom(thickWorld, source),
                OUTDOOR_PROBE,
                AcousticTracer.TraceQuality.FULL
        );
        float thinArrival = thin.directGain()
                + thin.diffractionContribution()
                + thin.earlyReflection().gain();
        float thickArrival = thick.directGain()
                + thick.diffractionContribution()
                + thick.earlyReflection().gain();

        assertTrue(
                thickArrival < thinArrival * 0.10F,
                () -> "A sealed shell must accumulate thickness across direct, diffracted, "
                        + "structural and reflected arrivals: thin=" + thin
                        + ", thick=" + thick
        );
    }

    @Test
    void diagonalIncidenceCannotIncreaseTransmissionThroughTheSameWall() {
        TestWorld world = new TestWorld(position -> position.getX() == 0
                && position.getY() >= -16 && position.getY() <= 16
                && position.getZ() >= -16 && position.getZ() <= 16);
        AcousticResult perpendicular = AcousticTracer.traceImmediate(
                world,
                new Vec3(-2.5, 2.5, 0.5),
                new Vec3(2.5, 2.5, 0.5),
                OUTDOOR_PROBE
        );
        AcousticResult diagonal = AcousticTracer.traceImmediate(
                world,
                new Vec3(-2.5, 2.5, -2.0),
                new Vec3(2.5, 2.5, 3.0),
                OUTDOOR_PROBE
        );
        AcousticResult mirroredDiagonal = AcousticTracer.traceImmediate(
                world,
                new Vec3(-2.5, 2.5, 3.0),
                new Vec3(2.5, 2.5, -2.0),
                OUTDOOR_PROBE
        );

        assertTrue(
                diagonal.directGain() <= perpendicular.directGain() + 1.0E-5F,
                () -> "A longer oblique chord through one wall cannot become louder: normal="
                        + perpendicular + ", diagonal=" + diagonal
        );
        assertTrue(
                Math.abs(diagonal.directGain() - mirroredDiagonal.directGain()) < 5.0E-4F,
                () -> "Mirroring the incidence angle must preserve transmission: first="
                        + diagonal + ", mirrored=" + mirroredDiagonal
        );
    }

    @Test
    void firstFrameFiniteWaveDoesNotLeakThroughADiagonalVoxelSeam() {
        TestWorld world = new TestWorld(position -> position.getX() == position.getZ()
                && position.getX() >= -12 && position.getX() <= 12
                && position.getY() >= -12 && position.getY() <= 12);
        Vec3 source = new Vec3(-4.5, 2.5, 6.5);
        Vec3 listener = new Vec3(4.5, 2.5, -2.5);

        AcousticResult immediate = AcousticTracer.traceImmediate(
                world, source, listener, OUTDOOR_PROBE
        );
        AcousticResult precise = AcousticTracer.trace(
                world,
                source,
                listener,
                OUTDOOR_PROBE,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                immediate.directGain() < 0.75F,
                () -> "A zero-width centre ray must not make a finite wave pass through "
                        + "corner-touching diagonal blocks: " + immediate
        );
        assertTrue(
                immediate.directGain()
                        < Math.max(precise.directGain() * 2.0F, 0.35F),
                () -> "The first frame and precise finite-wave estimates diverged at a "
                        + "diagonal seam: immediate=" + immediate + ", precise=" + precise
        );
    }

    @Test
    void soundAboveACeilingDoesNotJumpWhenTheIncidenceBecomesDiagonal() {
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean span = x >= -10 && x <= 10
                    && y >= 0 && y <= 7
                    && z >= -10 && z <= 10;
            boolean shell = span && (x == -10 || x == 10
                    || y == 0 || y == 7 || z == -10 || z == 10);
            boolean doorway = z == -10 && x >= -1 && x <= 1
                    && y >= 1 && y <= 3;
            return shell && !doorway;
        });
        Vec3 listener = new Vec3(0.5, 3.5, 0.5);
        RoomProbe listenerProbe = AcousticTracer.probeRoom(world, listener);
        AcousticResult previous = null;
        for (double x = 0.5; x <= 7.5; x += 0.25) {
            Vec3 source = new Vec3(x, 8.5, 0.5);
            AcousticResult current = AcousticTracer.trace(
                    world,
                    source,
                    listener,
                    AcousticTracer.probeSourceRoom(world, source),
                    listenerProbe,
                    AcousticTracer.TraceQuality.FULL
            );
            if (previous != null) {
                float larger = Math.max(previous.directGain(), current.directGain());
                float smaller = Math.min(previous.directGain(), current.directGain());
                AcousticResult prior = previous;
                assertTrue(
                        larger / Math.max(smaller, 1.0E-6F) < 1.40F,
                        () -> "A quarter-block move changed a ceiling path into a loud "
                                + "diagonal direct path: previous=" + prior
                                + ", current=" + current
                );
            }
            previous = current;
        }

        AcousticResult perpendicular = AcousticTracer.trace(
                world,
                new Vec3(0.5, 8.5, 0.5),
                listener,
                AcousticTracer.probeSourceRoom(world, new Vec3(0.5, 8.5, 0.5)),
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );
        AcousticResult oblique = AcousticTracer.trace(
                world,
                new Vec3(7.5, 8.5, 0.5),
                listener,
                AcousticTracer.probeSourceRoom(world, new Vec3(7.5, 8.5, 0.5)),
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );
        assertTrue(
                oblique.lowBandGain() <= perpendicular.lowBandGain() * 1.25F,
                () -> "A longer diagonal route through the same ceiling became louder: normal="
                        + perpendicular + ", oblique=" + oblique
        );
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ACOUSTICSYSTEM_PERFORMANCE_SMOKE", matches = "true")
    void finiteWaveOnsetRemainsBelowTenMilliseconds() {
        TestWorld world = new TestWorld(position -> position.getX() == position.getZ()
                && position.getX() >= -24 && position.getX() <= 24
                && position.getY() >= -12 && position.getY() <= 12);
        Vec3 source = new Vec3(-10.5, 2.5, 12.5);
        Vec3 listener = new Vec3(10.5, 2.5, -8.5);
        for (int warmup = 0; warmup < 6; warmup++) {
            AcousticTracer.traceImmediate(world, source, listener, OUTDOOR_PROBE);
        }
        double maximumMilliseconds = 0.0;
        for (int sample = 0; sample < 24; sample++) {
            long started = System.nanoTime();
            AcousticTracer.traceImmediate(world, source, listener, OUTDOOR_PROBE);
            maximumMilliseconds = Math.max(
                    maximumMilliseconds,
                    (System.nanoTime() - started) / 1_000_000.0
            );
        }
        double measured = maximumMilliseconds;
        System.out.println("maximumFiniteWaveOnsetMilliseconds=" + measured);
        assertTrue(
                measured < 10.0,
                () -> "finite-wave onset exceeded 10 ms: " + measured
        );
    }

    @Test
    void symmetricScreenDoesNotChooseAnArbitraryDiffractionSide() {
        SolidGeometry symmetricScreen = position -> position.getX() == 0
                && position.getY() >= 1 && position.getY() <= 3
                && position.getZ() >= -2 && position.getZ() <= 2;

        AcousticResult result = trace(symmetricScreen);

        assertTrue(
                Math.abs(result.apparentPosition().y - SOURCE.y) < 0.20,
                () -> "A vertically symmetric screen must not steer toward one arbitrary edge: "
                        + result
        );
        assertTrue(
                Math.abs(result.apparentPosition().z - SOURCE.z) < 0.20,
                () -> "A laterally symmetric screen must not flip the image left or right: "
                        + result
        );
    }

    @Test
    void multipleRoomExitsFormOneStablePowerWeightedDirection() {
        Vec3 source = new Vec3(0.5, 2.5, 0.5);
        Vec3 listener = new Vec3(0.5, 2.5, 8.0);
        TestWorld twinExitWorld = new TestWorld(position ->
                roomShellWithTwinFrontExits(position, true, true)
        );
        TestWorld rightExitWorld = new TestWorld(position ->
                roomShellWithTwinFrontExits(position, false, true)
        );

        AcousticResult twinExits = AcousticTracer.trace(
                twinExitWorld,
                source,
                listener,
                AcousticTracer.probeSourceRoom(twinExitWorld, source),
                AcousticTracer.probeRoom(twinExitWorld, listener),
                AcousticTracer.TraceQuality.FULL
        );
        AcousticResult rightExit = AcousticTracer.trace(
                rightExitWorld,
                source,
                listener,
                AcousticTracer.probeSourceRoom(rightExitWorld, source),
                AcousticTracer.probeRoom(rightExitWorld, listener),
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                twinExits.diffractionContribution() > 0.01F
                        && rightExit.diffractionContribution() > 0.01F,
                () -> "Both open-room cases must retain an audible propagation path: twin="
                        + twinExits + ", right=" + rightExit
        );
        assertTrue(
                Math.abs(twinExits.apparentPosition().x - source.x) < 0.35,
                () -> "Equal left/right exits must not select one arbitrary portal: " + twinExits
        );
        assertTrue(
                rightExit.apparentPosition().x > twinExits.apparentPosition().x + 0.20,
                () -> "Closing the left exit must move the image continuously toward the right "
                        + "exit: twin=" + twinExits + ", right=" + rightExit
        );
    }

    @Test
    void multiTurnVisibilitySearchFindsAPathThroughStaggeredScreens() {
        Vec3 source = new Vec3(-5.5, 2.5, 0.5);
        Vec3 listener = new Vec3(5.5, 2.5, 0.5);
        SolidGeometry staggeredScreens = position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            if (y < -10 || y > 14) {
                return false;
            }
            boolean positiveOpeningWall = x == -3 || x == 1;
            boolean negativeOpeningWall = x == -1 || x == 3;
            return positiveOpeningWall && z <= 4 && z >= -12
                    || negativeOpeningWall && z >= -4 && z <= 12;
        };
        SolidGeometry sealedWall = position -> position.getX() == 0
                && position.getY() >= -64 && position.getY() <= 64
                && position.getZ() >= -64 && position.getZ() <= 64;

        AcousticResult staggered = AcousticTracer.trace(
                new TestWorld(staggeredScreens),
                source,
                listener,
                OUTDOOR_PROBE,
                AcousticTracer.TraceQuality.FULL
        );
        AcousticResult sealed = AcousticTracer.trace(
                new TestWorld(sealedWall),
                source,
                listener,
                OUTDOOR_PROBE,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                staggered.diffractionContribution()
                        > sealed.diffractionContribution() * 1.5F,
                () -> "A four-turn open route must retain more energy than a sealed wall: "
                        + "staggered=" + staggered + ", sealed=" + sealed
        );
    }

    @Test
    void doorwayDiffractionPersistsImmediatelyBesideTheExteriorWall() {
        Vec3 source = new Vec3(3.5, 2.5, 0.5);
        Vec3 doorwayListener = new Vec3(0.5, 2.5, 7.0);
        Vec3 sideListener = new Vec3(3.5, 2.5, 6.15);
        SolidGeometry roomWithDoor = position -> roomShellWithFrontDoor(position, true);
        SolidGeometry sealedRoom = position -> roomShellWithFrontDoor(position, false);
        TestWorld openWorld = new TestWorld(roomWithDoor);
        TestWorld sealedWorld = new TestWorld(sealedRoom);

        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(openWorld, source);
        AcousticResult inFront = AcousticTracer.trace(
                openWorld,
                source,
                doorwayListener,
                sourceProbe,
                AcousticTracer.probeRoom(openWorld, doorwayListener),
                AcousticTracer.TraceQuality.FULL
        );
        AcousticResult besideWall = AcousticTracer.trace(
                openWorld,
                source,
                sideListener,
                sourceProbe,
                AcousticTracer.probeRoom(openWorld, sideListener),
                AcousticTracer.TraceQuality.FULL
        );
        AcousticResult sealedBesideWall = AcousticTracer.trace(
                sealedWorld,
                source,
                sideListener,
                AcousticTracer.probeSourceRoom(sealedWorld, source),
                AcousticTracer.probeRoom(sealedWorld, sideListener),
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                besideWall.diffractionContribution()
                        > sealedBesideWall.diffractionContribution() * 2.0F,
                () -> "Stepping beside an open doorway must not collapse to sealed-wall "
                        + "transmission: front=" + inFront + ", side=" + besideWall
                        + ", sealed=" + sealedBesideWall
        );
        assertTrue(
                besideWall.directGain() > inFront.directGain() * 0.12F,
                () -> "A nearby side shadow must attenuate continuously, not cut the route: "
                        + "front=" + inFront + ", side=" + besideWall
        );
    }

    @Test
    void portalGraphKeepsADoorwayAliveAcrossAnExteriorTurn() {
        Vec3 source = new Vec3(3.5, 2.5, 0.5);
        Vec3 listener = new Vec3(5.5, 2.5, 10.5);
        TestWorld openWorld = new TestWorld(position ->
                roomWithExteriorTurn(position, true)
        );
        TestWorld sealedWorld = new TestWorld(position ->
                roomWithExteriorTurn(position, false)
        );
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(openWorld, source);
        RoomProbe listenerProbe = AcousticTracer.probeRoom(openWorld, listener);

        AcousticResult aroundTurn = AcousticTracer.trace(
                openWorld,
                source,
                listener,
                sourceProbe,
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );
        AcousticResult sealed = AcousticTracer.trace(
                sealedWorld,
                source,
                listener,
                AcousticTracer.probeSourceRoom(sealedWorld, source),
                AcousticTracer.probeRoom(sealedWorld, listener),
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                aroundTurn.diffractionContribution() > 0.01F,
                () -> "The door -> exterior corner route was discarded as sealed: "
                        + aroundTurn + ", sourceOpenings=" + sourceProbe.openings()
                        + ", listenerOpenings=" + listenerProbe.openings()
        );
        assertTrue(
                aroundTurn.diffractionContribution()
                        > sealed.diffractionContribution() * 1.5F,
                () -> "A physical doorway around a second corner must carry more energy "
                        + "than the sealed room: open=" + aroundTurn + ", sealed=" + sealed
        );
    }

    @Test
    void roomToOutdoorMazeRetainsAContinuousDiffractedPath() {
        Vec3 source = new Vec3(3.5, 2.5, 0.5);
        Vec3 listener = new Vec3(5.5, 2.5, 19.5);
        TestWorld mazeWorld = new TestWorld(position ->
                roomToOutdoorMaze(position, true)
        );
        TestWorld sealedWorld = new TestWorld(position ->
                roomToOutdoorMaze(position, false)
        );
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(mazeWorld, source);
        RoomProbe listenerProbe = AcousticTracer.probeRoom(mazeWorld, listener);

        for (int warmup = 0; warmup < 3; warmup++) {
            AcousticTracer.trace(
                    mazeWorld,
                    source,
                    listener,
                    sourceProbe,
                    listenerProbe,
                    AcousticTracer.TraceQuality.FULL
            );
        }
        long started = System.nanoTime();
        AcousticResult maze = AcousticTracer.trace(
                mazeWorld,
                source,
                listener,
                sourceProbe,
                listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );
        double elapsedMilliseconds = (System.nanoTime() - started) / 1_000_000.0;
        AcousticResult sealed = AcousticTracer.trace(
                sealedWorld,
                source,
                listener,
                AcousticTracer.probeSourceRoom(sealedWorld, source),
                AcousticTracer.probeRoom(sealedWorld, listener),
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                maze.diffractionContribution() > 0.001F,
                () -> "The room -> alternating corridor -> outdoors path vanished: "
                        + maze + ", sourceOpenings=" + sourceProbe.openings()
        );
        assertTrue(
                maze.diffractionContribution()
                        > sealed.diffractionContribution() * 1.5F,
                () -> "The open maze must carry more edge energy than its sealed entrance: "
                        + "maze=" + maze + ", sealed=" + sealed
        );
        assertTrue(
                elapsedMilliseconds < 10.0,
                () -> "The room-to-outdoor maze trace exceeded 10 ms: "
                        + elapsedMilliseconds
        );
    }

    @Test
    void walkingOutsideTheMazeDoesNotSwitchDiffractionOnAndOff() {
        Vec3 source = new Vec3(3.5, 2.5, 0.5);
        TestWorld world = new TestWorld(position -> roomToOutdoorMaze(position, true));
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        AcousticResult previous = null;
        Vec3 previousListener = null;
        for (double x = 4.05; x <= 6.95; x += 0.10) {
            Vec3 listener = new Vec3(x, 2.5, 19.5);
            AcousticResult current = AcousticTracer.trace(
                    world,
                    source,
                    listener,
                    sourceProbe,
                    AcousticTracer.probeRoom(world, listener),
                    AcousticTracer.TraceQuality.FULL
            );
            assertTrue(
                    current.diffractionContribution() > 0.001F,
                    () -> "Walking outside the maze deleted the connected path at x="
                            + listener.x + ": " + current
            );
            if (previous != null) {
                float larger = Math.max(
                        previous.diffractionContribution(),
                        current.diffractionContribution()
                );
                float relativeStep = Math.abs(
                        current.diffractionContribution()
                                - previous.diffractionContribution()
                ) / Math.max(larger, 0.001F);
                AcousticResult prior = previous;
                assertTrue(
                        relativeStep < 0.35F,
                        () -> "A ten-centimetre listener move caused a diffraction jump at x="
                                + listener.x + ": previous=" + prior + ", current=" + current
                );
                Vec3 previousDirection = previous.apparentPosition().subtract(previousListener);
                Vec3 currentDirection = current.apparentPosition().subtract(listener);
                if (previousDirection.lengthSqr() > 1.0E-8
                        && currentDirection.lengthSqr() > 1.0E-8) {
                    double directionDot = previousDirection.normalize().dot(
                            currentDirection.normalize()
                    );
                    assertTrue(
                            directionDot > 0.985,
                            () -> "A ten-centimetre move caused an audible maze arrival-direction jump: "
                                    + "previous=" + prior + ", current=" + current
                    );
                }
            }
            previous = current;
            previousListener = listener;
        }
    }

    @Test
    void soundKeepsFollowingTheExitAroundADeepExteriorDiagonalShadow() {
        Vec3 source = new Vec3(7.5, 2.5, 5.5);
        TestWorld openWorld = new TestWorld(position ->
                deepExteriorShadowRoom(position, true)
        );
        TestWorld sealedWorld = new TestWorld(position ->
                deepExteriorShadowRoom(position, false)
        );
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(openWorld, source);
        RoomProbe sealedSourceProbe = AcousticTracer.probeSourceRoom(sealedWorld, source);
        float previous = -1.0F;
        for (double z = 4.5; z <= 9.0; z += 0.25) {
            Vec3 listener = new Vec3(11.5, 2.5, z);
            AcousticResult open = AcousticTracer.trace(
                    openWorld,
                    source,
                    listener,
                    sourceProbe,
                    AcousticTracer.probeRoom(openWorld, listener),
                    AcousticTracer.TraceQuality.FULL
            );
            AcousticResult sealed = AcousticTracer.trace(
                    sealedWorld,
                    source,
                    listener,
                    sealedSourceProbe,
                    AcousticTracer.probeRoom(sealedWorld, listener),
                    AcousticTracer.TraceQuality.FULL
            );
            assertTrue(
                    open.diffractionContribution() > 1.0E-4F
                            && open.diffractionContribution()
                            > sealed.diffractionContribution() * 1.5F + 1.0E-5F,
                    () -> "The exit path vanished behind the exterior diagonal shadow at "
                            + listener + ": open=" + open + ", sealed=" + sealed
            );
            if (previous >= 0.0F) {
                float previousContribution = previous;
                float larger = Math.max(previous, open.diffractionContribution());
                float smaller = Math.min(previous, open.diffractionContribution());
                assertTrue(
                        larger / Math.max(smaller, 1.0E-6F) < 1.55F,
                        () -> "Moving deeper behind the outside wall caused the exit path "
                                + "to jump at " + listener + ": previous="
                                + previousContribution + ", current=" + open
                );
            }
            previous = open.diffractionContribution();
        }
    }

    @Test
    void seededLargePerfectMazeRetainsACompleteAcousticRoute() {
        long[] seeds = {0x51A7E5L, 0xC0FFEE12L, 0x7A11C0DEL};
        for (long seed : seeds) {
            SeededPerfectMaze maze = new SeededPerfectMaze(seed, 25);
            TestWorld openWorld = new TestWorld(maze);
            TestWorld sealedWorld = new TestWorld(position ->
                    maze.isSolid(position) || position.getX() == maze.size() / 2
            );
            Vec3 source = maze.source();
            Vec3 listener = maze.listener();
            AcousticResult open = AcousticTracer.trace(
                    openWorld,
                    source,
                    listener,
                    AcousticTracer.probeSourceRoom(openWorld, source),
                    AcousticTracer.probeRoom(openWorld, listener),
                    AcousticTracer.TraceQuality.FULL
            );
            AcousticResult sealed = AcousticTracer.trace(
                    sealedWorld,
                    source,
                    listener,
                    AcousticTracer.probeSourceRoom(sealedWorld, source),
                    AcousticTracer.probeRoom(sealedWorld, listener),
                    AcousticTracer.TraceQuality.FULL
            );
            assertTrue(
                    open.diffractionContribution() > 1.0E-6F
                            && open.diffractionContribution()
                            > sealed.diffractionContribution() * 2.0F + 1.0E-7F,
                    () -> "The bidirectional graph failed to connect the seeded maze, seed="
                            + seed + ": open=" + open + ", sealed=" + sealed
            );
        }
    }

    @Test
    void seededNoiseCavesCarrySoundThroughLongIrregularRoutes() {
        long[] seeds = {0x5EEDC0DEL, 0x71A9B43DL, 0xC4A7E123L};
        for (long seed : seeds) {
            NoiseCave cave = new NoiseCave(seed, 64);
            TestWorld openWorld = new TestWorld(cave);
            TestWorld sealedWorld = new TestWorld(position ->
                    cave.isSolid(position) || position.getX() == cave.length() / 2
            );
            Vec3 source = cave.pointAt(1.5);
            Vec3 listener = cave.pointAt(cave.length() - 1.5);
            RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(openWorld, source);
            RoomProbe listenerProbe = AcousticTracer.probeRoom(openWorld, listener);
            AcousticResult open = AcousticTracer.trace(
                    openWorld,
                    source,
                    listener,
                    sourceProbe,
                    listenerProbe,
                    AcousticTracer.TraceQuality.FULL
            );
            AcousticResult sealed = AcousticTracer.trace(
                    sealedWorld,
                    source,
                    listener,
                    AcousticTracer.probeSourceRoom(sealedWorld, source),
                    AcousticTracer.probeRoom(sealedWorld, listener),
                    AcousticTracer.TraceQuality.FULL
            );

            assertTrue(
                    open.diffractionContribution() > 1.0E-4F,
                    () -> "The connected noise cave was treated as sealed for seed="
                            + seed + ": sourceOpenings=" + sourceProbe.openings().size()
                            + ", listenerOpenings=" + listenerProbe.openings().size()
                            + ", result=" + open
            );
            assertTrue(
                    open.diffractionContribution()
                            > sealed.diffractionContribution() * 2.0F + 1.0E-5F,
                    () -> "A connected cave must carry more bent-path energy than the "
                            + "same cave with a solid cross-section, seed=" + seed
                            + ": open=" + open + ", sealed=" + sealed
            );
            Vec3 expectedArrival = cave.pointAt(cave.length() - 4.0).subtract(listener);
            Vec3 actualArrival = open.apparentPosition().subtract(listener);
            assertTrue(
                    actualArrival.lengthSqr() > 1.0E-8
                            && actualArrival.normalize().dot(expectedArrival.normalize()) > 0.35,
                    () -> "The first arrival came from outside the final cave bend for seed="
                            + seed + ": expected=" + expectedArrival
                            + ", actual=" + actualArrival + ", result=" + open
            );
        }
    }

    @Test
    void walkingInsideASeededNoiseCaveKeepsPropagationContinuous() {
        NoiseCave cave = new NoiseCave(0x19D04A77L, 64);
        TestWorld world = new TestWorld(cave);
        Vec3 source = cave.pointAt(1.5);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        AcousticResult previous = null;
        for (double x = cave.length() - 5.0; x <= cave.length() - 1.5; x += 0.25) {
            Vec3 listener = cave.pointAt(x);
            AcousticResult current = AcousticTracer.trace(
                    world,
                    source,
                    listener,
                    sourceProbe,
                    AcousticTracer.probeRoom(world, listener),
                    AcousticTracer.TraceQuality.FULL
            );
            assertTrue(
                    current.diffractionContribution() > 1.0E-4F,
                    () -> "A connected cave path vanished while walking at x=" + listener.x
                            + ": " + current
            );
            if (previous != null) {
                float larger = Math.max(
                        previous.diffractionContribution(),
                        current.diffractionContribution()
                );
                float smaller = Math.min(
                        previous.diffractionContribution(),
                        current.diffractionContribution()
                );
                AcousticResult prior = previous;
                assertTrue(
                        larger / Math.max(smaller, 1.0E-6F) < 1.65F,
                        () -> "A quarter-block move caused a cave propagation jump: previous="
                                + prior + ", current=" + current
                );
            }
            previous = current;
        }
    }

    @Test
    void horizontalTunnelTurningIntoAVerticalShaftKeepsTheFinalArrivalDirection() {
        SolidGeometry bentShaft = position -> {
            double x = position.getX() + 0.5;
            double y = position.getY() + 0.5;
            double z = position.getZ() + 0.5;
            boolean horizontalTunnel = x >= 0.0 && x <= 12.0
                    && y >= 1.0 && y <= 4.0
                    && z >= 1.0 && z <= 4.0;
            boolean verticalShaft = x >= 9.0 && x <= 12.0
                    && y >= 1.0 && y <= 14.0
                    && z >= 1.0 && z <= 4.0;
            return !(horizontalTunnel || verticalShaft);
        };
        TestWorld world = new TestWorld(bentShaft);
        Vec3 source = new Vec3(1.5, 2.5, 2.5);
        Vec3 listener = new Vec3(10.5, 12.5, 2.5);
        AcousticResult result = AcousticTracer.trace(
                world,
                source,
                listener,
                AcousticTracer.probeSourceRoom(world, source),
                AcousticTracer.probeRoom(world, listener),
                AcousticTracer.TraceQuality.FULL
        );
        Vec3 arrival = result.apparentPosition().subtract(listener).normalize();

        assertTrue(
                result.diffractionContribution() > 1.0E-5F,
                () -> "The connected horizontal-to-vertical tunnel was treated as sealed: "
                        + result
        );
        assertTrue(
                arrival.y < -0.65,
                () -> "The final shaft segment must arrive from below: arrival="
                        + arrival + ", result=" + result
        );
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ACOUSTICSYSTEM_PERFORMANCE_SMOKE", matches = "true")
    void seededNoiseCaveTraceRemainsBelowTenMilliseconds() {
        NoiseCave cave = new NoiseCave(0x5EEDC0DEL, 64);
        TestWorld world = new TestWorld(cave);
        Vec3 source = cave.pointAt(1.5);
        Vec3 listener = cave.pointAt(cave.length() - 1.5);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        RoomProbe listenerProbe = AcousticTracer.probeRoom(world, listener);
        for (int warmup = 0; warmup < 16; warmup++) {
            Vec3 warmupListener = cave.pointAt(
                    cave.length() - 1.90 + warmup * 0.02
            );
            AcousticTracer.trace(
                    world, source, warmupListener, sourceProbe, listenerProbe,
                    AcousticTracer.TraceQuality.FULL
            );
        }
        double maximumMilliseconds = 0.0;
        double totalMilliseconds = 0.0;
        double[] elapsedSamples = new double[20];
        for (int sample = 0; sample < 20; sample++) {
            Vec3 movingListener = cave.pointAt(
                    cave.length() - 1.5 + (sample % 10) * 0.02
            );
            long started = System.nanoTime();
            AcousticTracer.trace(
                    world, source, movingListener, sourceProbe, listenerProbe,
                    AcousticTracer.TraceQuality.FULL
            );
            double elapsed = (System.nanoTime() - started) / 1_000_000.0;
            elapsedSamples[sample] = elapsed;
            totalMilliseconds += elapsed;
            maximumMilliseconds = Math.max(maximumMilliseconds, elapsed);
        }
        Arrays.sort(elapsedSamples);
        double percentile95 = elapsedSamples[18];
        double measured = maximumMilliseconds;
        System.out.println("maximumSeededNoiseCaveTraceMilliseconds=" + measured
                + ", percentile95SeededNoiseCaveTraceMilliseconds=" + percentile95
                + ", averageSeededNoiseCaveTraceMilliseconds="
                + totalMilliseconds / 20.0);
        assertTrue(percentile95 < 10.0, () ->
                "seeded noise cave p95 trace exceeded 10 ms: " + percentile95
                        + ", maximum=" + measured);
        assertTrue(measured < 25.0, () ->
                "seeded noise cave trace produced a non-GC spike: " + measured);
    }

    @Test
    void doorwayShadowChangesContinuouslyAlongTheExteriorWall() {
        Vec3 source = new Vec3(3.5, 2.5, 0.5);
        TestWorld openWorld = new TestWorld(
                position -> roomShellWithFrontDoor(position, true)
        );
        TestWorld sealedWorld = new TestWorld(
                position -> roomShellWithFrontDoor(position, false)
        );
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(openWorld, source);
        RoomProbe sealedSourceProbe = AcousticTracer.probeSourceRoom(sealedWorld, source);
        float previous = -1.0F;
        for (double x = 2.0; x <= 4.25; x += 0.25) {
            Vec3 listener = new Vec3(x, 2.5, 6.15);
            AcousticResult open = AcousticTracer.trace(
                    openWorld,
                    source,
                    listener,
                    sourceProbe,
                    AcousticTracer.probeRoom(openWorld, listener),
                    AcousticTracer.TraceQuality.FULL
            );
            AcousticResult sealed = AcousticTracer.trace(
                    sealedWorld,
                    source,
                    listener,
                    sealedSourceProbe,
                    AcousticTracer.probeRoom(sealedWorld, listener),
                    AcousticTracer.TraceQuality.FULL
            );
            assertTrue(
                    open.diffractionContribution()
                            > sealed.diffractionContribution() + 1.0E-3F,
                    () -> "The doorway path disappeared at listener=" + listener
                            + ", open=" + open + ", sealed=" + sealed
            );
            if (previous >= 0.0F) {
                float previousContribution = previous;
                float smaller = Math.min(previous, open.directGain());
                float larger = Math.max(previous, open.directGain());
                assertTrue(
                        larger / Math.max(smaller, 1.0E-6F) < 2.5F,
                        () -> "A 0.25-block move caused a diffraction step at listener="
                                + listener + ", previous=" + previousContribution
                                + ", current=" + open
                );
            }
            previous = open.directGain();
        }
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
                partial.reverbSend() > sealed.reverbSend() * 1.05F,
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
        assertTrue(
                Math.abs(result.reverbRoom().lateReverbGain()
                        - listenerProbe.acoustics().lateReverbGain()) < 1.0E-6F
                        && Math.abs(result.reverbRoom().decayTime()
                        - listenerProbe.acoustics().decayTime()) < 1.0E-6F,
                () -> "Per-source early reflections must not replace the listener's "
                        + "shared late field: result=" + result.reverbRoom()
                        + ", listener=" + listenerProbe.acoustics()
        );
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
    void physicalEarlyReflectionRemainsAudibleWithoutALateReverbField() {
        Vec3 source = new Vec3(-4.5, 2.5, 0.5);
        Vec3 listener = new Vec3(4.5, 2.5, 0.5);
        TestWorld world = new TestWorld(position -> {
            boolean ceiling = position.getY() == 5
                    && position.getX() >= -8 && position.getX() <= 8
                    && position.getZ() >= -3 && position.getZ() <= 3;
            boolean screen = position.getX() == 0
                    && position.getY() >= 1 && position.getY() <= 3
                    && position.getZ() >= -1 && position.getZ() <= 1;
            return ceiling || screen;
        });
        RoomProbe ceilingProbe = new RoomProbe(
                RoomAcoustics.OUTDOORS,
                List.of(new RoomProbe.SurfaceSample(
                        new Vec3(0.5, 5.0, 0.5),
                        new Vec3(0.0, -1.0, 0.0),
                        new AABB(0.0, 5.0, 0.0, 1.0, 6.0, 1.0),
                        2.5,
                        AcousticMaterialRegistry.find(Blocks.STONE.defaultBlockState())
                )),
                List.of()
        );
        AcousticResult withoutReflection = AcousticTracer.trace(
                world, source, listener, OUTDOOR_PROBE, AcousticTracer.TraceQuality.FULL
        );
        AcousticResult withReflection = AcousticTracer.trace(
                world, source, listener, ceilingProbe, AcousticTracer.TraceQuality.FULL
        );
        AcousticResult listenerOnlySurface = AcousticTracer.trace(
                world,
                source,
                listener,
                OUTDOOR_PROBE,
                ceilingProbe,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                Math.abs(withReflection.directGain() - withoutReflection.directGain()) < 1.0E-6F,
                () -> "A later image-source arrival must not be injected into the dry "
                        + "positional voice: dry=" + withoutReflection
                        + ", reflected=" + withReflection
        );
        assertTrue(
                withReflection.earlyReflection().gain()
                        > withoutReflection.earlyReflection().gain() + 0.05F,
                () -> "The validated image-source arrival must remain audible through its "
                        + "dedicated early-reflection channel outdoors: dry=" + withoutReflection
                        + ", reflected=" + withReflection
        );
        assertTrue(
                Math.abs(withReflection.reverbSend() - withoutReflection.reverbSend()) < 1.0E-6F,
                () -> "An early image path must not be counted again as late-field input: dry="
                        + withoutReflection + ", reflected=" + withReflection
        );
        assertTrue(
                listenerOnlySurface.earlyReflection().gain() < 1.0E-6F,
                () -> "A surface sampled only from the listener must not become a first-order "
                        + "source reflection: " + listenerOnlySurface
        );
        assertTrue(
                withReflection.reverbRoom().equals(ceilingProbe.acoustics()),
                () -> "A per-source image path must not overwrite the listener-owned shared field: "
                        + withReflection.reverbRoom()
        );
        assertTrue(
                withReflection.apparentPosition().distanceToSqr(
                        withoutReflection.apparentPosition()
                ) < 1.0E-8,
                () -> "A later specular reflection must add energy without overriding the "
                        + "first-arrival direction: dry=" + withoutReflection
                        + ", reflected=" + withReflection
        );
        assertTrue(withReflection.reverbRoom().lateReverbGain() == 0.0F);
    }

    @Test
    void listenerRoomNeverInventsASourceIndependentReflectionDirection() {
        Vec3 listener = new Vec3(0.5, 2.5, 0.5);
        TestWorld asymmetricRoom = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean shell = x >= -2 && x <= 8
                    && y >= 0 && y <= 5
                    && z >= -4 && z <= 4
                    && (x == -2 || x == 8 || y == 0 || y == 5 || z == -4 || z == 4);
            boolean extraReflector = x == -1 && y >= 1 && y <= 4 && z >= -3 && z <= 3;
            return shell || extraReflector;
        });
        RoomProbe probe = AcousticTracer.probeRoom(asymmetricRoom, listener);

        assertTrue(probe.acoustics().gain() > 0.0F, () -> "probe=" + probe.acoustics());
        assertTrue(
                probe.acoustics().reflectionsPan().lengthSqr() < 1.0E-12,
                () -> "Room geometry alone cannot choose the arrival side of every source: "
                        + probe.acoustics()
        );
    }

    @Test
    void aLeftWallReflectionCannotMoveARightHandSourceOrSharedField() {
        Vec3 listener = new Vec3(0.5, 2.5, 0.5);
        Vec3 source = new Vec3(4.5, 2.5, 0.5);
        TestWorld world = new TestWorld(position -> position.getX() == -2
                && position.getY() >= 0 && position.getY() <= 5
                && position.getZ() >= -5 && position.getZ() <= 5);
        RoomAcoustics listenerField = new RoomAcoustics(
                0.6F, 0.7F, 0.25F, 0.75F, 1.0F,
                1.2F, 0.7F, 1.1F,
                0.3F, 0.015F, Vec3.ZERO,
                0.8F, 0.025F, Vec3.ZERO,
                0.25F, 0.0F, 0.97F
        );
        RoomProbe withoutSurface = new RoomProbe(listenerField, List.of(), List.of());
        RoomProbe withLeftSurface = new RoomProbe(
                listenerField,
                List.of(new RoomProbe.SurfaceSample(
                        new Vec3(-1.0, 2.5, 0.5),
                        new Vec3(1.0, 0.0, 0.0),
                        new AABB(-2.0, 0.0, -5.0, -1.0, 6.0, 6.0),
                        2.5,
                        AcousticMaterialRegistry.find(Blocks.STONE.defaultBlockState())
                )),
                List.of()
        );
        AcousticTracer.ReflectionResult reflectedPath = AcousticTracer.estimateEarlyReflections(
                world, source, listener, withLeftSurface, 4
        );
        AcousticResult dry = AcousticTracer.trace(
                world, source, listener, withoutSurface, AcousticTracer.TraceQuality.FULL
        );
        AcousticResult reflected = AcousticTracer.trace(
                world, source, listener, withLeftSurface, AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                reflectedPath.gain() > 0.01F && reflectedPath.pan().x < -0.5,
                () -> "The image-source solver must actually find the left arrival: "
                        + reflectedPath
        );
        assertTrue(
                reflected.earlyReflection().gain() > dry.earlyReflection().gain()
                        && reflected.earlyReflection().arrivalDirection().x < -0.5,
                () -> "The left reflection must retain its calculated energy: dry="
                        + dry + ", reflected=" + reflected
        );
        assertTrue(
                reflected.apparentPosition().x > listener.x
                        && reflected.apparentPosition().distanceToSqr(dry.apparentPosition()) < 1.0E-10,
                () -> "A later reflection must not pull the right-hand first arrival to the left: "
                        + reflected
        );
        assertTrue(
                reflected.reverbRoom().equals(listenerField)
                        && reflected.reverbRoom().reflectionsPan().lengthSqr() < 1.0E-12,
                () -> "A per-source left reflection must not pan the listener-owned field: "
                        + reflected.reverbRoom()
        );
    }

    @Test
    void aLargeCaveProducesALaterFiniteEarlyFieldInsteadOfLosingEcho() {
        TestWorld smallRoom = new TestWorld(position -> rectangularShell(
                position, -4, 4, 0, 6, -4, 4
        ));
        TestWorld largeCave = new TestWorld(position -> rectangularShell(
                position, -18, 18, -8, 18, -18, 18
        ));
        Vec3 smallSource = new Vec3(-1.5, 3.0, 0.5);
        Vec3 smallListener = new Vec3(1.5, 3.0, 0.5);
        Vec3 caveSource = new Vec3(-1.5, 5.0, 0.5);
        Vec3 caveListener = new Vec3(1.5, 5.0, 0.5);
        AcousticTracer.ReflectionResult small = AcousticTracer.estimateEarlyReflections(
                smallRoom,
                smallSource,
                smallListener,
                AcousticTracer.probeSourceRoom(smallRoom, smallSource),
                4
        );
        AcousticTracer.ReflectionResult large = AcousticTracer.estimateEarlyReflections(
                largeCave,
                caveSource,
                caveListener,
                AcousticTracer.probeSourceRoom(largeCave, caveSource),
                4
        );

        assertTrue(small.gain() > 0.02F, () -> "small=" + small);
        assertTrue(large.gain() > 0.005F, () -> "large=" + large);
        assertTrue(
                large.delay() > small.delay(),
                () -> "A larger enclosure should move its aggregate first reflection later: small="
                        + small + ", large=" + large
        );
    }

    @Test
    void earlyReflectionRemainsContinuousDuringFineMovementInAComplexRoom() {
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean shell = rectangularShell(position, -12, 12, 0, 8, -10, 10);
            boolean leftBaffle = x == -3 && y >= 1 && y <= 6 && z >= -9 && z <= -2;
            boolean rightBaffle = x == 3 && y >= 1 && y <= 6 && z >= 2 && z <= 9;
            boolean pillars = (x == -7 || x == 7) && y >= 1 && y <= 6
                    && (z == -5 || z == 5);
            return shell || leftBaffle || rightBaffle || pillars;
        });
        Vec3 source = new Vec3(-8.5, 3.0, 0.5);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        AcousticTracer.ReflectionResult previous = null;
        for (int step = 0; step <= 40; step++) {
            Vec3 listener = new Vec3(5.0 + step * 0.05, 3.0, 0.5);
            AcousticTracer.ReflectionResult current = AcousticTracer.estimateEarlyReflections(
                    world, source, listener, sourceProbe, 4
            );
            assertTrue(Float.isFinite(current.gain()) && Float.isFinite(current.delay()));
            assertTrue(
                    current.gain() > 0.002F,
                    () -> "The complex room lost every early path at " + listener + ": " + current
            );
            if (previous != null) {
                AcousticTracer.ReflectionResult prior = previous;
                float gainStep = Math.abs(current.gain() - prior.gain());
                float delayStep = Math.abs(current.delay() - prior.delay());
                assertTrue(
                        gainStep < 0.10F,
                        () -> "A five-centimetre move caused an echo level jump: previous="
                                + prior + ", current=" + current
                );
                assertTrue(
                        delayStep < 0.010F,
                        () -> "A five-centimetre move caused an echo-time jump: previous="
                                + prior + ", current=" + current
                );
            }
            previous = current;
        }
    }

    @Test
    void reflectionPathBudgetAddsEnergyWithoutChangingTheEnvironmentDecision() {
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean shell = rectangularShell(position, -9, 9, 0, 9, -8, 8);
            boolean staggeredReflectors = y >= 1 && y <= 7
                    && ((x == -4 && z >= -7 && z <= -3)
                    || (x == 4 && z >= 3 && z <= 7)
                    || (z == -2 && x >= 1 && x <= 6));
            return shell || staggeredReflectors;
        });
        Vec3 source = new Vec3(-2.5, 3.5, 1.5);
        Vec3 listener = new Vec3(2.5, 3.5, -0.5);
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        float previousGain = 0.0F;
        for (int budget : new int[]{1, 2, 4, 8, 16}) {
            float priorGain = previousGain;
            AcousticTracer.ReflectionResult reflection = AcousticTracer.estimateEarlyReflections(
                    world, source, listener, sourceProbe, budget
            );
            assertTrue(
                    reflection.gain() + 1.0E-6F >= priorGain,
                    () -> "Adding independent image paths removed reflected energy at budget "
                            + budget + ": " + reflection
            );
            assertTrue(reflection.delay() >= 0.0F && reflection.delay() <= 0.3F);
            assertTrue(Double.isFinite(reflection.pan().lengthSqr()));
            previousGain = reflection.gain();
        }
        assertTrue(previousGain > 0.01F, "The multi-surface room must retain a finite echo field");
    }

    @Test
    void openingOrClosingOneExitDoesNotEraseUnrelatedPhysicalReflections() {
        Vec3 source = new Vec3(-2.5, 3.0, 0.5);
        Vec3 listener = new Vec3(2.5, 3.0, 0.5);
        TestWorld closed = new TestWorld(position -> rectangularShell(
                position, -7, 7, 0, 7, -7, 7
        ));
        TestWorld opened = new TestWorld(position -> {
            boolean shell = rectangularShell(position, -7, 7, 0, 7, -7, 7);
            boolean doorway = position.getZ() == 7
                    && Math.abs(position.getX()) <= 1
                    && position.getY() >= 1 && position.getY() <= 3;
            return shell && !doorway;
        });
        AcousticTracer.ReflectionResult closedReflection = AcousticTracer.estimateEarlyReflections(
                closed,
                source,
                listener,
                AcousticTracer.probeSourceRoom(closed, source),
                4
        );
        AcousticTracer.ReflectionResult openReflection = AcousticTracer.estimateEarlyReflections(
                opened,
                source,
                listener,
                AcousticTracer.probeSourceRoom(opened, source),
                4
        );

        assertTrue(closedReflection.gain() > 0.01F, () -> "closed=" + closedReflection);
        assertTrue(openReflection.gain() > 0.01F, () -> "open=" + openReflection);
        assertTrue(
                openReflection.gain() > closedReflection.gain() * 0.35F,
                () -> "Editing one exit erased reflections from the remaining walls: closed="
                        + closedReflection + ", open=" + openReflection
        );
    }

    private static boolean rectangularShell(
            BlockPos position,
            int minimumX,
            int maximumX,
            int minimumY,
            int maximumY,
            int minimumZ,
            int maximumZ
    ) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        boolean inside = x >= minimumX && x <= maximumX
                && y >= minimumY && y <= maximumY
                && z >= minimumZ && z <= maximumZ;
        return inside && (x == minimumX || x == maximumX
                || y == minimumY || y == maximumY
                || z == minimumZ || z == maximumZ);
    }

    @Test
    void contactVibrationTravelsThroughAConnectedFloorAndReradiatesNearTheListener() {
        SolidGeometry building = position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean floor = y == 0 && x >= -20 && x <= 20 && z >= -20 && z <= 20;
            boolean lowerDivider = x == 0 && y >= -10 && y <= 0
                    && z >= -20 && z <= 20;
            return floor || lowerDivider;
        };
        TestWorld world = new TestWorld(building);
        Vec3 contactSource = new Vec3(-4.5, 1.0, 0.5);
        Vec3 detachedSource = new Vec3(-4.5, 1.6, 0.5);
        Vec3 listener = new Vec3(4.5, -3.0, 0.5);
        AcousticResult contact = AcousticTracer.trace(
                world,
                contactSource,
                listener,
                OUTDOOR_PROBE,
                AcousticTracer.TraceQuality.FULL
        );
        AcousticTracer.StructuralPath structural = AcousticTracer.traceStructuralPath(
                world,
                contactSource,
                listener,
                AcousticMaterialRegistry.tuning()
        );
        AcousticTracer.StructuralPath blockCentred = AcousticTracer.traceStructuralPath(
                world,
                new Vec3(-4.5, 0.5, 0.5),
                listener,
                AcousticMaterialRegistry.tuning()
        );
        AcousticResult detached = AcousticTracer.trace(
                world,
                detachedSource,
                listener,
                OUTDOOR_PROBE,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(
                contact.lowBandGain() > detached.lowBandGain() * 1.5F,
                () -> "Mechanical coupling must emerge continuously from actual surface contact: contact="
                        + contact + ", detached=" + detached
        );
        assertTrue(
                contact.lowBandGain() > contact.highBandGain() * 8.0F,
                () -> "Structure-borne sound must arrive as a damped low-frequency thump: "
                        + contact
        );
        assertTrue(
                structural != null && structural.arrivalPoint().x >= 0.99
                        && contact.apparentPosition().x > -3.8,
                () -> "The vibration must reradiate from the listener-side floor instead of "
                        + "remaining at the blocked source: path=" + structural
                        + ", result=" + contact
        );
        assertTrue(
                blockCentred == null,
                () -> "A block-centred airborne sound must not masquerade as a "
                        + "surface-contact impulse: " + blockCentred
        );
        if ("true".equals(System.getenv("ACOUSTICSYSTEM_PERFORMANCE_SMOKE"))) {
            for (int warmup = 0; warmup < 4; warmup++) {
                AcousticTracer.trace(
                        world, contactSource, listener,
                        OUTDOOR_PROBE, AcousticTracer.TraceQuality.FULL
                );
            }
            double maximumMilliseconds = 0.0;
            for (int sample = 0; sample < 16; sample++) {
                long started = System.nanoTime();
                AcousticTracer.trace(
                        world, contactSource, listener,
                        OUTDOOR_PROBE, AcousticTracer.TraceQuality.FULL
                );
                maximumMilliseconds = Math.max(
                        maximumMilliseconds,
                        (System.nanoTime() - started) / 1_000_000.0
                );
            }
            double measured = maximumMilliseconds;
            System.out.println("maximumStructuralTraceMilliseconds=" + measured);
            assertTrue(
                    measured < 10.0,
                    () -> "connected-structure trace exceeded 10 ms: " + measured
            );
        }
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

    private static boolean roomShellWithFrontDoor(BlockPos position, boolean openDoor) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        boolean span = x >= -5 && x <= 5
                && y >= 0 && y <= 6
                && z >= -5 && z <= 5;
        boolean shell = span && (x == -5 || x == 5
                || y == 0 || y == 6 || z == -5 || z == 5);
        boolean doorway = z == 5
                && x >= -1 && x <= 1
                && y >= 1 && y <= 3;
        return shell && !(openDoor && doorway);
    }

    @Test
    void oneSealedAirVolumeUsesOneListenerOwnedDiffuseField() {
        Vec3 source = new Vec3(-3.5, 2.5, 0.5);
        Vec3 listener = new Vec3(3.5, 2.5, 0.5);
        TestWorld world = new TestWorld(position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean room = x >= -8 && x <= 8
                    && y >= 0 && y <= 6
                    && z >= -8 && z <= 8;
            return room && (x == -8 || x == 8
                    || y == 0 || y == 6 || z == -8 || z == 8);
        });
        RoomProbe sourceProbe = AcousticTracer.probeSourceRoom(world, source);
        RoomProbe listenerProbe = AcousticTracer.probeRoom(world, listener);
        AcousticResult result = AcousticTracer.trace(
                world, source, listener, sourceProbe, listenerProbe,
                AcousticTracer.TraceQuality.FULL
        );

        assertTrue(sourceProbe.openings().isEmpty()
                        && listenerProbe.openings().isEmpty(),
                "The regression geometry must remain a sealed diffuse volume");
        assertTrue(
                result.sourceRoomProbe() == null,
                () -> "Air-connected points in one sealed volume were routed as a remote sealed room: "
                        + result
        );
        assertTrue(
                result.reverbSend() > 0.5F,
                () -> "The shared sealed room must still be excited: " + result
        );
    }

    private static boolean roomShellWithTwinFrontExits(
            BlockPos position,
            boolean openLeft,
            boolean openRight
    ) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        boolean span = x >= -6 && x <= 6
                && y >= 0 && y <= 6
                && z >= -5 && z <= 5;
        boolean shell = span && (x == -6 || x == 6
                || y == 0 || y == 6 || z == -5 || z == 5);
        boolean leftExit = z == 5
                && x >= -3 && x <= -2
                && y >= 1 && y <= 4;
        boolean rightExit = z == 5
                && x >= 2 && x <= 3
                && y >= 1 && y <= 4;
        return shell && !(openLeft && leftExit) && !(openRight && rightExit);
    }

    private static boolean roomWithExteriorTurn(BlockPos position, boolean openDoor) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        boolean room = roomShellWithFrontDoor(position, openDoor);
        boolean exteriorScreen = z == 7
                && x >= -7 && x <= 3
                && y >= 0 && y <= 6;
        boolean corridorBoundary = z >= 5 && z <= 12
                && (x == -7 || x == 7 || y == 0 || y == 6);
        return room || exteriorScreen || corridorBoundary;
    }

    private static boolean roomToOutdoorMaze(BlockPos position, boolean openDoor) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        boolean room = roomShellWithFrontDoor(position, openDoor);
        boolean corridorBoundary = z >= 5 && z <= 17
                && (x == -7 || x == 7 || y == 0 || y == 6);
        boolean firstScreen = z == 8
                && x >= -7 && x <= 3
                && y >= 0 && y <= 6;
        boolean secondScreen = z == 12
                && x >= -3 && x <= 7
                && y >= 0 && y <= 6;
        boolean thirdScreen = z == 16
                && x >= -7 && x <= 3
                && y >= 0 && y <= 6;
        return room || corridorBoundary || firstScreen || secondScreen || thirdScreen;
    }

    private static boolean deepExteriorShadowRoom(
            BlockPos position,
            boolean openDoor
    ) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        boolean span = x >= 0 && x <= 10
                && y >= 0 && y <= 8
                && z >= 0 && z <= 10;
        boolean shell = span && (x == 0 || x == 10
                || y == 0 || y == 8 || z == 0 || z == 10);
        boolean doorway = x == 0
                && y >= 1 && y <= 3
                && z >= 2 && z <= 3;
        return shell && !(openDoor && doorway);
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

    @Test
    void blockCentredSourceKeepsItsAirRoutePastAnExteriorDiagonalShadow() {
        Vec3 source = new Vec3(-8.5, 2.5, -8.5);
        BlockPos sourceBlock = BlockPos.containing(source);
        SolidGeometry geometry = position -> {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            boolean inRoomSpan = x >= -12 && x <= 0
                    && y >= 0 && y <= 6
                    && z >= -12 && z <= 8;
            boolean shell = inRoomSpan && (x == -12 || x == 0
                    || y == 0 || y == 6 || z == -12 || z == 8);
            boolean doorway = x == 0 && y >= 1 && y <= 4
                    && z >= 2 && z <= 4;
            return position.equals(sourceBlock) || shell && !doorway;
        };
        TestWorld world = new TestWorld(geometry);
        Vec3 previousDirection = null;

        for (double z = -4.5; z >= -9.5; z -= 0.5) {
            Vec3 listener = new Vec3(3.5, 2.5, z);
            AcousticResult result = AcousticTracer.trace(
                    world,
                    source,
                    listener,
                    OUTDOOR_PROBE,
                    AcousticTracer.TraceQuality.FULL
            );
            assertTrue(
                    result.diffractionContribution() > 1.0E-4F,
                    () -> "The embedded block source lost its connected air path at "
                            + listener + ": " + result
            );
            Vec3 direction = result.apparentPosition().subtract(listener).normalize();
            if (previousDirection != null) {
                Vec3 prior = previousDirection;
                assertTrue(
                        prior.dot(direction) > 0.82,
                        () -> "Arrival direction jumped while crossing a cell boundary: prior="
                                + prior + ", current=" + direction + ", listener=" + listener
                );
            }
            previousDirection = direction;
        }
    }

    /**
     * Deterministic voxel cave whose centre line and radius come from smooth seeded
     * value noise. Everything outside the tube is solid, so reaching the far endpoint
     * requires following the connected bends instead of escaping around the fixture.
     */
    private record NoiseCave(long seed, int length) implements SolidGeometry {
        @Override
        public boolean isSolid(BlockPos position) {
            double x = position.getX() + 0.5;
            if (x < 0.0 || x > length) {
                return true;
            }
            double y = position.getY() + 0.5;
            double z = position.getZ() + 0.5;
            double centerY = centerY(x);
            double centerZ = centerZ(x);
            double radius = radius(x);
            double vertical = (y - centerY) / 0.82;
            double lateral = z - centerZ;
            return vertical * vertical + lateral * lateral > radius * radius;
        }

        private Vec3 pointAt(double x) {
            return new Vec3(x, centerY(x), centerZ(x));
        }

        private double centerY(double x) {
            return 4.5 + octaveNoise(seed ^ 0x6A09E667F3BCC909L, x, 0.075) * 2.2;
        }

        private double centerZ(double x) {
            return octaveNoise(seed ^ 0xBB67AE8584CAA73BL, x, 0.060) * 7.0;
        }

        private double radius(double x) {
            return 2.35 + octaveNoise(seed ^ 0x3C6EF372FE94F82BL, x, 0.11) * 0.35;
        }

        private static double octaveNoise(long seed, double x, double frequency) {
            double sum = 0.0;
            double amplitude = 1.0;
            double normalization = 0.0;
            for (int octave = 0; octave < 3; octave++) {
                sum += smoothValueNoise(seed + octave * 0x9E3779B97F4A7C15L,
                        x * frequency) * amplitude;
                normalization += amplitude;
                frequency *= 2.03;
                amplitude *= 0.5;
            }
            return sum / normalization;
        }

        private static double smoothValueNoise(long seed, double coordinate) {
            long cell = (long) Math.floor(coordinate);
            double fraction = coordinate - cell;
            double smooth = fraction * fraction * fraction
                    * (fraction * (fraction * 6.0 - 15.0) + 10.0);
            double first = randomSigned(seed, cell);
            double second = randomSigned(seed, cell + 1L);
            return first + (second - first) * smooth;
        }

        private static double randomSigned(long seed, long coordinate) {
            long value = seed ^ (coordinate * 0x9E3779B97F4A7C15L);
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            value ^= value >>> 31;
            return ((value >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
        }
    }

    private static final class SeededPerfectMaze implements SolidGeometry {
        private static final int[] STEP_X = {1, -1, 0, 0};
        private static final int[] STEP_Z = {0, 0, 1, -1};

        private final int cellCount;
        private final int size;
        private final boolean[][] open;

        private SeededPerfectMaze(long seed, int cellCount) {
            this.cellCount = cellCount;
            this.size = cellCount * 2 + 1;
            this.open = new boolean[size][size];
            carve(seed);
        }

        private void carve(long seed) {
            boolean[][] visited = new boolean[cellCount][cellCount];
            ArrayDeque<Integer> stack = new ArrayDeque<>();
            Random random = new Random(seed);
            visited[0][0] = true;
            open[1][1] = true;
            stack.addLast(0);
            while (!stack.isEmpty()) {
                int current = stack.getLast();
                int cellX = current % cellCount;
                int cellZ = current / cellCount;
                int[] candidates = new int[4];
                int candidateCount = 0;
                for (int direction = 0; direction < 4; direction++) {
                    int nextX = cellX + STEP_X[direction];
                    int nextZ = cellZ + STEP_Z[direction];
                    if (nextX >= 0 && nextX < cellCount
                            && nextZ >= 0 && nextZ < cellCount
                            && !visited[nextX][nextZ]) {
                        candidates[candidateCount++] = direction;
                    }
                }
                if (candidateCount == 0) {
                    stack.removeLast();
                    continue;
                }
                int direction = candidates[random.nextInt(candidateCount)];
                int nextX = cellX + STEP_X[direction];
                int nextZ = cellZ + STEP_Z[direction];
                int worldX = cellX * 2 + 1;
                int worldZ = cellZ * 2 + 1;
                open[worldX + STEP_X[direction]][worldZ + STEP_Z[direction]] = true;
                open[nextX * 2 + 1][nextZ * 2 + 1] = true;
                visited[nextX][nextZ] = true;
                stack.addLast(nextZ * cellCount + nextX);
            }
        }

        @Override
        public boolean isSolid(BlockPos position) {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            return y < 1 || y > 2
                    || x < 0 || x >= size
                    || z < 0 || z >= size
                    || !open[x][z];
        }

        private int size() {
            return size;
        }

        private Vec3 source() {
            return new Vec3(1.5, 1.5, 1.5);
        }

        private Vec3 listener() {
            return new Vec3(size - 1.5, 1.5, size - 1.5);
        }
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

        public int getMinY() {
            return -64;
        }

        public int getMinBuildHeight() {
            return -64;
        }

        @Override
        public int getHeight() {
            return 384;
        }
    }
}
