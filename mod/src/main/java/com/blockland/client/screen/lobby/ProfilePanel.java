package com.blockland.client.screen.lobby;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.profile.ClientProfile;
import com.blockland.client.profile.NameCards;
import com.blockland.client.ui.Draw;
import com.blockland.common.RankTier;
import com.blockland.network.packet.ProfilePackets;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The lobby's 个人主页 tab: the player's name card (big, with their best tier over all modes), their standing in each
 * ranked mode — every mode has a ladder of its own: tier, score, place in the mode, record; a row of the modes picks
 * which — a picker of the cards they own (animated ones play), and the top of the picked mode's ladder with their own
 * line highlighted. All of it comes from the server; choosing a card is a request it checks.
 */
public final class ProfilePanel {
    private static final int ROW_H = 18;
    private static final int CHIP_H = 16;
    private static final String CARD_SLOT = "card";

    private final Map<String, Spring> hover = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private float pickerScroll, boardScroll;
    private int pickerX, pickerY, pickerW, pickerH, pickerContent;
    private int boardX, boardY, boardW, boardH, boardContent;
    /** The ranked mode whose standing and ladder are shown ("" = none yet). */
    private String selectedMode = "";

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    /** The tab opened: profile and owned cards are asked for; the ladder once the profile says which modes are ranked. */
    public void open() {
        ClientProfile.request(ProfilePackets.Kind.PROFILE);
        ClientProfile.request(ProfilePackets.Kind.INVENTORY);
        if (!selectedMode.isEmpty()) ClientProfile.requestLeaderboard(selectedMode);
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, float alpha) {
        hits.clear();
        float time = (float) Ticker.global().time();
        ProfilePackets.S2CProfile profile = ClientProfile.profile();
        ProfilePackets.ModeRank rank = selected(profile);
        int leftW = Math.min(520, (int) (w * 0.6f));
        boardX = x + leftW + 14;
        boardY = y;
        boardW = w - leftW - 14;
        boardH = h;

        // ---- the card, big
        String name = profile != null && !profile.name().isEmpty() ? profile.name() : ClientLobbyState.selfName();
        RankTier tier = profile == null || !profile.ranked() ? null : bestTier(profile);
        int cardW = leftW, cardH = Math.round(cardW / NameCards.ASPECT);
        if (cardH > h * 0.4f) {
            cardH = Math.round(h * 0.4f);
            cardW = Math.round(cardH * NameCards.ASPECT);
        }
        Draw.glow(g, x + cardW / 2f, y + cardH / 2f, cardW * 0.6f, cardH * 0.9f, ColorMath.fade(0x30FFFFFF, alpha));
        NameCards.draw(g, profile == null ? "" : profile.card(), x, y, cardW, cardH, name, tier, time);

        // ---- the ranked modes, then the picked one's standing and record
        int sy = y + cardH + 10;
        modes(g, mouseX, mouseY, profile, x, sy, leftW, alpha);
        sy += CHIP_H + 6;
        stats(g, profile, rank, x, sy, leftW, alpha);

        // ---- card picker: the cards the player owns
        int py = sy + 64;
        Draw.text(g, tr("blockland.profile.cards"), x, py, 1f, ColorMath.fade(TEXT, alpha), false);
        String hint = tr("blockland.profile.cards_hint");
        Draw.text(g, hint, x + leftW - UiFont.width(hint), py, 1f, ColorMath.fade(DIM, alpha), false);
        pickerX = x;
        pickerY = py + 14;
        pickerW = leftW;
        pickerH = y + h - pickerY;
        picker(g, mouseX, mouseY, profile, name, alpha, time);

        // ---- the picked mode's ladder
        board(g, mouseX, mouseY, rank, alpha);
    }

    /**
     * The picked ranked mode (the first one until the player picks): its ladder is asked for whenever the pick changes.
     */
    private ProfilePackets.ModeRank selected(ProfilePackets.S2CProfile profile) {
        if (profile == null || profile.ranks().isEmpty()) return null;
        ProfilePackets.ModeRank found = null;
        for (ProfilePackets.ModeRank r : profile.ranks()) {
            if (r.modeId().equals(selectedMode)) found = r;
        }
        if (found == null) select(profile.ranks().get(0).modeId());
        return found != null ? found : profile.ranks().get(0);
    }

    private void select(String modeId) {
        if (modeId.equals(selectedMode)) return;
        selectedMode = modeId;
        boardScroll = 0;
        ClientProfile.requestLeaderboard(modeId);
    }

    /** The best tier the player holds in any mode (shown on their card), null when placed in none. */
    private static RankTier bestTier(ProfilePackets.S2CProfile profile) {
        RankTier best = null;
        for (ProfilePackets.ModeRank r : profile.ranks()) {
            if (r.matches() <= 0) continue;
            RankTier tier = RankTier.of(r.tier());
            if (best == null || tier.ordinal() > best.ordinal()) best = tier;
        }
        return best;
    }

    /** A row of the ranked modes; clicking one shows its standing and ladder. */
    private void modes(GuiGraphics g, int mouseX, int mouseY, ProfilePackets.S2CProfile profile, int x, int y, int w, float alpha) {
        if (profile == null || !profile.ranked()) return;
        if (profile.ranks().isEmpty()) {
            Draw.text(g, tr("blockland.profile.no_ranked_modes"), x, y + 4, 1f, ColorMath.fade(DIM, alpha), false);
            return;
        }
        int cx = x;
        for (ProfilePackets.ModeRank r : profile.ranks()) {
            String label = Draw.fit(r.title(), 140);
            int cw = UiFont.width(label) + 14;
            if (cx + cw > x + w) break;
            boolean on = r.modeId().equals(selectedMode);
            boolean over = inside(mouseX, mouseY, cx, y, cw, CHIP_H);
            float hv = spring("mode" + r.modeId(), over);
            Draw.rect(g, cx, y, cw, CHIP_H, ColorMath.fade(on ? 0x40FFB02E : CARD, alpha));
            if (on || hv > 0.01f) Draw.outline(g, cx, y, cw, CHIP_H, ColorMath.fade(on ? ACCENT : 0xFFFFFFFF, alpha * (on ? 1 : hv * 0.6f)));
            Draw.text(g, label, cx + 7, y + 4, 1f, ColorMath.fade(on ? ACCENT : TEXT, alpha), false);
            String id = r.modeId();
            if (!on) hits.add(new Hit(cx, y, cw, CHIP_H, () -> select(id)));
            cx += cw + 6;
        }
    }

    private void stats(GuiGraphics g, ProfilePackets.S2CProfile profile, ProfilePackets.ModeRank rank, int x, int y, int w, float alpha) {
        int boxW = (w - 3 * 8) / 4, boxH = 52;
        boolean placed = profile != null && profile.ranked() && rank != null && rank.matches() > 0;
        RankTier tier = placed ? RankTier.of(rank.tier()) : null;
        String[] labels = {tr("blockland.profile.tier"), tr("blockland.profile.score"), tr("blockland.profile.position"), tr("blockland.profile.record")};
        String[] values = {
                tier == null ? tr("blockland.profile.unranked") : tr(tier.langKey()),
                placed ? String.valueOf(rank.score()) : "—",
                placed && rank.position() > 0 ? tr("blockland.profile.position_value", rank.position()) : "—",
                rank == null ? "—" : tr("blockland.profile.record_value", rank.wins(), rank.matches(),
                        rank.matches() == 0 ? 0 : Math.round(rank.wins() * 100f / rank.matches()))};
        for (int i = 0; i < 4; i++) {
            int bx = x + i * (boxW + 8);
            Draw.rect(g, bx, y, boxW, boxH, ColorMath.fade(CARD, alpha));
            int accent = i == 0 && tier != null ? tier.color : i == 1 ? ACCENT : i == 2 ? INFO : MUTED;
            g.fill(bx, y, bx + 2, y + boxH, ColorMath.fade(accent, alpha));
            Draw.text(g, labels[i], bx + 8, y + 7, 1f, ColorMath.fade(DIM, alpha), false);
            float scale = 1.5f;
            String value = Draw.fit(values[i], (boxW - 14) / scale);
            Draw.text(g, value, bx + 8, y + 22, scale, ColorMath.fade(i == 0 && tier != null ? tier.color : TEXT, alpha), false);
        }
        if (profile != null) {
            long hours = profile.playtimeSeconds() / 3600, minutes = profile.playtimeSeconds() / 60 % 60;
            String play = tr("blockland.profile.playtime", hours, minutes);
            Draw.text(g, play, x, y + boxH + 4, 1f, ColorMath.fade(DIM, alpha), false);
        }
        if (profile != null && !profile.ranked()) {
            String off = tr("blockland.profile.ranked_off");
            Draw.text(g, off, x + w - UiFont.width(off), y + boxH + 4, 1f, ColorMath.fade(DIM, alpha), false);
        }
    }

    private void picker(GuiGraphics g, int mouseX, int mouseY, ProfilePackets.S2CProfile profile, String name, float alpha, float time) {
        Draw.rect(g, pickerX, pickerY, pickerW, pickerH, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, pickerX, pickerY, pickerW, pickerH, ColorMath.fade(LINE, alpha));
        List<ProfilePackets.Item> cards = ClientProfile.inventory().stream().filter(i -> i.slots().contains(CARD_SLOT)).toList();
        if (cards.isEmpty()) {
            String empty = tr(ClientProfile.inventoryLoaded() ? "blockland.profile.no_cards" : "blockland.inventory.loading");
            Draw.text(g, empty, pickerX + (pickerW - UiFont.width(empty)) / 2f, pickerY + pickerH / 2f - 4, 1f, ColorMath.fade(MUTED, alpha), false);
            return;
        }
        String worn = profile == null ? "" : profile.card();
        int cols = Math.max(1, (pickerW - 8) / 150);
        int cw = (pickerW - 8 - (cols - 1) * 6) / cols, ch = Math.round(cw / NameCards.ASPECT);
        int rows = (cards.size() + cols - 1) / cols;
        pickerContent = rows * (ch + 18);
        pickerScroll = clampScroll(pickerScroll, pickerContent, pickerH - 8);
        g.enableScissor(pickerX + 1, pickerY + 1, pickerX + pickerW - 1, pickerY + pickerH - 1);
        for (int i = 0; i < cards.size(); i++) {
            ProfilePackets.Item card = cards.get(i);
            String id = card.icon().startsWith("card:") ? card.icon().substring(5) : "";
            int cx = pickerX + 4 + (i % cols) * (cw + 6);
            int cy = pickerY + 4 + (i / cols) * (ch + 18) - Math.round(pickerScroll);
            if (cy + ch + 14 < pickerY || cy > pickerY + pickerH) continue;
            boolean on = card.equipped().contains(CARD_SLOT) || (worn.isEmpty() && "default".equals(id));
            boolean over = inside(mouseX, mouseY, cx, cy, cw, ch + 14) && inside(mouseX, mouseY, pickerX, pickerY, pickerW, pickerH);
            float hv = spring("card" + card.key(), over);
            NameCards.draw(g, id, cx, cy, cw, ch, name, null, time);
            if (on || hv > 0.01f) Draw.outline(g, cx - 1, cy - 1, cw + 2, ch + 2, ColorMath.fade(on ? ACCENT : 0xFFFFFFFF, alpha * (on ? 1 : hv)));
            String label = Draw.fit(card.name().getString(), cw - 4);
            Draw.text(g, label, cx, cy + ch + 3, 1f, ColorMath.fade(on ? ACCENT : InventoryPanel.rarityColor(card.rarity()), alpha), false);
            if (card.until() > 0) {
                String left = InventoryPanel.expiry(card.until());
                Draw.text(g, left, cx + cw - UiFont.width(left), cy + ch + 3, 1f, ColorMath.fade(DIM, alpha), false);
            }
            String key = card.key();
            if (!on) {
                hits.add(new Hit(cx, Math.max(cy, pickerY), cw, Math.min(cy + ch + 14, pickerY + pickerH) - Math.max(cy, pickerY),
                        () -> ClientProfile.equip(CARD_SLOT, key)));
            }
        }
        g.disableScissor();
    }

    private void board(GuiGraphics g, int mouseX, int mouseY, ProfilePackets.ModeRank rank, float alpha) {
        Draw.rect(g, boardX, boardY, boardW, boardH, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, boardX, boardY, boardW, boardH, ColorMath.fade(LINE, alpha));
        g.fill(boardX + 8, boardY + 9, boardX + 11, boardY + 19, ColorMath.fade(ACCENT, alpha));
        String title = rank == null ? tr("blockland.profile.leaderboard") : tr("blockland.profile.leaderboard_mode", rank.title());
        Draw.text(g, Draw.fit(title, boardW - 24), boardX + 16, boardY + 10, 1f, ColorMath.fade(TEXT, alpha), false);
        boolean current = rank != null && rank.modeId().equals(ClientProfile.leaderboardMode());
        List<ProfilePackets.Line> lines = current ? ClientProfile.leaderboard() : List.of();
        int top = boardY + 28;
        int viewH = boardY + boardH - 6 - top;
        if (lines.isEmpty()) {
            boolean loading = rank != null && !(current && ClientProfile.leaderboardLoaded());
            String empty = tr(loading ? "blockland.inventory.loading" : "blockland.profile.leaderboard_empty");
            Draw.text(g, empty, boardX + (boardW - UiFont.width(empty)) / 2f, top + 20, 1f, ColorMath.fade(MUTED, alpha), false);
            return;
        }
        String self = ClientLobbyState.selfId() == null ? "" : ClientLobbyState.selfId().toString();
        boardContent = lines.size() * ROW_H;
        boardScroll = clampScroll(boardScroll, boardContent, viewH);
        g.enableScissor(boardX + 1, top, boardX + boardW - 1, top + viewH);
        float ly = top - boardScroll;
        for (ProfilePackets.Line line : lines) {
            if (ly + ROW_H > top && ly < top + viewH) {
                boolean mine = line.uuid().equals(self);
                RankTier tier = RankTier.of(line.tier());
                int rx = boardX + 6, rw = boardW - 12, ry = Math.round(ly);
                if (mine) Draw.rect(g, rx, ry, rw, ROW_H - 2, ColorMath.fade(0x40FFB02E, alpha));
                else if (line.position() % 2 == 0) Draw.rect(g, rx, ry, rw, ROW_H - 2, ColorMath.fade(0x10FFFFFF, alpha));
                String pos = "#" + line.position();
                int podium = line.position() == 1 ? GOLD : line.position() == 2 ? 0xFFD0D6DE : line.position() == 3 ? 0xFFCD8A52 : MUTED;
                Draw.text(g, pos, rx + 4, ry + 4, 1f, ColorMath.fade(podium, alpha), false);
                g.fill(rx + 36, ry + 5, rx + 42, ry + 11, ColorMath.fade(tier.color, alpha));
                String score = String.valueOf(line.score());
                Draw.text(g, Draw.fit(line.name(), rw - 90 - UiFont.width(score)), rx + 48, ry + 4, 1f,
                        ColorMath.fade(mine ? ACCENT : TEXT, alpha), false);
                Draw.text(g, score, rx + rw - 4 - UiFont.width(score), ry + 4, 1f, ColorMath.fade(tier.color, alpha), false);
            }
            ly += ROW_H;
        }
        g.disableScissor();
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (hit.h > 0 && inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (inside(mouseX, mouseY, pickerX, pickerY, pickerW, pickerH)) {
            pickerScroll = clampScroll(pickerScroll - (float) amount * 30, pickerContent, pickerH - 8);
            return true;
        }
        if (inside(mouseX, mouseY, boardX, boardY, boardW, boardH)) {
            boardScroll = clampScroll(boardScroll - (float) amount * 30, boardContent, boardH - 34);
            return true;
        }
        return false;
    }

    private static float clampScroll(float value, int content, int view) {
        return Math.max(0, Math.min(value, Math.max(0, content - view)));
    }

    private float spring(String key, boolean on) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(0).config(320, 26));
        spring.setTarget(on ? 1 : 0);
        return spring.get();
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
