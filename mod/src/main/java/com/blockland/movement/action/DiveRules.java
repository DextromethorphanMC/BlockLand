package com.blockland.movement.action;

/**
 * Dive tuning as written in {@code data/blockland/movement/dive.json} ({@link MovementAction#DIVE}): sprinting, jump,
 * and prone pressed in the air throws the body flat forwards; it lands prone and slides on with the momentum. Speeds
 * are blocks per tick, times ticks, angles degrees, lengths blocks at scale 1. Whether a game mode allows it is the
 * mode's {@code allowDive} setting.
 */
public final class DiveRules {
    private static DiveRules instance;

    /** Slower than this (horizontal) and the press is an ordinary prone (or nothing in the air); sprinting is ~0.28. */
    public float minSpeed = 0.2f;
    /** The press counts within this many ticks of leaving the ground. */
    public int windowTicks = 10;
    /** Launch speed = speed × boost, kept within {@link #minLaunchSpeed} … {@link #maxLaunchSpeed}. */
    public float boost = 1.25f;
    public float minLaunchSpeed = 0.42f;
    public float maxLaunchSpeed = 0.6f;
    /** Upwards velocity added at the launch (blocks per tick): the body throws itself up and out. */
    public float lift = 0.14f;
    /** Horizontal speed kept per tick in the air (vanilla's air drag would stop a dive dead). */
    public float airDrag = 0.985f;
    /** A dive still airborne after this many ticks (a long drop) ends in a plain fall. */
    public int maxAirTicks = 40;
    /** On landing: the slide along the ground on the chest, its friction per tick and the speed it stops at. */
    public int landSlideTicks = 12;
    public float landFriction = 0.84f;
    public float landEndSpeed = 0.05f;
    /** Ticks after a dive before the next. */
    public int cooldownTicks = 30;
    /** Eye height in the dive (the crawl box): the camera and where shots start. */
    public float eyeHeight = 0.4f;
    public Camera camera = new Camera();
    public Pose pose = new Pose();
    public BodyBoxes.Body hitBody = ProneRules.defaultBody();
    public BodyBoxes.Head hitHead = ProneRules.defaultHead();

    /** First-person camera through the dive: looking a little down, a roll, a wider view, the landing's jolt. */
    public static final class Camera {
        public float pitch = 5;
        public float roll = 3;
        public float fovKick = 0.07f;
        public float landKick = 3.5f;
    }

    /** Third-person body in the air: the nose dips this many degrees as it flies. */
    public static final class Pose {
        public float bodyPitch = 12;
    }

    public static synchronized DiveRules get() {
        if (instance == null) instance = ActionData.load("dive", DiveRules.class, DiveRules::new);
        return instance;
    }

    /** Launch speed for a dive pressed at {@code speed} blocks per tick. */
    public float launchSpeed(double speed) {
        return (float) Math.max(minLaunchSpeed, Math.min(maxLaunchSpeed, speed * boost));
    }
}
