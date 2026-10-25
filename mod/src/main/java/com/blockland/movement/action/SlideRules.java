package com.blockland.movement.action;

/**
 * Slide tuning as written in {@code data/blockland/movement/slide.json} ({@link MovementAction#SLIDE}; read once by
 * {@link ActionData} — the same file on client and server, so the slider's prediction, the server's checks, the eye
 * height shots leave from and the hit boxes all agree). Speeds are blocks per tick, angles degrees, lengths blocks
 * at scale 1. Whether a game mode allows sliding at all is the mode's {@code allowSlide} setting (backend).
 */
public final class SlideRules {
    private static SlideRules instance;

    /** Slower than this (horizontal) and a sprint does not turn into a slide; sprinting is ~0.28. */
    public float entrySpeed = 0.2f;
    /** Start speed = entry speed × boost, kept within {@link #minStartSpeed} … {@link #maxStartSpeed}. */
    public float boost = 1.45f;
    public float minStartSpeed = 0.4f;
    public float maxStartSpeed = 0.56f;
    /** Ticks the start speed holds before friction sets in. */
    public int holdTicks = 3;
    /** Speed kept per tick after that. */
    public float friction = 0.92f;
    /** …on wet ground (rain falling on it): a film of water under the slide carries it further. */
    public float wetFriction = 0.945f;
    /** The slide ends below this speed… */
    public float endSpeed = 0.17f;
    /** …or after this many ticks. */
    public int maxTicks = 22;
    /** Ticks off the ground (a slide over a ledge) before the slide ends and the player falls with its momentum. */
    public int airTicks = 3;
    /** Ticks after a slide ends before the next can start. */
    public int cooldownTicks = 12;
    /** How fast (degrees per tick) and how far in total the slide turns towards where the player looks. */
    public float steering = 1.5f;
    public float maxSteer = 15;
    /** Eye height while sliding: the camera, and where the slider's shots and knife attacks start. */
    public float eyeHeight = 0.97f;
    public Camera camera = new Camera();
    public Pose pose = new Pose();
    public BodyBoxes.Body hitBody = new BodyBoxes.Body();
    public BodyBoxes.Head hitHead = new BodyBoxes.Head();

    /** First-person camera while sliding. */
    public static final class Camera {
        /** Roll (degrees) at full slide. */
        public float roll = 4;
        /** Field of view widening at full slide (0.05 = 5 %). */
        public float fovKick = 0.05f;
    }

    /** Third-person body: hips this high and this far ahead of the position, torso leaning back, legs forward. */
    public static final class Pose {
        public float hipHeight = 0.13f;
        public float lean = 25;
        public float forward = 0.25f;
    }

    public static synchronized SlideRules get() {
        if (instance == null) instance = ActionData.load("slide", SlideRules.class, SlideRules::new);
        return instance;
    }

    /** Speed a slide starts at when entered at {@code entry} blocks per tick. */
    public float startSpeed(double entry) {
        return (float) Math.max(minStartSpeed, Math.min(maxStartSpeed, entry * boost));
    }

    /** Speed {@code ticks} after the start (before the end checks): the start speed holds, then friction. */
    public float speedAt(float start, int ticks) {
        int decaying = Math.max(0, ticks - holdTicks);
        return start * (float) Math.pow(friction, decaying);
    }

    /** Whether a slide that started at {@code start} is over after {@code ticks}. */
    public boolean isOver(float start, int ticks) {
        return ticks >= maxTicks || speedAt(start, ticks) < endSpeed;
    }

    /**
     * The next tick's speed of a slide going at {@code speed} after {@code ticks}: held at first, then the friction of the
     * ground it slides on ({@code wet}: rain falling on it).
     */
    public float next(float speed, int ticks, boolean wet) {
        return ticks <= holdTicks ? speed : speed * (wet ? wetFriction : friction);
    }
}
