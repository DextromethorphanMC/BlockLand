package com.blockland.server.anticheat.predict;

/**
 * Everything the predictor needs about one move (anti-cheat spec §6), filled by the tracking layer from the player's
 * state, the blocks around them, their latency window and the server's velocities. Plain fields, reused per player.
 */
public final class MoveInput {
    // ---- the ticks before -----------------------------------------------------------------------------
    /**
     * The last accepted per-tick displacement (blocks), capped at what the envelope explained then — so a step that got
     * away with the tolerance never carries its excess into the next one.
     */
    public double prevDx, prevDy, prevDz;
    /**
     * Whether the last move started on the ground, and the slipperiness under it then: vanilla picks a tick's friction
     * before it moves, so this is what turned the last step into the velocity carried into this one.
     */
    public boolean carryGround;
    public double carrySlip = Physics.DEFAULT_SLIP;
    /** Whether this move starts on the ground (server-verified): it decides the acceleration and whether a jump is possible. */
    public boolean groundPrev;
    /** Slipperiness of the block under the player at the start of this move (0.6 plain, 0.98 ice, 0.989 blue ice). */
    public double slipPrev = Physics.DEFAULT_SLIP;
    /**
     * A second candidate (standing across two blocks, the client may have picked the other one as its support): the
     * envelope takes whichever of the two allows more.
     */
    public double slipAlt = Physics.DEFAULT_SLIP;
    /** Client ticks since the last movement packet (≥ 1): ticks in between moved the player too little to report. */
    public int ticks = 1;

    // ---- this move's own ends ---------------------------------------------------------------------------
    /**
     * The move ends with the box resting on something (the server's own collision, whatever the client claims): a fall
     * stopped short, or a step up onto an edge — also a jump that stepped onto a stair or slab, after which the client
     * says it is not on the ground because it was moving up.
     */
    public boolean restingNow;
    /** A ceiling right above: a jump there rises (almost) nothing but still pushes a sprinter forward. */
    public boolean ceiling;
    /**
     * A collidable entity (a boat, a shulker) under or beside the feet at either end: how far the client's copy of it may
     * be off the server's (it shows a moving one ticks late), added to the rise (0 = none).
     */
    public double platformRise;

    // ---- surroundings ---------------------------------------------------------------------------------
    public boolean inWater, inLava;
    /** In a liquid a moment ago (leaving water keeps its push and the hop out). */
    public boolean recentLiquid;
    /** On or entering a ladder, vine or scaffolding. */
    public boolean climbable;
    /** Against the side of a honey block (at either end): the fall may be held at {@link Physics#HONEY_SLIDE}. */
    public boolean honeySlide;
    /** Upwards speed a bounce gives (landed on slime / a bed last tick at this speed), 0 = none. */
    public double bounce;

    // ---- the player's own numbers over the latency window (largest / most lenient) ---------------------
    /** Movement speed attribute, sprint included (×1.3 whenever the server did not see the sprint). */
    public double speed = 0.1 * Physics.SPRINT;
    public double jumpStrength = Physics.DEFAULT_JUMP;
    /** Jump Boost amplifier, -1 = none. */
    public int jumpBoost = -1;
    public double stepHeight = Physics.DEFAULT_STEP;
    /** Smallest effective gravity (Slow Falling while falling: 0.01). */
    public double gravity = Physics.DEFAULT_GRAVITY;
    /** Water movement efficiency 0..1 (Depth Strider). */
    public double waterEfficiency;
    public boolean dolphinsGrace;
    /** Sneaking: at a block's edge vanilla holds the step back but keeps the velocity, which a later step then shows. */
    public boolean sneaking;

    // ---- what the server gave, the modules allow, and exemptions ---------------------------------------
    /** A velocity the server set (knockback) that may take effect this move. */
    public boolean hasSetVelocity;
    public double setVx, setVy, setVz;
    /** Velocities the server added (explosions) that may take effect this move, summed. */
    public double addVx, addVy, addVz;
    /** {@code MotionBudget} of the modules: the velocity a mechanic may set, and the fastest rise it may give. */
    public double budgetHorizontal, budgetUp;
    public boolean exemptHorizontal, exemptVertical;
    /** Slack for other entities pushing the player (0 unless something pushable is touching them). */
    public double pushSlack;
    /** The speed and the vertical check's tolerance multipliers (preset × mode × check), ≥ 0.1. */
    public double tolerance = 1;
    public double toleranceVertical = 1;

    // ---- the move itself ------------------------------------------------------------------------------
    public double dx, dy, dz;

    public void clearVelocities() {
        hasSetVelocity = false;
        setVx = setVy = setVz = 0;
        addVx = addVy = addVz = 0;
    }
}
