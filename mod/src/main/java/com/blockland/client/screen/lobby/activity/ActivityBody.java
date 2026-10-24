package com.blockland.client.screen.lobby.activity;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The part of an activity's screen below its header, made for the activity's {@code kind} by {@link ActivityBodies}.
 * Text-only activities use {@link TextBody}; future kinds (tasks, sign-in rewards…) register their own body and read
 * their settings from the activity's {@code data}, so the screen around them never changes.
 */
public interface ActivityBody {
    /** Draws the body into its area; {@code alpha} fades it in. */
    void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float alpha);

    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        return false;
    }

    /** Called every client tick while the screen is shown. */
    default void tick() {
    }
}
