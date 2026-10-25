package com.blockland.movement.action;

/**
 * Hit boxes of a body that is not upright (a slide, lying prone, a dive), along the action's direction from the
 * player's position, at scale 1 — the same body the third-person pose draws.
 */
public final class BodyBoxes {
    private BodyBoxes() {
    }

    /** The body (legs and torso): from {@code back} behind the position to {@code forward} ahead of it. */
    public static final class Body {
        public float back = 0.2f;
        public float forward = 1.0f;
        public float height = 0.85f;
        public float halfWidth = 0.3f;
    }

    /** The head: a cube of half size {@code half} around the point {@code back} behind the position, this high. */
    public static final class Head {
        public float back = 0.15f;
        public float height = 0.98f;
        public float half = 0.25f;
    }
}
