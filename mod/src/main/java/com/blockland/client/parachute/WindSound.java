package com.blockland.client.parachute;

import com.blockland.parachute.ParachuteContent;
import com.blockland.parachute.ParachuteRules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The wind the local jumper hears: a roar rising with the speed in free fall, the canopy's flutter once it is open.
 * Follows the listener (no position), fades in and out, and stops itself on the ground.
 */
final class WindSound extends AbstractTickableSoundInstance {
    private final LocalPlayer player;
    private float level;

    WindSound(LocalPlayer player) {
        super(ParachuteContent.sound("parachute.wind"), SoundSource.PLAYERS, RandomSource.create());
        this.player = player;
        this.looping = true;
        this.delay = 0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.01f;
    }

    @Override
    public void tick() {
        if (player.isRemoved()) {
            stop();
            return;
        }
        double fall = -player.getDeltaMovement().y;
        boolean open = ClientParachute.localOpen();
        boolean airborne = ParachuteRules.wears(player) && !player.onGround() && !player.isInWater();
        float target = !airborne ? 0 : open ? 0.45f : (float) Mth.clamp((fall - 0.5) / 1.8, 0, 1) * 0.9f;
        level += (target - level) * 0.12f;
        volume = Math.max(0.001f, level);
        pitch = open ? 0.9f : 0.85f + (float) Mth.clamp(fall / 3.5, 0, 0.35);
        if (target == 0 && level < 0.01f) stop();
    }
}
