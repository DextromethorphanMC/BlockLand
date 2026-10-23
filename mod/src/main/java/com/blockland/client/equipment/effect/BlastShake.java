package com.blockland.client.equipment.effect;

import com.blockland.anim.Spring;
import com.blockland.client.settings.ClientSettings;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * The camera's reaction to a nearby blast, on top of whatever the held weapon does: a jolt (the head snapping back and
 * to the side on springs) and a rumble that dies away (trauma² through smooth noise, like the weapon camera's §32
 * rumble), both falling off with distance. Scaled by the player's camera impact comfort setting.
 */
final class BlastShake {
    private static final float MAX_TRAUMA = 1.3f, DECAY = 1.6f;
    private static final float[] RUMBLE = {1.4f, 1.0f, 2.4f};

    private static final Spring pitch = spring(40, 0.38f), yaw = spring(34, 0.38f), roll = spring(30, 0.34f);
    private static float trauma;
    private static double time;
    private static long last;

    private BlastShake() {
    }

    private static Spring spring(float omega, float zeta) {
        return new Spring(0).config(omega * omega, 2 * zeta * omega);
    }

    static void init() {
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            long now = System.nanoTime();
            float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1e9f);
            last = now;
            time += dt;
            trauma *= (float) Math.exp(-dt * DECAY);
            pitch.update(dt);
            yaw.update(dt);
            roll.update(dt);
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
            float k = ClientSettings.get().cameraImpact;
            float r = trauma * trauma;
            event.setPitch(event.getPitch() + (pitch.get() + r * RUMBLE[0] * noise(time * 6.5, 11)) * k);
            event.setYaw(event.getYaw() + (yaw.get() + r * RUMBLE[1] * noise(time * 6.1, 37)) * k);
            event.setRoll(event.getRoll() + (roll.get() + r * RUMBLE[2] * noise(time * 5.3, 71)) * k);
        });
    }

    /**
     * A blast at {@code at}: {@code strength} at point blank, nothing beyond {@code reach} blocks.
     */
    static void blast(Vec3 at, float strength, float reach) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 eye = mc.player.getEyePosition();
        double distance = eye.distanceTo(at);
        float near = (float) Math.max(0, 1 - distance / reach);
        float s = strength * near * near;
        if (s < 0.01f) return;
        trauma = Math.min(MAX_TRAUMA, trauma + s * 0.9f);
        // the head snaps away from the blast: up (pitch is down-positive), and rolls away from its side
        Vec3 to = at.subtract(eye);
        float side = (float) mc.player.getViewVector(1).cross(new Vec3(0, 1, 0)).normalize().dot(to.normalize());
        pitch.punch(-6.5f * s);
        yaw.punch(2.2f * s * side);
        roll.punch(-5f * s * side + (mc.player.getRandom().nextFloat() - 0.5f) * 2 * s);
    }

    /** Smooth noise about -1..1 (two sines with incommensurate rates; enough for a short rumble). */
    private static float noise(double x, int seed) {
        return (float) (Math.sin(x + seed) * 0.6 + Math.sin(x * 2.37 + seed * 1.7) * 0.4);
    }
}
