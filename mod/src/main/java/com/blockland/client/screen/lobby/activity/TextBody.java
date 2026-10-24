package com.blockland.client.screen.lobby.activity;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.ActivityPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/** An activity's content as wrapped, scrollable paragraphs (blank lines kept). The body of {@code info} activities. */
final class TextBody implements ActivityBody {
    private static final int LINE_H = UiFont.LINE_HEIGHT + 3;

    private final String content;
    private List<FormattedCharSequence> lines = List.of();
    private int wrappedFor = -1;
    private float scroll;
    private int x, y, w, h, contentH;

    TextBody(ActivityPackets.Activity activity) {
        this.content = activity.content();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float alpha) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        if (content.isBlank()) {
            Draw.text(g, tr("blockland.activity.no_content"), x, y + 4, 1f, ColorMath.fade(DIM, alpha), false);
            return;
        }
        int textW = w - 10;
        if (wrappedFor != textW) {
            List<FormattedCharSequence> wrapped = new ArrayList<>();
            for (String paragraph : content.split("\n", -1)) {
                if (paragraph.isBlank()) wrapped.add(FormattedCharSequence.EMPTY);
                else wrapped.addAll(Draw.wrap(paragraph, textW));
            }
            lines = wrapped;
            wrappedFor = textW;
        }
        contentH = lines.size() * LINE_H;
        scroll = clampScroll(scroll);
        g.enableScissor(x, y, x + w, y + h);
        float ly = y - scroll;
        for (FormattedCharSequence line : lines) {
            if (ly + LINE_H > y && ly < y + h) Draw.text(g, line, x, ly, ColorMath.fade(0xFFD9E1EC, alpha));
            ly += LINE_H;
        }
        g.disableScissor();
        if (contentH > h) {
            float frac = h / (float) contentH;
            int barH = Math.max(12, Math.round(h * frac));
            int barY = y + Math.round((h - barH) * (scroll / Math.max(1, contentH - h)));
            g.fill(x + w - 2, barY, x + w, barY + barH, ColorMath.fade(0x55FFFFFF, alpha));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return false;
        scroll = clampScroll(scroll - (float) scrollY * 24);
        return true;
    }

    private float clampScroll(float value) {
        return Math.max(0, Math.min(value, Math.max(0, contentH - h)));
    }
}
