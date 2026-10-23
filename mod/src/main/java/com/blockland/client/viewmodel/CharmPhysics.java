package com.blockland.client.viewmodel;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * A charm hanging from its ring: a damped pendulum in world space, so it swings from everything the weapon really
 * does — turning the view, steps and jumps, recoil, reloads and knife flips — and settles hanging down. Verlet
 * integration at a fixed step.
 * <p>
 * The pendulum lives relative to its ring and feels gravity and the ring's acceleration, low-pass filtered. The ring's
 * raw motion is not smooth: the camera position is interpolated between game ticks (its velocity jumps twenty times
 * a second while walking) and mouse turns arrive as per-frame steps; a pendulum fed those kicks twitches. Filtering
 * the ring's velocity turns them into the accelerations a real charm would feel, and the acceleration passed on is
 * limited, so whipping the view around makes a swing, never a fling.
 */
public final class CharmPhysics {
    private static final double GRAVITY = 9.8;
    /** Velocity decay per second: a few visible swings, then it settles. */
    private static final double DAMPING = 6.0;
    private static final double STEP = 1 / 240.0;
    private static final int MAX_STEPS = 12;
    /** Furthest the charm swings away from its rest direction relative to the weapon (it would pass through it). */
    private static final double MAX_ANGLE = Math.toRadians(65);
    /** A ring jump this long in one frame is a teleport: start over instead of flinging the charm. */
    private static final double TELEPORT = 2.0;
    /** Time constant of each of the two ring velocity filters (s): removes per-tick and per-frame kicks, keeps real swings. */
    private static final double VELOCITY_SMOOTHING = 0.06;
    /** Accelerations below this (m/s²) are the small unevenness of walking: they do not swing the charm. */
    private static final double DEAD_ZONE = 2.0;
    /** The most ring acceleration passed on to the charm (m/s²). */
    private static final double MAX_ACCELERATION = 30.0;
    /** Share of the ring's acceleration the charm feels: a little calmer than a free pendulum, like a charm on a short cord. */
    private static final double RESPONSE = 0.45;
    /**
     * Shortest pendulum the motion is simulated with (m): a 2 cm charm would swing three times a second and flick
     * between frames; a slightly longer cord gives the weighty swing charms have in shooters.
     */
    private static final double MIN_SWING_LENGTH = 0.06;
    /** How quickly a charm pushed past its swing limit is eased back (share per step), instead of stopping dead. */
    private static final double LIMIT_SPRING = 0.25;

    private final double length;
    /** Direction from the ring to the hanging mass in the pivot's own space at rest. */
    private final Vector3f restDirection;

    private boolean started;
    /** The ring as the last frame left it, its filtered velocity, and the mass relative to the ring (now, before). */
    private final Vector3d ring = new Vector3d();
    private final Vector3d velocityRough = new Vector3d();
    private final Vector3d velocity = new Vector3d();
    private final Vector3d offset = new Vector3d();
    private final Vector3d offsetBefore = new Vector3d();
    private double carry;

    // scratch
    private final Vector3d ringAt = new Vector3d();
    private final Vector3d stepVelocity = new Vector3d();
    private final Vector3d acceleration = new Vector3d();
    private final Vector3d next = new Vector3d();
    private final Vector3d rest = new Vector3d();
    private final Vector3f f = new Vector3f();
    private final Vector3f g = new Vector3f();
    private final Quaternionf q = new Quaternionf();

    /**
     * @param massCenter centre of the swinging part in the pivot's own space (rest pose); its length is the pendulum
     *                   length, its direction where the charm hangs at rest
     */
    public CharmPhysics(Vector3f massCenter) {
        float len = massCenter.length();
        this.length = Math.max(MIN_SWING_LENGTH, len);
        this.restDirection = len < 1e-5f ? new Vector3f(0, -1, 0) : new Vector3f(massCenter).div(len);
    }

    public void reset() {
        started = false;
        carry = 0;
    }

    /**
     * Advances the pendulum and returns the pivot's local rotation for this frame.
     *
     * @param ringWorld     the ring (pivot origin) in world space this frame
     * @param parentToWorld rotation from the pivot's parent space to world space this frame
     * @param restLocal     the pivot's local rotation in the file (rest)
     * @param into          receives the local rotation
     */
    public Quaternionf step(double dt, Vector3d ringWorld, Quaternionf parentToWorld, Quaternionf restLocal, Quaternionf into) {
        // where the charm hangs when it does not move relative to the weapon, in world space
        restLocal.transform(restDirection, f);
        parentToWorld.transform(f);
        rest.set(f.x, f.y, f.z).normalize();
        if (!started || ring.distance(ringWorld) > TELEPORT) {
            started = true;
            ring.set(ringWorld);
            velocityRough.zero();
            velocity.zero();
            offset.set(rest).mul(length);
            offsetBefore.set(offset);
            carry = 0;
        }
        carry = Math.min(carry + Math.max(0, dt), STEP * MAX_STEPS);
        int steps = (int) (carry / STEP);
        carry -= steps * STEP;
        double keep = Math.exp(-DAMPING * STEP);
        double blend = 1 - Math.exp(-STEP / VELOCITY_SMOOTHING);
        for (int i = 1; i <= steps; i++) {
            // the ring's motion this step, spread evenly over the frame's steps, then filtered
            ring.lerp(ringWorld, 1.0 / (steps - i + 1), ringAt);
            stepVelocity.set(ringAt).sub(ring).div(STEP);
            velocityRough.lerp(stepVelocity, blend);
            acceleration.set(velocity);
            velocity.lerp(velocityRough, blend);
            acceleration.negate().add(velocity).div(STEP);
            double magnitude = acceleration.length();
            double passed = Math.min(MAX_ACCELERATION, Math.max(0, magnitude - DEAD_ZONE)) * RESPONSE;
            acceleration.mul(magnitude > 1e-9 ? passed / magnitude : 0);
            // Verlet in the ring's frame: gravity minus the ring's acceleration (the charm lags behind the ring)
            next.set(offset).sub(offsetBefore).mul(keep).add(offset);
            next.add(-acceleration.x * STEP * STEP, (-GRAVITY - acceleration.y) * STEP * STEP, -acceleration.z * STEP * STEP);
            constrain(next);
            offsetBefore.set(offset);
            offset.set(next);
            ring.set(ringAt);
        }
        ring.set(ringWorld);
        constrain(offset);

        // the pendulum direction back in the pivot's parent space, as a rotation of the rest pose
        next.set(offset).normalize();
        f.set((float) next.x, (float) next.y, (float) next.z);
        parentToWorld.invert(q).transform(f);
        restLocal.transform(restDirection, g);
        q.rotationTo(g, f);
        return into.set(q).mul(restLocal);
    }

    /**
     * Keeps the mass at the pendulum length from the ring; past the swing cone around its rest direction it is eased
     * back towards the cone (a hard stop would show as a jump).
     */
    private void constrain(Vector3d point) {
        double len = point.length();
        if (len < 1e-9) point.set(rest);
        else point.div(len);
        double cos = point.dot(rest);
        if (cos < Math.cos(MAX_ANGLE)) {
            // the point on the cone in the same swing plane
            Vector3d side = new Vector3d(point).sub(new Vector3d(rest).mul(cos));
            if (side.lengthSquared() < 1e-12) side.set(1, 0, 0);
            side.normalize();
            Vector3d onCone = new Vector3d(rest).mul(Math.cos(MAX_ANGLE)).add(side.mul(Math.sin(MAX_ANGLE)));
            // far past the limit (a teleport-like jerk) it is put back at once
            if (cos < Math.cos(MAX_ANGLE + Math.toRadians(25))) point.set(onCone);
            else point.lerp(onCone, LIMIT_SPRING).normalize();
        }
        point.mul(length);
    }
}
