package org.macaroon.acousticsystem.client.scene;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Synthetic immutable scenes used by propagation performance tests. */
public final class AcousticSceneFixtures {
    private AcousticSceneFixtures() {
    }

    public static AcousticScene sixtyFourCubeWithOffsetWallOpening() {
        return sixtyFourCube(worldZ -> worldZ >= 30);
    }

    public static AcousticScene sixtyFourCubeWithTwinWallOpenings() {
        return sixtyFourCube(worldZ ->
                worldZ >= 10 && worldZ <= 13
                        || worldZ >= 50 && worldZ <= 53
        );
    }

    public static AcousticScene thirtyTwoCubeWithWildIrregularTunnel() {
        Map<AcousticScene.SectionKey, BlockState[]> blocks = new HashMap<>();
        for (int sectionZ = 0; sectionZ < 2; sectionZ++) {
            for (int sectionX = 0; sectionX < 2; sectionX++) {
                BlockState[] states = new BlockState[4096];
                Arrays.fill(states, Blocks.STONE.defaultBlockState());
                blocks.put(
                        new AcousticScene.SectionKey(sectionX, 0, sectionZ),
                        states
                );
            }
        }
        for (int x = 2; x <= 20; x++) {
            carveTunnelCrossSection(blocks, x, 8, 2, 0);
        }
        for (int z = 2; z <= 28; z++) {
            carveTunnelCrossSection(blocks, 20, 8, z, 1);
        }
        for (int x = 20; x <= 29; x++) {
            carveTunnelCrossSection(blocks, x, 8, 28, 0);
        }
        // Non-solid wild cells occupy the complete tunnel cross-section. Treating
        // only BlockState.isAir() as navigable severs this otherwise open route.
        for (int y = 7; y <= 9; y++) {
            for (int z = 1; z <= 3; z++) {
                setBlock(blocks, 10, y, z, Blocks.TALL_GRASS.defaultBlockState());
            }
        }
        setBlock(blocks, 19, 7, 18, Blocks.WATER.defaultBlockState());
        Map<AcousticScene.SectionKey, AcousticSection> sections = new HashMap<>();
        blocks.forEach((key, states) -> sections.put(key, new AcousticSection(states)));
        return new AcousticScene(
                sections, 0, 16, 1L, new Object(), Map.of()
        );
    }

    public static AcousticScene sixtyFourCubeWithWildPerfectMaze(long seed) {
        Map<AcousticScene.SectionKey, BlockState[]> blocks = new HashMap<>();
        for (int sectionZ = 0; sectionZ < 4; sectionZ++) {
            for (int sectionX = 0; sectionX < 4; sectionX++) {
                BlockState[] states = new BlockState[4096];
                Arrays.fill(states, Blocks.STONE.defaultBlockState());
                blocks.put(
                        new AcousticScene.SectionKey(sectionX, 0, sectionZ),
                        states
                );
            }
        }
        final int mazeSize = 31;
        boolean[] visited = new boolean[mazeSize * mazeSize];
        int[] stack = new int[mazeSize * mazeSize];
        int top = 0;
        stack[0] = 0;
        visited[0] = true;
        carveMazeCell(blocks, 0, 0);
        Random random = new Random(seed);
        int[] candidates = new int[4];
        while (top >= 0) {
            int cell = stack[top];
            int cellX = cell % mazeSize;
            int cellZ = cell / mazeSize;
            int count = 0;
            if (cellX > 0 && !visited[cell - 1]) candidates[count++] = cell - 1;
            if (cellX + 1 < mazeSize && !visited[cell + 1]) candidates[count++] = cell + 1;
            if (cellZ > 0 && !visited[cell - mazeSize]) {
                candidates[count++] = cell - mazeSize;
            }
            if (cellZ + 1 < mazeSize && !visited[cell + mazeSize]) {
                candidates[count++] = cell + mazeSize;
            }
            if (count == 0) {
                top--;
                continue;
            }
            int next = candidates[random.nextInt(count)];
            int nextX = next % mazeSize;
            int nextZ = next / mazeSize;
            carveMazeConnection(blocks, cellX, cellZ, nextX, nextZ);
            visited[next] = true;
            stack[++top] = next;
        }
        // Scatter collision-free vegetation across complete corridor cross-sections.
        // The route remains physically open but is not composed solely of air states.
        for (int logicalZ = 0; logicalZ < mazeSize; logicalZ++) {
            for (int logicalX = 0; logicalX < mazeSize; logicalX++) {
                if ((logicalX * 17 + logicalZ * 31 + seed) % 43 != 0) {
                    continue;
                }
                int worldX = 1 + logicalX * 2;
                int worldZ = 1 + logicalZ * 2;
                for (int y = 7; y <= 9; y++) {
                    setBlock(
                            blocks, worldX, y, worldZ,
                            Blocks.TALL_GRASS.defaultBlockState()
                    );
                }
            }
        }
        Map<AcousticScene.SectionKey, AcousticSection> sections = new HashMap<>();
        blocks.forEach((key, states) -> sections.put(key, new AcousticSection(states)));
        return new AcousticScene(
                sections, 0, 16, 1L, new Object(), Map.of()
        );
    }

    private static void carveMazeCell(
            Map<AcousticScene.SectionKey, BlockState[]> blocks,
            int logicalX,
            int logicalZ
    ) {
        int worldX = 1 + logicalX * 2;
        int worldZ = 1 + logicalZ * 2;
        for (int y = 7; y <= 9; y++) {
            setBlock(blocks, worldX, y, worldZ, Blocks.AIR.defaultBlockState());
        }
    }

    private static void carveMazeConnection(
            Map<AcousticScene.SectionKey, BlockState[]> blocks,
            int fromX,
            int fromZ,
            int toX,
            int toZ
    ) {
        carveMazeCell(blocks, toX, toZ);
        int worldX = 1 + fromX * 2 + (toX - fromX);
        int worldZ = 1 + fromZ * 2 + (toZ - fromZ);
        for (int y = 7; y <= 9; y++) {
            setBlock(blocks, worldX, y, worldZ, Blocks.AIR.defaultBlockState());
        }
    }

    private static void carveTunnelCrossSection(
            Map<AcousticScene.SectionKey, BlockState[]> blocks,
            int centerX,
            int centerY,
            int centerZ,
            int axis
    ) {
        for (int y = centerY - 1; y <= centerY + 1; y++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                setBlock(
                        blocks,
                        centerX + (axis == 1 ? lateral : 0),
                        y,
                        centerZ + (axis == 0 ? lateral : 0),
                        Blocks.AIR.defaultBlockState()
                );
            }
        }
    }

    private static void setBlock(
            Map<AcousticScene.SectionKey, BlockState[]> blocks,
            int x,
            int y,
            int z,
            BlockState state
    ) {
        AcousticScene.SectionKey key = AcousticScene.SectionKey.fromBlock(x, y, z);
        BlockState[] states = blocks.get(key);
        if (states == null) {
            return;
        }
        int local = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        states[local] = state;
    }

    private static AcousticScene sixtyFourCube(java.util.function.IntPredicate opening) {
        Map<AcousticScene.SectionKey, AcousticSection> sections = new HashMap<>();
        for (int sectionZ = 0; sectionZ < 4; sectionZ++) {
            for (int sectionX = 0; sectionX < 4; sectionX++) {
                long[] air = new long[64];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int worldX = sectionX * 16 + x;
                            int worldZ = sectionZ * 16 + z;
                            boolean wall = worldX == 32 && !opening.test(worldZ);
                            if (!wall) {
                                int index = x | z << 4 | y << 8;
                                air[index >>> 6] |= 1L << (index & 63);
                            }
                        }
                    }
                }
                sections.put(
                        new AcousticScene.SectionKey(sectionX, 0, sectionZ),
                        new AcousticSection(air)
                );
            }
        }
        return new AcousticScene(
                sections, 0, 16, 1L, new Object(), Map.of()
        );
    }
}
