package com.blockland.mixin.hud;

import com.blockland.client.ui.ToastArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Toasts start where the open screen says ({@link ToastArea}): below the lobby's top bar instead of over it. */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
    @Unique
    private boolean blockland$moved;

    @Inject(method = "render", at = @At("HEAD"))
    private void blockland$moveDown(GuiGraphics g, CallbackInfo ci) {
        int offset = ToastArea.offset(Minecraft.getInstance().screen);
        blockland$moved = offset > 0;
        if (blockland$moved) {
            g.pose().pushMatrix();
            g.pose().translate(0, offset);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void blockland$restore(GuiGraphics g, CallbackInfo ci) {
        if (blockland$moved) g.pose().popMatrix();
        blockland$moved = false;
    }
}
