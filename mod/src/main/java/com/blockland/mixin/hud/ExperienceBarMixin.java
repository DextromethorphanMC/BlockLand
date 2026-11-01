package com.blockland.mixin.hud;

import com.blockland.client.hud.vanilla.VanillaHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.ExperienceBarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's experience bar stays off while BlockLand's hotbar draws it (UI spec §3.2, {@link VanillaHud#replaces}). */
@Mixin(ExperienceBarRenderer.class)
public abstract class ExperienceBarMixin {
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void blockland$bar(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        if (VanillaHud.replaces()) ci.cancel();
    }
}
