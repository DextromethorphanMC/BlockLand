package com.blockland.client.craft;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.craft.Craftable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The in-game 工匠's slot callouts: one box per replaceable slot (optic, laser, barrel, stock, charm …) in a column left or
 * right of the weapon, a thin line with a dot running from it to where the slot really sits on the model — its mount
 * node projected through the preview camera every frame, so the lines follow the model as it turns. Boxes keep their
 * side and order like the anchors do and glide (springs) when those change. Pure layout and drawing: the screen says
 * which slot is open and what a click does.
 */
final class MountCallouts {
    static final int BOX_H = 30;
    private static final int GAP = 6;

    /** Where one slot's box is this frame. */
    record Box(Craftable.Slot slot, int x, int y, int w, int h, boolean left, float anchorX, float anchorY, boolean anchored) {
    }

    private final Map<String, Spring> boxY = new HashMap<>();
    private final Map<String, Spring> hover = new HashMap<>();
    private final Map<String, Boolean> sides = new HashMap<>();
    private final List<Box> boxes = new ArrayList<>();

    List<Box> boxes() {
        return boxes;
    }

    /**
     * Places and draws the boxes.
     *
     * @param view    the preview camera ({@link WeaponPreviewRenderer#viewFor}) for {@code assembly}
     * @param px…ph   the preview picture on screen
     * @param leftX   x of the left column, {@code rightX} of the right one, {@code boxW} their width
     * @param top     the band the boxes may use vertically ({@code top} … {@code bottom})
     * @param open    the slot whose options are open (highlighted), or null
     */
    void render(GuiGraphics g, int mouseX, int mouseY, CraftSession session, WeaponAssembly assembly, Matrix4f view, float[] frame,
                int px, int py, int pw, int ph, int leftX, int rightX, int boxW, int top, int bottom, @Nullable String open, float a) {
        boxes.clear();
        List<Craftable.Slot> slots = session.replaceableSlots();
        if (slots.isEmpty() || a <= 0.01f) return;
        float aspect = pw / (float) Math.max(1, ph);
        float centreX = px + pw / 2f;
        // where each slot sits on the picture
        List<float[]> anchors = new ArrayList<>();
        for (Craftable.Slot slot : slots) {
            Vector3f at = anchorOf(assembly, slot, session, frame);
            float[] screen = project(view, at, aspect, px, py, pw, ph);
            anchors.add(screen);
        }
        // the side of each box: the anchor's half of the picture, kept unless it crosses clearly (no flicker at the middle)
        List<Integer> left = new ArrayList<>(), right = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            String id = slots.get(i).id();
            float ax = anchors.get(i) == null ? centreX : anchors.get(i)[0];
            Boolean was = sides.get(id);
            boolean isLeft = was == null ? ax < centreX : was ? ax < centreX + 14 : ax < centreX - 14;
            sides.put(id, isLeft);
            (isLeft ? left : right).add(i);
        }
        // a lopsided weapon (everything on the barrel) still spreads its boxes over both columns
        while (left.size() > right.size() + 1) moveExtreme(left, right, anchors, true);
        while (right.size() > left.size() + 1) moveExtreme(right, left, anchors, false);
        place(left, true, slots, anchors, leftX, boxW, top, bottom);
        place(right, false, slots, anchors, rightX, boxW, top, bottom);

        float pulse = 0.5f + 0.5f * (float) Math.sin(Ticker.global().time() * 3.2);
        for (Box box : boxes) {
            boolean over = inside(mouseX, mouseY, box.x(), box.y(), box.w(), box.h());
            boolean active = box.slot().id().equals(open);
            float hv = spring(hover, box.slot().id(), over || active ? 1 : 0).get();
            int lineColor = ColorMath.mix(0x70A9C4E8, ACCENT, hv);
            if (box.anchored()) {
                // from the box's inner edge: a short level run, then straight to the mount
                float ex = box.left() ? box.x() + box.w() : box.x();
                float ey = box.y() + box.h() / 2f;
                float elbow = ex + (box.left() ? 10 : -10);
                line(g, ex, ey, elbow, ey, ColorMath.fade(lineColor, a));
                line(g, elbow, ey, box.anchorX(), box.anchorY(), ColorMath.fade(lineColor, a));
                int dx = Math.round(box.anchorX()), dy = Math.round(box.anchorY());
                g.fill(dx - 1, dy - 1, dx + 2, dy + 2, ColorMath.fade(ColorMath.mix(TEXT, ACCENT, hv), a));
                int ring = 3 + Math.round(pulse * 2 + hv * 2);
                Draw.outline(g, dx - ring, dy - ring, ring * 2 + 1, ring * 2 + 1, ColorMath.fade(lineColor, a * (0.35f + 0.4f * (1 - pulse))));
            }
            drawBox(g, session, box, hv, active, a);
        }
    }

    /** The box under the mouse, or null. */
    @Nullable
    Box at(double mouseX, double mouseY) {
        for (Box box : boxes) {
            if (inside(mouseX, mouseY, box.x(), box.y(), box.w(), box.h())) return box;
        }
        return null;
    }

    private void drawBox(GuiGraphics g, CraftSession session, Box box, float hv, boolean active, float a) {
        Craftable.Slot slot = box.slot();
        String option = session.selected(slot.id(), session.edited);
        Craftable weapon = session.weapon;
        Craftable.OptionInfo info = weapon == null ? null : weapon.describe(slot.id(), option);
        int lift = Math.round(hv * 2) * (box.left() ? 1 : -1);
        int x = box.x() + lift;
        Draw.rect(g, x, box.y(), box.w(), box.h(), ColorMath.fade(ColorMath.mix(ColorMath.mix(0xD0101722, CARD_HOVER, hv), CARD_SELECTED, active ? 1 : 0), a));
        Draw.outline(g, x, box.y(), box.w(), box.h(), ColorMath.fade(ColorMath.mix(LINE, ACCENT, Math.max(hv, active ? 1 : 0)), a));
        // the accent edge faces the weapon
        int edge = box.left() ? x + box.w() - 2 : x;
        boolean changed = !option.equals(slot.defaultOption());
        int rarity = info == null ? INFO : CraftSession.rarityColor(info.rarity(), changed);
        g.fill(edge, box.y(), edge + 2, box.y() + box.h(), ColorMath.fade(rarity, a * (0.5f + 0.5f * hv)));
        int tx = x + (box.left() ? 7 : 9);
        String slotName = CraftSession.categoryName(slot.id()).getString();
        Draw.text(g, Draw.fit(slotName, box.w() - 40), tx, box.y() + 5, 0.75f, ColorMath.fade(DIM, a), false);
        String count = Component.translatable("blockland.craft.ingame.options", slot.options().size()).getString();
        Draw.text(g, count, x + box.w() - 8 - UiFont.width(count) * 0.75f, box.y() + 5, 0.75f, ColorMath.fade(DIM, a), false);
        String name = info == null ? option : info.name().getString();
        Draw.text(g, Draw.fit(name, box.w() - 16), tx, box.y() + 16, 1f, ColorMath.fade(changed ? TEXT : MUTED, a), false);
    }

    /** Puts one column's boxes in their anchors' order, spread over the band without overlapping, gliding there. */
    private void place(List<Integer> column, boolean left, List<Craftable.Slot> slots, List<float[]> anchors,
                       int x, int w, int top, int bottom) {
        column.sort(Comparator.comparingDouble(i -> anchors.get(i) == null ? top : anchors.get(i)[1]));
        int n = column.size();
        if (n == 0) return;
        float[] ys = new float[n];
        for (int k = 0; k < n; k++) {
            float[] anchor = anchors.get(column.get(k));
            ys[k] = (anchor == null ? top + k * (BOX_H + GAP) : anchor[1]) - BOX_H / 2f;
        }
        // push down past the one above, then back up from the bottom when the column ran out of room
        for (int k = 0; k < n; k++) ys[k] = Math.max(k == 0 ? top : ys[k - 1] + BOX_H + GAP, ys[k]);
        for (int k = n - 1; k >= 0; k--) ys[k] = Math.min(k == n - 1 ? bottom - BOX_H : ys[k + 1] - BOX_H - GAP, ys[k]);
        for (int k = 0; k < n; k++) ys[k] = Math.max(top, ys[k]);
        for (int k = 0; k < n; k++) {
            int index = column.get(k);
            Craftable.Slot slot = slots.get(index);
            Spring spring = boxY.computeIfAbsent(slot.id(), id -> new Spring(0).config(180, 22));
            spring.setTarget(ys[k]);
            if (spring.get() == 0) spring.set(ys[k]);
            float[] anchor = anchors.get(index);
            boxes.add(new Box(slot, x, Math.round(spring.get()), w, BOX_H, left, anchor == null ? 0 : anchor[0],
                    anchor == null ? 0 : anchor[1], anchor != null));
        }
    }

    /** Moves the anchor nearest to the other side from {@code from} to {@code to}. */
    private static void moveExtreme(List<Integer> from, List<Integer> to, List<float[]> anchors, boolean fromLeft) {
        int best = -1;
        float bestX = fromLeft ? -Float.MAX_VALUE : Float.MAX_VALUE;
        for (int i : from) {
            float x = anchors.get(i) == null ? 0 : anchors.get(i)[0];
            if (fromLeft ? x > bestX : x < bestX) {
                bestX = x;
                best = i;
            }
        }
        if (best < 0) return;
        from.remove(Integer.valueOf(best));
        to.add(best);
    }

    /**
     * Where a slot sits in the assembly's pose space: its mount node; for a slot without one (a magazine shown as a node
     * of the weapon) the node of the fitted option; else the middle of the weapon.
     */
    private static Vector3f anchorOf(WeaponAssembly assembly, Craftable.Slot slot, CraftSession session, float[] frame) {
        var source = assembly.model.source;
        int node = slot.mount().isEmpty() ? -1 : source.node(slot.mount());
        if (node < 0) {
            String option = session.selected(slot.id(), session.edited);
            String optionNode = slot.optionNodes().get(option);
            if (optionNode == null && !slot.optionNodes().isEmpty()) optionNode = slot.optionNodes().values().iterator().next();
            if (optionNode != null) node = source.node(optionNode);
        }
        if (node < 0 || node >= assembly.parts.get(0).pose().world.length) return new Vector3f(frame[0], frame[1], frame[2]);
        return assembly.parts.get(0).pose().world[node].getTranslation(new Vector3f());
    }

    /** A point of the pose space on the picture (GUI coordinates), or null when it is behind the camera. */
    @Nullable
    private static float[] project(Matrix4f view, Vector3f p, float aspect, int px, int py, int pw, int ph) {
        Vector3f v = view.transformPosition(p, new Vector3f());
        if (v.z > -0.01f) return null;
        float f = 1f / (float) Math.tan(Math.toRadians(WeaponPreviewRenderer.FOV) / 2);
        float ndcX = v.x * f / aspect / -v.z, ndcY = v.y * f / -v.z;
        return new float[]{px + (ndcX * 0.5f + 0.5f) * pw, py + (0.5f - ndcY * 0.5f) * ph};
    }

    /** A thin line between two points (GUI fills along it; the callouts are short). */
    static void line(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(1, Math.round(Math.max(Math.abs(dx), Math.abs(dy))));
        if (dy == 0 || dx == 0) {
            int ax = Math.round(Math.min(x0, x1)), ay = Math.round(Math.min(y0, y1));
            int bx = Math.round(Math.max(x0, x1)), by = Math.round(Math.max(y0, y1));
            g.fill(ax, ay, Math.max(ax + 1, bx), Math.max(ay + 1, by), color);
            return;
        }
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            int x = Math.round(x0 + dx * i / steps), y = Math.round(y0 + dy * i / steps);
            if (x == lastX && y == lastY) continue;
            g.fill(x, y, x + 1, y + 1, color);
            lastX = x;
            lastY = y;
        }
    }

    private static Spring spring(Map<String, Spring> map, String key, float target) {
        Spring spring = map.computeIfAbsent(key, k -> new Spring(0).config(320, 24));
        spring.setTarget(target);
        return spring;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
