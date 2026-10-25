package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.SectionCopies;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/** A section snapshot copies the block entities of its own section only ({@code SECTION_COPIES}). */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCopy")
public abstract class SectionCopyMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableMap;copyOf(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap;"))
    private ImmutableMap<BlockPos, BlockEntity> blockland$blockEntities(Map<BlockPos, BlockEntity> all, LevelChunk chunk, int sectionIndex) {
        return SectionCopies.blockEntities(all, chunk, sectionIndex);
    }
}
