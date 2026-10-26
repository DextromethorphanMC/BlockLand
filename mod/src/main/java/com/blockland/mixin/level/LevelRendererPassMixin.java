package com.blockland.mixin.level;

import com.blockland.client.graphics.frame.LevelPasses;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The level's passes before vanilla's terrain (rendering spec §22, {@link LevelPasses}): every level render hands over
 * its camera and matrices, and its frame graph gets BlockLand's pass between the sky and vanilla's main pass.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererPassMixin {
    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void blockland$beginLevel(GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline, Camera camera,
                                      Matrix4f viewRotation, Matrix4f projection, GpuBufferSlice fog, Vector4f fogColor,
                                      boolean sky, CallbackInfo ci) {
        LevelPasses.beginLevel(camera, viewRotation, projection, fog);
    }

    @ModifyArg(method = "renderLevel", index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"
                    + "Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;Lorg/joml/Matrix4f;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;ZZLnet/minecraft/client/DeltaTracker;"
                    + "Lnet/minecraft/util/profiling/ProfilerFiller;)V"))
    private FrameGraphBuilder blockland$beforeMain(FrameGraphBuilder builder) {
        LevelPasses.addBeforeMain(builder, targets);
        return builder;
    }
}
