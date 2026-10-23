package com.blockland.client.result;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The interactive results panel: the card with 「再来一局」 and 「返回大厅 (N)」. The world stays visible and the game
 * keeps running behind it. Closing it with Esc keeps the card on the HUD (without buttons) until the countdown ends.
 */
final class MatchResultScreen extends Screen {
    private MatchResultCard.Layout layout;

    MatchResultScreen() {
        super(Component.translatable("blockland.result.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (!MatchResults.active()) onClose();
    }

    /** No blur or dimming: the card is small and the match is still visible around it. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        layout = MatchResults.renderCard(g, true, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && layout != null) {
            if (layout.overPlay(mouseX, mouseY)) {
                MatchResults.playAgain();
                onClose();
                return true;
            }
            if (layout.overLobby(mouseX, mouseY)) {
                MatchResults.backToLobby();
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
