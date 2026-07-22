package org.macaroon.acousticsystem.client.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.Map;

/** Immutable world view safe to read from acoustic worker and sound threads. */
public final class AcousticScene implements BlockGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final FluidState EMPTY_FLUID = Fluids.EMPTY.defaultFluidState();

    private final long[] sectionKeys;
    private final AcousticSection[] sections;
    private final int sectionMask;
    private final int minY;
    private final int height;
    private final long revision;

    AcousticScene(Map<SectionKey, AcousticSection> sections, int minY, int height, long revision) {
        int tableSize = 1;
        while (tableSize < Math.max(2, sections.size() * 2)) {
            tableSize <<= 1;
        }
        this.sectionKeys = new long[tableSize];
        this.sections = new AcousticSection[tableSize];
        this.sectionMask = tableSize - 1;
        for (Map.Entry<SectionKey, AcousticSection> entry : sections.entrySet()) {
            SectionKey key = entry.getKey();
            long packed = packSection(key.x(), key.y(), key.z());
            int slot = tableSlot(packed);
            while (this.sections[slot] != null) {
                slot = (slot + 1) & sectionMask;
            }
            sectionKeys[slot] = packed;
            this.sections[slot] = entry.getValue();
        }
        this.minY = minY;
        this.height = height;
        this.revision = revision;
    }

    public long revision() {
        return revision;
    }

    public boolean isCollisionShapeFullBlock(BlockPos pos, BlockState state) {
        AcousticSection section = section(pos.getX(), pos.getY(), pos.getZ());
        return section != null && section.isCollisionShapeFullBlock(this, pos, state);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        AcousticSection section = section(pos.getX(), pos.getY(), pos.getZ());
        return section == null ? AIR : section.block(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        AcousticSection section = section(pos.getX(), pos.getY(), pos.getZ());
        return section == null ? EMPTY_FLUID : section.fluid(pos.getX(), pos.getY(), pos.getZ());
    }

    public int getMinY() {
        return minY;
    }

    public int getMinBuildHeight() {
        return minY;
    }

    @Override
    public int getHeight() {
        return height;
    }

    private AcousticSection section(int blockX, int blockY, int blockZ) {
        long packed = packSection(blockX >> 4, blockY >> 4, blockZ >> 4);
        int slot = tableSlot(packed);
        while (sections[slot] != null) {
            if (sectionKeys[slot] == packed) {
                return sections[slot];
            }
            slot = (slot + 1) & sectionMask;
        }
        return null;
    }

    private int tableSlot(long packed) {
        long mixed = packed;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        return (int) mixed & sectionMask;
    }

    private static long packSection(int x, int y, int z) {
        return ((long) x & 0x3FFFFFL) << 42
                | ((long) z & 0x3FFFFFL) << 20
                | ((long) y & 0xFFFFFL);
    }

    record SectionKey(int x, int y, int z) {
        static SectionKey fromBlock(int x, int y, int z) {
            return new SectionKey(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
        }
    }
}
