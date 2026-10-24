package com.blockland.client.rank;

import com.blockland.anim.ColorMath;
import com.blockland.client.ui.Draw;
import com.blockland.common.RankTier;
import com.blockland.network.packet.RankedPackets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

import static com.blockland.client.ui.Tr.tr;

/** A ranked match's outcome for a player not looking at its results panel: the change, and a promotion or demotion. */
final class RankToast implements Toast {
    private static final long SHOW_MILLIS = 6000;

    private final RankedPackets.S2CRankChange change;
    private Visibility visibility = Visibility.SHOW;

    RankToast(RankedPackets.S2CRankChange change) {
        this.change = change;
    }

    @Override
    public int width() {
        return 200;
    }

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long timeSinceVisible) {
        visibility = timeSinceVisible >= SHOW_MILLIS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void render(GuiGraphics g, Font font, long timeSinceVisible) {
        int w = width(), h = height();
        RankTier before = RankTier.of(change.tierBefore()), after = RankTier.of(change.tier());
        Draw.rect(g, 0, 0, w, h, 0xE6101318);
        Draw.outline(g, 0, 0, w, h, ColorMath.fade(after.color, 0.6f));
        Draw.rect(g, 0, 0, 2, h, after.color);
        String title = after.ordinal() > before.ordinal() ? tr("blockland.rank.toast.promoted", tr(after.langKey()))
                : after.ordinal() < before.ordinal() ? tr("blockland.rank.toast.demoted", tr(after.langKey()))
                : change.left() ? tr("blockland.rank.toast.left") : tr("blockland.rank.toast.title");
        Draw.text(g, Draw.fit(title, w - 12), 8, 6, 1f, 0xFFF2F4F7, false);
        Draw.text(g, Draw.fit(change.modeTitle(), w - RankBadge.changeWidth(change) - 18), 8, 19, 1f, 0xFF9AA4B5, false);
        RankBadge.change(g, change, w - 6, 17, 1);
    }
}
