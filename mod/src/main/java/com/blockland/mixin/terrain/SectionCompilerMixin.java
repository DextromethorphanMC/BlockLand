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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * A section compile reads the terrain switches once and keeps them for the whole section (rendering spec §20);
 * {@code ENCLOSED_BLOCKS} leaves out blocks whose every face is culled, {@code ENCLOSED_MODELS} does so before their
 * model is read, {@code HIDDEN_FLUIDS} leaves out fluid blocks without a face to draw, {@code FACE_GROUPS} groups the
 * finished meshes; every compile notes which layers draw animated sprites ({@code client/render/TerrainAnimation}).
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Shadow
    @Final
    private BlockRenderDispatcher blockRenderer;

    @Inject(method = "compile", at = @At("HEAD"))
    private void blockland$begin(SectionPos section, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
                                 CallbackInfoReturnable<SectionCompiler.Results> cir) {
        TerrainCompile.begin(section, region);
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"))
    private Iterable<BlockPos> blockland$blocks(BlockPos from, BlockPos to) {
        return TerrainCompile.blocks(BlockPos.betweenClosed(from, to));
    }

    /** A fluid without a face to draw is passed over as no fluid: no render layer is begun for it, nothing tesselated. */
    @Redirect(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z"))
    private boolean blockland$noFluid(FluidState fluid, SectionPos section, RenderSectionRegion region, VertexSorting sorting,
                                      SectionBufferBuilderPack buffers) {
        return fluid.isEmpty() || TerrainCompile.hiddenFluid(region, fluid);
    }

    /** An enclosed model block that would draw nothing is passed over as an invisible one. */
    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape blockland$renderShape(BlockState state) {
        RenderShape shape = state.getRenderShape();
        return shape == RenderShape.MODEL && TerrainCompile.enclosedModel(state, blockRenderer) ? RenderShape.INVISIBLE : shape;
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLjava/util/List;)V"))
    private void blockland$renderBatched(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack pose,
                                         VertexConsumer buffer, boolean cull, List<BlockModelPart> parts) {
        if (cull && TerrainCompile.enclosed(pos, parts)) return;
        TerrainCompile.animatedBlock(buffer, state, parts, dispatcher);
        dispatcher.renderBatched(state, pos, level, pose, buffer, cull, parts);
    }

    /** The compile's builder of a layer, so the blocks drawn into it can be told apart by layer. */
    @Inject(method = "getOrBeginLayer", at = @At("RETURN"))
    private void blockland$layer(java.util.Map<net.minecraft.client.renderer.chunk.ChunkSectionLayer, com.mojang.blaze3d.vertex.BufferBuilder> layers,
                                 SectionBufferBuilderPack buffers, net.minecraft.client.renderer.chunk.ChunkSectionLayer layer,
                                 CallbackInfoReturnable<com.mojang.blaze3d.vertex.BufferBuilder> cir) {
        TerrainCompile.layer(layer, cir.getReturnValue());
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void blockland$end(SectionPos section, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
                               CallbackInfoReturnable<SectionCompiler.Results> cir) {
        TerrainCompile.finish(cir.getReturnValue());
        TerrainCompile.end();
    }
}
