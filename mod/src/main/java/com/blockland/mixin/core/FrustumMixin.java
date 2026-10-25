package com.blockland.mixin.core;

import com.blockland.BlockLand;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's culling backs the camera out of the frustum until a cube around it fits — forever when the frustum is not a
 * proper one (a non-finite or degenerate camera or projection), which freezes the game. After an impossible number of
 * steps (a real frustum needs a handful) the loop stops and says what the frustum was, so the cause can be found.
 */
@Mixin(Frustum.class)
public abstract class FrustumMixin {
    @Unique
    private static final int BLOCKLAND$MAX_STEPS = 10_000;

    @Shadow
    @Final
    private Matrix4f matrix;
    @Shadow
    private Vector4f viewVector;
    @Shadow
    private double camX;
    @Shadow
    private double camY;
    @Shadow
    private double camZ;

    @Unique
    private int blockland$steps;

    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"))
    private void blockland$countSteps(int size, CallbackInfoReturnable<Frustum> cir) {
        blockland$steps = 0;
    }

    @Redirect(method = "offsetToFullyIncludeCameraCube",
            at = @At(value = "INVOKE", target = "Lorg/joml/FrustumIntersection;intersectAab(FFFFFF)I"))
    private int blockland$boundedSteps(FrustumIntersection intersection, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int result = intersection.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
        if (result != FrustumIntersection.INSIDE && ++blockland$steps == BLOCKLAND$MAX_STEPS) {
            BlockLand.LOGGER.error("[BlockLand] Culling frustum never contains the camera: matrix {}, view {}, camera {} {} {}",
                    matrix, viewVector, camX, camY, camZ, new Throwable("where"));
            return FrustumIntersection.INSIDE;
        }
        return result;
    }
}
