package com.blockland.movement.action;

import javax.annotation.Nullable;

/**
 * The body's special movements (spec §47). Each is data ({@code data/blockland/movement/<id>.json}) plus rules, the
 * mode setting that allows it ({@code allowSlide}, {@code allowProne}, {@code allowDive}) and a place in the action
 * state every side shares ({@link BodyActions}): the eye position, the hit boxes, the camera and the poses follow it.
 */
public enum MovementAction {
    /** 滑铲: sprinting, crouch drops the player into a fast low slide (slide.json). */
    SLIDE("slide", "allowSlide"),
    /** 匍匐: lying flat, crawling slowly; the gun can be fired and aimed (prone.json). */
    PRONE("prone", "allowProne"),
    /** 飞扑: a sprint jump turned into a dive forwards, landing prone (dive.json). */
    DIVE("dive", "allowDive");

    private static final MovementAction[] VALUES = values();

    private final String id;
    private final String setting;

    MovementAction(String id, String setting) {
        this.id = id;
        this.setting = setting;
    }

    public String id() {
        return id;
    }

    /** The game mode setting (backend) that allows it. */
    public String setting() {
        return setting;
    }

    /** Lying on the ground or flying flat: the low crawl box, the prone hit boxes. */
    public boolean lying() {
        return this != SLIDE;
    }

    /** The wire index; -1 = no action. */
    public static int index(@Nullable MovementAction action) {
        return action == null ? -1 : action.ordinal();
    }

    @Nullable
    public static MovementAction byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : null;
    }
}
