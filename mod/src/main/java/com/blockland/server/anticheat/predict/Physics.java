package com.blockland.server.anticheat.predict;

/**
 * Vanilla 1.21.8 player movement as {@code LivingEntity.travel} runs it (anti-cheat spec §6): a tick moves by the
 * velocity stored last tick plus this tick's input acceleration; what is left after collisions times the friction is
 * stored for the next. Pure numbers, no world.
 */
public final class Physics {
    /** Horizontal friction factor in the air ({@code 0.91}); on ground it is the block's slipperiness times this. */
    public static final double AIR_FRICTION = 0.91;
    /** Vertical drag applied after gravity. */
    public static final double VERTICAL_DRAG = 0.98;
    /** Ground acceleration = movement speed × this / slipperiness³. */
    public static final double GROUND_ACCEL = 0.21600002;
    /** Air acceleration of a player that may be sprinting ({@code Player.getFlyingSpeed}: 0.02, sprinting 0.026). */
    public static final double AIR_ACCEL = 0.025999999;
    /** The forward push of a sprint jump. */
    public static final double SPRINT_JUMP = 0.2;
    /** The sprint modifier of the movement speed attribute (+30 %, multiplied). */
    public static final double SPRINT = 1.3;
    public static final double DEFAULT_SLIP = 0.6;
    public static final double DEFAULT_GRAVITY = 0.08;
    /** Gravity while falling with Slow Falling. */
    public static final double SLOW_FALL_GRAVITY = 0.01;
    public static final double DEFAULT_JUMP = 0.42;
    /** Jump Boost adds this per level. */
    public static final double JUMP_BOOST = 0.1;
    public static final double DEFAULT_STEP = 0.6;
    /** Ladders, vines, scaffolding: the climbing speed. */
    public static final double CLIMB = 0.2;
    /** Water: input acceleration and the friction of a swimming (sprinting) player; Dolphin's Grace. */
    public static final double WATER_ACCEL = 0.02;
    public static final double WATER_FRICTION = 0.9;
    public static final double DOLPHIN_FRICTION = 0.96;
    public static final double LAVA_FRICTION = 0.5;
    /** Fastest rise in a liquid: swimming up, the 0.3 hop out of water onto an edge, a bubble of buoyancy. */
    public static final double LIQUID_RISE = 0.5;
    /** What flowing water can push a player sideways per tick, on top of their own speed. */
    public static final double FLUID_FLOW = 0.1;
    /**
     * Sliding down the side of a honey block: every tick ends with the fall set to 0.05 before gravity
     * ({@code HoneyBlock.doSlideMovement}), so each move falls exactly this far, far slower than a free fall.
     */
    public static final double HONEY_SLIDE = (-0.05 - 0.08) * 0.98F;
    /** Ground acceleration of the fastest plain ground (ordinary blocks): what other players' pushes add, at most. */
    public static final double ENTITY_PUSH = 0.08;

    private Physics() {
    }

    public static double groundFriction(double slip) {
        return slip * AIR_FRICTION;
    }

    public static double groundAccel(double speed, double slip) {
        return speed * GROUND_ACCEL / (slip * slip * slip);
    }

    /** {@code getJumpPower}: the strength attribute plus Jump Boost ({@code amplifier} -1 = none), block factor 1. */
    public static double jumpPower(double strength, int boostAmplifier) {
        return strength + (boostAmplifier >= 0 ? JUMP_BOOST * (boostAmplifier + 1) : 0);
    }

    /** The vertical velocity one airborne tick after {@code dy}: gravity, then drag. */
    public static double fall(double dy, double gravity) {
        return (dy - gravity) * VERTICAL_DRAG;
    }
}
