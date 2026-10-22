package com.blockland.client.viewmodel;

import com.blockland.anim.Spring;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Procedural first-person motion in the style of modern shooters, replacing Minecraft's view bobbing while a weapon
 * is held: a figure-eight weapon bob locked to the footsteps, lean into strafing, look inertia, jump / landing
 * response, crouch offset, idle breathing, the raised "tactical sprint" carry and a matching (much subtler) camera
 * bob. Every input is smoothed with springs from the {@code anim} library, so starting, stopping and turning never
 * snap. Aiming down sights calms everything to a few percent.
 */
public final class ViewmodelMotion {
    /** Blocks travelled per walk cycle (two footsteps). */
    private static final float STRIDE = 2.7f;
    private static final float WALK_SPEED = 0.215f; // blocks per tick at normal walking pace

    private final Spring walk = new Spring(0).config(42, 12);
    private final Spring strafe = new Spring(0).config(60, 12);
    private final Spring forward = new Spring(0).config(60, 12);
    private final Spring sprint = new Spring(0).config(70, 14);
    private final Spring crouch = new Spring(0).config(110, 17);
    private final Spring airborne = new Spring(0).config(80, 11);
    private final Spring land = new Spring(0).config(150, 9);
    private final Spring lookYaw = new Spring(0).config(95, 13);
    private final Spring lookPitch = new Spring(0).config(95, 13);
    private float phase;
    private boolean wasOnGround = true;
    private boolean initialised;
    private float lastYaw, lastPitch;
    private double lastVerticalSpeed;
    private double time;
    private float cameraPitch, cameraYaw, cameraRoll;

    // ---- per frame ------------------------------------------------------------------------------

    public void update(float dt, Player player, boolean sprinting, float aim) {
        time += dt;
        Vec3 v = player.getDeltaMovement();
        double horizontal = Math.sqrt(v.x * v.x + v.z * v.z);
        boolean onGround = player.onGround();

        // movement relative to where the player looks: x = right, z = forward
        float yawRad = (float) Math.toRadians(player.getYRot());
        double fwdX = -Math.sin(yawRad), fwdZ = Math.cos(yawRad);
        double along = v.x * fwdX + v.z * fwdZ;
        double side = v.x * -fwdZ + v.z * fwdX;
        float speed = (float) (horizontal / WALK_SPEED);
        walk.setTarget(onGround ? Mth.clamp(speed, 0, 1.35f) : 0);
        forward.setTarget(Mth.clamp((float) (along / WALK_SPEED), -1, 1.3f));
        strafe.setTarget(Mth.clamp((float) (side / WALK_SPEED), -1, 1));
        sprint.setTarget(sprinting ? 1 : 0);
        crouch.setTarget(player.isCrouching() ? 1 : 0);
        airborne.setTarget(onGround ? 0 : Mth.clamp((float) (-v.y * 2.5), -1, 1));

        // the cycle advances with distance actually travelled, so steps match the feet at any speed
        if (onGround) phase += (float) (horizontal * 20 * dt / STRIDE * Math.PI * 2);
        else phase += dt * 1.2f; // drift slowly in the air instead of freezing mid-step
        if (phase > Math.PI * 64) phase -= (float) (Math.PI * 64);

        if (onGround && !wasOnGround) {
            // landing: the harder the fall, the deeper the dip
            land.impulse((float) -Math.min(2.6, Math.max(0.6, -lastVerticalSpeed * 7)));
        } else if (!onGround && wasOnGround && v.y > 0) {
            land.impulse(1.1f); // jump: the gun lags upwards first
        }
        wasOnGround = onGround;
        lastVerticalSpeed = v.y;

        // look inertia: the weapon trails fast camera turns
        float yaw = player.getViewYRot(1), pitch = player.getViewXRot(1);
        if (!initialised) {
            lastYaw = yaw;
            lastPitch = pitch;
            initialised = true;
        }
        float dYaw = Mth.wrapDegrees(yaw - lastYaw), dPitch = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;
        if (dt > 0 && Math.abs(dYaw) < 90) {
            float calm = 1 - 0.7f * aim;
            lookYaw.impulse(Mth.clamp(-dYaw * 7f, -160, 160) * calm);
            lookPitch.impulse(Mth.clamp(-dPitch * 7f, -160, 160) * calm);
        }
        updateCamera(aim);
    }

    private void updateCamera(float aim) {
        float w = Math.min(walk.get(), 1.2f) * (1 - sprint.get()), s = sprint.get();
        float calm = 1 - 0.7f * aim;
        float step = (float) Math.sin(phase), twice = (float) Math.sin(phase * 2);
        cameraPitch = (twice * 0.32f * w + twice * 0.55f * s) * calm + Math.max(0, -land.get()) * 1.6f - Math.max(0, land.get()) * 0.4f;
        cameraYaw = step * 0.12f * w * calm;
        cameraRoll = (step * 0.42f * w + step * 0.95f * s) * calm - strafe.get() * 0.55f * calm;
    }

    // ---- transforms -------------------------------------------------------------------------------

    /**
     * Screen-space offsets (applied before aiming): bob, breathing, landing, strafe lag.
     *
     * @param aim 0 = hip, 1 = aimed down sights
     */
    public void applyOffsets(Matrix4f root, float aim) {
        float hip = 1 - 0.9f * aim;
        float w = Math.min(walk.get(), 1.2f) * (1 - sprint.get());
        float s = sprint.get();
        float step = (float) Math.sin(phase);
        float dip = (float) (1 - Math.cos(phase * 2)) * 0.5f; // 0 at mid-stride, 1 on each foot contact
        float breathe = (float) Math.sin(time * 1.55);
        float x = step * 0.0062f * w * hip
                + step * 0.011f * s
                - strafe.get() * 0.0065f * hip
                - lookYaw.get() * 0.00011f * hip;
        float y = -dip * 0.0058f * w * hip
                - dip * 0.012f * s
                + breathe * 0.0011f * (1 - 0.65f * aim)
                + land.get() * 0.013f * (1 - 0.6f * aim)
                + airborne.get() * 0.006f * hip
                - crouch.get() * 0.007f * hip
                - lookPitch.get() * 0.00011f * hip;
        float z = Math.max(0, forward.get()) * 0.006f * hip * (1 - s);
        root.translate(x, y, z);
    }

    /**
     * Rotations about the weapon pivot (applied after aiming): bob roll/yaw, strafe lean, look inertia, crouch cant
     * and — without an authored sprint clip — the raised tactical-sprint carry.
     */
    public void applyRotations(Matrix4f root, float aim, boolean proceduralSprintPose) {
        float hip = 1 - 0.88f * aim;
        float w = Math.min(walk.get(), 1.2f) * (1 - sprint.get());
        float s = sprint.get();
        float step = (float) Math.sin(phase), twice = (float) Math.sin(phase * 2);
        if (proceduralSprintPose && s > 0.001f) {
            // pistol carried high: muzzle up beside the face, canted in, closer to the eye
            root.translate(-0.035f * s, 0.07f * s, 0.055f * s);
            root.rotateX((float) Math.toRadians(58 * s));
            root.rotateY((float) Math.toRadians(9 * s));
            root.rotateZ((float) Math.toRadians(15 * s));
        }
        float roll = step * 1.25f * w * hip + step * 2.8f * s
                - strafe.get() * 3.6f * hip
                + lookYaw.get() * 0.035f * hip
                + crouch.get() * 3f * hip;
        float yaw = (float) Math.cos(phase) * 0.55f * w * hip + lookYaw.get() * 0.05f * hip;
        float pitch = twice * 0.5f * w * hip + twice * 1.6f * s + lookPitch.get() * 0.05f * hip
                + land.get() * 2.6f * (1 - 0.6f * aim) + (float) Math.sin(time * 1.55 + 0.6) * 0.22f * (1 - 0.5f * aim);
        root.rotateZ((float) Math.toRadians(roll));
        root.rotateY((float) Math.toRadians(yaw));
        root.rotateX((float) Math.toRadians(pitch));
    }

    // ---- outputs ----------------------------------------------------------------------------------

    public float sprintAmount() {
        return sprint.get();
    }

    /** Time within a looping clip of {@code duration} seconds, locked to the walk cycle (one loop = two steps). */
    public float cycleTime(float duration) {
        double cycles = phase / (Math.PI * 2);
        return (float) ((cycles - Math.floor(cycles)) * duration);
    }

    public float cameraPitch() {
        return cameraPitch;
    }

    public float cameraYaw() {
        return cameraYaw;
    }

    public float cameraRoll() {
        return cameraRoll;
    }
}
