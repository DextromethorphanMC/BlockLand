package com.blockland.client.movement;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * The movement keys while the local player is in an action (spec §47): they still reach everyone reading them, but the
 * action decides what moves the player. In a slide and a dive the keys no longer push (the action's momentum does);
 * lying prone they crawl, slower. Crouch never reaches vanilla (it would stop a slide at every ledge, and lying down
 * the action owns the pose), nor do jump and sprint lying down or in the air.
 */
final class ActionInput extends ClientInput {
    enum Mode { SLIDE, CRAWL, FLY }

    final ClientInput inner;
    private Mode mode;
    /** Share of the keys' push while crawling (0 while a landing's momentum still carries the body). */
    private float crawl = 1;

    ActionInput(ClientInput inner, Mode mode) {
        this.inner = inner;
        this.mode = mode;
        apply();
    }

    void setMode(Mode mode) {
        this.mode = mode;
        apply();
    }

    void setCrawl(float crawl) {
        this.crawl = crawl;
    }

    @Override
    public void tick() {
        inner.tick();
        apply();
    }

    private void apply() {
        Input keys = inner.keyPresses;
        if (mode == Mode.SLIDE) {
            keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), false, keys.sprint());
            moveVector = Vec2.ZERO;
        } else if (mode == Mode.CRAWL) {
            keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), false, false, false);
            moveVector = inner.getMoveVector().scale(crawl);
        } else {
            keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), false, false, false);
            moveVector = Vec2.ZERO;
        }
    }
}
