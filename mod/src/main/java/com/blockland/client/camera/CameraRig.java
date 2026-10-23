package com.blockland.client.camera;

import com.blockland.BlockLand;
import com.blockland.anim.Spring;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * The first-person camera's dynamic feedback (asset spec §32), one per weapon viewmodel. Everything that moves the view
 * — a shot, a magazine slammed home, a slide or bolt, a landing, a footfall, a knife connecting, the body pushing off
 * or stopping — goes through four layers, all on springs and elapsed time (the {@code anim} library), so the motion has
 * real acceleration, follow-through and rhythm and looks the same at any frame rate:
 * <ul>
 *   <li><b>kick</b>: pitch / yaw / roll in two stages — a fast <i>snap</i> spring (the jolt) and a slower, better damped
 *       <i>body</i> spring (the head and shoulders following through) — so a hit lands sharply and settles with weight
 *       instead of a mechanical return to centre;</li>
 *   <li><b>shake</b>: a high <i>rattle</i> — light, barely damped oscillators (14–19 Hz, rotation and a hair of head
 *       movement) that every shot or slam strikes in a fresh direction, so the frame rings like struck metal and dies
 *       out in a fifth of a second, building up in a burst — and a low <i>rumble</i> (the weight of a heavy shot or a
 *       hard landing): a trauma channel decaying on its own, read through smooth gradient noise and scaled by
 *       trauma². Neither is random per-frame jitter;</li>
 *   <li><b>FOV punch</b>: a brief widening (or narrowing) of the view, the impact without turning it;</li>
 *   <li><b>micro-translation</b>: millimetres to a couple of centimetres of head movement (pushed back by a shot,
 *       compressed by a landing, carried across by a side step), applied to the camera position, plus the head's
 *       continuous locomotion bob ({@link #bob}) under the locomotion comfort setting.</li>
 * </ul>
 * Aimed shots leave along the view, so while aiming the pitch and yaw of kicks and shake are calmed (roll — which turns
 * about the aim point — and the FOV punch keep the impact). The player's comfort settings scale it all
 * ({@link #setIntensity}). Pure presentation: the real recoil (guns.json) is applied to the player's look elsewhere.
 */
public final class CameraRig {
    /** Rattle peak per unit of trauma struck (degrees pitch / yaw / roll, mm of head movement); rumble at trauma 1. */
    private static final float[] RATTLE = {0.32f, 0.22f, 0.7f};
    private static final float RATTLE_SHIFT = 1.4f;
    private static final float[] RUMBLE = {0.5f, 0.32f, 0.95f};
    private static final float RUMBLE_HZ = 5.2f, RUMBLE_DECAY = 1.8f;
    /** Largest trauma per channel and largest head movement (mm) — comfort. */
    private static final float MAX_TRAUMA = 1.2f, MAX_SHIFT = 30f;

    /** Comfort settings: impacts (kicks, shake, FOV, head movement) and locomotion sway, 0..1.5. */
    private static float impact = 1, motion = 1;

    private final Spring snapPitch = spring(58, 0.48f), snapYaw = spring(52, 0.46f), snapRoll = spring(48, 0.42f);
    /** The rattle: struck oscillators, each axis at its own frequency so the ringing never beats in step. */
    private final Spring rattlePitch = spring(98, 0.16f), rattleYaw = spring(110, 0.17f), rattleRoll = spring(88, 0.15f);
    private final Spring rattleShift = spring(120, 0.2f);
    private final java.util.Random random = new java.util.Random();
    private final Spring bodyPitch = spring(10, 0.72f), bodyYaw = spring(9, 0.72f), bodyRoll = spring(9, 0.68f);
    private final Spring fov = spring(20, 0.45f);
    private final Spring shiftX = spring(20, 0.55f), shiftY = spring(18, 0.5f), shiftZ = spring(22, 0.55f);
    private float rumble;
    /** The head's continuous locomotion bob, mm (right, up, back), set every frame by the weapon's motion layer. */
    private float bobX, bobY, bobZ;
    private double time;
    private final int seed = (int) (Math.random() * 1_000_000);
    /** 0 = hip, 1 = aimed: pitch / yaw calm down so the aim point stays honest. */
    private float aim;

    private static Spring spring(float omega, float zeta) {
        return new Spring(0).config(omega * omega, 2 * zeta * omega);
    }

    /** The player's comfort settings (the settings module sets them). */
    public static void setIntensity(float impacts, float locomotion) {
        impact = Math.max(0, Math.min(1.5f, impacts));
        motion = Math.max(0, Math.min(1.5f, locomotion));
    }

    /** Multiplier for the rhythmic locomotion sway (bob, lean), which the weapon's motion layer computes itself. */
    public static float motionIntensity() {
        return motion;
    }

    // ---- inputs ----------------------------------------------------------------------------------------------------

    /**
     * A jolt, degrees (pitch up, yaw left, roll left as in Minecraft's camera roll), first peak of the sum.
     *
     * @param snap share carried by the fast spring (1 = a crack, 0.3 = a heavy shove the body follows through)
     */
    public void kick(float pitch, float yaw, float roll, float snap) {
        float s = Math.max(0, Math.min(1, snap)), b = 1 - s;
        if (pitch != 0) {
            snapPitch.punch(pitch * s);
            bodyPitch.punch(pitch * b);
        }
        if (yaw != 0) {
            snapYaw.punch(yaw * s);
            bodyYaw.punch(yaw * b);
        }
        if (roll != 0) {
            snapRoll.punch(roll * s);
            bodyRoll.punch(roll * b);
        }
    }

    /** The body leans continuously (inertia of starting, stopping, strafing): impulses on the slow layer, deg/s. */
    public void lean(float pitchRate, float rollRate) {
        if (pitchRate != 0) bodyPitch.impulse(pitchRate);
        if (rollRate != 0) bodyRoll.impulse(rollRate);
    }

    /**
     * Strikes the rattle (a fresh direction each time, its size {@code rattle}) and adds low-frequency trauma.
     */
    public void shake(float rattle, float rumbleTrauma) {
        if (rattle > 0) {
            float r = Math.min(MAX_TRAUMA, rattle);
            rattlePitch.punch(RATTLE[0] * r * (0.55f + 0.45f * random.nextFloat()));     // gunfire rattles upward first
            rattleYaw.punch(RATTLE[1] * r * (random.nextFloat() * 2 - 1));
            rattleRoll.punch(RATTLE[2] * r * (random.nextFloat() * 2 - 1));
            rattleShift.punch(RATTLE_SHIFT * r);
        }
        rumble = Math.min(MAX_TRAUMA, rumble + Math.max(0, rumbleTrauma));
    }

    /** A brief change of the field of view, percent (+ wider, - narrower). */
    public void punchFov(float percent) {
        fov.punch(percent);
    }

    /** Moves the head, millimetres in camera space: right, up, back (towards the player). */
    public void nudge(float right, float up, float back) {
        if (right != 0) shiftX.punch(right);
        if (up != 0) shiftY.punch(up);
        if (back != 0) shiftZ.punch(back);
    }

    /**
     * The head's rhythmic locomotion movement this frame, millimetres in camera space (right, up, back) — continuous,
     * not an impulse; scaled by the locomotion comfort setting, calmed while aiming.
     */
    public void bob(float right, float up, float back) {
        bobX = right;
        bobY = up;
        bobZ = back;
    }

    /** Advances the shake's clock and trauma; {@code aim} 0..1 calms what would move the aim point. */
    public void update(float dt, float aim) {
        time += dt;
        this.aim = Math.max(0, Math.min(1, aim));
        rumble *= (float) Math.exp(-dt * RUMBLE_DECAY);
    }

    // ---- outputs ---------------------------------------------------------------------------------------------------

    /** Degrees, Minecraft convention (positive pitch looks down). */
    public float pitch() {
        return -(snapPitch.get() + bodyPitch.get() + rattlePitch.get() + shake(0)) * impact * (1 - 0.55f * aim);
    }

    public float yaw() {
        return (snapYaw.get() + bodyYaw.get() + rattleYaw.get() + shake(1)) * impact * (1 - 0.6f * aim);
    }

    public float roll() {
        return (snapRoll.get() + bodyRoll.get() + rattleRoll.get() + shake(2)) * impact;
    }

    /** Multiplier of the field of view. */
    public float fovScale() {
        return 1 + fov.get() / 100f * impact * (1 - 0.3f * aim);
    }

    /** The low rumble on one axis: trauma² × fractal gradient noise, never per-frame random. */
    private float shake(int axis) {
        float r = rumble * rumble;
        if (r < 1e-5f) return 0;
        return r * RUMBLE[axis] * fbm((float) time * RUMBLE_HZ, seed + 7 + axis * 131);
    }

    // ---- head movement ---------------------------------------------------------------------------------------------

    @Nullable
    private static Method setPosition;
    private static boolean lookedUp;

    /**
     * Moves the rendered camera by this rig's head movement (call from the camera-angles event, after the camera was
     * set up). Small and clamped; the hand pass is drawn in view space, so the weapon stays with the head.
     */
    public void moveCamera(Camera camera) {
        float k = impact * (1 - 0.5f * aim) / 1000f, m = motion * (1 - 0.5f * aim) / 1000f;
        float x = clamp(shiftX.get()) * k + clamp(bobX) * m, y = clamp(shiftY.get()) * k + clamp(bobY) * m;
        float z = clamp(shiftZ.get() + rattleShift.get()) * k + clamp(bobZ) * m;
        if (Math.abs(x) + Math.abs(y) + Math.abs(z) < 1e-5f || !camera.isInitialized()) return;
        Method method = setter();
        if (method == null) return;
        Vector3f left = camera.getLeftVector(), up = camera.getUpVector(), look = camera.getLookVector();
        Vec3 at = camera.getPosition().add(-left.x * x + up.x * y - look.x * z, -left.y * x + up.y * y - look.y * z,
                -left.z * x + up.z * y - look.z * z);
        try {
            method.invoke(camera, at);
        } catch (ReflectiveOperationException | RuntimeException e) {
            setPosition = null;
        }
    }

    private static float clamp(float mm) {
        return Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, mm));
    }

    @Nullable
    private static Method setter() {
        if (lookedUp) return setPosition;
        lookedUp = true;
        try {
            setPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
            setPosition.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Camera head movement unavailable: {}", e.toString());
            setPosition = null;
        }
        return setPosition;
    }

    // ---- noise -----------------------------------------------------------------------------------------------------

    /** Two octaves of 1D gradient noise, about -1..1. */
    private static float fbm(float x, int seed) {
        return (gradient(x, seed) + 0.5f * gradient(x * 2.03f + 17.3f, seed ^ 0x5bd1e995)) / 1.5f;
    }

    /** 1D Perlin noise: smooth, zero at integer points, random slopes. */
    private static float gradient(float x, int seed) {
        int i = (int) Math.floor(x);
        float f = x - i;
        float g0 = slope(i, seed), g1 = slope(i + 1, seed);
        float u = f * f * f * (f * (f * 6 - 15) + 10);
        return 2 * (g0 * f + (g1 * (f - 1) - g0 * f) * u);
    }

    private static float slope(int i, int seed) {
        int h = i * 0x27d4eb2d ^ seed * 0x165667b1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return (h & 0xffff) / 32767.5f - 1;
    }
}
