package com.blockland.client.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Client side of what equipment does when it goes off (explosion flash and debris, smoke volume, flames), the flash
 * blindness and the adrenaline rush. The thrown entity calls in here from its client tick and entity events; this
 * side knows nothing about the equipment module. Contract: shared notes, round-i-contracts.md.
 */
public final class ClientEquipmentEffects {
    private ClientEquipmentEffects() {
    }

    public static void init(BusGroup modBus) {
        FxWorld.init();
        BlastShake.init();
        FlashBlind.init();
        AdrenalineRush.init();
        BLNetwork.setEffectClientListener(FlashBlind::onFlash);
        // first in the HUD: the screen passes run on the finished frame before any HUD element is drawn
        // with the HUD hidden (F1) the vanilla stack is skipped: the same passes then run from the root, so hiding the
        // HUD never gets anyone out of a flash
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addBelow(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("equipment_screen"), ForgeLayeredDraw.CAMERA_OVERLAY,
                        (g, delta) -> renderScreen(g))
                .addBelow(BlockLand.id("equipment_screen_hidden_hud"), ForgeLayeredDraw.PRE_SLEEP_STACK, (g, delta) -> {
                    if (Minecraft.getInstance().options.hideGui) renderScreen(g);
                }));
    }

    /** The thrown entity went off (entity event {@code 60}), at its position. */
    public static void detonated(Entity thrown, String kind, JsonObject params) {
        if (!(thrown.level() instanceof ClientLevel level)) return;
        Vec3 at = thrown.position();
        switch (kind) {
            case "frag" -> Blast.frag(level, at);
            case "flash" -> Blast.flash(level, at);
            case "molotov" -> shatter(level, at);
            default -> {
            }
        }
    }

    /** Every client tick while the thrown entity is an active area (smoke, fire); {@code activeTicks} counts up from 0. */
    public static void tickActive(Entity thrown, String kind, JsonObject params, int activeTicks) {
        switch (kind) {
            case "smoke" -> {
                SmokeCloud cloud = FxWorld.find(thrown.getId(), SmokeCloud.class);
                if (cloud == null) {
                    cloud = new SmokeCloud(thrown.getId(), thrown.position(), params);
                    FxWorld.add(cloud);
                }
                cloud.keep(activeTicks);
            }
            case "molotov" -> {
                FirePatch patch = FxWorld.find(thrown.getId(), FirePatch.class);
                if (patch == null) {
                    patch = new FirePatch(thrown.getId(), thrown.position(), params);
                    FxWorld.add(patch);
                }
                patch.keep(activeTicks);
            }
            default -> {
            }
        }
    }

    /** The bottle breaking: glass and a gout of flame (the fire itself comes with the active ticks). */
    private static void shatter(ClientLevel level, Vec3 at) {
        var random = level.random;
        for (int i = 0; i < 18; i++) {
            level.addParticle(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                            net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()), at.x, at.y + 0.1, at.z,
                    (random.nextFloat() - 0.5) * 0.6, random.nextFloat() * 0.4, (random.nextFloat() - 0.5) * 0.6);
            level.addParticle(net.minecraft.core.particles.ParticleTypes.FLAME, at.x, at.y + 0.2, at.z,
                    (random.nextFloat() - 0.5) * 0.25, random.nextFloat() * 0.18, (random.nextFloat() - 0.5) * 0.25);
        }
    }

    /** How much smoke the camera is in (0..1). */
    private static float smokeAtCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || !mc.gameRenderer.getMainCamera().isInitialized()) return 0;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        float density = 0;
        for (FxWorld.Fx fx : FxWorld.all()) {
            if (fx instanceof SmokeCloud cloud) density = Math.max(density, cloud.density(eye));
        }
        return density;
    }

    private static void renderScreen(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        AdrenalineRush.render(g);
        FlashBlind.render(g);
        float smoke = smokeAtCamera();
        if (smoke > 0.01f) {
            // inside the cloud: a soft grey wall, thickest in the middle of it
            int alpha = Math.round(255 * Mth.clamp(smoke * 0.96f, 0, 0.96f));
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), ARGB.color(alpha, 178, 180, 184));
        }
    }
}
