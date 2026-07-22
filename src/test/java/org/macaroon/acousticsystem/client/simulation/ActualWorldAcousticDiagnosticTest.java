package org.macaroon.acousticsystem.client.simulation;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.DataInputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a copied Anvil region from the currently played development world. This is
 * deliberately opt-in: the normal test suite must remain independent of a local save.
 */
@EnabledIfEnvironmentVariable(named = "ACOUSTICSYSTEM_ACTUAL_WORLD", matches = ".+")
class ActualWorldAcousticDiagnosticTest {
    private static final Path SNAPSHOT = Path.of("build", "actual-world-snapshot");
    private static final Vec3 PLAYER_FEET = new Vec3(
            -172.55970654588705,
            69.0,
            -355.9184708426083
    );
    private static final Vec3 LISTENER = PLAYER_FEET.add(0.0, 1.62, 0.0);
    private static final int HORIZONTAL_RADIUS = 72;
    private static final int MIN_Y = 40;
    private static final int MAX_Y = 112;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void traceEveryNearbyWorldSoundBlockFromTheExactListenerPosition() throws Exception {
        CompoundTag level = NbtIo.readCompressed(
                SNAPSHOT.resolve("level.dat"),
                NbtAccounter.unlimitedHeap()
        );
        System.out.println("ACTUAL levelData=" + level);
        Path legacyPlayer = SNAPSHOT.resolve("player.dat");
        if (Files.exists(legacyPlayer)) {
            CompoundTag player = NbtIo.readCompressed(
                    legacyPlayer,
                    NbtAccounter.unlimitedHeap()
            );
            ListTag savedPosition = player.getListOrEmpty("Pos");
            System.out.println("ACTUAL savedPlayerPosition=("
                    + savedPosition.getDoubleOr(0, Double.NaN) + ", "
                    + savedPosition.getDoubleOr(1, Double.NaN) + ", "
                    + savedPosition.getDoubleOr(2, Double.NaN) + ")");
            ListTag savedRotation = player.getListOrEmpty("Rotation");
            System.out.println("ACTUAL savedPlayerRotation=("
                    + savedRotation.getFloatOr(0, Float.NaN) + ", "
                    + savedRotation.getFloatOr(1, Float.NaN) + ")");
        }
        ActualWorld world = loadSnapshot();
        List<BlockPos> sources = world.soundBlocks();
        System.out.println("ACTUAL listener=" + LISTENER + " blocks=" + world.states().size());
        RoomProbe exactListenerRoom = AcousticTracer.probeRoom(world, LISTENER);
        System.out.println("ACTUAL listenerRoom=" + exactListenerRoom.acoustics()
                + " openings=" + exactListenerRoom.openings().size()
                + " surfaces=" + exactListenerRoom.surfaces().size());
        System.out.println("ACTUAL soundBlocks=" + sources);
        for (int y = 67; y <= 72; y++) {
            printSlice(world, y, -182, -148, -366, -332);
        }

        for (BlockPos position : sources) {
            Vec3 source = Vec3.atCenterOf(position);
            RoomProbe sourceRoom = AcousticTracer.probeSourceRoom(world, source);
            RoomProbe listenerRoom = AcousticTracer.probeRoom(world, LISTENER);
            System.out.println("ACTUAL roomPair source=" + position
                    + " sourceGain=" + sourceRoom.acoustics().gain()
                    + " sourceOpenings=" + sourceRoom.openings().size()
                    + " listenerGain=" + listenerRoom.acoustics().gain()
                    + " listenerOpenings=" + listenerRoom.openings().size());
            long started = System.nanoTime();
            AcousticResult result = AcousticTracer.trace(
                    world,
                    source,
                    LISTENER,
                    sourceRoom,
                    listenerRoom,
                    AcousticTracer.TraceQuality.FULL
            );
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            System.out.println("ACTUAL source=" + position
                    + " distance=" + source.distanceTo(LISTENER)
                    + " elapsedMs=" + elapsedMs
                    + " result=" + result);
        }
        BlockPos playing = new BlockPos(-173, 69, -358);
        Vec3 playingSource = Vec3.atCenterOf(playing);
        RoomProbe playingRoom = AcousticTracer.probeSourceRoom(world, playingSource);
        for (double z = -343.95; z >= -360.0; z -= 0.5) {
            Vec3 movingListener = new Vec3(LISTENER.x, LISTENER.y, z);
            RoomProbe movingRoom = AcousticTracer.probeRoom(world, movingListener);
            long started = System.nanoTime();
            AcousticResult result = AcousticTracer.trace(
                    world,
                    playingSource,
                    movingListener,
                    playingRoom,
                    movingRoom,
                    AcousticTracer.TraceQuality.FULL
            );
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            Vec3 apparentDirection = result.apparentPosition().subtract(movingListener).normalize();
            System.out.printf(
                    "ACTUAL motion z=%.2f ms=%.3f direct=%.5f diffraction=%.5f path=%.3f dir=(%.3f,%.3f,%.3f)%n",
                    z,
                    elapsedMs,
                    result.directGain(),
                    result.diffractionContribution(),
                    result.propagationDistance(),
                    apparentDirection.x,
                    apparentDirection.y,
                    apparentDirection.z
            );
        }
        for (double x = LISTENER.x - 2.0; x <= LISTENER.x + 2.0; x += 0.25) {
            Vec3 movingListener = new Vec3(x, LISTENER.y, LISTENER.z);
            RoomProbe movingRoom = AcousticTracer.probeRoom(world, movingListener);
            AcousticResult result = AcousticTracer.trace(
                    world,
                    playingSource,
                    movingListener,
                    playingRoom,
                    movingRoom,
                    AcousticTracer.TraceQuality.FULL
            );
            AcousticTracer.ReflectionResult sourceFieldReflection = AcousticTracer.estimateEarlyReflections(
                    world,
                    playingSource,
                    movingListener,
                    playingRoom,
                    4
            );
            System.out.printf(
                    "ACTUAL xmotion x=%.3f direct=%.5f diffraction=%.5f reverb=%.5f early=%.5f sourceEarly=%.5f path=%.3f%n",
                    x,
                    result.directGain(),
                    result.diffractionContribution(),
                    result.reverbSend(),
                    result.earlyReflection().gain(),
                    sourceFieldReflection.gain(),
                    result.propagationDistance()
            );
        }
        for (double z = LISTENER.z - 0.6; z <= LISTENER.z + 0.6; z += 0.05) {
            Vec3 movingListener = new Vec3(LISTENER.x, LISTENER.y, z);
            AcousticResult result = AcousticTracer.trace(
                    world,
                    playingSource,
                    movingListener,
                    playingRoom,
                    AcousticTracer.probeRoom(world, movingListener),
                    AcousticTracer.TraceQuality.FULL
            );
            AcousticResult immediate = AcousticTracer.traceImmediate(
                    world,
                    playingSource,
                    movingListener,
                    RoomAcoustics.OUTDOORS
            );
            AcousticTracer.StructuralPath structural = AcousticTracer.traceStructuralPath(
                    world,
                    playingSource,
                    movingListener,
                    org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry.tuning()
            );
            float structuralLow = structural == null ? 0.0F
                    : structural.bands()[0] * 0.24F + structural.bands()[1] * 0.26F
                    + structural.bands()[2] * 0.27F + structural.bands()[3] * 0.23F;
            Vec3 direction = result.apparentPosition().subtract(movingListener).normalize();
            System.out.printf(
                    "ACTUAL fine z=%.3f block=%s structural=%.5f structuralD=%.2f direct=%.5f immediate=%.5f diffraction=%.5f reverb=%.5f dir=(%.3f,%.3f,%.3f)%n",
                    z,
                    world.getBlockState(BlockPos.containing(movingListener)),
                    structuralLow,
                    structural == null ? 0.0 : structural.pathDistance(),
                    result.directGain(),
                    immediate.directGain(),
                    result.diffractionContribution(),
                    result.reverbSend(),
                    direction.x,
                    direction.y,
                    direction.z
            );
        }
        for (double z = LISTENER.z - 4.0; z <= LISTENER.z + 4.0001; z += 0.5) {
            StringBuilder row = new StringBuilder();
            row.append(String.format("ACTUAL field z=%8.3f", z));
            for (double x = LISTENER.x - 4.0; x <= LISTENER.x + 4.0001; x += 0.5) {
                Vec3 point = new Vec3(x, LISTENER.y, z);
                if (!world.getBlockState(BlockPos.containing(point)).isAir()) {
                    row.append("   ####");
                    continue;
                }
                AcousticResult result = AcousticTracer.trace(
                        world,
                        playingSource,
                        point,
                        playingRoom,
                        AcousticTracer.probeRoom(world, point),
                        AcousticTracer.TraceQuality.FULL
                );
                row.append(String.format(" %6.3f", result.directGain()));
            }
            System.out.println(row);
        }
    }

    private static void printSlice(ActualWorld world, int y, int minX, int maxX, int minZ, int maxZ) {
        System.out.println("ACTUAL slice y=" + y + " x=" + minX + ".." + maxX
                + " z=" + minZ + ".." + maxZ);
        int listenerX = (int) Math.floor(LISTENER.x);
        int listenerZ = (int) Math.floor(LISTENER.z);
        for (int z = minZ; z <= maxZ; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                BlockPos position = new BlockPos(x, y, z);
                if (x == listenerX && z == listenerZ) {
                    row.append('L');
                } else if (world.soundBlocks().contains(position)) {
                    row.append('S');
                } else {
                    BlockState state = world.getBlockState(position);
                    row.append(state.isAir() ? '.' : state.is(Blocks.WATER) ? '~' : '#');
                }
            }
            System.out.println("ACTUAL " + z + " " + row);
        }
    }

    private static ActualWorld loadSnapshot() throws Exception {
        Map<Long, BlockState> states = new HashMap<>();
        List<BlockPos> soundBlocks = new ArrayList<>();
        Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        Codec<PalettedContainer<BlockState>> codec = PalettedContainer.codecRW(
                BlockState.CODEC,
                strategy,
                Blocks.AIR.defaultBlockState()
        );
        int minX = (int) Math.floor(LISTENER.x) - HORIZONTAL_RADIUS;
        int maxX = (int) Math.floor(LISTENER.x) + HORIZONTAL_RADIUS;
        int minZ = (int) Math.floor(LISTENER.z) - HORIZONTAL_RADIUS;
        int maxZ = (int) Math.floor(LISTENER.z) + HORIZONTAL_RADIUS;
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);

        RegionStorageInfo info = new RegionStorageInfo("actual-world", Level.OVERWORLD, "chunk");
        try (RegionFile region = new RegionFile(
                info,
                SNAPSHOT.resolve("r.-1.-1.mca"),
                SNAPSHOT,
                false
        )) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                    try (DataInputStream input = region.getChunkDataInputStream(chunkPos)) {
                        if (input == null) {
                            continue;
                        }
                        CompoundTag chunk = NbtIo.read(input);
                        ListTag sections = chunk.getListOrEmpty("sections");
                        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
                            CompoundTag section = sections.getCompoundOrEmpty(sectionIndex);
                            int sectionY = section.getByteOr("Y", (byte) 0);
                            int baseY = sectionY << 4;
                            if (baseY > MAX_Y || baseY + 15 < MIN_Y) {
                                continue;
                            }
                            CompoundTag blockStates = section.getCompoundOrEmpty("block_states");
                            if (blockStates.isEmpty()) {
                                continue;
                            }
                            PalettedContainer<BlockState> palette = codec.parse(NbtOps.INSTANCE, blockStates).getOrThrow();
                            for (int localY = 0; localY < 16; localY++) {
                                int y = baseY + localY;
                                if (y < MIN_Y || y > MAX_Y) {
                                    continue;
                                }
                                for (int localZ = 0; localZ < 16; localZ++) {
                                    int z = (chunkZ << 4) + localZ;
                                    if (z < minZ || z > maxZ) {
                                        continue;
                                    }
                                    for (int localX = 0; localX < 16; localX++) {
                                        int x = (chunkX << 4) + localX;
                                        if (x < minX || x > maxX) {
                                            continue;
                                        }
                                        BlockState state = palette.get(localX, localY, localZ);
                                        if (state.isAir()) {
                                            continue;
                                        }
                                        BlockPos position = new BlockPos(x, y, z);
                                        states.put(position.asLong(), state);
                                        if (state.is(Blocks.JUKEBOX) || state.is(Blocks.NOTE_BLOCK)) {
                                            soundBlocks.add(position.immutable());
                                        }
                                    }
                                }
                            }
                        }
                        ListTag blockEntities = chunk.getListOrEmpty("block_entities");
                        for (int blockEntityIndex = 0; blockEntityIndex < blockEntities.size(); blockEntityIndex++) {
                            CompoundTag blockEntity = blockEntities.getCompoundOrEmpty(blockEntityIndex);
                            String serialized = blockEntity.toString();
                            if (serialized.contains("jukebox") || serialized.contains("note_block")) {
                                System.out.println("ACTUAL blockEntity=" + serialized);
                            }
                        }
                    }
                }
            }
        }
        return new ActualWorld(states, List.copyOf(soundBlocks));
    }

    private record ActualWorld(
            Map<Long, BlockState> states,
            List<BlockPos> soundBlocks
    ) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return states.getOrDefault(position.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            BlockState state = getBlockState(position);
            FluidState fluid = state.getFluidState();
            return fluid.isEmpty() ? Fluids.EMPTY.defaultFluidState() : fluid;
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
