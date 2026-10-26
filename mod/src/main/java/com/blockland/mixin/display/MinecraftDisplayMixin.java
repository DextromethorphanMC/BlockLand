package com.blockland.mixin.display;

import com.blockland.client.graphics.display.DisplayScaling;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A display settings change takes effect at the start of a frame (rendering spec §21), when nothing of the frame is drawn
 * yet and the main target is Minecraft's own: the window's virtual framebuffer is resized there.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftDisplayMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void blockland$display(boolean renderLevel, CallbackInfo ci) {
        DisplayScaling.beginFrame();
    }
}
