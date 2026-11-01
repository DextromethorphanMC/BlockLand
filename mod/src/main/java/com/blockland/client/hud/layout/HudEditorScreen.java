package com.blockland.client.hud.layout;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.settings.ClientSettings;
import com.blockland.client.ui.Draw;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The HUD editor (UI spec §3.1), over the live game: every registered {@link HudElement} drawn where it is (a sample
 * where it has nothing to show, greyed when turned off), outlined; dragged with the left mouse it moves, snapping its
 * edges and middle to the screen's edges (at the chosen margin), the screen's centre lines and the other elements'
 * edges and middles, with the guide lines it snapped to drawn (Alt drags freely). A resizable element
 * ({@link HudElement#resize}) has a grip in its bottom-right corner: dragged, its right and bottom edges follow (snapping
 * the same way), its top-left corner staying put. The arrow keys nudge the selected one (Shift: 10 px; with Ctrl they
 * resize it), a right click puts it back where it started, at its first size. A small toolbar: done, reset all,
 * snapping, margin, and for the selected element reset and show/hide. Esc or done saves and goes back.
 */
public final class HudEditorScreen extends Screen {
    /** How close (GUI px) an edge or middle has to come to a line to snap to it. */
    private static final int SNAP = 5;
    private static final int TOOL_H = 18;
    /** The resize grip's side (GUI px), in the box's bottom-right corner. */
    private static final int GRIP = 7;

    @Nullable
    private final Screen parent;
    @Nullable
    private HudElement selected, dragging, resizing;
    private double grabX, grabY;
    /** While resizing: the box's top-left corner, which stays put. */
    private int fixedX, fixedY;
    /** The lines the dragged element snapped to this frame: {position, colour}. */
    private final List<int[]> guidesX = new ArrayList<>(), guidesY = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    /** The toolbar's box this frame (clicks there never reach the elements). */
    private int toolX, toolY, toolW, toolH;

    private record Button(String label, int x, int y, int w, boolean accent, Runnable action) {
    }

    public HudEditorScreen(@Nullable Screen parent) {
        super(Component.translatable("blockland.hud.editor.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        HudLayout.setEditing(true);
    }

    @Override
    public void removed() {
        HudLayout.setEditing(false);
        ClientSettings.save();
    }

    @Override
    public void onClose() {
        ClientSettings.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static HudSettings settings() {
        return HudLayout.settings();
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /** A light dim over the game and the screen's centre lines, faint. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x30000000);
        g.fill(width / 2, 0, width / 2 + 1, height, 0x14FFFFFF);
        g.fill(0, height / 2, width, height / 2 + 1, 0x14FFFFFF);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        DeltaTracker delta = Minecraft.getInstance().getDeltaTracker();
        HudElement held = dragging != null ? dragging : resizing;
        HudElement over = held != null ? held : elementAt(mouseX, mouseY);
        for (HudElement e : HudLayout.elements()) {
            int[] at = HudLayout.place(e, width, height);
            int w = Math.max(1, e.width()), h = Math.max(1, e.height());
            e.render(g, delta, at[0], at[1], true);
            boolean on = HudLayout.enabled(e);
            if (!on) {
                g.fill(at[0], at[1], at[0] + w, at[1] + h, 0xA0101418);
                String hidden = tr("blockland.hud.editor.hidden");
                Draw.text(g, hidden, at[0] + (w - UiFont.width(hidden) * 0.75f) / 2f, at[1] + h / 2f - 3, 0.75f, DIM, false);
            }
            int edge = e == selected ? ACCENT : e == over ? 0xD0FFFFFF : on ? 0x50FFFFFF : 0x30FFFFFF;
            Draw.outline(g, at[0] - 1, at[1] - 1, w + 2, h + 2, edge);
            // the resize grip: on the selected element, and on the one under the mouse (fainter)
            if (e.resize() != null && (e == selected || e == over) && dragging == null) {
                boolean hot = e == resizing || resizing == null && overGrip(e, mouseX, mouseY);
                grip(g, at[0] + w, at[1] + h, hot ? 0xFFFFFFFF : e == selected ? ACCENT : 0x90FFFFFF);
            }
        }
        // the dragged element's guides
        for (int[] guide : guidesX) g.fill(guide[0], 0, guide[0] + 1, height, guide[1]);
        for (int[] guide : guidesY) g.fill(0, guide[0], width, guide[0] + 1, guide[1]);
        // the name of the one under the mouse (or held, or selected)
        HudElement named = over != null ? over : selected;
        if (named != null) label(g, named);
        if (resizing != null) sizeTag(g, resizing);
        if (held == null) toolbar(g, mouseX, mouseY);
    }

    /** The grip in a box's bottom-right corner ({@code right}, {@code bottom}): three short diagonal strokes. */
    private static void grip(GuiGraphics g, int right, int bottom, int color) {
        g.fill(right - GRIP, bottom - GRIP, right, bottom, 0xB0101418);
        for (int i = 0; i < 3; i++) {
            int len = 2 + i * 2;
            for (int k = 0; k < len; k++) {
                g.fill(right - 2 - k, bottom - 1 - len + k, right - 1 - k, bottom - len + k, color);
            }
        }
    }

    /** Whether (mx, my) is on the element's grip (a little beyond its corner too, so it is easy to catch). */
    private boolean overGrip(HudElement e, double mx, double my) {
        if (e.resize() == null) return false;
        int[] at = HudLayout.place(e, width, height);
        int right = at[0] + Math.max(1, e.width()), bottom = at[1] + Math.max(1, e.height());
        return inside(mx, my, right - GRIP, bottom - GRIP, GRIP + 3, GRIP + 3);
    }

    /** The element's size by its bottom-right corner while it is being resized. */
    private void sizeTag(GuiGraphics g, HudElement e) {
        int[] at = HudLayout.place(e, width, height);
        String text = e.width() + " × " + e.height();
        int tw = Math.round(UiFont.width(text) * 0.8f) + 8;
        int tx = Math.min(width - tw - 2, at[0] + e.width() + 4);
        int ty = Math.min(height - 13, at[1] + e.height() + 4);
        Draw.rect(g, tx, ty, tw, 11, 0xD0101418);
        Draw.text(g, text, tx + 4, ty + 2, 0.8f, ACCENT, false);
    }

    /** The element's name on a small tag over its box (under it near the top of the screen). */
    private void label(GuiGraphics g, HudElement e) {
        int[] at = HudLayout.place(e, width, height);
        String name = tr(e.nameKey());
        int tw = Math.round(UiFont.width(name) * 0.8f) + 8;
        int tx = Math.max(2, Math.min(width - tw - 2, at[0]));
        int ty = at[1] - 13 >= 2 ? at[1] - 13 : at[1] + Math.max(1, e.height()) + 3;
        Draw.rect(g, tx, ty, tw, 11, 0xD0101418);
        Draw.text(g, name, tx + 4, ty + 2, 0.8f, e == selected ? ACCENT : TEXT, false);
    }

    /** The toolbar: what it does, the selected element's own buttons, how to use the editor. */
    private void toolbar(GuiGraphics g, int mouseX, int mouseY) {
        buttons.clear();
        HudSettings s = settings();
        List<Button> top = new ArrayList<>();
        top.add(button(tr("blockland.hud.editor.done"), true, this::onClose));
        top.add(button(tr("blockland.hud.editor.reset_all"), false, () -> {
            HudLayout.resetAll();
            ClientSettings.save();
        }));
        top.add(button(tr("blockland.hud.editor.snap", tr(s.snap ? "blockland.settings.on" : "blockland.settings.off")), false,
                () -> s.snap = !s.snap));
        top.add(button(tr("blockland.hud.editor.margin", s.snapMargin), false, () -> s.snapMargin = s.snapMargin > 0 ? 0 : 4));
        List<Button> second = new ArrayList<>();
        String selectedLine;
        if (selected != null) {
            HudElement e = selected;
            selectedLine = tr(e.nameKey()) + (e.resize() != null ? "  " + e.width() + " × " + e.height() : "");
            second.add(button(tr("blockland.hud.editor.reset"), false, () -> HudLayout.resetElement(e)));
            second.add(button(tr(HudLayout.enabled(e) ? "blockland.hud.editor.hide" : "blockland.hud.editor.show"), false,
                    () -> HudLayout.setEnabled(e, !HudLayout.enabled(e))));
            // the element's own choices (the hotbar's 横 / 竖), each a button cycling its values
            for (HudElement.Option o : e.options()) {
                second.add(button(tr(o.nameKey()) + " · " + tr(o.nameKey() + "." + HudLayout.option(e, o)), false,
                        () -> HudLayout.cycleOption(e, o)));
            }
        } else {
            selectedLine = tr(HudLayout.elements().isEmpty() ? "blockland.hud.editor.empty" : "blockland.hud.editor.none");
        }
        String hint = tr("blockland.hud.editor.hint");
        String title = tr("blockland.hud.editor.title");
        int topW = Math.round(UiFont.width(title)) + 12 + rowWidth(top);
        int secondW = Math.round(UiFont.width(selectedLine)) + 12 + rowWidth(second);
        int hintW = Math.round(UiFont.width(hint) * 0.75f);
        toolW = Math.min(width - 8, Math.max(topW, Math.max(secondW, hintW)) + 16);
        toolH = 8 + TOOL_H + 4 + TOOL_H + 4 + 10 + 6;
        toolX = (width - toolW) / 2;
        // above the crosshair, where HUD elements rarely sit
        toolY = Math.max(4, height / 2 - toolH - 24);
        Draw.rect(g, toolX, toolY, toolW, toolH, PANEL);
        Draw.outline(g, toolX, toolY, toolW, toolH, LINE);
        g.fill(toolX, toolY, toolX + toolW, toolY + 1, ACCENT);
        int y = toolY + 8;
        Draw.text(g, title, toolX + 8, y + 5, 1f, TEXT, false);
        layoutRow(top, toolX + toolW - 8 - rowWidth(top), y);
        y += TOOL_H + 4;
        Draw.text(g, Draw.fit(selectedLine, toolW - 24 - rowWidth(second)), toolX + 8, y + 5, 1f, selected != null ? ACCENT : MUTED, false);
        layoutRow(second, toolX + toolW - 8 - rowWidth(second), y);
        y += TOOL_H + 4;
        Draw.text(g, Draw.fit(hint, (toolW - 16) / 0.75f), toolX + 8, y + 1, 0.75f, DIM, false);
        for (Button b : buttons) {
            boolean hover = inside(mouseX, mouseY, b.x, b.y, b.w, TOOL_H - 2);
            int bg = b.accent ? (hover ? ColorMath.mix(ACCENT, 0xFFFFFFFF, 0.2f) : ACCENT) : hover ? CARD_HOVER : CARD;
            Draw.rect(g, b.x, b.y, b.w, TOOL_H - 2, bg);
            Draw.text(g, b.label, b.x + 6, b.y + 4, 1f, b.accent ? 0xFF101418 : TEXT, false);
        }
    }

    private static Button button(String label, boolean accent, Runnable action) {
        return new Button(label, 0, 0, Math.round(UiFont.width(label)) + 12, accent, action);
    }

    private static int rowWidth(List<Button> row) {
        int w = 0;
        for (Button b : row) w += b.w + 4;
        return Math.max(0, w - 4);
    }

    /** Places a row of buttons from {@code x} on line {@code y} (they are clicked where they were drawn). */
    private void layoutRow(List<Button> row, int x, int y) {
        for (Button b : row) {
            buttons.add(new Button(b.label, x, y, b.w, b.accent, b.action));
            x += b.w + 4;
        }
    }

    // ---- input ------------------------------------------------------------------------------------

    @Nullable
    private HudElement elementAt(double mouseX, double mouseY) {
        List<HudElement> all = HudLayout.elements();
        for (int i = all.size() - 1; i >= 0; i--) {
            HudElement e = all.get(i);
            int[] at = HudLayout.place(e, width, height);
            int w = Math.max(6, e.width()), h = Math.max(6, e.height());
            if (inside(mouseX, mouseY, at[0] - 1, at[1] - 1, w + 2, h + 2)) return e;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dragging == null && resizing == null && inside(mouseX, mouseY, toolX, toolY, toolW, toolH)) {
            if (button == 0) {
                for (Button b : buttons) {
                    if (inside(mouseX, mouseY, b.x, b.y, b.w, TOOL_H - 2)) {
                        b.action.run();
                        clickSound();
                        return true;
                    }
                }
            }
            return true;
        }
        // a grip: the selected element's first, then the one under the mouse
        if (button == 0) {
            HudElement under = elementAt(mouseX, mouseY);
            HudElement gripped = selected != null && overGrip(selected, mouseX, mouseY) ? selected
                    : under != null && overGrip(under, mouseX, mouseY) ? under : null;
            if (gripped != null) {
                int[] at = HudLayout.place(gripped, width, height);
                selected = resizing = gripped;
                fixedX = at[0];
                fixedY = at[1];
                // where the mouse holds the corner
                grabX = mouseX - (at[0] + gripped.width());
                grabY = mouseY - (at[1] + gripped.height());
                return true;
            }
        }
        HudElement e = elementAt(mouseX, mouseY);
        if (e == null) {
            if (button == 0) selected = null;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        selected = e;
        if (button == 1) {
            HudLayout.resetElement(e);
            clickSound();
            return true;
        }
        if (button == 0) {
            int[] at = HudLayout.place(e, width, height);
            dragging = e;
            grabX = mouseX - at[0];
            grabY = mouseY - at[1];
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (resizing != null) {
            resizeTo(mouseX, mouseY);
            return true;
        }
        if (dragging == null) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        int w = Math.max(1, dragging.width()), h = Math.max(1, dragging.height());
        int x = (int) Math.round(mouseX - grabX), y = (int) Math.round(mouseY - grabY);
        guidesX.clear();
        guidesY.clear();
        if (settings().snap && !hasAltDown()) {
            x = snapAxis(x, w, true);
            y = snapAxis(y, h, false);
        }
        x = Math.max(0, Math.min(width - w, x));
        y = Math.max(0, Math.min(height - h, y));
        HudLayout.move(dragging, x, y, width, height);
        return true;
    }

    /**
     * Resizes the held element so its bottom-right corner follows the mouse: its right and bottom edges snap like a
     * moved box's (Alt: freely), it stays inside the screen and within its limits, and its top-left corner stays put.
     */
    private void resizeTo(double mouseX, double mouseY) {
        HudElement e = resizing;
        HudElement.Resize r = e.resize();
        int right = (int) Math.round(mouseX - grabX), bottom = (int) Math.round(mouseY - grabY);
        guidesX.clear();
        guidesY.clear();
        if (settings().snap && !hasAltDown()) {
            right += snapDelta(new int[]{right}, true, e);
            bottom += snapDelta(new int[]{bottom}, false, e);
        }
        int w = Math.min(width - fixedX, right - fixedX), h = Math.min(height - fixedY, bottom - fixedY);
        if (r.square()) {
            // one side for both: the one the mouse pulled further
            int side = Math.min(Math.max(w, h), Math.min(width - fixedX, height - fixedY));
            w = h = side;
            // a snap line only holds for the side that decided
            (right - fixedX >= bottom - fixedY ? guidesY : guidesX).clear();
        }
        int[] got = HudLayout.resize(e, w, h);
        // a limit overrode the edge: its line no longer lines up
        if (got[0] != right - fixedX) guidesX.clear();
        if (got[1] != bottom - fixedY) guidesY.clear();
        HudLayout.move(e, Math.max(0, Math.min(width - got[0], fixedX)), Math.max(0, Math.min(height - got[1], fixedY)), width, height);
    }

    /**
     * Snaps one axis of the dragged box ({@code pos}, {@code size}): its start, middle or end, whichever comes closest
     * within {@link #SNAP} to the screen's edges (at the margin), its middle, or another element's start, middle or end.
     * The line it snapped to joins the guides.
     */
    private int snapAxis(int pos, int size, boolean horizontal) {
        return pos + snapDelta(new int[]{pos, pos + size / 2, pos + size}, horizontal, dragging);
    }

    /**
     * How far to shift the positions {@code own} (all together) on one axis so the one closest to a line lies on it,
     * within {@link #SNAP}: the screen's edges (at the margin), its middle, and every element's start, middle and end but
     * {@code self}'s. 0 when none is close; the line it snapped to joins the guides.
     */
    private int snapDelta(int[] own, boolean horizontal, @Nullable HudElement self) {
        int screen = horizontal ? width : height;
        int margin = settings().snapMargin;
        // targets: {position, colour}
        List<int[]> targets = new ArrayList<>();
        targets.add(new int[]{margin, ACCENT});
        targets.add(new int[]{screen - margin, ACCENT});
        targets.add(new int[]{screen / 2, INFO});
        for (HudElement other : HudLayout.elements()) {
            if (other == self) continue;
            int[] at = HudLayout.place(other, width, height);
            int start = horizontal ? at[0] : at[1], length = Math.max(1, horizontal ? other.width() : other.height());
            targets.add(new int[]{start, GOLD});
            targets.add(new int[]{start + length / 2, GOLD});
            targets.add(new int[]{start + length, GOLD});
        }
        int best = SNAP + 1;
        int[] line = null;
        for (int[] t : targets) {
            for (int o : own) {
                int d = t[0] - o;
                if (Math.abs(d) < Math.abs(best)) {
                    best = d;
                    line = t;
                }
            }
        }
        if (line == null) return 0;
        // the line sits on the pixel inside the screen (a right or bottom edge is the pixel before it)
        int at = Math.min(screen - 1, line[0]);
        (horizontal ? guidesX : guidesY).add(new int[]{at, line[1]});
        return best;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if ((dragging != null || resizing != null) && button == 0) {
            dragging = null;
            resizing = null;
            guidesX.clear();
            guidesY.clear();
            ClientSettings.save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (selected != null && dragging == null && resizing == null) {
            int step = hasShiftDown() ? 10 : 1;
            int dx = key == GLFW.GLFW_KEY_LEFT ? -step : key == GLFW.GLFW_KEY_RIGHT ? step : 0;
            int dy = key == GLFW.GLFW_KEY_UP ? -step : key == GLFW.GLFW_KEY_DOWN ? step : 0;
            if ((dx != 0 || dy != 0) && hasControlDown() && selected.resize() != null) {
                // Ctrl: resize by the right and bottom edges, the top-left corner staying put
                int[] at = HudLayout.place(selected, width, height);
                int w = selected.width() + dx, h = selected.height() + dy;
                if (selected.resize().square()) w = h = selected.width() + (dx != 0 ? dx : dy);
                int[] got = HudLayout.resize(selected, Math.min(width - at[0], w), Math.min(height - at[1], h));
                HudLayout.move(selected, Math.max(0, Math.min(width - got[0], at[0])), Math.max(0, Math.min(height - got[1], at[1])),
                        width, height);
                return true;
            }
            if (dx != 0 || dy != 0) {
                int[] at = HudLayout.place(selected, width, height);
                int w = Math.max(1, selected.width()), h = Math.max(1, selected.height());
                HudLayout.move(selected, Math.max(0, Math.min(width - w, at[0] + dx)), Math.max(0, Math.min(height - h, at[1] + dy)),
                        width, height);
                return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
