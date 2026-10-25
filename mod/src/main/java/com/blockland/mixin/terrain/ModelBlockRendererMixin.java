package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.QuadWriter;
import com.blockland.client.perf.terrain.TerrainCompile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Section meshing ({@code SECTION_MESHING}): block quads written straight into the section's buffer
 * ({@link QuadWriter}) and, for multipart models, each face's culling decided once per block instead of once per part
 * ({@link TerrainCompile#face}). Both only while a compile that meshes the new way runs on the thread.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    @Shadow
    private static boolean shouldRenderFace(BlockAndTintGetter level, BlockState state, boolean cull, Direction direction, BlockPos neighbour) {
        throw new AssertionError();
    }

    @Redirect(method = "putQuadData", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFFF[IIZ)V"))
    private void blockland$putBulkData(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green,
                                       float blue, float alpha, int[] lights, int overlay, boolean readExistingColor) {
        if (!QuadWriter.put(consumer, pose, quad, brightness, red, green, blue, alpha, lights, overlay, readExistingColor)) {
            consumer.putBulkData(pose, quad, brightness, red, green, blue, alpha, lights, overlay, readExistingColor);
        }
    }

    @Redirect(method = "tesselateWithAO", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;shouldRenderFace(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;ZLnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean blockland$faceAO(BlockAndTintGetter level, BlockState state, boolean cull, Direction direction, BlockPos neighbour,
                                     BlockAndTintGetter blockLevel, List<BlockModelPart> parts, BlockState blockState, BlockPos pos) {
        return blockland$face(level, state, cull, direction, neighbour, parts, pos);
    }

    @Redirect(method = "tesselateWithoutAO", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;shouldRenderFace(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;ZLnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean blockland$faceFlat(BlockAndTintGetter level, BlockState state, boolean cull, Direction direction, BlockPos neighbour,
                                       BlockAndTintGetter blockLevel, List<BlockModelPart> parts, BlockState blockState, BlockPos pos) {
        return blockland$face(level, state, cull, direction, neighbour, parts, pos);
    }

    /** Single-part models (almost every block) ask each face once anyway: vanilla's call, no lookup. */
    @Unique
    private static boolean blockland$face(BlockAndTintGetter level, BlockState state, boolean cull, Direction direction, BlockPos neighbour,
                                          List<BlockModelPart> parts, BlockPos pos) {
        TerrainCompile c;
        if (parts.size() < 2 || (c = TerrainCompile.meshing()) == null) return shouldRenderFace(level, state, cull, direction, neighbour);
        int known = c.face(level, state, pos, cull, direction, neighbour);
        if (known >= 0) return known != 0;
        boolean render = shouldRenderFace(level, state, cull, direction, neighbour);
        c.faceDecided(direction, neighbour, render);
        return render;
    }
}
