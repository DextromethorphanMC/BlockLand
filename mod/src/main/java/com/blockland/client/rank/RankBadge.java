package com.blockland.client.rank;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Ticker;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.common.RankTier;
import com.blockland.network.packet.RankedPackets;
import net.minecraft.client.gui.GuiGraphics;

import static com.blockland.client.ui.Tr.tr;

/**
 * The tier badge (段位徽章) and the small ranked widgets built from it, for every screen that shows a tier — the lobby,
 * the results panel, and the profile and loading screens (usable without a world). A badge is a pill in the tier's
 * colour with a pip per tier step (one for 煤炭 … five for 猎首) and the tier's name; 猎首 glows.
 */
public final class RankBadge {
    private static final int GOLD = 0xFFFFC24A;
    private static final int UP = 0xFF6EE07A;
    private static final int DOWN = 0xFFFF6B5A;
    private static final int MUTED = 0xFF9AA4B5;

    private RankBadge() {
    }

    /** Width of {@link #draw} at {@code scale}. */
    public static float width(RankTier tier, float scale) {
        return (pipsWidth(tier) + 4 + UiFont.width(tr(tier.langKey())) + 8) * scale;
    }

    /** Height of {@link #draw} at {@code scale}. */
    public static float height(float scale) {
        return 12 * scale;
    }

    /** A tier badge with its top left at (x, y). */
    public static void draw(GuiGraphics g, RankTier tier, float x, float y, float scale, float alpha) {
        if (alpha <= 0.01f) return;
        String name = tr(tier.langKey());
        int w = Math.round(width(tier, 1)), h = 12;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        if (tier == RankTier.HEADHUNTER) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(Ticker.global().time() * 3));
            Draw.glow(g, w / 2f, h / 2f, w * 0.75f, h * 1.2f, ColorMath.fade(tier.color, alpha * (0.25f + 0.2f * pulse)));
        }
        Draw.rect(g, 0, 0, w, h, ColorMath.fade(ColorMath.mix(0xFF0B0F17, tier.color, 0.22f), alpha * 0.92f));
        Draw.outline(g, 0, 0, w, h, ColorMath.fade(tier.color, alpha * 0.85f));
        // one pip per step of the ladder
        int steps = tier.ordinal() + 1;
        for (int i = 0; i < steps; i++) Draw.rect(g, 4 + i * 3, 4, 2, 4, ColorMath.fade(tier.color, alpha));
        Draw.text(g, name, 4 + pipsWidth(tier) + 4, 2, 1f, ColorMath.fade(ColorMath.lighten(tier.color, 0.08f), alpha), false);
        g.pose().popMatrix();
    }

    /** The 「排位」 tag of a ranked mode, right-aligned at {@code right}. */
    public static void modeTag(GuiGraphics g, int right, int y, float alpha) {
        String label = tr("blockland.rank.mode_tag");
        int w = UiFont.width(label) + 8;
        Draw.rect(g, right - w, y, w, 12, ColorMath.fade(0x55FFC24A, alpha));
        Draw.outline(g, right - w, y, w, 12, ColorMath.fade(GOLD, alpha * 0.7f));
        Draw.text(g, label, right - w + 4, y + 2, 1f, ColorMath.fade(GOLD, alpha), false);
    }

    /**
     * The player's own standing ("[钢铁] 1150 · #12", or 「定级中」 before a ranked match), right-aligned at {@code right}.
     *
     * @return its width
     */
    public static float standing(GuiGraphics g, float right, float y, float alpha) {
        RankedPackets.S2CRank rank = ClientRank.standing();
        if (rank == null) return 0;
        RankTier tier = RankTier.of(rank.tier());
        String text = rank.matches() <= 0 ? tr("blockland.rank.unplaced", rank.score())
                : rank.position() > 0 ? tr("blockland.rank.score_position", rank.score(), rank.position())
                : tr("blockland.rank.score", rank.score());
        float textW = UiFont.width(text), badgeW = width(tier, 1);
        float x = right - textW - 4 - badgeW;
        draw(g, tier, x, y, 1, alpha);
        Draw.text(g, text, right - textW, y + 2, 1f, ColorMath.fade(0xFFF2F4F7, alpha), false);
        return textW + 4 + badgeW;
    }

    /** Width {@link #change} takes for this change. */
    public static float changeWidth(RankedPackets.S2CRankChange change) {
        RankTier tier = RankTier.of(change.tier());
        return UiFont.width(changeText(change)) + 4 + width(tier, 1);
    }

    /** A score change ("+25 → 1045" and the new tier), right-aligned at {@code right}. */
    public static void change(GuiGraphics g, RankedPackets.S2CRankChange change, float right, float y, float alpha) {
        RankTier tier = RankTier.of(change.tier());
        String text = changeText(change);
        float textW = UiFont.width(text);
        draw(g, tier, right - textW - 4 - width(tier, 1), y, 1, alpha);
        int color = change.delta() > 0 ? UP : change.delta() < 0 ? DOWN : MUTED;
        Draw.text(g, text, right - textW, y + 2, 1f, ColorMath.fade(color, alpha), false);
    }

    private static String changeText(RankedPackets.S2CRankChange change) {
        String delta = change.delta() > 0 ? "+" + change.delta() : String.valueOf(change.delta());
        return tr("blockland.rank.change", delta, change.after());
    }

    private static int pipsWidth(RankTier tier) {
        return (tier.ordinal() + 1) * 3 - 1;
    }
}
