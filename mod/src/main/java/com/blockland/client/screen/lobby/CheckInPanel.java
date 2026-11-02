package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.checkin.ClientCheckIn;
import com.blockland.client.font.UiFont;
import com.blockland.client.profile.ItemIcons;
import com.blockland.client.ui.Draw;
import com.blockland.client.ui.Tr;
import com.blockland.network.packet.CheckInPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The daily check-in (每日签到) over the lobby: the cycle as day cards (checked in, today, still to come; the cycle's
 * last day is its grand prize), the streak, best streak and total, the milestones the admin set on top (连续 / 累计 /
 * 指定日期签到: what they grant, how far the player is) and one button that checks in — or counts down to the next day. A check-in that went through is celebrated on its card: a stamp slams in, sparks burst, the streak
 * counts up and the reward rises over the card. Opened by the top bar's 签到 button; Escape, the cross or a click
 * outside closes it. The server decides everything; this only shows {@link ClientCheckIn}.
 */
public final class CheckInPanel {
    private static final int PAD = 16, GAP = 6, MAX_COLS = 7, MIN_CARD = 40, MAX_CARD = 74;
    private static final int HEADER = 40, STATS = 22, FOOTER = 40;
    private static final int BUTTON_W = 150, BUTTON_H = 22;
    /** The milestones under the cycle: up to {@link #MS_MAX} rows of {@link #MS_ROW} px, two to a line. */
    private static final int MS_HEADER = 14, MS_ROW = 26, MS_MAX = 6;

    private final AnimValue openness = new AnimValue(0);
    private final Spring buttonHover = new Spring(0).config(320, 26);
    private final Spring closeHover = new Spring(0).config(320, 26);
    private final Spring cardPunch = new Spring(0).config(260, 14);
    private final AnimValue stamp = new AnimValue(1);
    private final AnimValue streakShown = new AnimValue(0);
    private final AnimValue rise = new AnimValue(1);
    private final AnimValue flash = new AnimValue(0);
    private final List<Spark> sparks = new ArrayList<>();
    private final Random random = new Random();
    private boolean open;
    private int seenVersion = -1;
    /** The card just checked in (the celebration plays on it), -1 when none. */
    private int celebrated = -1;
    private String celebratedName = "";
    /** The milestones the celebrated check-in reached (their names). */
    private List<String> celebratedEarned = List.of();
    private double lastTime = -1;

    // layout, recomputed every frame
    private int px, py, pw, ph, cols, cardW, cardH, gridX, gridY;
    private int bx, by, cx, cy;
    private int msY, msH;
    private List<CheckInPackets.Milestone> shownMilestones = List.of();

    private static final class Spark {
        float x, y, vx, vy, life, max, size;
        int color;
    }

    /** Asks for the player's check-in (the lobby opened). */
    public CheckInPanel() {
        ClientCheckIn.request();
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        if (open) return;
        open = true;
        ClientCheckIn.request();
        Anim.fromTo(openness, 0, 1, 0.35).ease(Ease.BACK_OUT);
    }

    public void close() {
        if (!open) return;
        open = false;
        Anim.to(openness, 0, 0.15).ease(Ease.QUAD_IN);
    }

    /** The top bar's button shows a dot: there is a check-in waiting for today. Keeps the state fresh across midnight. */
    public boolean pending() {
        ClientCheckIn.maintain();
        return ClientCheckIn.claimable();
    }

    // ---- rendering --------------------------------------------------------------------------

    public void render(GuiGraphics g, int mouseX, int mouseY, int width, int height) {
        float o = Math.max(0, Math.min(1, openness.get()));
        CheckInPackets.S2CState state = ClientCheckIn.state();
        double now = Ticker.global().time();
        float dt = lastTime < 0 ? 0 : (float) Math.min(0.1, now - lastTime);
        lastTime = now;
        if (o <= 0.001f && !open) {
            sparks.clear();
            seenVersion = ClientCheckIn.version();
            return;
        }
        layout(state, width, height);
        if (seenVersion != ClientCheckIn.version()) {
            seenVersion = ClientCheckIn.version();
            if (state != null && state.justClaimed() >= 0 && open) celebrate(state, state.justClaimed());
        }
        if (!open) mouseX = mouseY = -1000;

        g.fill(0, 0, width, height, ColorMath.fade(0xB0000000, o));
        g.pose().pushMatrix();
        float scale = 0.92f + 0.08f * o;
        g.pose().translate(px + pw / 2f, py + ph / 2f);
        g.pose().scale(scale, scale);
        g.pose().translate(-(px + pw / 2f), -(py + ph / 2f));
        float alpha = Math.min(1, o);

        Draw.glow(g, px + pw / 2f, py + ph / 2f, pw * 0.75f, ph * 0.7f, ColorMath.fade(0x30FFB02E, alpha));
        Draw.rect(g, px, py, pw, ph, ColorMath.fade(0xF20C121C, alpha));
        Draw.outline(g, px, py, pw, ph, ColorMath.fade(LINE, alpha));
        g.fill(px + 1, py, px + pw - 1, py + 2, ColorMath.fade(ACCENT, alpha));

        header(g, mouseX, mouseY, alpha);
        if (state == null) {
            message(g, tr("blockland.checkin.loading"), MUTED, alpha);
        } else if (!state.error().isEmpty()) {
            message(g, tr(state.error()), ERROR, alpha);
        } else {
            stats(g, state, alpha);
            for (int i = 0; i < state.days().size(); i++) card(g, mouseX, mouseY, state, i, alpha);
            milestones(g, mouseX, mouseY, state, alpha);
            celebration(g, dt, alpha);
        }
        button(g, mouseX, mouseY, state, alpha);
        g.pose().popMatrix();
    }

    private void layout(CheckInPackets.S2CState state, int width, int height) {
        int n = state == null || state.days().isEmpty() ? MAX_COLS : state.days().size();
        shownMilestones = state == null || !state.error().isEmpty() ? List.of() : ordered(state.milestones());
        int msRows = (shownMilestones.size() + 1) / 2;
        msH = shownMilestones.isEmpty() ? 0 : MS_HEADER + msRows * (MS_ROW + 4) + 4;
        int maxW = Math.min(width - 24, 2 * PAD + MAX_COLS * MAX_CARD + (MAX_COLS - 1) * GAP);
        int maxCardsH = height - 24 - HEADER - STATS - FOOTER - 12 - msH;
        // the fewest columns (up to a week a row) whose cards still fit the screen's height, else the most that fit
        cols = Math.min(n, MAX_COLS);
        int rows = 0;
        for (int c = Math.min(n, MAX_COLS); c <= Math.min(n, 16); c++) {
            cols = c;
            rows = (n + c - 1) / c;
            int byWidth = (maxW - 2 * PAD - (c - 1) * GAP) / c;
            int byHeight = (maxCardsH - (rows - 1) * GAP) / rows - 28;
            cardW = Math.max(MIN_CARD, Math.min(MAX_CARD, Math.min(byWidth, byHeight)));
            if (byHeight >= MIN_CARD || cardW * c + (c - 1) * GAP + 2 * PAD > maxW) break;
        }
        cardH = cardW + 28;
        int gridW = cols * cardW + (cols - 1) * GAP;
        pw = Math.max(Math.min(width - 24, 300), gridW + 2 * PAD);
        ph = HEADER + STATS + rows * cardH + (rows - 1) * GAP + 12 + msH + FOOTER;
        px = (width - pw) / 2;
        py = Math.max(12, (height - ph) / 2);
        gridX = px + (pw - gridW) / 2;
        gridY = py + HEADER + STATS + 6;
        msY = gridY + rows * cardH + (rows - 1) * GAP + 6;
        bx = px + (pw - BUTTON_W) / 2;
        by = py + ph - FOOTER + (FOOTER - BUTTON_H) / 2;
        cx = px + pw - 18;
        cy = py + 8;
    }

    private void header(GuiGraphics g, int mouseX, int mouseY, float alpha) {
        g.fill(px + PAD, py + 14, px + PAD + 3, py + 26, ColorMath.fade(ACCENT, alpha));
        Draw.text(g, tr("blockland.checkin.title"), px + PAD + 8, py + 13, 1.4f, ColorMath.fade(TEXT, alpha), false);
        String sub = Draw.fit(tr("blockland.checkin.subtitle"), pw - 2 * PAD - 8);
        Draw.text(g, sub, px + PAD + 8, py + 28, 0.8f, ColorMath.fade(DIM, alpha), false);
        boolean over = inside(mouseX, mouseY, cx - 2, cy - 2, 14, 14);
        closeHover.setTarget(over ? 1 : 0);
        float hv = closeHover.get();
        if (hv > 0.01f) Draw.rect(g, cx - 2, cy - 2, 14, 14, ColorMath.fade(0x22FFFFFF, hv * alpha));
        int color = ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), alpha);
        // a cross of two pixel diagonals
        for (int k = 0; k < 8; k++) {
            g.fill(cx + 1 + k, cy + 1 + k, cx + 2 + k, cy + 2 + k, color);
            g.fill(cx + 8 - k, cy + 1 + k, cx + 9 - k, cy + 2 + k, color);
        }
    }

    private void message(GuiGraphics g, String text, int color, float alpha) {
        Draw.text(g, text, px + pw / 2f - UiFont.width(text) / 2f, py + HEADER + STATS + 20, 1f, ColorMath.fade(color, alpha), false);
    }

    private void stats(GuiGraphics g, CheckInPackets.S2CState state, float alpha) {
        int streak = celebrated >= 0 && streakShown.isAnimating() ? Math.round(streakShown.get()) : state.streak();
        String[] labels = {tr("blockland.checkin.streak"), tr("blockland.checkin.best"), tr("blockland.checkin.total")};
        int[] values = {streak, Math.max(state.best(), streak), state.total()};
        int chipW = (pw - 2 * PAD - 2 * GAP) / 3, y = py + HEADER;
        for (int i = 0; i < 3; i++) {
            int x = px + PAD + i * (chipW + GAP);
            Draw.rect(g, x, y, chipW, STATS - 4, ColorMath.fade(CARD, alpha));
            String value = tr("blockland.checkin.days_value", values[i]);
            float pop = i == 0 && celebrated >= 0 ? Math.max(0, cardPunch.get()) : 0;
            Draw.text(g, labels[i], x + 6, y + 5, 0.85f, ColorMath.fade(MUTED, alpha), false);
            int valueColor = i == 0 && values[0] > 0 ? ACCENT : TEXT;
            float s = 1f + pop;
            Draw.text(g, value, x + chipW - 6 - UiFont.width(value) * s, y + 5 - pop * 4, s, ColorMath.fade(valueColor, alpha), false);
        }
    }

    private void card(GuiGraphics g, int mouseX, int mouseY, CheckInPackets.S2CState state, int i, float alpha) {
        CheckInPackets.Day day = state.days().get(i);
        int x = gridX + i % cols * (cardW + GAP), y = gridY + i / cols * (cardH + GAP);
        boolean today = i == state.position();
        boolean claimable = today && ClientCheckIn.claimable();
        boolean grand = i == state.days().size() - 1 && state.days().size() > 1;
        String itemName = Tr.text(day.itemName());
        String name = !day.label().isEmpty() ? day.label() : !itemName.isEmpty() ? itemName : "";
        int rarity = day.reward() && !day.rarity().isEmpty() ? InventoryPanel.rarityColor(day.rarity()) : NEUTRAL_EDGE;

        g.pose().pushMatrix();
        float punch = i == celebrated ? cardPunch.get() : 0;
        float lift = claimable ? 1.5f + (float) Math.sin(Ticker.global().time() * 3) * 1.5f : 0;
        g.pose().translate(x + cardW / 2f, y + cardH / 2f - lift);
        g.pose().scale(1 + punch, 1 + punch);
        g.pose().translate(-(x + cardW / 2f), -(y + cardH / 2f));

        if (claimable) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(Ticker.global().time() * 3);
            Draw.glow(g, x + cardW / 2f, y + cardH / 2f, cardW * 0.9f, cardH * 0.75f, ColorMath.fade(0x55FFB02E, alpha * (0.5f + 0.5f * pulse)));
        }
        if (grand) Draw.glow(g, x + cardW / 2f, y + cardH / 2f, cardW * 0.8f, cardH * 0.65f, ColorMath.fade(0x30FFD54F, alpha));
        boolean over = inside(mouseX, mouseY, x, y, cardW, cardH);
        Draw.rect(g, x, y, cardW, cardH, ColorMath.fade(over || claimable ? CARD_HOVER : CARD, alpha));
        g.fill(x + 1, y, x + cardW - 1, y + 2, ColorMath.fade(rarity, alpha * (day.claimed() ? 0.5f : 1)));
        int edge = claimable ? ACCENT : today && day.claimed() ? OK : grand ? GOLD : 0;
        if (edge != 0) Draw.outline(g, x, y, cardW, cardH, ColorMath.fade(edge, alpha * (claimable ? 0.9f : 0.7f)));

        String top = today ? tr("blockland.checkin.today") : tr("blockland.checkin.day", i + 1);
        int topColor = claimable ? ACCENT : today ? OK : day.claimed() ? DIM : MUTED;
        Draw.text(g, top, x + cardW / 2f - UiFont.width(top) * 0.8f / 2f, y + 5, 0.8f, ColorMath.fade(topColor, alpha), false);

        int s = cardW - 14, ix = x + 7, iy = y + 15;
        Draw.rect(g, ix, iy, s, s, ColorMath.fade(0xFF0E1420, alpha));
        if (day.reward() && !day.icon().isEmpty()) {
            ItemIcons.draw(g, day.icon(), name, ix + 2, iy + 2, s - 4, s - 4, (float) Ticker.global().time());
        } else {
            glyph(g, day.reward(), ix, iy, s, ColorMath.fade(day.reward() ? GOLD : MUTED, alpha), i + 1);
        }
        if (day.claimed()) g.fill(ix, iy, ix + s, iy + s, ColorMath.fade(0x8C05070B, alpha));

        String label = name.isEmpty() ? tr("blockland.checkin.day", i + 1) : name;
        label = Draw.fit(label, (cardW - 6) / 0.8f);
        Draw.text(g, label, x + cardW / 2f - UiFont.width(label) * 0.8f / 2f, iy + s + 3, 0.8f,
                ColorMath.fade(day.claimed() ? MUTED : TEXT, alpha), false);
        if (day.reward()) {
            String term = day.durationDays() > 0 ? tr("blockland.checkin.duration", day.durationDays()) : tr("blockland.checkin.permanent");
            Draw.text(g, term, x + cardW / 2f - UiFont.width(term) * 0.7f / 2f, iy + s + 13, 0.7f, ColorMath.fade(DIM, alpha), false);
        }
        if (grand) {
            String tag = tr("blockland.checkin.grand");
            int tw = Math.round(UiFont.width(tag) * 0.7f) + 6;
            Draw.rect(g, x + cardW - tw - 2, y + 15, tw, 9, ColorMath.fade(GOLD, alpha));
            Draw.text(g, tag, x + cardW - tw + 1, y + 16, 0.7f, ColorMath.fade(0xFF2A1E05, alpha), false);
        }
        if (day.claimed()) stamp(g, ix + s / 2f, iy + s / 2f, s, i == celebrated ? stamp.get() : 1, alpha);
        g.pose().popMatrix();
    }

    // ---- milestones (连续 / 累计 / 指定日期签到) -------------------------------------------------

    /**
     * The milestones worth showing first, at most {@link #MS_MAX}: the ones today's check-in paid, then the closest
     * still to come, then the one-time ones paid before.
     */
    private static List<CheckInPackets.Milestone> ordered(List<CheckInPackets.Milestone> all) {
        List<CheckInPackets.Milestone> list = new ArrayList<>(all);
        list.sort(Comparator.comparingInt(CheckInPanel::rank));
        return list.size() > MS_MAX ? List.copyOf(list.subList(0, MS_MAX)) : list;
    }

    private static int rank(CheckInPackets.Milestone m) {
        if (!m.achieved()) return m.checkInsLeft();
        // repeating and date milestones only show achieved on the day they paid; one-time ones stay achieved
        return m.repeat() || m.kind() == CheckInPackets.MilestoneKind.DATE ? -1 : 1_000_000;
    }

    private static int kindColor(CheckInPackets.MilestoneKind kind) {
        return switch (kind) {
            case STREAK -> ACCENT;
            case TOTAL -> INFO;
            case DATE -> GOLD;
        };
    }

    private void milestones(GuiGraphics g, int mouseX, int mouseY, CheckInPackets.S2CState state, float alpha) {
        if (shownMilestones.isEmpty()) return;
        int x0 = px + PAD, w = pw - 2 * PAD;
        Draw.text(g, tr("blockland.checkin.milestones"), x0, msY + 2, 0.8f, ColorMath.fade(DIM, alpha), false);
        int more = state.milestones().size() - shownMilestones.size();
        if (more > 0) {
            String text = tr("blockland.checkin.milestones_more", more);
            Draw.text(g, text, x0 + w - UiFont.width(text) * 0.8f, msY + 2, 0.8f, ColorMath.fade(DIM, alpha), false);
        }
        int cellW = shownMilestones.size() > 1 ? (w - GAP) / 2 : w;
        for (int i = 0; i < shownMilestones.size(); i++) {
            int x = x0 + i % 2 * (cellW + GAP), y = msY + MS_HEADER + i / 2 * (MS_ROW + 4);
            milestone(g, mouseX, mouseY, shownMilestones.get(i), x, y, cellW, alpha);
        }
    }

    private void milestone(GuiGraphics g, int mouseX, int mouseY, CheckInPackets.Milestone m, int x, int y, int w, float alpha) {
        boolean over = inside(mouseX, mouseY, x, y, w, MS_ROW);
        int color = kindColor(m.kind());
        Draw.rect(g, x, y, w, MS_ROW, ColorMath.fade(over ? CARD_HOVER : CARD, alpha));
        g.fill(x, y, x + 2, y + MS_ROW, ColorMath.fade(color, alpha * (m.achieved() ? 0.5f : 1)));

        // the first reward's icon (a gift box when it has none)
        int s = MS_ROW - 6, ix = x + 5, iy = y + 3;
        Draw.rect(g, ix, iy, s, s, ColorMath.fade(0xFF0E1420, alpha));
        CheckInPackets.Reward first = m.rewards().isEmpty() ? null : m.rewards().get(0);
        if (first != null && !first.icon().isEmpty()) {
            ItemIcons.draw(g, first.icon(), Tr.text(first.name()), ix + 1, iy + 1, s - 2, s - 2, (float) Ticker.global().time());
        } else {
            glyph(g, true, ix, iy, s, ColorMath.fade(GOLD, alpha), 0);
        }

        // right: where it stands — achieved, check-ins to go, or days to the date
        String status;
        int statusColor;
        if (m.achieved()) {
            status = tr("blockland.checkin.ms.done");
            statusColor = OK;
        } else if (m.kind() == CheckInPackets.MilestoneKind.DATE) {
            status = m.checkInsLeft() == 0 ? tr("blockland.checkin.ms.date_today") : tr("blockland.checkin.ms.date_in", m.checkInsLeft());
            statusColor = m.checkInsLeft() == 0 ? ACCENT : MUTED;
        } else {
            status = tr("blockland.checkin.ms.left", m.checkInsLeft());
            statusColor = m.checkInsLeft() <= 1 ? ACCENT : MUTED;
        }
        float statusW = UiFont.width(status) * 0.75f;
        int textX = ix + s + 5, textW = x + w - 6 - textX;
        Draw.text(g, status, x + w - 6 - statusW, y + 4, 0.75f, ColorMath.fade(statusColor, alpha), false);
        String name = Draw.fit(Tr.text(m.name()), (textW - statusW - 6) / 0.85f);
        Draw.text(g, name, textX, y + 3, 0.85f, ColorMath.fade(m.achieved() ? MUTED : TEXT, alpha), false);

        // under it: what it grants, and for counts the progress
        StringBuilder rewards = new StringBuilder();
        for (CheckInPackets.Reward r : m.rewards()) {
            if (!rewards.isEmpty()) rewards.append(tr("blockland.checkin.list_separator"));
            rewards.append(Tr.text(r.name()));
        }
        String progress = m.kind() == CheckInPackets.MilestoneKind.DATE || m.target() <= 0 ? ""
                : Math.min(m.progress(), m.target()) + "/" + m.target();
        float progressW = UiFont.width(progress) * 0.7f;
        String line = Draw.fit(tr("blockland.checkin.ms.rewards", rewards.toString()), (textW - progressW - 6) / 0.7f);
        Draw.text(g, line, textX, y + 13, 0.7f, ColorMath.fade(DIM, alpha), false);
        if (!progress.isEmpty()) Draw.text(g, progress, x + w - 6 - progressW, y + 14, 0.7f, ColorMath.fade(MUTED, alpha), false);
        if (m.kind() != CheckInPackets.MilestoneKind.DATE && m.target() > 0) {
            float f = m.achieved() ? 1 : Math.min(1, m.progress() / (float) m.target());
            g.fill(textX, y + MS_ROW - 3, x + w - 6, y + MS_ROW - 2, ColorMath.fade(0x22FFFFFF, alpha));
            g.fill(textX, y + MS_ROW - 3, textX + Math.round((x + w - 6 - textX) * f), y + MS_ROW - 2,
                    ColorMath.fade(m.achieved() ? OK : color, alpha));
        }
    }

    /** A day without its own icon: a gift box for a reward, the day's number for a plain check-in. */
    private static void glyph(GuiGraphics g, boolean reward, int x, int y, int s, int color, int day) {
        if (!reward) {
            String n = String.valueOf(day);
            float scale = Math.max(1, s / 18f);
            Draw.text(g, n, x + s / 2f - UiFont.width(n) * scale / 2f, y + s / 2f - 4 * scale, scale, color, false);
            return;
        }
        int u = Math.max(1, s / 12), bw = u * 8, bh = u * 6, gx = x + (s - bw) / 2, gy = y + (s - bh) / 2 + u;
        g.fill(gx, gy, gx + bw, gy + bh, ColorMath.fade(color, 0.35f));
        g.fill(gx - u, gy - 2 * u, gx + bw + u, gy, color);
        g.fill(gx + bw / 2 - u / 2 - 1, gy - 2 * u, gx + bw / 2 + u / 2 + 1, gy + bh, 0xFFE0524B);
        g.fill(gx + bw / 2 - 3 * u, gy - 4 * u, gx + bw / 2 - u, gy - 2 * u, color);
        g.fill(gx + bw / 2 + u, gy - 4 * u, gx + bw / 2 + 3 * u, gy - 2 * u, color);
    }

    /** The 已签 stamp, tilted over the card's icon; {@code t} runs 0 → 1 as it slams in (it starts big). */
    private static void stamp(GuiGraphics g, float cx, float cy, int size, float t, float alpha) {
        if (t <= 0) return;
        String text = tr("blockland.checkin.stamp");
        float scale = Math.max(0.8f, size / 34f) * (1 + 1.4f * (1 - Math.min(1, t)));
        float a = alpha * Math.min(1, t * 1.6f);
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate((float) Math.toRadians(-14));
        g.pose().scale(scale, scale);
        int w = UiFont.width(text) + 8, h = 13;
        Draw.outline(g, -w / 2, -h / 2, w, h, ColorMath.fade(OK, a));
        Draw.outline(g, -w / 2 + 1, -h / 2 + 1, w - 2, h - 2, ColorMath.fade(ColorMath.withAlpha(OK, 0x80), a));
        Draw.text(g, text, -UiFont.width(text) / 2f, -h / 2f + 3, 1f, ColorMath.fade(OK, a), false);
        g.pose().popMatrix();
    }

    private void button(GuiGraphics g, int mouseX, int mouseY, CheckInPackets.S2CState state, float alpha) {
        boolean claimable = ClientCheckIn.claimable() && !ClientCheckIn.claiming();
        boolean retry = state != null && !state.error().isEmpty();
        boolean over = (claimable || retry) && inside(mouseX, mouseY, bx, by, BUTTON_W, BUTTON_H);
        buttonHover.setTarget(over ? 1 : 0);
        float hv = buttonHover.get();
        String label;
        int color;
        if (state == null) {
            label = tr("blockland.checkin.loading");
            color = DISABLED;
        } else if (retry) {
            label = tr("blockland.checkin.retry");
            color = INFO;
        } else if (ClientCheckIn.claiming()) {
            label = tr("blockland.checkin.claiming");
            color = DISABLED;
        } else if (!state.enabled()) {
            label = tr("blockland.checkin.disabled");
            color = DISABLED;
        } else if (state.claimedToday()) {
            label = tr("blockland.checkin.claimed");
            color = DISABLED;
        } else {
            label = tr("blockland.checkin.claim");
            color = GO;
        }
        if (claimable) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(Ticker.global().time() * 4);
            Draw.glow(g, bx + BUTTON_W / 2f, by + BUTTON_H / 2f, BUTTON_W * 0.7f, BUTTON_H * 1.4f, ColorMath.fade(0x502FB257, alpha * (0.4f + 0.6f * pulse)));
        }
        Draw.rect(g, bx, by, BUTTON_W, BUTTON_H, ColorMath.fade(ColorMath.lighten(color, 0.15f * hv), alpha));
        Draw.outline(g, bx, by, BUTTON_W, BUTTON_H, ColorMath.fade(0x30FFFFFF, alpha));
        Draw.text(g, label, bx + BUTTON_W / 2f - UiFont.width(label) / 2f, by + 7, 1f, ColorMath.fade(TEXT, alpha), claimable);
        if (state != null && state.error().isEmpty() && state.claimedToday()) {
            String next = tr("blockland.checkin.next", countdown(ClientCheckIn.millisToNextDay()));
            Draw.text(g, next, px + pw / 2f - UiFont.width(next) * 0.8f / 2f, by + BUTTON_H + 3, 0.8f, ColorMath.fade(DIM, alpha), false);
        }
    }

    // ---- the celebration ------------------------------------------------------------------

    private void celebrate(CheckInPackets.S2CState state, int index) {
        celebrated = index;
        CheckInPackets.Day day = state.days().get(index);
        String itemName = Tr.text(day.itemName());
        celebratedName = !day.label().isEmpty() ? day.label() : itemName;
        celebratedEarned = state.justEarned().stream().map(Tr::text).filter(s -> !s.isEmpty()).toList();
        Anim.killTweensOf(stamp, streakShown, rise, flash);
        Anim.fromTo(stamp, 0, 1, 0.45).ease(Ease.BACK_OUT).delay(0.05);
        Anim.fromTo(streakShown, Math.max(0, state.streak() - 1), state.streak(), 0.7).ease(Ease.CUBIC_OUT).delay(0.15);
        Anim.fromTo(rise, 0, 1, 1.8).ease(Ease.CUBIC_OUT).delay(0.2);
        Anim.fromTo(flash, 1, 0, 0.6).ease(Ease.QUAD_OUT);
        cardPunch.punch(0.16f);
        int x = gridX + index % cols * (cardW + GAP) + cardW / 2, y = gridY + index / cols * (cardH + GAP) + cardH / 2 - 4;
        int[] colors = {ACCENT, GOLD, 0xFFFFFFFF, OK, day.reward() && !day.rarity().isEmpty() ? InventoryPanel.rarityColor(day.rarity()) : ACCENT};
        for (int k = 0; k < 44; k++) {
            Spark spark = new Spark();
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = 50 + random.nextFloat() * 120;
            spark.x = x;
            spark.y = y;
            spark.vx = (float) Math.cos(angle) * speed;
            spark.vy = (float) Math.sin(angle) * speed - 40;
            spark.max = spark.life = 0.6f + random.nextFloat() * 0.6f;
            spark.size = random.nextFloat() < 0.3f ? 2 : 1;
            spark.color = colors[random.nextInt(colors.length)];
            sparks.add(spark);
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.3f, 0.5f));
    }

    private void celebration(GuiGraphics g, float dt, float alpha) {
        if (celebrated < 0) return;
        int x = gridX + celebrated % cols * (cardW + GAP), y = gridY + celebrated / cols * (cardH + GAP);
        float f = flash.get();
        if (f > 0.01f) {
            float r = cardW * (0.6f + 1.6f * (1 - f));
            Draw.glow(g, x + cardW / 2f, y + cardH / 2f, r, r, ColorMath.fade(0xB0FFE7A3, f * alpha));
        }
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.life -= dt;
            if (s.life <= 0) {
                sparks.remove(i);
                continue;
            }
            s.vy += 220 * dt;
            s.vx *= (float) Math.pow(0.35, dt);
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            float a = alpha * Math.min(1, s.life / s.max * 1.5f);
            int size = Math.round(s.size);
            g.fill(Math.round(s.x), Math.round(s.y), Math.round(s.x) + size, Math.round(s.y) + size, ColorMath.fade(s.color, a));
        }
        float t = rise.get();
        if (t < 1) {
            // the day's reward, and above it every milestone the check-in reached
            List<String> lines = new ArrayList<>();
            if (!celebratedName.isEmpty()) lines.add(tr("blockland.checkin.got", celebratedName));
            for (String earned : celebratedEarned) lines.add(tr("blockland.checkin.milestone_got", earned));
            float a = alpha * (t < 0.15f ? t / 0.15f : t > 0.7f ? (1 - t) / 0.3f : 1);
            for (int k = 0; k < lines.size(); k++) {
                String text = lines.get(k);
                float ty = y - 6 - t * 18 - k * 16;
                float tx = Math.max(px + 4, Math.min(px + pw - 4 - UiFont.width(text), x + cardW / 2f - UiFont.width(text) / 2f));
                Draw.rect(g, Math.round(tx) - 4, Math.round(ty) - 3, UiFont.width(text) + 8, 14, ColorMath.fade(0xE0141C29, a));
                Draw.text(g, text, tx, ty, 1f, ColorMath.fade(k == 0 && !celebratedName.isEmpty() ? GOLD : ACCENT, a), true);
            }
        }
    }

    // ---- input ------------------------------------------------------------------------------

    /** While open the panel takes every click: the button, the cross, or outside it to close. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (!open) return false;
        CheckInPackets.S2CState state = ClientCheckIn.state();
        if (inside(mouseX, mouseY, cx - 2, cy - 2, 14, 14) || !inside(mouseX, mouseY, px, py, pw, ph)) {
            click();
            close();
            return true;
        }
        if (inside(mouseX, mouseY, bx, by, BUTTON_W, BUTTON_H)) {
            if (state != null && !state.error().isEmpty()) {
                click();
                ClientCheckIn.request();
            } else if (ClientCheckIn.claimable() && !ClientCheckIn.claiming()) {
                click();
                ClientCheckIn.claim();
            }
        }
        return true;
    }

    private static String countdown(long millis) {
        long s = millis / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
