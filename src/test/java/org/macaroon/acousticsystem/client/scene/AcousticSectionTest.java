package org.macaroon.acousticsystem.client.scene;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticSectionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void worldCoordinatesWrapToTheirSectionLocalCoordinates() {
        assertEquals(0, AcousticSection.local(0));
        assertEquals(15, AcousticSection.local(15));
        assertEquals(0, AcousticSection.local(16));
        assertEquals(15, AcousticSection.local(-1));
        assertEquals(9, AcousticSection.local(20_937));
        assertEquals(0, AcousticSection.local(-28_256));
    }

    @Test
    void sealedAirVolumesAreProvenDisconnectedWithoutVoxelSearch() {
        long[] air = new long[64];
        setAir(air, 2, 2, 2);
        setAir(air, 12, 12, 12);
        AcousticScene scene = scene(Map.of(
                new AcousticScene.SectionKey(0, 0, 0), new AcousticSection(air)
        ));

        int first = scene.airComponentId(2, 2, 2);
        int second = scene.airComponentId(12, 12, 12);
        assertNotEquals(first, second);
        assertTrue(scene.isAirComponentClosed(first));
        assertTrue(scene.isAirComponentClosed(second));
    }

    @Test
    void componentsJoinAcrossSectionFacesAndExposeUncapturedBoundaries() {
        long[] leftAir = new long[64];
        long[] rightAir = new long[64];
        setAir(leftAir, 15, 8, 8);
        setAir(rightAir, 0, 8, 8);
        AcousticScene joined = scene(Map.of(
                new AcousticScene.SectionKey(0, 0, 0), new AcousticSection(leftAir),
                new AcousticScene.SectionKey(1, 0, 0), new AcousticSection(rightAir)
        ));
        assertEquals(
                joined.airComponentId(15, 8, 8),
                joined.airComponentId(16, 8, 8)
        );
        assertTrue(joined.isAirComponentClosed(joined.airComponentId(15, 8, 8)));

        long[] boundaryAir = new long[64];
        setAir(boundaryAir, 0, 8, 8);
        AcousticScene open = scene(Map.of(
                new AcousticScene.SectionKey(0, 0, 0), new AcousticSection(boundaryAir)
        ));
        assertFalse(open.isAirComponentClosed(open.airComponentId(0, 8, 8)));
    }

    @Test
    void hierarchicalPortalGraphFindsTheOffsetOpening() {
        AcousticScene scene = AcousticSceneFixtures
                .sixtyFourCubeWithOffsetWallOpening();
        List<List<Long>> paths = scene.portalPaths(
                10, 8, 10,
                54, 8, 10,
                4
        );
        assertFalse(paths.isEmpty());
        List<Long> shortest = paths.get(0);
        assertEquals(BlockPos.asLong(10, 8, 10), shortest.get(0));
        assertEquals(BlockPos.asLong(54, 8, 10), shortest.get(shortest.size() - 1));
        for (int index = 1; index < shortest.size(); index++) {
            long previous = shortest.get(index - 1);
            long current = shortest.get(index);
            int distance = Math.abs(BlockPos.getX(previous) - BlockPos.getX(current))
                    + Math.abs(BlockPos.getY(previous) - BlockPos.getY(current))
                    + Math.abs(BlockPos.getZ(previous) - BlockPos.getZ(current));
            assertEquals(1, distance);
            assertTrue(scene.isAir(
                    BlockPos.getX(current), BlockPos.getY(current), BlockPos.getZ(current)
            ));
        }
    }

    @Test
    void portalGraphRetainsMultipleDisconnectedOpeningsForKShortestSearch() {
        AcousticScene scene = AcousticSceneFixtures
                .sixtyFourCubeWithTwinWallOpenings();
        List<List<Long>> paths = scene.portalPaths(
                10, 8, 30,
                54, 8, 30,
                4
        );
        assertTrue(paths.size() >= 2, () -> "paths=" + paths.size());
        assertNotEquals(paths.get(0), paths.get(1));
    }

    @Test
    void collisionBvhRejectsEmptySpaceButRetainsWalls() {
        AcousticScene scene = AcousticSceneFixtures
                .sixtyFourCubeWithOffsetWallOpening();
        assertFalse(scene.mayIntersectPotentialCollision(
                new Vec3(10.5, 8.5, 10.5),
                new Vec3(25.5, 8.5, 10.5)
        ));
        assertTrue(scene.mayIntersectPotentialCollision(
                new Vec3(10.5, 8.5, 10.5),
                new Vec3(54.5, 8.5, 10.5)
        ));
        assertFalse(scene.mayIntersectPotentialCollision(
                new Vec3(10.5, 8.5, 40.5),
                new Vec3(54.5, 8.5, 40.5)
        ));
    }

    @Test
    void nonCollidingWildBlocksRemainInThePropagationGraph() {
        AcousticScene scene = AcousticSceneFixtures
                .thirtyTwoCubeWithWildIrregularTunnel();
        assertFalse(scene.isAir(10, 8, 2));
        assertTrue(scene.isPropagationOpen(10, 8, 2));
        assertFalse(scene.isAir(19, 7, 18));
        assertTrue(scene.isPropagationOpen(19, 7, 18));
        assertEquals(
                scene.airComponentId(2, 8, 2),
                scene.airComponentId(29, 8, 28)
        );
        List<List<Long>> paths = scene.portalPaths(
                2, 8, 2,
                29, 8, 28,
                4
        );
        assertFalse(paths.isEmpty());
        assertTrue(paths.get(0).stream().anyMatch(cell ->
                BlockPos.getX(cell) == 10
                        && BlockPos.getZ(cell) >= 1
                        && BlockPos.getZ(cell) <= 3
        ));
    }

    private static AcousticScene scene(Map<AcousticScene.SectionKey, AcousticSection> sections) {
        return new AcousticScene(sections, 0, 32, 1L, new Object(), Map.of());
    }

    private static void setAir(long[] mask, int x, int y, int z) {
        int index = x | z << 4 | y << 8;
        mask[index >>> 6] |= 1L << (index & 63);
    }
}
