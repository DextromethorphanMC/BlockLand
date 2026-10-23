package com.blockland.client.effect;

import com.blockland.BlockLand;
import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.effect.ActionLocks;
import com.blockland.effect.BLEffects;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import static com.blockland.client.ui.Tr.tr;

/**
 * Client side of locked hands (粘液缠身): vanilla attack/use input is dropped (weapons check {@link ActionLocks}
 * themselves; the server rejects everything anyway), and the screen is covered in green slime that slowly drips and
 * clears as the effect runs out. Driven only by the effect vanilla syncs to the player.
 */
public final class ClientActionLock {
    private static final ResourceLocation OVERLAY = BlockLand.id("textures/misc/slime_overlay.png");
    private static final ResourceLocation DRIPS = BlockLand.id("textures/misc/slime_overlay_drips.png");
    private static final float FADE_IN = 0.15f;
    private static final float FADE_OUT = 0.8f;

    /** When the current slime started, to fade it in and to time the drips (0 = not slimed). */
    private static long slimedAt;
    private static int lastTicks;

    private ClientActionLock() {
    }

    public static void init(BusGroup modBus) {
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(event -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || event.isPickBlock() || !ActionLocks.isLocked(player)) return false;
            event.setSwingHand(false);
            return true;
        });
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addBelow(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("slime_overlay"), ForgeLayeredDraw.CROSSHAIR, ClientActionLock::render));
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        MobEffectInstance slime = player == null || !BLEffects.SLIMED.isPresent() ? null : player.getEffect(BLEffects.slimed());
        if (slime == null) {
            slimedAt = 0;
            return;
        }
        long now = System.nanoTime();
        // a new splat, or a fresh hit while still slimed
        if (slimedAt == 0 || slime.getDuration() > lastTicks) slimedAt = now;
        lastTicks = slime.getDuration();
        float age = (now - slimedAt) / 1e9f, left = slime.getDuration() / 20f;
        float alpha = Mth.clamp(age / FADE_IN, 0, 1) * Mth.clamp(left / FADE_OUT, 0, 1);
        if (alpha <= 0.01f) return;
        int w = g.guiWidth(), h = g.guiHeight();
        // the splat lands with a small squash, then the goo sags and drips down
        float settle = 1 - (float) Math.exp(-age * 6);
        int sag = Math.round(settle * h * 0.03f);
        int color = ColorMath.fade(0xFFFFFFFF, alpha);
        g.blit(RenderPipelines.GUI_TEXTURED, OVERLAY, 0, sag - Math.round(h * 0.03f), 0, 0, w, h + Math.round(h * 0.03f), 512, 256, 512, 256, color);
        int drip = Math.floorMod(Math.round(age * h * 0.06f), h);
        int dripColor = ColorMath.fade(0xFFFFFFFF, alpha * 0.85f);
        g.blit(RenderPipelines.GUI_TEXTURED, DRIPS, 0, drip - h, 0, 0, w, h, 256, 256, 256, 256, dripColor);
        g.blit(RenderPipelines.GUI_TEXTURED, DRIPS, 0, drip, 0, 0, w, h, 256, 256, 256, 256, dripColor);
        String text = tr("blockland.effect.slimed_overlay", String.format("%.1f", left));
        Draw.text(g, text, w / 2f - UiFont.width(text) / 2f, h / 2f + 30, 1f, ColorMath.fade(0xFFB6FF7A, alpha), true);
    }
}
