package com.blockland.movement.action;

/**
 * Prone tuning as written in {@code data/blockland/movement/prone.json} ({@link MovementAction#PRONE}): lying flat in
 * vanilla's crawl box (0.6 high), crawling slowly, low eyes and hit boxes; guns fire and aim. Speeds are multipliers,
 * times in ticks, lengths blocks at scale 1. Whether a game mode allows it is the mode's {@code allowProne} setting.
 */
public final class ProneRules {
    private static ProneRules instance;

    /** Eye height lying down: the camera (vanilla's crawl pose eye) and where shots and cuts start. */
    public float eyeHeight = 0.4f;
    /** Crawl speed as a share of vanilla's crawl (itself the sneaking speed): 0.6 ≈ a fifth of walking. */
    public float crawlSpeed = 0.6f;
    /** Ticks the body takes to go down and to get up again (the weapon's drop and rise follow them). */
    public int enterTicks = 7;
    public int exitTicks = 9;
    /** Ticks after getting up before going down again. */
    public int cooldownTicks = 6;
    /** Blocks crawled per stroke cycle (both elbows): the weapon's crawl bob. */
    public float strokeLength = 0.9f;
    /** Aimed lying down the weapon still drifts a little: degrees. */
    public float aimSway = 0.45f;
    public Camera camera = new Camera();
    public BodyBoxes.Body hitBody = defaultBody();
    public BodyBoxes.Head hitHead = defaultHead();

    /** First-person camera jolts when the body hits the ground and when it gets up (degrees of pitch). */
    public static final class Camera {
        public float dropKick = 1.6f;
        public float riseKick = 0.8f;
    }

    static BodyBoxes.Body defaultBody() {
        BodyBoxes.Body b = new BodyBoxes.Body();
        b.back = 1.05f;
        b.forward = 0.35f;
        b.height = 0.45f;
        b.halfWidth = 0.32f;
        return b;
    }

    static BodyBoxes.Head defaultHead() {
        BodyBoxes.Head h = new BodyBoxes.Head();
        h.back = -0.55f;
        h.height = 0.3f;
        h.half = 0.22f;
        return h;
    }

    public static synchronized ProneRules get() {
        if (instance == null) instance = ActionData.load("prone", ProneRules.class, ProneRules::new);
        return instance;
    }
}
