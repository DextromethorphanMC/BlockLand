package com.blockland.client.perf.terrain;

import com.blockland.client.perf.Optimization;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;

/**
 * The block entities a section snapshot ({@code SectionCopy}, 27 per rebuilt section, made on the render thread when
 * the rebuild is scheduled) keeps: vanilla copies the whole chunk column's map into each one; only those inside the
 * snapshot's section are kept here, from the same map at the same moment.
 * <p>
 * Exactness. The map is read only by {@code SectionCopy.getBlockEntity(pos)}, which {@code RenderSectionRegion} calls
 * on the snapshot of {@code pos}'s own section ({@code index} of its section coordinates). A position outside the
 * region's 3×3×3 sections either fails the array index in both paths or lands on a snapshot of another chunk column
 * (equal column offsets force equal section offsets in {@code index}), whose map holds nothing at {@code pos} in either
 * path: chunk maps are keyed by positions inside their column. So every lookup returns the same object.
 */
public final class SectionCopies {
    private SectionCopies() {
    }

    /** The map for the snapshot of {@code chunk}'s section {@code sectionIndex} (vanilla: all of {@code all}). */
    public static ImmutableMap<BlockPos, BlockEntity> blockEntities(Map<BlockPos, BlockEntity> all, LevelChunk chunk, int sectionIndex) {
        if (!TerrainSwitches.on(Optimization.SECTION_COPIES)) return ImmutableMap.copyOf(all);
        if (all.isEmpty()) return ImmutableMap.of();
        int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
        ImmutableMap.Builder<BlockPos, BlockEntity> kept = null;
        for (Map.Entry<BlockPos, BlockEntity> e : all.entrySet()) {
            if (SectionPos.blockToSectionCoord(e.getKey().getY()) != sectionY) continue;
            if (kept == null) kept = ImmutableMap.builder();
            kept.put(e.getKey(), e.getValue());
        }
        return kept == null ? ImmutableMap.of() : kept.buildOrThrow();
    }
}
