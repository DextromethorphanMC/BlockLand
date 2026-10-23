package com.blockland.client.gun;

import com.blockland.anim.Spring;
import com.blockland.client.camera.CameraRig;
import com.blockland.common.Handling;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Random;

/**
 * The first-person gun's shot recoil (asset spec §31.4), layered on top of the fire clip (which only carries the hands'
 * own reaction and the mechanism): every shot shoves the weapon back and flips the muzzle up about a pivot (the wrist,
 * or the shoulder for a stocked weapon) with a little random twist, then springs return it with a slight overshoot.
 * Sustained fire heats up: the weapon rides back and climbs, the twist grows and the camera shakes harder; it all
 * settles when the trigger rests. Aimed shots leave along the sight line as drawn and through the camera, so aimed the
 * kick is mostly a shove straight back and a roll (neither turns the sight line) with only a trace of rise, yaw and
 * camera punch: the visual recoil stays off the real one (guns.json {@code recoil}, applied to the player's view). The
 * weapon's {@link Handling} recoil profile shapes it.
 * <p>
 * The shot reaches the camera through the {@link CameraRig} (spec §32): a snap kick up with a body follow-through, a
 * roll that swings to alternating sides in a rhythm (repeating a side now and then) instead of random jitter, a short
 * FOV punch, the head pushed back a few millimetres, high-frequency rattle building with sustained fire and a
 * low-frequency rumble for heavy rounds — each scaled by the weapon's camera profile ({@code cameraKick},
 * {@code cameraRoll}, {@code cameraFov}, {@code cameraRumble}, {@code recoilShake}).
 */
final class GunRecoil {
    /**
     * Peak kick per unit of the gun's {@code kick} at the hip: millimetres back / up / sideways (the springs run in mm,
     * so their rest threshold stays invisible), degrees rise / yaw / roll.
     */
    private static final float BACK = 12f, LIFT = 1.6f, SIDE = 0.7f, RISE = 4.2f, YAW = 0.9f, ROLL = 1.8f;
    /** Camera per unit kick: degrees pitch / yaw / roll, FOV punch (%), head pushed back (mm), rattle and rumble trauma. */
    private static final float CAM_PITCH = 0.34f, CAM_YAW = 0.12f, CAM_ROLL = 0.55f, CAM_FOV = 1.1f, CAM_BACK = 3f;
    private static final float BUZZ = 0.26f, RUMBLE = 0.32f;
    /** Largest shove back at the hip / aimed (mm): an aimed optic must stay clear of the eye. */
    private static final float BACK_MAX_HIP = 30, BACK_MAX_AIMED = 8;

    private final Random random = new Random();
    private Handling handling = new Handling();
    private final CameraRig camera;
    /** Side the camera rolled to on the last shot (the next swings to the other side, mostly). */
    private float rollSide = 1;

    GunRecoil(CameraRig camera) {
        this.camera = camera;
    }

    private final Spring back = new Spring(0), lift = new Spring(0), side = new Spring(0);
    private final Spring rise = new Spring(0), yaw = new Spring(0), roll = new Spring(0);
    /** The crack: stiff springs that peak about 12 ms after the shot and are gone in 60 ms, under the travel. */
    private final Spring crackBack = new Spring(0), crackRise = new Spring(0), crackRoll = new Spring(0);
    /** Share of the shove and flip carried by the crack (the rest is the heavier travel). */
    private static final float CRACK = 0.42f;
    /** Sustained fire: the weapon rides back and climbs while it is hot. */
    private final Spring climbPitch = new Spring(0).config(64, 13.6), climbBack = new Spring(0).config(64, 13.6);
    /** 0..1: how hot sustained fire has made the weapon. */
    private float heat;
    private float sinceShot = 10;
    private final Vector3f pivot = new Vector3f();

    void setHandling(Handling handling) {
        this.handling = handling == null ? new Handling() : handling;
        float snap = Mth.clamp(this.handling.recoilSnap, 0.3f, 3f);
        tune(back, 26 * snap, 0.55f);
        tune(lift, 26 * snap, 0.55f);
        tune(side, 22 * snap, 0.5f);
        tune(rise, 20 * snap, 0.42f);
        tune(yaw, 17 * snap, 0.38f);
        tune(roll, 17 * snap, 0.36f);
        tune(crackBack, 130 * snap, 0.55f);
        tune(crackRise, 120 * snap, 0.5f);
        tune(crackRoll, 110 * snap, 0.45f);
        heat = 0;
    }

    /** Natural frequency (rad/s) and damping ratio of a recoil spring. */
    private static void tune(Spring spring, float omega, float zeta) {
        spring.config(omega * omega, 2 * zeta * omega);
    }

    /**
     * A shot.
     *
     * @param kick the gun's kick (attachments included; a dual gun's volley is stronger)
     * @param aim  0 = hip, 1 = aimed down sights
     * @param guns bit 1 = the right (or only) gun fired, bit 2 = a dual gun's left one
     * @param dual the weapon is a dual pair (a single shot twists towards the gun that fired)
     */
    void shot(float kick, float aim, int guns, boolean dual) {
        Handling h = handling;
        float hot = 1 + heat;
        float bias = !dual ? 0 : guns == 1 ? 1 : guns == 2 ? -1 : 0;
        float shove = Math.min(BACK * kick * h.recoilBack * (1 - 0.55f * aim), Mth.lerp(aim, BACK_MAX_HIP, BACK_MAX_AIMED));
        back.punch(shove * (1 - CRACK));
        crackBack.punch(shove * CRACK);
        lift.punch(LIFT * kick * (1 - 0.7f * aim));
        side.punch(SIDE * kick * h.recoilTwist * (signed() + 0.6f * bias) * (1 - 0.6f * aim));
        float flip = RISE * kick * h.recoilRise * (0.85f + 0.3f * random.nextFloat()) * (1 - 0.94f * aim);
        rise.punch(flip * (1 - CRACK));
        crackRise.punch(flip * CRACK);
        crackRoll.punch(0.6f * ROLL * kick * h.recoilTwist * signed() * (1 - 0.45f * aim));
        yaw.punch(YAW * kick * h.recoilTwist * hot * (signed() + 0.5f * bias) * (1 - 0.93f * aim));
        roll.punch(ROLL * kick * h.recoilTwist * (1 + 0.7f * heat) * (signed() - 0.6f * bias) * (1 - 0.45f * aim));
        shotCamera(kick, aim, bias, hot);
        heat = Math.min(1, heat + 0.16f);
        sinceShot = 0;
        float climb = (float) Math.sqrt(Math.max(0, kick));
        climbPitch.setTarget(heat * 2.2f * climb * h.recoilRise * (1 - 0.9f * aim));
        climbBack.setTarget(heat * 4f * climb * h.recoilBack * (1 - 0.5f * aim));
    }

    /**
     * The shot's camera: a snap kick up (heavier rounds lean more on the slow body layer: a shove, not a crack), a yaw
     * drift towards the twist, a roll swinging to the other side than last time (three times in four), the FOV punch,
     * the head pushed back, rattle and rumble.
     */
    private void shotCamera(float kick, float aim, float bias, float hot) {
        Handling h = handling;
        rollSide = random.nextFloat() < 0.75f ? -rollSide : rollSide;
        float heavy = Math.min(1, Math.max(0, (kick - 0.8f) / 1.4f));
        float snap = 0.75f - 0.35f * heavy;
        float pitch = CAM_PITCH * kick * h.cameraKick * (0.9f + 0.2f * random.nextFloat());
        float yaw = CAM_YAW * kick * h.cameraKick * hot * (0.6f * signed() + 0.6f * bias);
        float roll = CAM_ROLL * kick * h.cameraRoll * (1 + 0.5f * heat) * (rollSide * (0.75f + 0.25f * random.nextFloat()) - 0.4f * bias);
        camera.kick(pitch, yaw, roll, snap);
        camera.punchFov(CAM_FOV * kick * h.cameraFov * (1 - 0.5f * aim));
        camera.nudge(0, 0.3f * kick, CAM_BACK * kick * h.recoilBack);
        camera.shake(BUZZ * kick * h.recoilShake * (0.6f + 0.6f * heat), RUMBLE * kick * h.cameraRumble);
    }

    /** The trigger breaks on an empty chamber: the hands flinch a little, nothing more. */
    void dry(float aim) {
        back.punch(1.2f * (1 - 0.5f * aim));
        rise.punch(0.35f * (1 - 0.9f * aim));
        roll.punch(0.25f * signed());
        camera.kick(-0.08f, 0, 0.1f * signed(), 0.8f);
    }

    /** -1..1 with a bias away from 0 (a kick always twists noticeably one way or the other). */
    private float signed() {
        float r = random.nextFloat() * 2 - 1;
        return Math.signum(r) * (0.35f + 0.65f * Math.abs(r));
    }

    void update(float dt) {
        sinceShot += dt;
        if (sinceShot > 0.09f) {
            // the trigger rests: the weapon cools and comes down off its climb
            heat = Math.max(0, heat - dt * 1.8f);
            float cool = (float) Math.exp(-dt * 6);              // exact per elapsed time, whatever the frame rate
            climbPitch.setTarget(climbPitch.target() * cool);
            climbBack.setTarget(climbBack.target() * cool);
        }
    }

    /**
     * Applies the weapon's kick to the viewmodel root: rotations about the pivot ({@code grip} plus the profile's
     * {@code recoilPivot} behind it), then the shove.
     */
    void apply(Matrix4f root, Vector3f grip) {
        pivot.set(grip).add(0, 0, Math.max(0, handling.recoilPivot));
        root.translate(pivot);
        root.rotateX((float) Math.toRadians(rise.get() + crackRise.get() + climbPitch.get()));
        root.rotateY((float) Math.toRadians(-yaw.get()));
        root.rotateZ((float) Math.toRadians(roll.get() + crackRoll.get()));
        root.translate(-pivot.x, -pivot.y, -pivot.z);
        root.translate(side.get() * 0.001f, lift.get() * 0.001f, (back.get() + crackBack.get() + climbBack.get()) * 0.001f);
    }
}
