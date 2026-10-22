package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Stagger;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.ClientMedia;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.LobbyGroupEntry;
import com.blockland.network.packet.LobbyModeEntry;
import com.blockland.network.packet.MediaPackets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The "选择游戏模式" column: group cards and ungrouped mode cards; opening a group shows only its modes.
 * Cards have spring-driven hover/press feedback and the level change is an animated slide + stagger.
 */
public final class ModeBrowser {
    private static final int MODE_H = 64;
    private static final int GROUP_H = 78;
    private static final int GAP = 7;
    private static final int HEADER_H = 50;

    /** Remembered across lobby openings, like a real menu. */
    private static String openGroupId;

    private final Font font;
    private final Map<String, CardFx> fx = new HashMap<>();
    private final Spring scroll = new Spring(0).config(240, 30);
    private final Spring backHover = new Spring(0).config(300, 26);
    private final AnimValue listAlpha = new AnimValue(1);
    private final AnimValue listShift = new AnimValue(0);
    private String shownGroupId = openGroupId;
    private boolean transitioning;
    @Nullable
    private String pressedKey;
    private int x, y, w, h;

    /** Per-card animation state. */
    private static final class CardFx {
        final Spring hover = new Spring(0).config(320, 26);
        final Spring press = new Spring(0).config(520, 30);
        final AnimValue enter = new AnimValue(1);
        final AnimValue selected = new AnimValue(0);
    }

    private sealed interface Item permits GroupItem, ModeItem {
        String key();
    }

    private record GroupItem(LobbyGroupEntry group) implements Item {
        public String key() {
            return "g:" + group.id();
        }
    }

    private record ModeItem(LobbyModeEntry mode) implements Item {
        public String key() {
            return "m:" + mode.id();
        }
    }

    public enum Result { NONE, SELECTED }

    public ModeBrowser(Font font) {
        this.font = font;
    }

    // ---- content --------------------------------------------------------------------------------

    private List<Item> items() {
        List<Item> items = new ArrayList<>();
        if (shownGroupId != null) {
            LobbyGroupEntry group = ClientLobbyState.group(shownGroupId).orElse(null);
            if (group == null) {
                shownGroupId = null;
                openGroupId = null;
            } else {
                for (String id : group.modeIds()) ClientLobbyState.mode(id).ifPresent(m -> items.add(new ModeItem(m)));
                return items;
            }
        }
        Set<String> grouped = new HashSet<>();
        for (LobbyGroupEntry group : ClientLobbyState.groups()) {
            items.add(new GroupItem(group));
            grouped.addAll(group.modeIds());
        }
        for (LobbyModeEntry mode : ClientLobbyState.modes()) {
            if (!grouped.contains(mode.id())) items.add(new ModeItem(mode));
        }
        return items;
    }

    /** Modes the player can currently pick (used to keep a valid selection). */
    public List<LobbyModeEntry> visibleModes() {
        return items().stream().filter(i -> i instanceof ModeItem).map(i -> ((ModeItem) i).mode()).toList();
    }

    public boolean isInGroup() {
        return shownGroupId != null;
    }

    private CardFx fx(Item item) {
        return fx.computeIfAbsent(item.key(), k -> new CardFx());
    }

    private static int height(Item item) {
        return item instanceof GroupItem ? GROUP_H : MODE_H;
    }

    private int listTop() {
        return y + (shownGroupId != null ? HEADER_H : 0);
    }

    private int contentHeight(List<Item> items) {
        int total = 0;
        for (Item item : items) total += height(item) + GAP;
        return Math.max(0, total - GAP);
    }

    // ---- animation ------------------------------------------------------------------------------

    /** Cards fly in one after another (screen open). */
    public void playIntro(double delay) {
        List<Item> items = items();
        for (Item item : items) fx(item).enter.snap(0);
        Stagger.of(items, 0.045, (item, i) -> Anim.to(fx(item).enter, 1, 0.55).ease(Ease.QUART_OUT)).delay(delay);
    }

    private void changeLevel(@Nullable String groupId, int direction) {
        if (transitioning) return;
        transitioning = true;
        Anim.timeline()
                .add(Anim.to(listAlpha, 0, 0.12).ease(Ease.QUAD_IN), 0)
                .add(Anim.to(listShift, -direction * 18, 0.12).ease(Ease.QUAD_IN), 0)
                .call(() -> {
                    shownGroupId = groupId;
                    openGroupId = groupId;
                    scroll.set(0);
                    listShift.snap(direction * 26);
                    playIntro(0);
                }, ">")
                .add(Anim.to(listAlpha, 1, 0.2).ease(Ease.QUAD_OUT), ">")
                .add(Anim.to(listShift, 0, 0.45).ease(Ease.EXPO_OUT), "<")
                .call(() -> transitioning = false, "<0.12");
    }

    // ---- rendering ------------------------------------------------------------------------------

    public void render(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph, String selectedModeId, float alpha) {
        x = px;
        y = py;
        w = pw;
        h = ph;
        List<Item> items = items();
        int top = listTop();
        int maxScroll = Math.max(0, contentHeight(items) - (y + h - top));
        if (scroll.target() > maxScroll) scroll.setTarget(maxScroll);
        float a = alpha * listAlpha.get();

        g.pose().pushMatrix();
        g.pose().translate(listShift.get(), 0);
        if (shownGroupId != null) renderHeader(g, mouseX, mouseY, a);
        g.pose().popMatrix();

        g.enableScissor(x - 6, top, x + w + 6, y + h); // fixed on screen while the list slides
        g.pose().pushMatrix();
        g.pose().translate(listShift.get(), 0);
        float cy = top - scroll.get();
        boolean listHovered = !transitioning && mouseY >= top && mouseY < y + h;
        for (Item item : items) {
            int ih = height(item);
            CardFx f = fx(item);
            boolean hovered = listHovered && inside(mouseX, mouseY, x, cy, w, ih);
            f.hover.setTarget(hovered ? 1 : 0);
            boolean selected = item instanceof ModeItem m && m.mode().id().equals(selectedModeId);
            f.selected.toIfChanged(selected ? 1 : 0, 0.3, Ease.CUBIC_OUT);
            if (cy + ih >= top && cy <= y + h) {
                if (item instanceof GroupItem group) drawGroup(g, group.group(), x, cy, w, ih, f, a);
                else drawMode(g, ((ModeItem) item).mode(), x, cy, w, ih, f, a);
            }
            cy += ih + GAP;
        }
        g.pose().popMatrix();
        g.disableScissor();

        if (maxScroll > 0) {
            int trackH = y + h - top;
            int barH = Math.max(18, trackH * trackH / (trackH + maxScroll));
            int barY = top + Math.round((trackH - barH) * (scroll.get() / maxScroll));
            Draw.rect(g, x + w + 3, barY, 2, barH, ColorMath.fade(0x66FFFFFF, a));
        }
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY, float a) {
        LobbyGroupEntry group = ClientLobbyState.group(shownGroupId).orElse(null);
        if (group == null) return;
        boolean hovered = !transitioning && inside(mouseX, mouseY, x, y, 84, 14);
        backHover.setTarget(hovered ? 1 : 0);
        float hv = backHover.get();
        int color = ColorMath.fade(ColorMath.mix(MUTED, ACCENT, hv), a);
        Draw.text(g, font, "‹", x - hv * 3, y + 2, 1.2f, color, false);
        Draw.text(g, font, "全部模式", x + 10, y + 3, 1f, color, false);
        g.fill(x + 10, y + 13, x + 10 + Math.round(font.width("全部模式") * hv), y + 14, ColorMath.fade(ACCENT, a));

        Draw.text(g, font, Draw.fit(font, group.title(), (int) ((w - 4) / 1.35f)), x, y + 20, 1.35f, ColorMath.fade(TEXT, a), true);
        if (!group.description().isEmpty()) {
            Draw.text(g, font, Draw.fit(font, group.description(), w), x, y + 35, 1f, ColorMath.fade(DIM, a), false);
        }
    }

    private void drawMode(GuiGraphics g, LobbyModeEntry m, int cx, float cy, int cw, int ch, CardFx f, float alpha) {
        float hov = f.hover.get(), sel = f.selected.get(), press = f.press.get(), enter = f.enter.get();
        float a = alpha * Math.max(0, Math.min(1, enter));
        if (a <= 0.01f) return;
        boolean limited = m.isLimited();
        boolean available = m.available() && !LimitedTime.isOver(m.availableUntil());
        int accent = limited ? GOLD : m.accentColor() | 0xFF000000;

        g.pose().pushMatrix();
        g.pose().translate(cx + cw / 2f + (1 - enter) * 22 + hov * 2, cy + ch / 2f);
        float scale = 1 - press * 0.025f;
        g.pose().scale(scale, scale);
        g.pose().translate(-cw / 2f, -ch / 2f);

        int base = ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hov), CARD_SELECTED, sel);
        Draw.glow(g, 0, ch / 2f, 40, ch * 0.9f, ColorMath.fade(accent, 0.22f * sel * a));
        Draw.rect(g, 0, 0, cw, ch, ColorMath.fade(base, a));

        // cover on the right, blended into the card
        ClientMedia.Image cover = ClientMedia.get(MediaPackets.MODE, m.id(), m.coverVersion());
        int coverX = Math.round(cw * 0.34f);
        if (cover != null) {
            float coverAlpha = (available ? 0.95f : 0.35f) * (0.85f + 0.15f * Math.max(hov, sel));
            Draw.cover(g, cover, coverX, 1, cw - coverX - 1, ch - 2, 1 + 0.07f * hov, ColorMath.fade(0xFFFFFFFF, a * coverAlpha));
            Draw.fadeX(g, coverX, 1, Math.round((cw - coverX) * 0.75f), ch - 2, ColorMath.fade(base, a), true);
            Draw.fadeY(g, coverX, ch / 2, cw - coverX - 1, ch / 2 - 1, ColorMath.fade(0x99000000, a), false);
        }

        // accent bar + selection outline
        int bar = Math.round(2 + 2 * Math.max(hov, sel));
        Draw.rect(g, 0, 0, bar, ch, ColorMath.fade(available ? accent : DIM, a * (0.55f + 0.45f * Math.max(hov, sel))));
        if (sel > 0.01f) Draw.outline(g, 0, 0, cw, ch, ColorMath.fade(accent, a * 0.75f * sel));

        // text
        int textMax = cw - 24;
        Draw.text(g, font, Draw.fit(font, m.title(), (int) (textMax / 1.2f)), 12, 9, 1.2f, ColorMath.fade(available ? TEXT : MUTED, a), true);
        String time = m.timeLimitSeconds() > 0 ? "限时 " + clock(m.timeLimitSeconds()) : "不限时";
        Draw.text(g, font, m.formatLabel() + "  ·  " + m.requiredPlayers() + " 人  ·  " + time, 12, 26, 1f, ColorMath.fade(MUTED, a), false);
        if (!available) {
            Draw.text(g, font, Draw.fit(font, m.unavailableReason(), textMax), 12, 44, 1f, ColorMath.fade(ERROR, a), false);
        } else {
            float pulse = (float) (0.5 + 0.5 * Math.sin(Ticker.global().time() * 4));
            int dot = m.playing() > 0 ? OK : m.queued() > 0 ? ACCENT : DIM;
            float dotAlpha = m.playing() > 0 || m.queued() > 0 ? 0.55f + 0.45f * pulse : 0.6f;
            Draw.glow(g, 15, 48, 5, 5, ColorMath.fade(dot, a * 0.5f * dotAlpha));
            Draw.rect(g, 13, 46, 4, 4, ColorMath.fade(dot, a * dotAlpha));
            Draw.text(g, font, "排队 " + m.queued() + "/" + m.requiredPlayers() + "    游戏中 " + m.playing(), 22, 44, 1f,
                    ColorMath.fade(m.queued() > 0 ? ACCENT : DIM, a), false);
        }

        if (limited) {
            // a thin gold frame marks limited-time modes even when the card is not selected
            Draw.outline(g, 0, 0, cw, ch, ColorMath.fade(GOLD, a * (0.35f + 0.4f * Math.max(hov, sel))));
            LimitedTime.modeBadge(g, font, m.availableUntil(), cw - 6, ch - 17, a);
        }

        // type tag, readable on top of the cover
        int tagW = font.width(m.typeName()) + 8;
        Draw.rect(g, cw - tagW - 6, 6, tagW, 12, ColorMath.fade(0xAA05080D, a));
        Draw.text(g, font, m.typeName(), cw - tagW - 2, 8, 1f, ColorMath.fade(accent, a), false);
        if (m.hasKit()) {
            int kitW = font.width("装备") + 8;
            Draw.rect(g, cw - tagW - kitW - 10, 6, kitW, 12, ColorMath.fade(0xAA05080D, a));
            Draw.text(g, font, "装备", cw - tagW - kitW - 6, 8, 1f, ColorMath.fade(MUTED, a), false);
        }
        g.pose().popMatrix();
    }

    private void drawGroup(GuiGraphics g, LobbyGroupEntry group, int cx, float cy, int cw, int ch, CardFx f, float alpha) {
        float hov = f.hover.get(), press = f.press.get(), enter = f.enter.get();
        float a = alpha * Math.max(0, Math.min(1, enter));
        if (a <= 0.01f) return;

        g.pose().pushMatrix();
        g.pose().translate(cx + cw / 2f + (1 - enter) * 22 + hov * 2, cy + ch / 2f);
        float scale = 1 - press * 0.025f;
        g.pose().scale(scale, scale);
        g.pose().translate(-cw / 2f, -ch / 2f);

        Draw.glow(g, cw * 0.3f, ch / 2f, cw * 0.5f, ch, ColorMath.fade(ACCENT, 0.12f * hov * a));
        Draw.rect(g, 0, 0, cw, ch, ColorMath.fade(CARD, a));
        ClientMedia.Image cover = ClientMedia.get(MediaPackets.GROUP, group.id(), group.coverVersion());
        if (cover != null) {
            Draw.cover(g, cover, 1, 1, cw - 2, ch - 2, 1 + 0.06f * hov, ColorMath.fade(0xFFFFFFFF, a * (0.8f + 0.2f * hov)));
        }
        // legibility: dark on the left, clear on the right
        Draw.fadeX(g, 1, 1, Math.round(cw * 0.9f), ch - 2, ColorMath.fade(0xF0070B12, a), true);
        Draw.fadeX(g, 1, 1, Math.round(cw * 0.55f), ch - 2, ColorMath.fade(0xA0070B12, a), true);
        Draw.fadeY(g, 1, ch / 2, cw - 2, ch / 2 - 1, ColorMath.fade(0x80000000, a), false);

        Draw.rect(g, 0, 0, Math.round(2 + 2 * hov), ch, ColorMath.fade(ACCENT, a * (0.6f + 0.4f * hov)));
        if (hov > 0.01f) Draw.outline(g, 0, 0, cw, ch, ColorMath.fade(ACCENT, a * 0.45f * hov));

        Draw.text(g, font, Draw.fit(font, group.title(), (int) ((cw - 40) / 1.4f)), 12, 10, 1.4f, ColorMath.fade(TEXT, a), true);
        if (!group.description().isEmpty()) {
            Draw.text(g, font, Draw.fit(font, group.description(), cw - 44), 12, 30, 1f, ColorMath.fade(MUTED, a), false);
        }
        int queued = 0, playing = 0;
        for (String id : group.modeIds()) {
            LobbyModeEntry mode = ClientLobbyState.mode(id).orElse(null);
            if (mode != null) {
                queued += mode.queued();
                playing += mode.playing();
            }
        }
        Draw.text(g, font, group.modeIds().size() + " 个模式  ·  排队 " + queued + "  ·  游戏中 " + playing, 12, ch - 17, 1f,
                ColorMath.fade(ColorMath.mix(MUTED, TEXT, hov), a), true);
        Draw.text(g, font, "›", cw - 18 + hov * 5, ch / 2f - 7, 1.8f, ColorMath.fade(ColorMath.mix(MUTED, ACCENT, hov), a), true);
        long soonest = 0;
        for (String id : group.modeIds()) {
            LobbyModeEntry mode = ClientLobbyState.mode(id).orElse(null);
            if (mode != null && mode.isLimited() && (soonest == 0 || mode.availableUntil() < soonest)) soonest = mode.availableUntil();
        }
        if (soonest > 0) {
            int bw = font.width("限时") + 10;
            LimitedTime.badge(g, font, "限时", cw - bw - 26, 8, a);
        }
        g.pose().popMatrix();
    }

    // ---- input ----------------------------------------------------------------------------------

    /**
     * @param select receives the clicked mode id
     */
    public Result mouseClicked(double mouseX, double mouseY, java.util.function.Consumer<String> select) {
        if (transitioning) return Result.NONE;
        if (shownGroupId != null && inside(mouseX, mouseY, x, y, 84, 14)) {
            changeLevel(null, -1);
            return Result.NONE;
        }
        int top = listTop();
        if (mouseY < top || mouseY >= y + h) return Result.NONE;
        float cy = top - scroll.get();
        for (Item item : items()) {
            int ih = height(item);
            if (inside(mouseX, mouseY, x, cy, w, ih)) {
                CardFx f = fx(item);
                f.press.setTarget(1);
                pressedKey = item.key();
                if (item instanceof GroupItem group) {
                    changeLevel(group.group().id(), 1);
                } else {
                    select.accept(((ModeItem) item).mode().id());
                    f.hover.impulse(6);
                }
                return Result.SELECTED;
            }
            cy += ih + GAP;
        }
        return Result.NONE;
    }

    public void mouseReleased() {
        if (pressedKey != null) {
            CardFx f = fx.get(pressedKey);
            if (f != null) f.press.setTarget(0);
            pressedKey = null;
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!inside(mouseX, mouseY, x, y, w, h)) return false;
        List<Item> items = items();
        int maxScroll = Math.max(0, contentHeight(items) - (y + h - listTop()));
        scroll.setTarget(Math.max(0, Math.min(maxScroll, scroll.target() - (float) amount * 34)));
        return true;
    }

    /** Leaves an open group (ESC); returns false when already at the top level. */
    public boolean back() {
        if (shownGroupId == null || transitioning) return false;
        changeLevel(null, -1);
        return true;
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String clock(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
