package com.blockland.mixin.level;

import com.blockland.client.graphics.sky.CelestialBodies;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The sun and the moon drawn by BlockLand's shader in place of vanilla's (rendering spec §14): only forwards. */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"))
    private void blockland$begin(PoseStack pose, MultiBufferSource.BufferSource buffers, float sunAngle, int moonPhase, float alpha,
                                 float stars, CallbackInfo ci) {
        CelestialBodies.begin(pose.last().pose(), moonPhase);
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("RETURN"))
    private void blockland$end(PoseStack pose, MultiBufferSource.BufferSource buffers, float sunAngle, int moonPhase, float alpha,
                               float stars, CallbackInfo ci) {
        CelestialBodies.end();
    }

    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
    private void blockland$sun(float alpha, MultiBufferSource buffers, PoseStack pose, CallbackInfo ci) {
        if (CelestialBodies.sun(alpha, buffers)) ci.cancel();
    }

    @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
    private void blockland$moon(int phase, float alpha, MultiBufferSource buffers, PoseStack pose, CallbackInfo ci) {
        if (CelestialBodies.moon(alpha, buffers)) ci.cancel();
    }
}
