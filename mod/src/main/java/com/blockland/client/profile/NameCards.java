package com.blockland.client.profile;

import com.blockland.common.RankTier;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;

/**
 * Draws a player's name card (名片: static or animated) — the facade every screen that shows players uses (the profile,
 * the inventory, the match loading screen). Usable in the lobby (no world needed). The profile module implements it.
 */
public final class NameCards {
    /** How cards are drawn (installed by the profile module). */
    public interface Painter {
        Painter PLAIN = (g, cardId, x, y, w, h, name, tier, time) -> g.fill(x, y, x + w, y + h, 0xC0202630);

        /**
         * @param cardId the card's id (without {@code card:}), null or unknown = the default card
         * @param tier   the player's tier for the badge, null = no badge
         * @param time   seconds, for animated cards
         */
        void draw(GuiGraphics g, @Nullable String cardId, int x, int y, int w, int h, String name, @Nullable RankTier tier, float time);
    }

    /** Width : height of a card (draw at any size of this ratio). */
    public static final float ASPECT = 3.2f;

    private static volatile Painter painter = Painter.PLAIN;

    private NameCards() {
    }

    public static void setPainter(@Nullable Painter next) {
        painter = next == null ? Painter.PLAIN : next;
    }

    public static void draw(GuiGraphics g, @Nullable String cardId, int x, int y, int w, int h, String name, @Nullable RankTier tier, float time) {
        painter.draw(g, cardId, x, y, w, h, name, tier, time);
    }
}
