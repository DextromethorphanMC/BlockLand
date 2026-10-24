package com.blockland.client.viewmodel;

import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.camera.CameraRig;
import com.blockland.common.Handling;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Procedural first-person motion in the style of modern shooters, replacing Minecraft's view bobbing while a weapon
 * is held. Everything is driven by springs (the {@code anim} library), so starting, stopping and turning never snap,
 * and the weapon's {@link Handling} sets how heavy it feels:
 * <ul>
 *   <li>a figure-eight weapon bob and a subtle camera bob locked to the footsteps, damped when aiming;</li>
 *   <li>look sway — the weapon trails fast turns (it shifts and turns against the turn, banking into it) and swings
 *       back — and movement inertia: it lags behind acceleration and swings through when the player stops (heavier
 *       weapons further and slower);</li>
 *   <li>breathing that deepens and quickens after sprinting and calms down again, a few percent while aiming;</li>
 *   <li>jump / landing response, crouch offset, strafe lean;</li>
 *   <li>locomotion weights for the rig's clips: walk, the lowered running carry and the raised tactical sprint
 *       (tactical at the start of a sprint, for {@code tacticalSprint} seconds, then the run). Leaving a sprint takes
 *       {@code sprintOut} seconds, so firing and aiming out of a run stay responsive. Going in and out of a carry the
 *       weapon swings through a dip and a roll instead of sliding straight, and coming out it settles with a bounce;</li>
 *   <li>aiming down sights: the weapon swings in on an arc (a cant, a dip, a pull towards the cheek half-way) and
 *       settles with a small dip;</li>
 *   <li>whole-body moves from outside ({@link BodyMotion}): in a slide the weapon tilts and drops low;</li>
 *   <li>swimming ({@link SwimPose}): the weapon rests in a low carry riding the strokes, the free arm out of view, and
 *       comes up quickly whenever the hands work it or it is aimed;</li>
 *   <li>the hands working the weapon (spec §31.4): clip events shove the whole rig — weapon, both arms, camera — by
 *       {@link ViewmodelImpulses}, so a slap jolts the hand that only holds the weapon too;</li>
 *   <li>weight and rotational inertia: the weapon's mass and its reach (eye to muzzle, measured from the model) set how
 *       far and how slowly it trails turns, moves and shoves — a long rifle swings wider and settles later than a pistol;</li>
 *   <li>the surroundings: a wall in front pushes the weapon back and tips it up out of the way (aiming still brings the
 *       sights up: the tuck yields to the aim, and the weapon fires from either pose), something close on the right
 *       tucks it in, looking down or up shifts it, jumping leaves it behind, landing compresses the arms, crouching and
 *       starting a sprint jolt it.</li>
 * </ul>
 * Everything runs on springs and elapsed time, so it looks the same at any frame rate.
 */
public final class ViewmodelMotion {
    /** Blocks travelled per walk cycle (two footsteps). */
    private static final float STRIDE = 2.7f;
    private static final float WALK_SPEED = 0.215f; // blocks per tick at normal walking pace

    /** Whole-body moves the weapon follows, supplied by the movement module (wired by the client entry point). */
    public interface BodyMotion {
        BodyMotion NONE = () -> 0;

        /** 0..1 how far into a slide the local player is. */
        float slide();
    }

    private static BodyMotion body = BodyMotion.NONE;

    public static void setBodyMotion(BodyMotion motion) {
        body = motion == null ? BodyMotion.NONE : motion;
    }

    private Handling handling = new Handling();
    /** Where the body's jolts go (landings, footfalls, pushing off, stopping, the hands' shoves). */
    private final CameraRig camera;
    private int lastStep;

    public ViewmodelMotion(CameraRig camera) {
        this.camera = camera;
    }

    private final Spring walk = new Spring(0).config(42, 12);
    private final Spring strafe = new Spring(0).config(60, 12);
    private final Spring forward = new Spring(0).config(60, 12);
    private final Spring run = new Spring(0).config(70, 14);
    private final Spring tactical = new Spring(0).config(70, 14);
    private final Spring crouch = new Spring(0).config(110, 17);
    private final Spring airborne = new Spring(0).config(80, 11);
    private final Spring land = new Spring(0).config(150, 9);
    private final Spring lookYaw = new Spring(0).config(95, 13);
    private final Spring lookPitch = new Spring(0).config(95, 13);
    private final Spring inertiaSide = new Spring(0).config(90, 11);
    private final Spring inertiaForward = new Spring(0).config(90, 11);
    private final Spring adsSettle = new Spring(0).config(260, 14);
    private final Spring slide = new Spring(0).config(120, 16);
    /** Out of a sprint carry: the weapon arrives at the ready with a little bounce. */
    private final Spring sprintSettle = new Spring(0).config(240, 13);
    /** Shoves of the hands working the weapon and of body moves: mm and degrees about the pivot, camera degrees. */
    private final Spring pushBack = new Spring(0), pushUp = new Spring(0), pushSide = new Spring(0);
    private final Spring pushPitch = new Spring(0), pushYaw = new Spring(0), pushRoll = new Spring(0);
    /** 0..1 how hard a wall in front presses the weapon back / something on the right tucks it in; view pitch -1..1. */
    private final Spring blocked = new Spring(0).config(110, 19), tucked = new Spring(0).config(90, 17);
    private final Spring lookDown = new Spring(0).config(60, 14);
    /** The swimming pose's layer: the carry, the strokes, the weapon brought up to be used. */
    private final SwimPose swim = new SwimPose();
    /** Eye to muzzle (or blade tip) in the rest pose, metres; rotational inertia relative to a pistol. */
    private float reach = 0.45f, inertia = 1;
    private boolean wasCrouching;
    private float lastSprint;
    private float phase;
    private boolean wasOnGround = true;
    private boolean initialised;
    private float lastYaw, lastPitch;
    private double lastVerticalSpeed;
    private double lastSide, lastAlong;
    private double time;
    /** 0..1: how out of breath the player is (sprinting raises it, resting lowers it). */
    private float exertion;
    private double breathPhase;
    /** 0..1 of the tactical sprint left; the sprint drops to the lowered run when it runs out. */
    private float tacticalLeft = 1;
    private boolean sprintWasTactical;
    private boolean wasSprinting;
    private float lastAim;
    private boolean walkClip;
    private float cameraPitch, cameraYaw, cameraRoll;

    /** The weapon's feel; springs are re-tuned to its weight. */
    public void setHandling(Handling handling) {
        this.handling = handling == null ? new Handling() : handling;
        retune();
    }

    /** Eye to muzzle (or blade tip) in the rest pose, measured from the model: the lever arm of its rotational inertia. */
    public void setReach(float reach) {
        this.reach = Mth.clamp(reach, 0.25f, 1.6f);
        retune();
    }

    /**
     * Mass sets how far the weapon moves under a shove or an acceleration, mass x lever arm how it turns: the look sway
     * follows the rotational inertia (a 1 m rifle trails a turn far wider and settles slower than a pistol).
     */
    private void retune() {
        float weight = Math.max(0.3f, handling.weight);
        inertia = Mth.clamp(weight * (float) Math.pow(reach / 0.42f, 1.2), 0.4f, 10f);
        // heavier: lower stiffness (trails further) and relatively less damping (swings back longer)
        float k = 1 / (float) Math.sqrt(weight);
        float turn = (float) Math.pow(inertia, -0.7), turnDamp = (float) Math.pow(inertia, -0.35);
        lookYaw.config(95 * turn, 13 * turnDamp);
        lookPitch.config(95 * turn, 13 * turnDamp);
        inertiaSide.config(90 * k * k, 11 * k);
        inertiaForward.config(90 * k * k, 11 * k);
        land.config(150 * k * k, 9 * k);
        // shoves ring at ~6 Hz on a pistol, lower and longer on heavy weapons
        float push = 38 * k, spin = 32 / (float) Math.sqrt(inertia);
        for (Spring sp : new Spring[]{pushBack, pushUp, pushSide}) sp.config(push * push, 2 * 0.38f * push);
        for (Spring sp : new Spring[]{pushPitch, pushYaw, pushRoll}) sp.config(spin * spin, 2 * 0.34f * spin);
        // leaving a sprint: critically damped within the sprint-out time
        float omega = 4.6f / Math.max(0.08f, handling.sprintOut);
        run.config(omega * omega, 2 * omega * 0.92f);
        tactical.config(omega * omega, 2 * omega * 0.92f);
    }

    /**
     * The hands worked the weapon (a clip event): the whole rig takes the shove, less the heavier the weapon; the camera
     * (the body) reacts along.
     */
    public void react(ViewmodelImpulses.Impulse impulse) {
        float s = 1 / (float) Math.sqrt(Math.max(0.3f, handling.weight)), r = 1 / (float) Math.sqrt(inertia);
        pushBack.punch(impulse.back * s);
        pushUp.punch(impulse.up * s);
        pushSide.punch(impulse.side * s);
        pushPitch.punch(impulse.pitch * r);
        pushYaw.punch(impulse.yaw * r);
        pushRoll.punch(impulse.roll * r);
        camera.kick(impulse.camera[0], impulse.camera[1], impulse.camera[2], 0.65f);
        camera.shake(0.12f * impulse.shake, impulse.shake);
    }

    /** 0..1 how hard a wall in front presses the weapon back (the pose yields to aiming; nothing waits on it). */
    public float obstruction() {
        return Mth.clamp(blocked.get(), 0, 1);
    }

    /** The rig has an authored walk loop: the procedural bob steps back so they do not add up. */
    public void setWalkClip(boolean walkClip) {
        this.walkClip = walkClip;
    }

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
        crouch.setTarget(player.isCrouching() ? 1 : 0);
        if (player.isCrouching() != wasCrouching && initialised) {
            // the body drops (or rises) under the weapon: it lags a beat, then follows
            pushUp.punch((player.isCrouching() ? 3.2f : -2.4f) / (float) Math.sqrt(Math.max(0.3f, handling.weight)));
            camera.kick(player.isCrouching() ? -0.35f : 0.25f, 0, 0, 0.35f);
            camera.nudge(0, player.isCrouching() ? -6 : 4, 0);
        }
        wasCrouching = player.isCrouching();
        sense(player, aim);
        slide.setTarget(body.slide());
        airborne.setTarget(onGround ? 0 : Mth.clamp((float) (-v.y * 2.5), -1, 1));

        // inertia: the weapon lags behind changes of velocity (starting, stopping, strafing)
        if (dt > 0 && initialised) {
            float push = 22 * handling.inertia * (float) Math.sqrt(Math.max(0.3f, handling.weight));
            inertiaSide.impulse((float) -(side - lastSide) / WALK_SPEED * push * (1 - 0.8f * aim));
            inertiaForward.impulse((float) -(along - lastAlong) / WALK_SPEED * push * (1 - 0.8f * aim));
            // the body pitches into a stop and leans out of a strafe change, then the slow camera layer settles it
            float body = handling.inertia * (float) Math.sqrt(Math.max(0.3f, handling.weight)) * (1 - 0.6f * aim);
            camera.lean((float) -(along - lastAlong) / WALK_SPEED * 13f * body, (float) (side - lastSide) / WALK_SPEED * 11f * body);
        }
        lastSide = side;
        lastAlong = along;

        if (sprinting && !wasSprinting && initialised) {
            // pushing off into a sprint: the weapon is left behind for a moment
            pushBack.punch(6f * (float) Math.sqrt(Math.max(0.3f, handling.weight)));
            pushPitch.punch(-2f);
            camera.kick(-0.5f, 0, 0, 0.3f);
            camera.nudge(0, 0, 8);
        }
        updateSprint(dt, sprinting);
        float carry = sprintAmount();
        if (lastSprint > 0.2f && carry <= 0.2f) sprintSettle.impulse(-1.1f * (float) Math.sqrt(Math.max(0.3f, handling.weight)));
        lastSprint = carry;
        exertion = Mth.clamp(exertion + (sprinting ? dt / 5f : -dt / 9f), 0, 1);

        // the cycle advances with distance actually travelled, so steps match the feet at any speed; stepping backwards
        // or sideways takes shorter steps than walking forwards
        float[] dir = directionShares();
        float stride = STRIDE * (dir[0] + 0.72f * dir[1] + 0.84f * dir[2]);
        if (onGround) phase += (float) (horizontal * 20 * dt / stride * Math.PI * 2);
        else phase += dt * 1.2f; // drift slowly in the air instead of freezing mid-step
        if (phase > Math.PI * 64) phase -= (float) (Math.PI * 64);

        float heft = (float) Math.sqrt(Math.max(0.3f, handling.weight));
        float foot = phase % 6.2832f < 3.1416f ? 1 : -1;
        if (onGround && !wasOnGround) {
            // landing: the arms compress under the weapon's weight, the harder the fall the deeper; the muzzle dips and
            // the body's jolt reaches the camera
            float hard = (float) Math.min(2.6, Math.max(0.6, -lastVerticalSpeed * 7));
            land.impulse(-hard * heft);
            pushPitch.punch(-1.6f * hard * heft / (float) Math.sqrt(inertia));
            pushRoll.punch(foot * 0.8f * hard);
            camera.kick(-0.6f * hard, 0, -foot * 0.4f * hard, 0.45f);
            camera.nudge(0, -9f * hard * heft, 0);
            camera.shake(0.06f * hard, 0.2f * hard);
        } else if (!onGround && wasOnGround && v.y > 0) {
            // jump: the body pushes up and the weapon is left behind (dips), then floats while airborne
            land.impulse(-0.9f * heft);
            pushPitch.punch(-0.8f * heft);
            camera.kick(0.3f, 0, 0, 0.4f);
            camera.nudge(0, -3, 0);
        }
        // a footfall lands on the camera as a heel strike (a rhythm, not a sine), harder when running, and each direction
        // has its own: forwards a nod and a sway over the foot, backwards a short choppy bump that knocks the head back,
        // sideways the leading foot carries the head across and the trailing one gathers it
        int step = (int) Math.floor(phase / Math.PI);
        if (step != lastStep && onGround && initialised) {
            float pace = Math.min(walk.get(), 1.3f) * (1 - sprintAmount()) + 1.8f * sprintAmount();
            if (pace > 0.25f) {
                float k = pace * (1 - 0.75f * aim);
                float lead = step % 2 == 0 ? 1 : -1, toward = Math.signum(strafe.get());
                float f = dir[0], b = dir[1], sd = dir[2];
                camera.kick((-0.27f * f - 0.22f * b - 0.12f * sd) * k, 0,
                        (lead * (0.22f * f + 0.12f * b + 0.1f * sd) - toward * 0.16f * sd) * k, 0.6f + 0.25f * b);
                camera.nudge(toward * sd * (lead > 0 ? 2.8f : 1.1f) * k, (-1.9f * f - 1.5f * b - 1.1f * sd) * k, 1.6f * b * k);
                if (sprintAmount() > 0.5f) camera.shake(0, 0.07f * pace);
            }
        }
        lastStep = step;
        wasOnGround = onGround;
        lastVerticalSpeed = v.y;

        // look sway: the weapon trails fast camera turns and swings back
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
            // turn rate in, clamped per second (frame-rate independent); the rotational inertia is in the springs
            float calm = (1 - 0.7f * swim.aimCalm(aim)) * handling.sway;
            lookYaw.impulse(Mth.clamp(-dYaw / dt * 7f, -9600, 9600) * dt * calm);
            lookPitch.impulse(Mth.clamp(-dPitch / dt * 7f, -9600, 9600) * dt * calm);
        }
        lookDown.setTarget(Mth.clamp(pitch / 90f, -1, 1));

        // reaching the sights: a small settle
        if (aim >= 0.97f && lastAim < 0.97f) adsSettle.impulse(-0.9f);
        lastAim = aim;

        // breathing: slower and shallower at rest, quick and deep after a sprint
        breathPhase += dt * (1.55 + 2.2 * exertion);
        swim.update(dt, player, aim);
        updateCamera(aim);
    }

    private void updateSprint(float dt, boolean sprinting) {
        if (sprinting) {
            if (!wasSprinting) sprintWasTactical = tacticalLeft > 0.25f;
            if (sprintWasTactical) {
                tacticalLeft -= dt / Math.max(0.1f, handling.tacticalSprint);
                if (tacticalLeft <= 0) sprintWasTactical = false;
            }
        } else {
            tacticalLeft = Math.min(1, tacticalLeft + dt / Math.max(0.1f, handling.tacticalSprint * 2));
        }
        wasSprinting = sprinting;
        tactical.setTarget(sprinting && sprintWasTactical ? 1 : 0);
        run.setTarget(sprinting && !sprintWasTactical ? 1 : 0);
    }

    /**
     * The locomotion camera, with a character per direction of travel (shares from {@link #directionShares}): forwards
     * a deep nod twice per cycle, a roll and a sway over each foot and a lean into the walk; backwards shorter, choppier
     * steps (a sharper wave), less nod and the body held back; sideways little nod, a strong lean into the strafe and
     * the head carried across over the leading foot. Sprints deepen everything and lean further in.
     */
    private void updateCamera(float aim) {
        float w = walkAmount(), s = run.get(), tac = tactical.get();
        float calm = 1 - 0.7f * aim;
        float bob = calm * handling.bob;
        float step = (float) Math.sin(phase), twice = (float) Math.sin(phase * 2);
        float[] dir = directionShares();
        float f = dir[0], b = dir[1], sd = dir[2];
        // backwards the steps snap: the same rhythm with sharper turns
        float chop = Math.signum(twice) * (float) Math.pow(Math.abs(twice), 0.55);
        float nod = (twice * 0.4f * f + chop * 0.24f * b + twice * 0.16f * sd) * w + twice * 0.62f * s + twice * 0.78f * tac;
        float roll = (step * 0.66f * f + step * 0.34f * b + step * 0.5f * sd) * w + step * 1.4f * s + step * 1.75f * tac;
        float turn = (step * 0.2f * f + step * 0.1f * b + step * 0.06f * sd) * w + step * 0.16f * (s + tac);
        // leaning with the movement: into a forward walk or run (positive = looking down), back when backpedalling
        float lean = (0.5f * f - 0.4f * b) * Math.min(1, w) + 0.85f * s + 1.05f * tac;
        float breath = (float) Math.sin(breathPhase) * (0.06f + 0.16f * exertion) * (1 - 0.6f * aim);
        cameraPitch = nod * bob + lean * calm
                + Math.max(0, -land.get()) * 0.8f - Math.max(0, land.get()) * 0.3f + breath;
        cameraYaw = turn * bob;
        cameraRoll = roll * bob - strafe.get() * 1.2f * calm;
        // the head: lowest on each footfall, swaying over the stance foot; strafing carries it across
        float sway = (1.4f * f + 1.0f * b + 3.0f * sd) * w + 2.4f * s + 3.0f * tac;
        float rise = (2.4f * f + 1.5f * b + 1.1f * sd) * w + 4.2f * s + 5.2f * tac;
        camera.bob(step * sway * bob, -(float) Math.cos(phase * 2) * rise * bob, 0);
    }

    /**
     * How the player moves relative to the view: shares of forwards, backwards and sideways that add up to 1 (all
     * forwards when standing still, so the rhythm starts as a walk).
     */
    private float[] directionShares() {
        float f = Math.max(0, forward.get()), b = Math.max(0, -forward.get()), sd = Math.abs(strafe.get());
        float sum = f + b + sd;
        if (sum < 0.05f) return new float[]{1, 0, 0};
        return new float[]{f / sum, b / sum, sd / sum};
    }

    /**
     * Probes the surroundings along the weapon: the view line and a line from where the weapon sits (low right) out to
     * its reach, and a short line to the right. Walls set the push-back and tuck springs.
     */
    private void sense(Player player, float aim) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Level level = player.level();
        if (!camera.isInitialized() || level == null) return;
        Vec3 eye = camera.getPosition();
        Vector3f look = camera.getLookVector(), up = camera.getUpVector(), left = camera.getLeftVector();
        Vec3 forward = new Vec3(look.x, look.y, look.z);
        Vec3 hand = eye.add(-left.x * 0.10 - up.x * 0.08, -left.y * 0.10 - up.y * 0.08, -left.z * 0.10 - up.z * 0.08);
        float span = reach + 0.12f;
        double free = Math.min(free(level, player, eye, forward, span), free(level, player, hand, forward, span));
        blocked.setTarget(Mth.clamp((float) (span - free) / (reach * 0.65f), 0, 1));
        Vec3 from = eye.add(forward.scale(0.25));
        double side = free(level, player, from, new Vec3(-left.x, -left.y, -left.z), 0.38);
        tucked.setTarget(Mth.clamp((float) (0.38 - side) / 0.26f, 0, 1) * (1 - 0.8f * aim));
    }

    /** Distance along {@code dir} (unit) to the first block collision, at most {@code max}. */
    private static double free(Level level, Player player, Vec3 from, Vec3 dir, double max) {
        Vec3 to = from.add(dir.scale(max));
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS ? max : hit.getLocation().distanceTo(from);
    }

    private float walkAmount() {
        return Math.min(walk.get(), 1.2f) * (1 - sprintAmount());
    }

    // ---- transforms -------------------------------------------------------------------------------

    /**
     * Screen-space offsets (applied before aiming): bob, breathing, inertia, landing, strafe lag, look lag.
     *
     * @param aim 0 = hip, 1 = aimed down sights
     */
    public void applyOffsets(Matrix4f root, float aim) {
        float hip = 1 - 0.9f * aim;
        float bob = handling.bob * (walkClip ? 0.4f : 1f);
        float w = walkAmount() * bob, s = Math.max(run.get(), tactical.get()) * handling.bob;
        float step = (float) Math.sin(phase);
        float dip = (float) (1 - Math.cos(phase * 2)) * 0.5f; // 0 at mid-stride, 1 on each foot contact
        float breathe = (float) Math.sin(breathPhase) * (1 + 1.6f * exertion);
        float sl = slide.get();
        float swing = adsSwing(aim), runArc = arc(run.get()), tacArc = arc(tactical.get());
        float wall = wallPress() * (1 - aim), tuck = Mth.clamp(tucked.get(), 0, 1), down = lookDown.get();
        float x = step * 0.0062f * w * hip
                + step * 0.011f * s
                - strafe.get() * 0.0065f * hip
                + soft(lookYaw.get(), 40) * 0.00028f * hip
                + inertiaSide.get() * 0.0016f * hip
                - sl * 0.010f * hip
                + swing * 0.003f + runArc * 0.006f - tacArc * 0.004f
                + pushSide.get() * 0.001f - wall * 0.015f - tuck * 0.03f;
        float y = -dip * 0.0058f * w * hip
                - dip * 0.012f * s
                + breathe * 0.0011f * (1 - 0.65f * aim)
                + land.get() * 0.013f * (1 - 0.6f * aim)
                + airborne.get() * 0.006f * hip
                - crouch.get() * 0.007f * hip
                - soft(lookPitch.get(), 40) * 0.00028f * hip
                + adsSettle.get() * 0.004f
                - sl * 0.022f * hip
                - swing * 0.007f - runArc * 0.012f - tacArc * 0.016f + sprintSettle.get() * 0.006f
                + pushUp.get() * 0.001f - wall * 0.02f
                + (Math.max(0, down) * 0.010f - Math.max(0, -down) * 0.012f) * hip;
        float z = Math.max(0, forward.get()) * 0.006f * hip * (1 - s)
                - inertiaForward.get() * 0.0018f * hip
                + sl * 0.012f * hip
                + swing * 0.008f
                + pushBack.get() * 0.001f + wall * 0.055f
                + (-Math.max(0, down) * 0.006f + Math.max(0, -down) * 0.006f) * hip;
        root.translate(x, y, z);
        swim.applyOffsets(root, aim);
    }

    /** How far the wall pose is in: the push-back spring eased (anim library), so the weapon tips away smoothly. */
    private float wallPress() {
        return (float) Ease.SINE_IN_OUT.apply(Mth.clamp(blocked.get(), 0, 1));
    }

    /** 0 at the hip and at the sights, 1 half-way: how far into its arc the weapon is on its way to the sights. */
    private float adsSwing(float aim) {
        return 4 * aim * (1 - aim) * handling.adsTilt / 6f;
    }

    /** 0 at both ends of a blend weight, 1 half-way (clamped: springs may overshoot a hair). */
    private static float arc(float w) {
        float u = Mth.clamp(w, 0, 1);
        return 4 * u * (1 - u);
    }

    /** Smoothly limits a sway value to about ±limit (fast flicks do not throw the weapon off the screen). */
    private static float soft(float v, float limit) {
        return limit * (float) Math.tanh(v / limit);
    }

    /**
     * Rotations about the weapon pivot (applied after aiming): bob roll/yaw, strafe lean, look sway, inertia tilt,
     * crouch cant, the ADS cant-in and — without authored sprint clips — the sprint carries.
     *
     * @param proceduralSprint the rig has no sprint clips: pose the carries here
     */
    public void applyRotations(Matrix4f root, float aim, boolean proceduralSprint) {
        float hip = 1 - 0.88f * aim;
        float w = walkAmount() * handling.bob * (walkClip ? 0.4f : 1f);
        float s = run.get(), tac = tactical.get();
        float step = (float) Math.sin(phase), twice = (float) Math.sin(phase * 2);
        if (proceduralSprint) {
            if (tac > 0.001f) {
                // tactical sprint: weapon carried high, muzzle up beside the face
                root.translate(-0.035f * tac, 0.07f * tac, 0.055f * tac);
                root.rotateX((float) Math.toRadians(58 * tac));
                root.rotateY((float) Math.toRadians(9 * tac));
                root.rotateZ((float) Math.toRadians(15 * tac));
            }
            if (s > 0.001f) {
                // run: lowered and canted towards the chest
                root.translate(-0.02f * s, -0.045f * s, 0.035f * s);
                root.rotateX((float) Math.toRadians(-22 * s));
                root.rotateY((float) Math.toRadians(28 * s));
                root.rotateZ((float) Math.toRadians(-18 * s));
            }
        }
        float sprinting = Math.max(s, tac) * handling.bob;
        // the weapon cants in on the way up to the sights and back out (peaks halfway)
        float cant = 4 * aim * (1 - aim) * handling.adsTilt;
        // sliding: the weapon tips over towards the body and turns in a little
        float sl = slide.get();
        float runArc = arc(s), tacArc = arc(tac);
        float lagYaw = soft(lookYaw.get(), 40), lagPitch = soft(lookPitch.get(), 40);
        float wall = wallPress() * (1 - aim), tuck = Mth.clamp(tucked.get(), 0, 1), down = lookDown.get();
        // look sway lags: turning right the muzzle trails left and the weapon banks into the turn; looking up it trails low
        float roll = step * 1.25f * w * hip + step * 2.8f * sprinting
                - strafe.get() * 3.6f * hip
                + lagYaw * 0.10f * hip
                + crouch.get() * 3f * hip
                - inertiaSide.get() * 0.12f * hip
                - cant
                + sl * 11f * (1 - 0.7f * aim)
                + tacArc * 6f - runArc * 4f
                + pushRoll.get() + wall * 18f + tuck * 10f;
        float yaw = (float) Math.cos(phase) * 0.55f * w * hip - lagYaw * 0.18f * hip + cant * 0.35f + sl * 3f * hip
                + pushYaw.get() + wall * 10f + tuck * 4f;
        float breathe = (float) Math.sin(breathPhase + 0.6) * (0.22f + 0.35f * exertion);
        float pitch = twice * 0.5f * w * hip + twice * 1.6f * sprinting - lagPitch * 0.16f * hip
                + land.get() * 2.6f * (1 - 0.6f * aim) + breathe * (1 - 0.5f * aim)
                + inertiaForward.get() * 0.09f * hip - cant * 0.4f + adsSettle.get() * 0.6f + sl * 2.5f * hip
                - runArc * 3f + sprintSettle.get() * 1.4f
                + pushPitch.get() + wall * 38f + (-Math.max(0, down) * 3f + Math.max(0, -down) * 2f) * hip;
        root.rotateZ((float) Math.toRadians(roll));
        root.rotateY((float) Math.toRadians(yaw));
        root.rotateX((float) Math.toRadians(pitch));
        swim.applyRotations(root, aim);
    }

    // ---- outputs ----------------------------------------------------------------------------------

    /** The swimming layer (its carry style; the rig tells it when the hands work the weapon). */
    public SwimPose swim() {
        return swim;
    }

    /** 0..1 how far into a sprint carry (run or tactical) the weapon is; firing waits until it is lowered. */
    public float sprintAmount() {
        return Math.max(run.get(), tactical.get());
    }

    /** Clip weights for the rig: the walk loop, the lowered run and the raised tactical sprint. */
    public float walkWeight() {
        return Mth.clamp(walkAmount(), 0, 1);
    }

    public float runWeight() {
        return Mth.clamp(run.get(), 0, 1);
    }

    public float tacticalWeight() {
        return Mth.clamp(tactical.get(), 0, 1);
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
