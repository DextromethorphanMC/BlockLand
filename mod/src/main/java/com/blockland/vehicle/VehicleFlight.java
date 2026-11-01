package com.blockland.vehicle;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Flight (vehicle spec §4.1): one tick of an aircraft from its pilot's input, the same on the server and in the pilot's
 * prediction — plain maths on the hull's state; {@link VehicleEntity} moves the result through the world.
 *
 * <p>Jet: thrust along the nose (W full, nothing cruise, S idle, sprint the afterburner), drag growing with the square
 * of the speed (full thrust in level flight is {@code maxSpeed}; S also opens the speed brakes a little); climbing
 * trades speed for height, diving the other
 * way. The nose turns towards where the pilot looks — pitch at {@code pitchRate}, yaw at {@code turnRate}, banking
 * into the turn; A/D work the rudder. Below {@code takeoffSpeed} the wings carry less and less: the nose drops and it
 * sinks. On the ground it rolls on its wheels (A/D steer, space brakes, S backs up slowly) and rotates off the runway
 * once fast enough while the pilot looks up.
 *
 * <p>Helicopter: the nose turns to the pilot's view; W/S fly forward and back, A/D sideways, jump climbs, sprint sinks,
 * nothing held hovers where it is; the hull tips into its acceleration. Without a pilot the rotor freewheels and it
 * falls.
 */
final class VehicleFlight {
    /** Seconds per tick. */
    private static final double DT = 0.05;
    /** Blocks/s² along the nose from gravity per unit of sin(pitch): what climbing costs and diving gives. */
    private static final double SLOPE = 14;
    /** Blocks/s² an aircraft sinks with nothing holding it up (vanilla's gravity is about 32). */
    private static final double FALL = 24;
    /** Share of a jet's thrust that speeds it up on the ground. */
    private static final double GROUND_THRUST = 0.5;

    /** The hull after the tick: velocity (blocks/tick), yaw, pitch (+ nose up), roll (+ right side up). */
    record Step(Vec3 velocity, float yaw, float pitch, float roll) {
    }

    private VehicleFlight() {
    }

    /** The nose's direction for a yaw (Minecraft degrees) and pitch (+ = up). */
    static Vec3 nose(float yaw, float pitch) {
        Vec3 f = VehicleFrame.forward(yaw);
        double p = Math.toRadians(pitch), c = Math.cos(p);
        return new Vec3(f.x * c, Math.sin(p), f.z * c);
    }

    // ---- jet ------------------------------------------------------------------------------------

    static Step jet(VehicleData.Movement m, VehicleControl c, Vec3 v, float yaw, float pitch, float roll, boolean ground, boolean engine) {
        Vec3 n = nose(yaw, pitch);
        double along = v.dot(n) * 20, speed = v.length() * 20;
        double max = Math.max(1, m.maxSpeed);
        if (ground) {
            // on its wheels it gathers speed more slowly than in the air (rolling drag, the engine spooling)
            double thrust = !engine ? 0 : c.boost() ? m.boostThrust : c.throttle() > 0.01f ? c.throttle() : 0;
            // rolling: the speed along the heading (raising the nose does not slow the wheels)
            Vec3 heading = VehicleFrame.forward(yaw);
            double s = (v.x * heading.x + v.z * heading.z) * 20 + thrust * m.accel * GROUND_THRUST * DT;
            double friction = 1.2 + (c.brake() ? m.brake : 0) + (c.throttle() < -0.01f && s > 1 ? m.brake * 0.5 : 0);
            s -= Math.signum(s) * Math.min(Math.abs(s), friction * DT + m.accel * sq(s / max) * DT);
            if (engine && c.throttle() < -0.01f && s <= 1) s = Math.max(-m.reverseSpeed, s - 3 * DT);
            // the nose wheel steers, less as it speeds up
            double grip = Mth.clamp(Math.abs(s) / 3, 0, 1) * (1 - 0.6 * Mth.clamp(Math.abs(s) / Math.max(1, m.takeoffSpeed), 0, 1));
            yaw += (float) (m.taxiTurn * c.steer() * grip * Math.signum(s) * DT);
            // rotating: fast enough and the pilot looks up
            boolean rotate = engine && s >= m.takeoffSpeed * 0.95 && c.lookPitch() < -3;
            float targetPitch = rotate ? Mth.clamp(-c.lookPitch(), 0, 14) : 0;
            pitch = Mth.approach(pitch, targetPitch, (float) ((rotate ? m.pitchRate * 0.5 : 30) * DT));
            roll = Mth.approach(roll, 0, (float) (60 * DT));
            double climb = s >= m.takeoffSpeed && pitch > 0 ? Math.sin(Math.toRadians(pitch)) * s : 0;
            Vec3 f = VehicleFrame.forward(yaw);
            double vy = climb > 0 ? climb / 20 : v.y - 0.04;
            return new Step(new Vec3(f.x * s / 20, vy, f.z * s / 20), yaw, pitch, roll);
        }
        boolean slow = c.throttle() < -0.01f;
        double thrust = !engine || slow ? 0 : c.boost() ? m.boostThrust : c.throttle() > 0.01f ? 1 : 0.6;
        // S: idle and the speed brakes out a little; space: all the way
        double drag = m.accel * sq(along / max) * (c.brake() ? 1.8 : 1) + (c.brake() ? 3 : slow ? 2.5 : 0);
        double s = Math.max(0, along + (thrust * m.accel - drag - SLOPE * Math.sin(Math.toRadians(pitch))) * DT);
        // how much the wings carry, and how well the controls bite
        double lift = sq(Mth.clamp(speed / Math.max(1, m.takeoffSpeed), 0, 1));
        double authority = Mth.clamp((speed - 0.3 * m.takeoffSpeed) / Math.max(1, 0.7 * m.takeoffSpeed), 0.15, 1);
        float yawError = Mth.wrapDegrees(c.lookYaw() - yaw);
        double yawRate = (Mth.clamp(yawError * 2.5, -m.turnRate, m.turnRate) + c.steer() * 10) * authority;
        yaw += (float) (yawRate * DT);
        float targetPitch = Mth.clamp(-c.lookPitch(), -80, 80);
        double pitchRate = Mth.clamp((targetPitch - pitch) * 3, -m.pitchRate, m.pitchRate) * authority;
        // a stall drops the nose
        pitch = Mth.clamp((float) (pitch + (pitchRate - (1 - lift) * 30) * DT), -85, 85);
        // banked into the turn: turning right (yaw up) lowers the right wing
        float bank = (float) Mth.clamp(-(yawRate / Math.max(1, m.turnRate)) * m.maxBank, -m.maxBank, m.maxBank);
        roll = Mth.approach(roll, bank, (float) (120 * DT));
        Vec3 want = nose(yaw, pitch).scale(s / 20);
        Vec3 next = v.add(want.subtract(v).scale(0.08 + 0.3 * lift)).add(0, -(1 - lift) * FALL * DT / 20, 0);
        return new Step(next, yaw, pitch, roll);
    }

    // ---- helicopter -----------------------------------------------------------------------------

    static Step heli(VehicleData.Movement m, VehicleControl c, Vec3 v, float yaw, float pitch, float roll, boolean ground, boolean engine) {
        if (!engine) {
            // the rotor freewheels: it falls (not quite as fast as a stone) and levels out
            double vy = Math.max(-18 / 20.0, v.y - FALL * DT / 20);
            double keep = ground ? 0.5 : 0.98;
            return new Step(new Vec3(v.x * keep, vy, v.z * keep), yaw, Mth.approach(pitch, 0, 1), Mth.approach(roll, 0, 1));
        }
        float yawError = Mth.wrapDegrees(c.lookYaw() - yaw);
        double yawRate = Mth.clamp(yawError * 4, -m.turnRate, m.turnRate);
        yaw += (float) (yawRate * DT);
        Vec3 f = VehicleFrame.forward(yaw), r = VehicleFrame.right(yaw);
        double vf = (v.x * f.x + v.z * f.z) * 20, vs = (v.x * r.x + v.z * r.z) * 20;
        // sitting on its skids until it climbs
        boolean parked = ground && !c.brake();
        double tf = parked ? 0 : c.throttle() > 0 ? m.maxSpeed * c.throttle() : m.reverseSpeed * c.throttle();
        double ts = parked ? 0 : m.strafeSpeed * c.steer();
        vf = Mth.approach((float) vf, (float) tf, (float) ((tf == 0 ? m.brake : m.accel) * DT));
        vs = Mth.approach((float) vs, (float) ts, (float) ((ts == 0 ? m.brake : m.accel) * DT));
        if (parked) {
            vf *= 0.6;
            vs *= 0.6;
        }
        double ty = c.brake() ? m.climbRate : c.boost() ? -m.descendRate : 0;
        double vy = Mth.approach((float) (v.y * 20), (float) ty, (float) (14 * DT));
        // the hull tips into where it is going (nose down to fly forward, the right side down to slide right)
        double maxF = Math.max(1, m.maxSpeed), maxS = Math.max(1, m.strafeSpeed);
        double targetPitch = parked ? 0 : -(tf - vf) / maxF * 25 - vf / maxF * 8;
        double targetRoll = parked ? 0 : -(ts - vs) / maxS * 15 - vs / maxS * 6 - yawRate / Math.max(1, m.turnRate) * 8 * Mth.clamp(vf / maxF, 0, 1);
        pitch = Mth.approach(pitch, (float) Mth.clamp(targetPitch, -25, 20), (float) (60 * DT));
        roll = Mth.approach(roll, (float) Mth.clamp(targetRoll, -30, 30), (float) (80 * DT));
        return new Step(new Vec3((f.x * vf + r.x * vs) / 20, vy / 20, (f.z * vf + r.z * vs) / 20), yaw, pitch, roll);
    }

    private static double sq(double x) {
        return x * x;
    }
}
