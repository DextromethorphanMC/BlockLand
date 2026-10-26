package com.blockland.mixin.display;

import com.blockland.client.graphics.display.DisplayScaling;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * GUI mouse positions through the picture's rectangle (rendering spec §21): with black bars the cursor is measured from
 * the picture's edge and scaled by its size, so a click in a bar lands outside the GUI; drags scale the same. A picture
 * that fills the window (stretched, or the native size) maps like vanilla. Turning the camera never comes here.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    private static final String SCALED_X = "Lnet/minecraft/client/MouseHandler;getScaledXPos(Lcom/mojang/blaze3d/platform/Window;D)D";
    private static final String SCALED_Y = "Lnet/minecraft/client/MouseHandler;getScaledYPos(Lcom/mojang/blaze3d/platform/Window;D)D";

    @ModifyArg(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At(value = "INVOKE", target = SCALED_X), index = 1)
    private double blockland$pictureX(Window window, double x) {
        return DisplayScaling.mouseX(window, x);
    }

    @ModifyArg(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At(value = "INVOKE", target = SCALED_Y), index = 1)
    private double blockland$pictureY(Window window, double y) {
        return DisplayScaling.mouseY(window, y);
    }

    @Redirect(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;D)D",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getScreenWidth()I"))
    private static int blockland$pictureWidth(Window window) {
        return DisplayScaling.mouseWidth(window);
    }

    @Redirect(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;D)D",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getScreenHeight()I"))
    private static int blockland$pictureHeight(Window window) {
        return DisplayScaling.mouseHeight(window);
    }
}
