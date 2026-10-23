package com.blockland.client.combat;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.CombatPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.ui.Tr.tr;

/**
 * The kill medals of modern shooters, below the crosshair: every kill pops in a medal — 击杀 / 爆头击杀, the streak
 * (双杀 … 五杀, 暴走, 无人能挡), the victim and the points — that lands with a springy overshoot, holds, then fades
 * while the next one pushes it up and shrinks it. Accent colours rise with the streak.
 */
final class KillMedals {
    /** Medals on screen at once (older ones leave early). */
    private static final int MAX = 3;
    private static final float HOLD = 2.4f, FADE = 0.35f;

    private static final int WHITE = 0xFFF2F4F7, HEADSHOT = 0xFFFFA235, DOUBLE = 0xFF4FC3F7, TRIPLE = 0xFF7C8CFF,
            QUAD = 0xFFC77DFF, PENTA = 0xFFFF5CA8, RAMPAGE = 0xFFFF3B3B, UNSTOPPABLE = 0xFFFFD54F;

    private static final class Medal {
        final String title, line;
        final int color, points;
        final boolean headshot;
        /** Pop: starts large and lands on 1 with a little overshoot. */
        final Spring scale = new Spring(1.9f).config(520, 20);
        /** Rise when pushed up the stack (slot index as a float). */
        final Spring slot = new Spring(0).config(300, 26);
        final AnimValue alpha = new AnimValue(0);
        /** Width of the accent bars either side of the title, 0..1. */
        final AnimValue bars = new AnimValue(0);
        final long born = System.nanoTime();

        Medal(String title, String line, int color, int points, boolean headshot) {
            this.title = title;
            this.line = line;
            this.color = color;
            this.points = points;
            this.headshot = headshot;
            scale.setTarget(1);
            alpha.to(1, 0.07, Ease.QUAD_OUT);
            bars.to(1, 0.32, Ease.EXPO_OUT).delay(0.05);
        }

        float age() {
            return (System.nanoTime() - born) / 1e9f;
        }

        void leave(double delay) {
            alpha.to(0, FADE, Ease.QUAD_IN).delay(delay);
        }
    }

    private static final List<Medal> medals = new ArrayList<>();

    private KillMedals() {
    }

    static void show(CombatPackets.S2CKillNotice notice) {
        int streak = Math.max(1, notice.streak());
        String title;
        int color;
        if (streak >= 10) {
            title = tr("blockland.kill.unstoppable");
            color = UNSTOPPABLE;
        } else if (streak >= 6) {
            title = tr("blockland.kill.rampage");
            color = RAMPAGE;
        } else if (streak >= 2) {
            title = tr("blockland.kill.streak." + streak);
            color = switch (streak) {
                case 2 -> DOUBLE;
                case 3 -> TRIPLE;
                case 4 -> QUAD;
                default -> PENTA;
            };
        } else {
            title = tr(notice.headshot() ? "blockland.kill.headshot" : "blockland.kill.kill");
            color = notice.headshot() ? HEADSHOT : WHITE;
        }
        StringBuilder line = new StringBuilder();
        if (notice.headshot() && streak >= 2) line.append(tr("blockland.kill.headshot_tag")).append("  ");
        if (streak >= 6) line.append(tr("blockland.kill.streak_count", streak)).append("  ");
        line.append(tr("blockland.kill.victim", notice.victim()));
        int points = 100 + (notice.headshot() ? 50 : 0) + Math.min(streak - 1, 8) * 25;

        // the stack moves up: older medals shrink back and leave sooner
        for (Medal old : medals) old.slot.setTarget(old.slot.target() + 1);
        Medal medal = new Medal(title, line.toString(), color, points, notice.headshot());
        medal.leave(HOLD);
        medals.add(0, medal);
        while (medals.size() > MAX) medals.remove(medals.size() - 1);
        if (medals.size() > 1) medals.get(1).leave(0.6);
        sounds(streak, notice.headshot());
    }

    private static void sounds(int streak, boolean headshot) {
        var sounds = Minecraft.getInstance().getSoundManager();
        if (streak >= 2) {
            sounds.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 0.85f + 0.09f * Math.min(streak, 10), 0.55f));
        }
        if (streak >= 6) sounds.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.7f + 0.05f * Math.min(streak, 10), 0.45f));
        if (headshot) sounds.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.75f, 0.35f));
    }

    static void clear() {
        medals.clear();
    }

    static void render(GuiGraphics g, int w, int h) {
        medals.removeIf(m -> m.age() > HOLD + FADE + 0.2f && m.alpha.get() < 0.02f);
        float cx = w / 2f, baseY = h * 0.64f;
        for (int i = medals.size() - 1; i >= 0; i--) {
            Medal m = medals.get(i);
            float a = m.alpha.get();
            if (a < 0.03f) continue;
            float slot = m.slot.get();
            // older medals sit higher, smaller and dimmer
            float size = Math.max(0.55f, 1 - 0.22f * slot) * m.scale.get();
            float y = baseY - slot * 22;
            float dim = a * Math.max(0.35f, 1 - 0.3f * slot);
            drawMedal(g, m, cx, y, size, dim);
        }
    }

    private static void drawMedal(GuiGraphics g, Medal m, float cx, float y, float size, float a) {
        float titleScale = 1.65f * size;
        float titleW = UiFont.widthF(m.title) * titleScale;
        float titleH = UiFont.LINE_HEIGHT * titleScale;
        // soft glow in the accent colour behind the title
        Draw.glow(g, cx, y + titleH / 2, titleW * 0.75f + 18, titleH * 1.1f, ColorMath.fade(ColorMath.withAlpha(m.color, 0x55), a));
        // accent bars growing outwards from the title
        float bar = 26 * size * m.bars.get();
        int barColor = ColorMath.fade(m.color, a * 0.9f);
        int barY = Math.round(y + titleH / 2);
        g.fill(Math.round(cx - titleW / 2 - 6 - bar), barY - 1, Math.round(cx - titleW / 2 - 6), barY + 1, barColor);
        g.fill(Math.round(cx + titleW / 2 + 6), barY - 1, Math.round(cx + titleW / 2 + 6 + bar), barY + 1, barColor);
        if (m.headshot) headshotIcon(g, cx - titleW / 2 - 12 - bar, barY, size, ColorMath.fade(HEADSHOT, a));
        UiFont.draw(g, m.title, cx - titleW / 2, y, titleScale, ColorMath.fade(m.color, a), true);
        if (size < 0.8f) return;
        // victim and points underneath
        String points = "+" + m.points;
        float pw = UiFont.widthF(points), lw = UiFont.widthF(m.line);
        float lineY = y + titleH + 2;
        float x = cx - (pw + 6 + lw) / 2;
        UiFont.draw(g, points, x, lineY, ColorMath.fade(0xFFFFE082, a), true);
        UiFont.draw(g, m.line, x + pw + 6, lineY, ColorMath.fade(0xFFD5DAE1, a), true);
    }

    /** A small reticle: the headshot mark. */
    static void headshotIcon(GuiGraphics g, float cx, float cy, float size, int color) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(size, size);
        g.fill(-4, -4, 4, -3, color);
        g.fill(-4, 3, 4, 4, color);
        g.fill(-4, -3, -3, 3, color);
        g.fill(3, -3, 4, 3, color);
        g.fill(-1, -6, 1, -2, color);
        g.fill(-1, 2, 1, 6, color);
        g.fill(-6, -1, -2, 1, color);
        g.fill(2, -1, 6, 1, color);
        g.pose().popMatrix();
    }
}
