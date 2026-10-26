package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.TerrainCompile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * A section compile reads the terrain switches once and keeps them for the whole section (rendering spec §20);
 * {@code ENCLOSED_BLOCKS} leaves out blocks whose every face is culled, {@code FACE_GROUPS} groups the finished meshes.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Inject(method = "compile", at = @At("HEAD"))
    private void blockland$begin(SectionPos section, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
                                 CallbackInfoReturnable<SectionCompiler.Results> cir) {
        TerrainCompile.begin(section, region);
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLjava/util/List;)V"))
    private void blockland$renderBatched(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack pose,
                                         VertexConsumer buffer, boolean cull, List<BlockModelPart> parts) {
        if (cull && TerrainCompile.enclosed(pos, parts)) return;
        dispatcher.renderBatched(state, pos, level, pose, buffer, cull, parts);
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void blockland$end(SectionPos section, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
                               CallbackInfoReturnable<SectionCompiler.Results> cir) {
        TerrainCompile.finish(cir.getReturnValue());
        TerrainCompile.end();
    }
}
