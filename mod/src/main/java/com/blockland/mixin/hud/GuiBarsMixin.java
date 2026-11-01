package com.blockland.mixin.hud;

import com.blockland.client.hud.vanilla.VanillaHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's hotbar, health / armour / food / air, selected item name and experience level stay off while BlockLand
 * draws its own (UI spec §3.2, {@link VanillaHud#replaces}); the spectator bar, a ridden animal's health and the
 * locator and jump bars stay vanilla's.
 */
@Mixin(Gui.class)
public abstract class GuiBarsMixin {
    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void blockland$hotbar(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        if (VanillaHud.replaces()) ci.cancel();
    }

    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void blockland$health(GuiGraphics g, CallbackInfo ci) {
        if (VanillaHud.replaces()) ci.cancel();
    }

    @Inject(method = "renderSelectedItemName(Lnet/minecraft/client/gui/GuiGraphics;I)V", at = @At("HEAD"), cancellable = true)
    private void blockland$itemName(GuiGraphics g, int yShift, CallbackInfo ci) {
        if (VanillaHud.replaces()) ci.cancel();
    }

    @Redirect(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;renderExperienceLevel(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;I)V"))
    private void blockland$experienceLevel(GuiGraphics g, Font font, int level) {
        if (!VanillaHud.replaces()) ContextualBarRenderer.renderExperienceLevel(g, font, level);
    }
}
