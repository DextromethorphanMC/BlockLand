package com.blockland.client.screen.lobby;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A lobby tab another client module provides (e.g. 工匠): drawn inside the lobby screen below its top bar like the
 * lobby's own panels, so the lobby never depends on the module (it is installed at start-up).
 */
public interface LobbyTab {
    /** The tab was switched to (or the lobby reopened on it). */
    void open();

    /** Draws the tab into the content area; {@code alpha} fades it while tabs switch. */
    void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float alpha);

    boolean mouseClicked(double mouseX, double mouseY, int button);

    default boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        return false;
    }

    /** Esc: true when the tab closed something of its own (the lobby then stays on it). */
    default boolean back() {
        return false;
    }

    /** Called every client tick while the tab is shown. */
    default void tick() {
    }
}
