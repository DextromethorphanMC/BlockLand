package com.blockland.client.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.client.render.RenderClock;
import com.blockland.effect.BLEffects;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;

import javax.annotation.Nullable;

/**
 * The adrenaline rush in first person, while the {@code blockland:adrenaline} effect lasts: a hard onset (the view
 * punches out and the colours tear for a moment), then the edges of the view stream outward — radial blur, colour
 * split, speed lines that grow with how fast the player moves — under a red vignette that closes in on every
 * heartbeat (a heartbeat sound in step, racing at first and calming down), and the field of view breathing wider.
 * Everything fades with the effect's last seconds. One screen pass on the finished frame.
 */
final class AdrenalineRush {
    /** Seconds of the onset kick, and of the fade at the end. */
    private static final float ONSET = 0.6f, TAIL = 2.2f;
    private static final float BPM_START = 168, BPM_END = 118;

    /** The rush's elapsed time and total length (s); total 0 = no rush. */
    private static float elapsed, total;
    private static int lastDuration;
    /** Heartbeat clock: beats so far (fraction = phase), last beat index a sound was played for. */
    private static double beats;
    private static long lastBeatSound = -1;
    private static float smoothSpeed;
    private static long lastFrame;
    @Nullable
    private static TextureTarget scene;
    @Nullable
    private static MappableRingBuffer ubo;

    private AdrenalineRush() {
    }

    static void init() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            float i = intensity();
            if (i <= 0.001f || !event.usedConfiguredFov()) return;
            float breathe = (float) Math.sin(beats * Math.PI * 2) * 0.012f;
            event.setFOV(event.getFOV() * (1 + 0.07f * i + 0.12f * kick() + breathe * i));
        });
    }

    private static void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        MobEffectInstance effect = player == null || !BLEffects.ADRENALINE.isPresent() ? null : player.getEffect(BLEffects.adrenaline());
        if (effect == null) {
            total = 0;
            lastDuration = 0;
            return;
        }
        int duration = effect.getDuration();
        // a fresh injection (or a longer one on top): restart the clock
        if (total == 0 || duration > lastDuration + 1) {
            total = duration / 20f;
            elapsed = 0;
            beats = 0;
            lastBeatSound = -1;
        }
        lastDuration = duration;
        elapsed = Math.max(elapsed, total - duration / 20f);
        double vx = player.getDeltaMovement().x, vz = player.getDeltaMovement().z;
        smoothSpeed += ((float) Math.sqrt(vx * vx + vz * vz) - smoothSpeed) * 0.3f;
    }

    /** 0..1 overall strength: up in a blink, down over the last seconds. */
    private static float intensity() {
        if (total <= 0) return 0;
        float in = Mth.clamp(elapsed / 0.15f, 0, 1), out = Mth.clamp((total - elapsed) / TAIL, 0, 1);
        return in * out * out * (3 - 2 * out);
    }

    /** 0..1 onset punch, decaying over {@link #ONSET}. */
    private static float kick() {
        if (total <= 0 || elapsed > ONSET) return 0;
        float k = 1 - elapsed / ONSET;
        return k * k;
    }

    /** Heart rate (beats per second) at this point of the rush. */
    private static float rate() {
        float k = total <= 0 ? 1 : Mth.clamp(elapsed / total, 0, 1);
        return Mth.lerp(k, BPM_START, BPM_END) / 60f;
    }

    /** The pulse of the vignette: a double knock (lub-dub) per beat. */
    private static float pulse(double phase) {
        float p = (float) (phase - Math.floor(phase));
        float lub = (float) Math.exp(-Math.pow((p - 0.04) / 0.07, 2)), dub = 0.6f * (float) Math.exp(-Math.pow((p - 0.3) / 0.08, 2));
        return Math.min(1, lub + dub);
    }

    /** The HUD's first layer: advances the clock and draws the rush. */
    static void render(GuiGraphics g) {
        long now = RenderClock.nanos();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (total <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isPaused()) {
            elapsed += dt;
            beats += dt * rate();
        }
        float i = intensity();
        if (i <= 0.002f) return;
        long beat = (long) Math.floor(beats);
        if (beat != lastBeatSound) {
            lastBeatSound = beat;
            mc.getSoundManager().play(new SimpleSoundInstance(BlockLand.id("equipment.heartbeat"), SoundSource.PLAYERS, 0.25f + 0.75f * i, 1f,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
        }
        float pulse = pulse(beats);
        float lines = Mth.clamp(0.35f + smoothSpeed / 0.32f, 0.35f, 1.3f);
        GpuTextureView main = ScreenPasses.mainColor();
        if (main == null) return;
        scene = ScreenPasses.fullSize(scene, "BlockLand adrenaline scene");
        if (ubo == null) ubo = ScreenPasses.ring("adrenaline");
        var target = mc.getMainRenderTarget();
        ScreenPasses.pass("BlockLand adrenaline: copy", scene.getColorTextureView(), ScreenPasses.COPY, main, null);
        ScreenPasses.write(ubo, i, pulse, (float) target.width / Math.max(1, target.height), elapsed, Math.min(1, lines), kick(), 0, 0);
        ScreenPasses.pass("BlockLand adrenaline", main, ScreenPasses.RUSH, scene.getColorTextureView(), ubo);
    }
}
