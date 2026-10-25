package com.blockland.server.anticheat.predict;

/**
 * The physics envelope of one move (anti-cheat spec §6): the farthest a vanilla client could have gone this tick from
 * where it was going last tick — every input, a jump, the most lenient state of its latency window, whatever the server
 * pushed it with and whatever the modules' mechanics allow — plus a small tolerance. Only an upper bound: falling
 * faster, walking slower or bumping into walls is always fine. The tolerance is added on top of the physics and never
 * carried on (the caller caps the next move's carried step at {@link MoveEnvelope#rawHorizontal}), so it cannot build up
 * over many ticks. Pure and allocation-free (see {@code tools/anticheat/check_movement.py}).
 */
public final class MovePredictor {
    /** Relative and absolute slack per unit of tolerance (float rounding, collision snapping, unmodelled pushes). */
    private static final double RELATIVE_SLACK = 0.01;
    private static final double ABSOLUTE_SLACK = 0.01;
    private static final double VERTICAL_SLACK = 0.02;
    /** A rise this small is not a jump (only a sneaking step back from an edge). */
    private static final double RISE = 1e-4;
    /** Velocity a sneaking player may have built up behind an edge that held its steps back. */
    private static final double SNEAK_CARRY = 0.1;

    private MovePredictor() {
    }

    public static void envelope(MoveInput in, MoveEnvelope out) {
        double tol = Math.max(0.1, in.tolerance);
        boolean liquid = in.inWater || in.inLava;

        // ---- horizontal: last step through last tick's friction, plus this tick's acceleration --------------------
        double carryFriction;
        double accel;
        if (in.inLava) {
            carryFriction = Physics.LAVA_FRICTION;
            accel = Physics.WATER_ACCEL;
        } else if (in.inWater) {
            carryFriction = in.dolphinsGrace ? Physics.DOLPHIN_FRICTION : Physics.WATER_FRICTION;
            // Depth Strider blends the water acceleration towards the land speed
            accel = Physics.WATER_ACCEL + Math.max(0, in.speed - Physics.WATER_ACCEL) * Math.min(1, Math.max(0, in.waterEfficiency));
        } else {
            carryFriction = in.carryGround ? Physics.groundFriction(positive(in.carrySlip)) : Physics.AIR_FRICTION;
            if (in.groundPrev) {
                // of the two support candidates the less slippery one accelerates more
                accel = Physics.groundAccel(in.speed, Math.min(positive(in.slipPrev), positive(in.slipAlt)));
            } else {
                accel = Physics.AIR_ACCEL;
            }
        }
        double carried = Math.sqrt(in.prevDx * in.prevDx + in.prevDz * in.prevDz);
        if (in.sneaking) carried = Math.max(carried, SNEAK_CARRY);
        // ticks the client did not report: it stood (nearly) still, its velocity wore off
        for (int i = 1; i < in.ticks && i < 20; i++) carried *= carryFriction;
        double raw = carried * carryFriction + accel;
        // a sprint jump pushes forward — only on a move that jumps (rises, or hits a ceiling right away)
        if (in.groundPrev && !liquid && (in.dy > RISE || in.ceiling)) raw += Physics.SPRINT_JUMP;
        if (liquid || in.recentLiquid) raw += Physics.FLUID_FLOW;
        if (in.hasSetVelocity) {
            // a set velocity replaces what the player had; their input (and a jump) still add to it
            double set = Math.sqrt(in.setVx * in.setVx + in.setVz * in.setVz) + accel + (in.groundPrev ? Physics.SPRINT_JUMP : 0);
            raw = Math.max(raw, set);
        }
        raw += Math.sqrt(in.addVx * in.addVx + in.addVz * in.addVz);
        // a mechanic that sets the velocity (a slide, a dive): input and a jump out of it still add to that speed
        if (in.budgetHorizontal > 0) {
            raw = Math.max(raw, in.budgetHorizontal + accel + (in.groundPrev && !liquid && (in.dy > RISE || in.ceiling) ? Physics.SPRINT_JUMP : 0));
        }
        raw += in.pushSlack;
        out.rawHorizontal = raw;
        out.maxHorizontal = raw * (1 + RELATIVE_SLACK * tol) + ABSOLUTE_SLACK * tol;
        out.horizontal = Math.sqrt(in.dx * in.dx + in.dz * in.dz);
        out.accel = accel;
        out.friction = carryFriction;
        out.checkHorizontal = !in.exemptHorizontal;

        // ---- vertical: a jump from the ground, else gravity on last tick's rise; a landing stops a fall short ----------
        double rawUp;
        if (in.groundPrev) {
            rawUp = Physics.jumpPower(in.jumpStrength, in.jumpBoost);
        } else {
            rawUp = Physics.fall(in.prevDy, in.gravity);
        }
        // ending at rest on a block: the fall was cut short, or the move stepped up onto an edge (a jump into a stair or a
        // slab steps up as well, up to the step height)
        if (in.restingNow) rawUp = Math.max(rawUp, in.stepHeight);
        if (in.climbable) rawUp = Math.max(rawUp, Physics.CLIMB);
        if (liquid) rawUp = Math.max(rawUp, Math.max(Physics.LIQUID_RISE, in.prevDy + 0.1));
        else if (in.recentLiquid) rawUp = Math.max(rawUp, Physics.LIQUID_RISE);
        if (in.bounce > 0) rawUp = Math.max(rawUp, in.bounce);
        // a honey block's side holds the fall at a slow, constant speed
        if (in.honeySlide) rawUp = Math.max(rawUp, Physics.HONEY_SLIDE);
        if (in.hasSetVelocity) rawUp = Math.max(rawUp, in.setVy);
        rawUp += Math.max(0, in.addVy);
        // standing on (or stepping onto) a boat or a shulker the client sees where it was some ticks ago
        rawUp += in.platformRise;
        if (in.budgetUp > 0) rawUp = Math.max(rawUp, in.budgetUp);
        out.rawUp = rawUp;
        out.maxUp = rawUp + VERTICAL_SLACK * Math.max(0.1, in.toleranceVertical);
        out.up = in.dy;
        out.checkVertical = !in.exemptVertical;
    }

    private static double positive(double slip) {
        return slip > 0 ? slip : Physics.DEFAULT_SLIP;
    }
}
