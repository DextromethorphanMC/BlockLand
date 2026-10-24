package com.blockland.client.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.network.packet.EffectPackets;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;

import javax.annotation.Nullable;

/**
 * Being flashed: the view whites out (held at full white for the first part of the flash, then clearing), the frame
 * seen at that instant stays burnt in as a pale afterimage that fades more slowly, and hearing turns to a ringing
 * tone — every other sound that starts meanwhile is muffled. Strength and times come from the server per player.
 * The white-out is a screen pass with the afterimage.
 */
final class FlashBlind {
    /** Share of the flash's time spent at full white (scaled by its strength). */
    private static final float HOLD = 0.4f;

    private static float strength, seconds, deafen;
    private static long startNanos;
    private static boolean capturePending;
    @Nullable
    private static TextureTarget afterimage;
    private static boolean haveAfterimage;
    @Nullable
    private static MappableRingBuffer ubo;

    private FlashBlind() {
    }

    static void init() {
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> strength = 0);
        PlaySoundEvent.BUS.addListener(event -> {
            float muffle = muffle();
            SoundInstance sound = event.getSound();
            if (muffle <= 0.02f || sound == null || sound instanceof TickableSoundInstance || isRing(sound)) return;
            event.setSound(new Muffled(sound, 1 - 0.85f * muffle));
        });
    }

    static void onFlash(EffectPackets.S2CFlash flash) {
        float now = elapsed();
        // a second flash on top of a running one: whichever leaves the view white longer wins
        if (strength > 0 && white(now) > flash.strength()) return;
        strength = Mth.clamp(flash.strength(), 0, 1);
        seconds = Math.max(0.2f, flash.seconds());
        deafen = flash.deafen();
        startNanos = System.nanoTime();
        capturePending = strength > 0.3f;
        haveAfterimage = false;
        Minecraft mc = Minecraft.getInstance();
        if (deafen > 0.2f && mc.player != null) {
            mc.getSoundManager().play(new SimpleSoundInstance(BlockLand.id("equipment.flash_ring"), SoundSource.MASTER,
                    Math.min(1, 0.35f + deafen / 3f), 1f, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                    0, 0, 0, true));
        }
    }

    private static boolean isRing(SoundInstance sound) {
        return sound.getLocation().equals(BlockLand.id("equipment.flash_ring"));
    }

    private static float elapsed() {
        return (System.nanoTime() - startNanos) / 1e9f;
    }

    /** 0..1 white over the view at {@code t} seconds into the flash. */
    private static float white(float t) {
        if (strength <= 0) return 0;
        float hold = seconds * HOLD * strength;
        if (t < hold) return Math.min(1, strength * 1.15f);
        float k = Mth.clamp((t - hold) / Math.max(0.05f, seconds - hold), 0, 1);
        return Math.min(1, strength * 1.15f) * (1 - k) * (1 - k);
    }

    /** The afterimage fades a little behind the white. */
    private static float after(float t) {
        if (!haveAfterimage) return 0;
        float k = Mth.clamp(t / (seconds * 1.25f), 0, 1);
        return 0.85f * strength * (1 - k * k);
    }

    private static float muffle() {
        if (strength <= 0 || deafen <= 0) return 0;
        float t = elapsed();
        return Mth.clamp(1 - t / deafen, 0, 1);
    }

    /** The HUD's first layer: the screen pass. */
    static void render(GuiGraphics g) {
        if (strength <= 0) return;
        float t = elapsed();
        if (t > seconds * 1.3f && t > deafen) {
            strength = 0;
            return;
        }
        float white = white(t);
        GpuTextureView main = ScreenPasses.mainColor();
        if (main == null) return;
        if (capturePending) {
            capturePending = false;
            afterimage = ScreenPasses.fullSize(afterimage, "BlockLand flash afterimage");
            ScreenPasses.pass("BlockLand flash: capture", afterimage.getColorTextureView(), ScreenPasses.COPY, main, null);
            haveAfterimage = true;
        }
        float after = after(t);
        if (white <= 0.004f && after <= 0.004f) return;
        if (ubo == null) ubo = ScreenPasses.ring("flash");
        var target = Minecraft.getInstance().getMainRenderTarget();
        ScreenPasses.write(ubo, white, after, (float) target.width / Math.max(1, target.height), t, 0, 0, 0, 0);
        GpuTextureView picture = haveAfterimage && afterimage != null ? afterimage.getColorTextureView() : main;
        if (picture == main) {
            // nothing burnt in: white only (the pass must not read the target it writes)
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), ARGB.color(Math.round(255 * white), 255, 255, 255));
            return;
        }
        ScreenPasses.pass("BlockLand flash", main, ScreenPasses.FLASH, picture, ubo);
    }

    /** A sound played quieter while the ears ring. */
    private record Muffled(SoundInstance sound, float scale) implements SoundInstance {
        @Override
        public ResourceLocation getLocation() {
            return sound.getLocation();
        }

        @Nullable
        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            return sound.resolve(manager);
        }

        @Override
        public net.minecraft.client.resources.sounds.Sound getSound() {
            return sound.getSound();
        }

        @Override
        public SoundSource getSource() {
            return sound.getSource();
        }

        @Override
        public boolean isLooping() {
            return sound.isLooping();
        }

        @Override
        public boolean isRelative() {
            return sound.isRelative();
        }

        @Override
        public int getDelay() {
            return sound.getDelay();
        }

        @Override
        public float getVolume() {
            return sound.getVolume() * scale;
        }

        @Override
        public float getPitch() {
            return sound.getPitch();
        }

        @Override
        public double getX() {
            return sound.getX();
        }

        @Override
        public double getY() {
            return sound.getY();
        }

        @Override
        public double getZ() {
            return sound.getZ();
        }

        @Override
        public Attenuation getAttenuation() {
            return sound.getAttenuation();
        }

        @Override
        public boolean canStartSilent() {
            return sound.canStartSilent();
        }

        @Override
        public boolean canPlaySound() {
            return sound.canPlaySound();
        }
    }
}
