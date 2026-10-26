package com.blockland.mixin.display;

import com.blockland.client.graphics.display.DisplayScaling;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Presenting the finished frame (rendering spec §21): a picture that is not the window's size is blitted into its
 * rectangle — the whole window, or centred with black bars cleared around it — with linear filtering. The window's size
 * keeps vanilla's 1:1 blit untouched.
 */
@Mixin(GlCommandEncoder.class)
public abstract class GlCommandEncoderPresentMixin {
    private static final String BLIT = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V";

    /** After vanilla disabled the scissor test and opened the colour mask, before it attaches the texture and blits. */
    @Inject(method = "presentTexture", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;bindFrameBufferTextures(IIIII)V"))
    private void blockland$destination(GpuTextureView texture, CallbackInfo ci) {
        DisplayScaling.beforePresentBlit(texture.getWidth(0), texture.getHeight(0));
    }

    @ModifyArg(method = "presentTexture", at = @At(value = "INVOKE", target = BLIT), index = 6)
    private int blockland$x0(int x) {
        return DisplayScaling.presentX0(x);
    }

    @ModifyArg(method = "presentTexture", at = @At(value = "INVOKE", target = BLIT), index = 7)
    private int blockland$y0(int y) {
        return DisplayScaling.presentY0(y);
    }

    @ModifyArg(method = "presentTexture", at = @At(value = "INVOKE", target = BLIT), index = 8)
    private int blockland$x1(int x) {
        return DisplayScaling.presentX1(x);
    }

    @ModifyArg(method = "presentTexture", at = @At(value = "INVOKE", target = BLIT), index = 9)
    private int blockland$y1(int y) {
        return DisplayScaling.presentY1(y);
    }

    @ModifyArg(method = "presentTexture", at = @At(value = "INVOKE", target = BLIT), index = 11)
    private int blockland$filter(int filter) {
        return DisplayScaling.presentFilter(filter);
    }
}
