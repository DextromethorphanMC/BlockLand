package com.blockland.client.hud;

import com.blockland.BlockLand;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Spring;
import com.blockland.client.ui.Draw;
import com.blockland.craft.CraftData;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A special skin's kill icon (asset spec §38), shared by every weapon HUD: on a confirmed kill the skin's artwork burns
 * in above the kill medals — igniting from its heart outwards (the burn-in sheet), spinning into place with an
 * overshoot behind a flash and a burst of embers — then glows and breathes while embers rise off it, and burns away the
 * way it came. The weapons only hand it the skin's data ({@link #show}).
 */
public final class KillIcon {
    /** Seconds of the burn-in and of the burn-away. */
    private static final float IN = 0.45f, OUT = 0.5f;
    private static final int FRAMES = 16, SHEET = 4, CELL = 256;
    private static final int HOT = 0xFFFFD9A0;

    private static final class Ember {
        float x, y, vx, vy, age, life, size;
    }

    @Nullable
    private static CraftData.SkinKillIcon icon;
    private static ResourceLocation texture, glow, burn;
    private static long born, last;
    private static boolean leaving;
    /** Lands on 1 with an overshoot. */
    private static final Spring scale = new Spring(1).config(320, 15);
    private static final List<Ember> embers = new ArrayList<>();
    private static final Random random = new Random();
    private static float trickle;

    private KillIcon() {
    }

    public static void init(BusGroup modBus) {
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw().addBelow(ForgeLayeredDraw.POST_SLEEP_STACK,
                BlockLand.id("kill_icon"), ForgeLayeredDraw.TITLE_OVERLAY, KillIcon::render));
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
    }

    /** A confirmed kill with a skin that has a kill icon: it (re)starts. */
    public static void show(CraftData.SkinKillIcon data) {
        if (data.texture == null || data.texture.isBlank()) return;
        icon = data;
        texture = BlockLand.id(data.texture + ".png");
        glow = BlockLand.id(data.texture + "_glow.png");
        burn = BlockLand.id(data.texture + "_burn.png");
        born = last = System.nanoTime();
        leaving = false;
        scale.set(1.75f);
        scale.setTarget(1);
        burst(Math.max(0, data.embers), 0.42f, 70, 150);
    }

    public static void clear() {
        icon = null;
        embers.clear();
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (icon == null || mc.level == null || mc.options.hideGui) return;
        long now = System.nanoTime();
        float t = (now - born) / 1e9f, dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        float seconds = Math.max(IN + OUT + 0.2f, icon.seconds);
        float size = icon.size;
        if (!leaving && t > seconds - OUT) {
            leaving = true;
            burst(Math.max(0, icon.embers) * 3 / 4, 0.38f, 30, 90);
        }
        tick(dt, t, seconds);
        if (t > seconds + 1.2f && embers.isEmpty()) {
            icon = null;
            return;
        }
        float cx = g.guiWidth() / 2f, cy = g.guiHeight() * 0.64f - size * 0.62f - 6;
        int rgb = icon.rgb();

        if (t < seconds) {
            float in = Mth.clamp(t / IN, 0, 1), out = Mth.clamp((t - (seconds - OUT)) / OUT, 0, 1);
            float breathe = 1 + 0.018f * (float) Math.sin(t * 5.2) + (out > 0 ? 0.1f * out * out : 0);
            float s = scale.get() * breathe;
            float spin = (float) Math.toRadians(-16) * (1 - in) * (1 - in);
            // the flash behind it as it lands, a shock ring of light growing out
            if (in < 1) {
                float u = in;
                Draw.glow(g, cx, cy, size * (0.45f + 1.25f * u), size * (0.45f + 1.25f * u), ColorMath.fade(HOT, 0.85f * (1 - u) * (1 - u)));
            }
            // the glow: pulsing and flickering like embers under it, flaring with the burn-in and the burn-away
            float flicker = 0.72f + 0.16f * (float) Math.sin(t * 9.3) + 0.12f * (float) Math.sin(t * 23.7 + 1.3);
            float flare = Math.max(1 - in, out) * 0.6f;
            float glowAlpha = Mth.clamp((0.42f * flicker + flare) * (out > 0.85f ? (1 - out) / 0.15f : 1), 0, 1);
            drawCentered(g, glow, cx, cy, size * 1.55f * s / 128f, 0, 0, 128, 128, spin, ColorMath.withAlpha(rgb, Math.round(255 * glowAlpha)));
            // the artwork: the burn-in frames, the whole icon, the burn-in frames backwards
            int frame = in < 1 ? Math.min(FRAMES - 1, (int) (in * FRAMES)) : out > 0 ? Math.min(FRAMES - 1, (int) ((1 - out) * FRAMES)) : -1;
            float k = size * s / CELL;
            if (frame < 0) {
                drawCentered(g, texture, cx, cy, k, 0, 0, CELL, CELL, spin, 0xFFFFFFFF);
            } else {
                drawCentered(g, burn, cx, cy, k, (frame % SHEET) * CELL, (frame / SHEET) * CELL, CELL * SHEET, CELL * SHEET, spin, 0xFFFFFFFF);
            }
        }
        for (Ember e : embers) {
            float u = e.age / e.life;
            int color = u < 0.25f ? ColorMath.mix(HOT, 0xFF000000 | rgb, u / 0.25f)
                    : ColorMath.mix(0xFF000000 | rgb, 0xFF3A0A04, (u - 0.25f) / 0.75f);
            int faded = ColorMath.fade(color, (1 - u) * (1 - u));
            float x = cx + e.x, y = cy + e.y;
            if (e.size > 1.4f) Draw.glow(g, x, y, e.size * 2.6f, e.size * 2.6f, ColorMath.fade(color, 0.35f * (1 - u)));
            int half = Math.max(1, Math.round(e.size / 2));
            g.fill(Math.round(x) - half, Math.round(y) - half, Math.round(x) + half, Math.round(y) + half, faded);
        }
    }

    /**
     * Draws a region of a texture centred on (cx, cy), turned by {@code angle} and scaled by {@code k} (texture
     * pixels to GUI pixels): drawn at its texture size, so the transform keeps it sharp.
     */
    private static void drawCentered(GuiGraphics g, ResourceLocation tex, float cx, float cy, float k, int u, int v, int texW, int texH,
                                     float angle, int color) {
        int region = texW == CELL * SHEET ? CELL : texW;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(angle);
        g.pose().scale(k, k);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, -region / 2, -region / 2, u, v, region, region, region, region, texW, texH, color);
        g.pose().popMatrix();
    }

    /** Embers thrown out from a ring round the icon's heart. */
    private static void burst(int count, float ring, float minSpeed, float maxSpeed) {
        if (icon == null) return;
        for (int i = 0; i < count; i++) {
            Ember e = new Ember();
            double a = random.nextDouble() * Math.PI * 2;
            float r = icon.size * ring * (0.4f + 0.6f * random.nextFloat());
            float speed = minSpeed + random.nextFloat() * (maxSpeed - minSpeed);
            e.x = (float) Math.cos(a) * r;
            e.y = (float) Math.sin(a) * r;
            e.vx = (float) Math.cos(a) * speed;
            e.vy = (float) Math.sin(a) * speed - 20;
            e.life = 0.5f + random.nextFloat() * 0.7f;
            e.size = 1 + random.nextFloat() * 1.6f;
            embers.add(e);
        }
    }

    /** Moves the embers (drag, rising heat, a little turbulence) and lets a few rise off the glowing icon. */
    private static void tick(float dt, float t, float seconds) {
        if (icon != null && t > IN && t < seconds - OUT) {
            trickle += dt * Math.max(0, icon.embers) * 0.5f;
            while (trickle >= 1) {
                trickle--;
                Ember e = new Ember();
                e.x = (random.nextFloat() - 0.5f) * icon.size * 0.7f;
                e.y = (random.nextFloat() - 0.3f) * icon.size * 0.5f;
                e.vx = (random.nextFloat() - 0.5f) * 14;
                e.vy = -18 - random.nextFloat() * 26;
                e.life = 0.7f + random.nextFloat() * 0.8f;
                e.size = 0.8f + random.nextFloat() * 1.2f;
                embers.add(e);
            }
        }
        float drag = (float) Math.exp(-2.6 * dt);
        embers.removeIf(e -> {
            e.age += dt;
            e.vx = e.vx * drag + (float) Math.sin(e.age * 11 + e.life * 7) * 22 * dt;
            e.vy = e.vy * drag - 46 * dt;
            e.x += e.vx * dt;
            e.y += e.vy * dt;
            return e.age >= e.life;
        });
    }
}
