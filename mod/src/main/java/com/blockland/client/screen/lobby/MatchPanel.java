package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimColor;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.ClientMedia;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.client.ui.Tr;
import com.blockland.network.packet.LobbyModeEntry;
import com.blockland.network.packet.MediaPackets;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * Bottom-right block: information about the selected (or queued) mode, the map choice ("any map" or one of the mode's
 * maps, kept per mode) and the big matchmaking button. The button colour morphs between states, the label
 * cross-fades, and while queued a light sweep runs across it.
 */
public final class MatchPanel {
    private static final int KIT_W = 60;
    private static final int KIT_H = 15;
    /** Height of the map row between the info block and the button. */
    public static final int MAP_H = 22;
    private static final int LIST_ROW = 20;
    private static final int LIST_MAX = 7;

    public enum Action { NONE, CONSUMED, MATCH, KIT }

    private final AnimColor buttonColor = new AnimColor(DISABLED);
    private final Spring hover = new Spring(0).config(300, 24);
    private final Spring press = new Spring(0).config(600, 28);
    private final Spring kitHover = new Spring(0).config(300, 24);
    private final AnimValue labelIn = new AnimValue(1);
    private final AnimValue timerPop = new AnimValue(1);
    private final AnimValue infoIn = new AnimValue(1);
    private final AnimValue listIn = new AnimValue(0);
    private final Map<String, Spring> rowHover = new HashMap<>();
    private String label = "";
    private String infoKey = "";
    private int lastQueuedSeconds = -1;
    private int x, infoY, w, infoH, mapY, btnY, btnH;
    private boolean kitVisible;
    private boolean listOpen;
    @Nullable
    private LobbyModeEntry mapMode;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public MatchPanel() {
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph, int my, int by, int bh,
                       @Nullable LobbyModeEntry selected, float alpha) {
        x = px;
        infoY = py;
        w = pw;
        infoH = ph;
        mapY = my;
        btnY = by;
        btnH = bh;
        hits.clear();
        boolean queued = ClientLobbyState.isQueued();
        boolean inGame = ClientLobbyState.inGame();
        LobbyModeEntry shown = queued ? ClientLobbyState.mode(ClientLobbyState.queuedModeId()).orElse(selected) : selected;

        // info swaps slide in when the content changes
        String key = (queued ? "q:" : "s:") + (shown == null ? "" : shown.id());
        if (!key.equals(infoKey)) {
            if (!infoKey.isEmpty()) Anim.fromTo(infoIn, 0, 1, 0.35).ease(Ease.CUBIC_OUT);
            infoKey = key;
            setList(false);
        }
        renderInfo(g, mouseX, mouseY, shown, queued, alpha);
        renderMapRow(g, mouseX, mouseY, shown, queued || inGame, alpha);

        // button state
        String newLabel;
        int color;
        boolean enabled = true;
        if (inGame) {
            newLabel = tr("blockland.match.loading");
            color = DISABLED;
            enabled = false;
        } else if (queued) {
            newLabel = tr("blockland.match.cancel");
            color = CANCEL;
        } else if (!ClientLobbyState.isLeader()) {
            newLabel = tr("blockland.match.wait_leader");
            color = DISABLED;
            enabled = false;
        } else if (selected != null && selected.available()) {
            newLabel = tr("blockland.match.start");
            color = GO;
        } else {
            newLabel = tr("blockland.match.start");
            color = DISABLED;
            enabled = false;
        }
        if (!newLabel.equals(label)) {
            if (!label.isEmpty()) Anim.fromTo(labelIn, 0, 1, 0.3).ease(Ease.CUBIC_OUT);
            label = newLabel;
        }
        buttonColor.toIfChanged(color, 0.35, Ease.QUAD_OUT);
        boolean hovered = enabled && inside(mouseX, mouseY, x, btnY, w, btnH);
        hover.setTarget(hovered ? 1 : 0);

        float hv = hover.get(), pr = press.get();
        int base = ColorMath.lighten(buttonColor.get(), 0.08f * hv);
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2f, btnY + btnH / 2f);
        float scale = 1 + 0.015f * hv - 0.03f * pr;
        g.pose().scale(scale, scale);
        g.pose().translate(-w / 2f, -btnH / 2f);
        Draw.glow(g, w / 2f, btnH / 2f, w * 0.6f, btnH * 1.1f, ColorMath.fade(buttonColor.get(), alpha * (enabled ? 0.18f + 0.22f * hv : 0)));
        Draw.rect(g, 0, 0, w, btnH, ColorMath.fade(base, alpha));
        Draw.fadeY(g, 0, 0, w, btnH / 2, ColorMath.fade(0x26FFFFFF, alpha), true);
        g.fill(0, btnH - 3, w, btnH, ColorMath.fade(0x40000000, alpha));
        if (queued || inGame) {
            // light sweep
            double t = (Ticker.global().time() * 0.55) % 1.0;
            float sx = (float) (-0.25 + t * 1.5) * w;
            g.enableScissor(0, 0, w, btnH); // scissor follows the pose
            Draw.glow(g, sx, btnH / 2f, w * 0.18f, btnH * 0.9f, ColorMath.fade(0x55FFFFFF, alpha));
            g.disableScissor();
        }
        float li = labelIn.get();
        float labelScale = 1.6f;
        float labelW = UiFont.width(label) * labelScale;
        Draw.text(g, label, (w - labelW) / 2, (btnH - UiFont.LINE_HEIGHT * labelScale) / 2 + 1 + (1 - li) * 6,
                labelScale, ColorMath.fade(enabled ? TEXT : MUTED, alpha * li), true);
        g.pose().popMatrix();

        renderMapList(g, mouseX, mouseY, alpha);
    }

    private void renderInfo(GuiGraphics g, int mouseX, int mouseY, @Nullable LobbyModeEntry mode, boolean queued, float alpha) {
        Draw.rect(g, x, infoY, w, infoH, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, x, infoY, w, infoH, ColorMath.fade(LINE, alpha));
        float a = alpha * infoIn.get();
        float shift = (1 - infoIn.get()) * 8;
        int tx = x + 10;
        kitVisible = false;

        if (queued) {
            int seconds = ClientLobbyState.queuedSeconds();
            if (seconds != lastQueuedSeconds) {
                if (lastQueuedSeconds >= 0) Anim.fromTo(timerPop, 1.12f, 1, 0.35).ease(Ease.BACK_OUT);
                lastQueuedSeconds = seconds;
            }
            float pulse = (float) (0.5 + 0.5 * Math.sin(Ticker.global().time() * 5));
            Draw.glow(g, tx + 3, infoY + 11, 6, 6, ColorMath.fade(ACCENT, a * 0.6f * pulse));
            Draw.rect(g, tx, infoY + 8, 5, 5, ColorMath.fade(ACCENT, a));
            String searching = tr("blockland.match.searching");
            String dots = ".".repeat((int) (Ticker.global().time() * 2.5 % 4));
            Draw.text(g, searching + dots, tx + 10 + shift, infoY + 7, 1f, ColorMath.fade(ACCENT, a), false);
            if (ClientLobbyState.priorityQueue()) {
                String priority = tr("blockland.match.priority");
                int badgeX = tx + 10 + UiFont.width(searching + "...") + 8;
                int badgeW = UiFont.width(priority) + 8;
                Draw.rect(g, badgeX, infoY + 5, badgeW, 12, ColorMath.fade(0x33FFD54F, a));
                Draw.text(g, priority, badgeX + 4, infoY + 7, 1f, ColorMath.fade(GOLD, a), false);
            }
            String timer = clock(seconds);
            float ts = 1.6f * timerPop.get();
            float tw = UiFont.width(timer) * ts;
            Draw.text(g, timer, x + w - 10 - tw, infoY + 6 - (ts - 1.6f) * 4, ts, ColorMath.fade(TEXT, a), true);
            if (mode != null) {
                Draw.text(g, Draw.fit(mode.title(), w - 20), tx + shift, infoY + 24, 1f, ColorMath.fade(TEXT, a), false);
                // queue fill bar
                float fill = Math.min(1, mode.queued() / (float) Math.max(1, mode.requiredPlayers()));
                int barW = w - 20;
                Draw.rect(g, tx, infoY + 40, barW, 3, ColorMath.fade(0x22FFFFFF, a));
                Draw.rect(g, tx, infoY + 40, Math.max(3, Math.round(barW * fill)), 3, ColorMath.fade(ACCENT, a));
                Draw.text(g, tr("blockland.match.queue_count", mode.queued(), mode.requiredPlayers()), tx, infoY + 46, 1f,
                        ColorMath.fade(MUTED, a), false);
            }
            return;
        }
        lastQueuedSeconds = -1;
        if (mode == null) {
            Draw.text(g, tr("blockland.match.pick_mode"), tx, infoY + infoH / 2f - 4, 1f, ColorMath.fade(MUTED, alpha), false);
            return;
        }
        int accent = mode.isLimited() ? GOLD : mode.accentColor() | 0xFF000000;
        Draw.text(g, tr("blockland.match.selected"), tx + shift, infoY + 7, 1f, ColorMath.fade(DIM, a), false);
        Draw.text(g, Draw.fit(mode.title(), (int) ((w - KIT_W - 30) / 1.25f)), tx + shift, infoY + 19, 1.25f,
                ColorMath.fade(TEXT, a), true);
        String desc = mode.description().isBlank() ? mode.typeName() + " · " + Tr.text(mode.formatLabel()) : mode.description();
        if (mode.isLimited()) {
            // limited-time mode: when it ends replaces the description line
            String ends = LimitedTime.isOver(mode.availableUntil()) ? tr("blockland.match.limited_over")
                    : LimitedTime.remaining(mode.availableUntil()) + " · " + LimitedTime.endsAt(mode.availableUntil());
            Draw.text(g, Draw.fit(ends, w - 20), tx + shift, infoY + 38, 1f, ColorMath.fade(GOLD, a), false);
            Draw.text(g, Draw.fit(desc, w - 20), tx + shift, infoY + 49, 1f, ColorMath.fade(DIM, a), false);
        } else {
            Draw.text(g, Draw.fit(desc, w - 20), tx + shift, infoY + 38, 1f, ColorMath.fade(MUTED, a), false);
        }
        g.fill(x, infoY + 4, x + 2, infoY + infoH - 4, ColorMath.fade(accent, a));

        if (mode.hasKit()) {
            kitVisible = true;
            int kx = kitX(), ky = kitY();
            kitHover.setTarget(inside(mouseX, mouseY, kx, ky, KIT_W, KIT_H) ? 1 : 0);
            float kh = kitHover.get();
            Draw.rect(g, kx, ky, KIT_W, KIT_H, ColorMath.fade(ColorMath.mix(0xFF243048, 0xFF34446A, kh), a));
            g.fill(kx + 1, ky + KIT_H - 1, kx + 1 + Math.round((KIT_W - 2) * (0.35f + 0.65f * kh)), ky + KIT_H, ColorMath.fade(ACCENT, a));
            String kit = tr("blockland.match.kit");
            float lw = UiFont.width(kit);
            Draw.text(g, kit, kx + (KIT_W - lw) / 2, ky + 4, 1f, ColorMath.fade(TEXT, a), false);
        }
    }

    // ---- map choice ---------------------------------------------------------------------------------

    /** "地图: ◀ name ▶" — arrows step through any map and the mode's maps, the name opens the full list. */
    private void renderMapRow(GuiGraphics g, int mouseX, int mouseY, @Nullable LobbyModeEntry mode, boolean locked, float alpha) {
        mapMode = mode;
        if (mode == null || mode.maps().isEmpty()) return;
        boolean queuedHere = ClientLobbyState.isQueued() && ClientLobbyState.queuedModeId().equals(mode.id());
        String current = queuedHere ? ClientLobbyState.queuedMapId() : ClientLobbyState.chosenMap(mode.id());
        Draw.rect(g, x, mapY, w, MAP_H, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, x, mapY, w, MAP_H, ColorMath.fade(listOpen ? ACCENT : LINE, alpha));
        String caption = tr("blockland.match.map");
        Draw.text(g, caption, x + 8, mapY + 7, 1f, ColorMath.fade(DIM, alpha), false);
        int arrowsLeft = x + 14 + UiFont.width(caption);
        int nameX = arrowsLeft + 16, nameW = x + w - 22 - nameX;
        LobbyModeEntry.MapOption option = option(mode, current);
        ClientMedia.Image cover = option == null || option.coverVersion().isEmpty() ? null
                : ClientMedia.get(MediaPackets.MAP, option.id(), option.coverVersion());
        int textX = nameX + 2;
        if (cover != null) {
            Draw.cover(g, cover, nameX, mapY + 3, 28, MAP_H - 6, 1f, ColorMath.fade(0xFFFFFFFF, alpha));
            textX = nameX + 32;
        }
        String name = option == null ? tr("blockland.match.map_any") : option.name();
        boolean nameHovered = !locked && inside(mouseX, mouseY, nameX, mapY, nameW, MAP_H);
        int nameColor = locked ? MUTED : nameHovered ? ACCENT : TEXT;
        Draw.text(g, Draw.fit(name, x + w - 24 - textX), textX, mapY + 7, 1f, ColorMath.fade(nameColor, alpha), false);
        if (locked) return;
        arrow(g, mouseX, mouseY, arrowsLeft, "‹", alpha, () -> step(mode, -1));
        arrow(g, mouseX, mouseY, x + w - 18, "›", alpha, () -> step(mode, 1));
        hits.add(new Hit(nameX, mapY, nameW, MAP_H, () -> setList(!listOpen)));
    }

    private void arrow(GuiGraphics g, int mouseX, int mouseY, int ax, String glyph, float alpha, Runnable action) {
        boolean hovered = inside(mouseX, mouseY, ax, mapY + 3, 12, MAP_H - 6);
        float hv = spring("arrow" + ax, hovered);
        Draw.rect(g, ax, mapY + 3, 12, MAP_H - 6, ColorMath.fade(ColorMath.mix(0xFF1A2333, 0xFF2A3650, hv), alpha));
        Draw.text(g, glyph, ax + 3.5f, mapY + 7, 1f, ColorMath.fade(ColorMath.mix(MUTED, ACCENT, hv), alpha), false);
        hits.add(new Hit(ax, mapY + 3, 12, MAP_H - 6, action));
    }

    /** The full list, opening upwards over the info block. */
    private void renderMapList(GuiGraphics g, int mouseX, int mouseY, float alpha) {
        float open = listIn.get();
        LobbyModeEntry mode = mapMode;
        if (open < 0.02f || mode == null || mode.maps().isEmpty()) return;
        List<LobbyModeEntry.MapOption> options = new ArrayList<>();
        options.add(null);
        options.addAll(mode.maps());
        int rows = Math.min(LIST_MAX, options.size());
        int listH = rows * LIST_ROW + 4;
        int top = mapY - 4 - listH;
        float a = alpha * open;
        g.pose().pushMatrix();
        g.pose().translate(0, (1 - open) * 6);
        Draw.glow(g, x + w / 2f, top + listH / 2f, w * 0.6f, listH * 0.7f, ColorMath.fade(0x66000000, a));
        Draw.rect(g, x, top, w, listH, ColorMath.fade(0xF50C121C, a));
        Draw.outline(g, x, top, w, listH, ColorMath.fade(ColorMath.withAlpha(ACCENT, 0x66), a));
        String current = ClientLobbyState.chosenMap(mode.id());
        for (int i = 0; i < rows; i++) {
            LobbyModeEntry.MapOption option = options.get(i);
            int ry = top + 2 + i * LIST_ROW;
            String id = option == null ? "" : option.id();
            boolean hovered = inside(mouseX, mouseY, x + 2, ry, w - 4, LIST_ROW);
            float hv = spring("row" + id, hovered);
            boolean chosen = id.equals(current);
            if (hv > 0.01f || chosen) {
                Draw.rect(g, x + 2, ry, w - 4, LIST_ROW, ColorMath.fade(chosen ? CARD_SELECTED : CARD_HOVER, a * Math.max(hv, chosen ? 1 : 0)));
            }
            int tx = x + 8;
            ClientMedia.Image cover = option == null || option.coverVersion().isEmpty() ? null
                    : ClientMedia.get(MediaPackets.MAP, option.id(), option.coverVersion());
            if (cover != null) {
                Draw.cover(g, cover, tx, ry + 2, 28, LIST_ROW - 4, 1f, ColorMath.fade(0xFFFFFFFF, a));
            } else {
                Draw.rect(g, tx, ry + 2, 28, LIST_ROW - 4, ColorMath.fade(option == null ? 0xFF243048 : 0xFF1A2333, a));
                if (option == null) Draw.text(g, "?", tx + 11.5f, ry + 6, 1f, ColorMath.fade(MUTED, a), false);
            }
            String name = option == null ? tr("blockland.match.map_any") : option.name();
            Draw.text(g, Draw.fit(name, w - 60), tx + 34, ry + 6, 1f, ColorMath.fade(chosen ? ACCENT : TEXT, a), false);
            if (chosen) Draw.text(g, "✔", x + w - 16, ry + 6, 1f, ColorMath.fade(ACCENT, a), false);
            hits.add(new Hit(x + 2, ry, w - 4, LIST_ROW, () -> {
                ClientLobbyState.chooseMap(mode.id(), id);
                setList(false);
            }));
        }
        g.pose().popMatrix();
    }

    private void step(LobbyModeEntry mode, int direction) {
        List<String> ids = new ArrayList<>();
        ids.add("");
        mode.maps().forEach(m -> ids.add(m.id()));
        int index = Math.max(0, ids.indexOf(ClientLobbyState.chosenMap(mode.id())));
        ClientLobbyState.chooseMap(mode.id(), ids.get(Math.floorMod(index + direction, ids.size())));
    }

    @Nullable
    private static LobbyModeEntry.MapOption option(LobbyModeEntry mode, String id) {
        if (id == null || id.isEmpty()) return null;
        return mode.maps().stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    private void setList(boolean open) {
        if (listOpen == open) return;
        listOpen = open;
        Anim.to(listIn, open ? 1 : 0, open ? 0.25 : 0.12).ease(open ? Ease.BACK_OUT : Ease.QUAD_IN);
    }

    public boolean isListOpen() {
        return listOpen;
    }

    // ---- input ----------------------------------------------------------------------------------------

    private int kitX() {
        return x + w - KIT_W - 8;
    }

    private int kitY() {
        return infoY + 6;
    }

    public Action mouseClicked(double mouseX, double mouseY) {
        // the last hits drawn are on top (the open map list)
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return Action.CONSUMED;
            }
        }
        if (listOpen) {
            setList(false);
            return Action.CONSUMED;
        }
        if (inside(mouseX, mouseY, x, btnY, w, btnH)) {
            press.setTarget(1);
            return Action.MATCH;
        }
        if (kitVisible && !ClientLobbyState.isQueued() && inside(mouseX, mouseY, kitX(), kitY(), KIT_W, KIT_H)) {
            return Action.KIT;
        }
        return Action.NONE;
    }

    public void mouseReleased() {
        press.setTarget(0);
    }

    /** Escape closes the map list first. */
    public boolean back() {
        if (!listOpen) return false;
        setList(false);
        return true;
    }

    private float spring(String key, boolean on) {
        Spring spring = rowHover.computeIfAbsent(key, k -> new Spring(0).config(320, 26));
        spring.setTarget(on ? 1 : 0);
        return spring.get();
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static String clock(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
