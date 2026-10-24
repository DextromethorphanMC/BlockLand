package com.blockland.client.bomb.fx;

import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.settings.ClientSettings;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * The camera's reaction to the bomb: the shock arrives a moment after the flash (later the further away), snaps the
 * head away from the blast on springs, punches the field of view and leaves a low rumble that dies away over a few
 * seconds (trauma² through smooth noise). Falls off with distance; scaled by the player's camera impact setting.
 */
final class BombShake {
    /** Blocks per second the felt shock travels (slower than sound, so it reads as a wave). */
    private static final float SHOCK_SPEED = 120;
    private static final float MAX_TRAUMA = 1.6f, DECAY = 0.9f;
    private static final float[] RUMBLE = {1.8f, 1.3f, 2.8f};

    private static final Spring pitch = spring(32, 0.32f), yaw = spring(28, 0.34f), roll = spring(24, 0.3f),
            fov = spring(22, 0.45f);
    private static float trauma;
    private static double time;
    private static long last;
    /** A shock on its way: lands when {@code time} reaches {@code pendingAt}. */
    private static double pendingAt = -1;
    private static float pendingStrength, pendingSide;

    private BombShake() {
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
            if (pendingAt >= 0 && time >= pendingAt) land();
            trauma *= (float) Math.exp(-dt * DECAY);
            // the springs run on the shared ticker (several ticks per frame are harmless)
            Ticker.global().tick();
            if (Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON) return;
            float k = ClientSettings.get().cameraImpact;
            float r = trauma * trauma;
            event.setPitch(event.getPitch() + (pitch.get() + r * RUMBLE[0] * noise(time * 7.5, 13)) * k);
            event.setYaw(event.getYaw() + (yaw.get() + r * RUMBLE[1] * noise(time * 6.7, 41)) * k);
            event.setRoll(event.getRoll() + (roll.get() + r * RUMBLE[2] * noise(time * 5.9, 79)) * k);
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            if (!event.usedConfiguredFov()) return;
            float k = ClientSettings.get().cameraImpact;
            event.setFOV(event.getFOV() * (1 + fov.get() * 0.01f * k));
        });
    }

    /** The bomb went off at {@code at}: felt within {@code reach} blocks, {@code strength} at point blank. */
    static void blast(Vec3 at, float strength, float reach) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 eye = mc.player.getEyePosition();
        double distance = eye.distanceTo(at);
        float near = (float) Math.max(0, 1 - distance / reach);
        float s = strength * near * near;
        if (s < 0.01f) return;
        Vec3 to = at.subtract(eye).normalize();
        pendingSide = (float) mc.player.getViewVector(1).cross(new Vec3(0, 1, 0)).normalize().dot(to);
        pendingStrength = Math.max(pendingStrength, s);
        pendingAt = time + Math.min(0.6, distance / SHOCK_SPEED);
    }

    private static void land() {
        float s = pendingStrength;
        pendingAt = -1;
        pendingStrength = 0;
        trauma = Math.min(MAX_TRAUMA, trauma + s * 0.8f);
        // the head snaps away from the blast and up; the view widens for an instant as the pressure hits
        pitch.punch(-5.5f * s);
        yaw.punch(2.6f * s * pendingSide);
        roll.punch(-5.5f * s * pendingSide);
        fov.punch(4.5f * Math.min(1.5f, s));
    }

    static void clear() {
        trauma = 0;
        pendingAt = -1;
        pendingStrength = 0;
    }

    /** Smooth noise about -1..1 (two sines with incommensurate rates; enough for a rumble). */
    private static float noise(double x, int seed) {
        return (float) (Math.sin(x + seed) * 0.6 + Math.sin(x * 2.37 + seed * 1.7) * 0.4);
    }
}
