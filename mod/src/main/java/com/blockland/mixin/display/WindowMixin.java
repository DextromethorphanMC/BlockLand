package com.blockland.mixin.display;

import com.blockland.client.graphics.display.DisplayScaling;
import com.mojang.blaze3d.platform.Window;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The virtual framebuffer (rendering spec §21): GLFW's framebuffer size is kept as the window's real size, and the window
 * stores the virtual size in its place (Minecraft's {@code getWidth/getHeight}), so vanilla's resize check sees the
 * virtual size change — or not. Native ratio and resolution store exactly what GLFW reported.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow
    @Final
    private long window;
    @Shadow
    private int framebufferWidth;
    @Shadow
    private int framebufferHeight;

    @Inject(method = "onFramebufferResize", at = @At("HEAD"))
    private void blockland$realSize(long handle, int width, int height, CallbackInfo ci) {
        if (handle == window) DisplayScaling.framebufferResized(width, height);
    }

    @Redirect(method = "onFramebufferResize", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;framebufferWidth:I",
            opcode = Opcodes.PUTFIELD))
    private void blockland$storeWidth(Window self, int width) {
        framebufferWidth = DisplayScaling.storedWidth(width);
    }

    @Redirect(method = "onFramebufferResize", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;framebufferHeight:I",
            opcode = Opcodes.PUTFIELD))
    private void blockland$storeHeight(Window self, int height) {
        framebufferHeight = DisplayScaling.storedHeight(height);
    }

    @Inject(method = "refreshFramebufferSize", at = @At("TAIL"))
    private void blockland$refreshed(CallbackInfo ci) {
        DisplayScaling.framebufferRefreshed((Window) (Object) this);
    }
}
