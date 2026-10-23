package com.blockland.client.craft;

import com.blockland.BlockLand;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Spring;
import com.blockland.client.font.UiFont;
import com.blockland.client.spray.SprayProjector;
import com.blockland.client.spray.SprayScene;
import com.blockland.client.ui.Draw;
import com.blockland.craft.Build;
import com.blockland.craft.Craftable;
import com.blockland.craft.Spray;
import com.blockland.craft.SprayCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * The 喷漆 page of the 工匠 screen (asset spec §29): the sprays placed on the weapon (add, pick, remove), a palette of the
 * sprays it may wear, the side, size and angle of the selected one, and placing it by clicking or dragging on the
 * preview (which shows that side square on, in the weapon root's own frame like the placements). Edits only the build; saving and the server's checks stay the screen's.
 */
final class SprayEditor {
    private static final float SIZE_STEP = 0.0025f;
    private static final float ROTATION_STEP = 15f;
    private static final int THUMB = 24;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Spring> hover = new HashMap<>();
    private int selected = -1;
    /** The side looked at (the selected spray's, or where new ones go). */
    private String side = Spray.LEFT;
    /** The spray id the palette last picked (new sprays start with it). */
    @Nullable
    private String lastPicked;
    private boolean placing;
    /** Whether the selected spray lands on the weapon, remembered for the placement it was computed for. */
    @Nullable
    private Spray checked;
    private boolean checkedLands = true;

    /** A different weapon was picked: nothing selected. */
    void reset() {
        selected = -1;
        placing = false;
        checked = null;
    }

    /**
     * The preview's rotation that shows the side square on in the weapon root's frame (the left side looks along the
     * root's +X); null while the weapon's model is missing.
     */
    @Nullable
    Quaternionf sideView(Craftable weapon, Build build) {
        SprayScene scene = SprayScene.of(weapon, build);
        return scene == null ? null : scene.sideView(shownSide(build));
    }

    private String shownSide(Build build) {
        Spray spray = selectedSpray(build);
        return spray != null ? spray.side() : side;
    }

    @Nullable
    private Spray selectedSpray(Build build) {
        return selected >= 0 && selected < build.sprays().size() ? build.sprays().get(selected) : null;
    }

    // ---- the panel ------------------------------------------------------------------------------

    /**
     * Draws the page into the option column; {@code top} is the scrolled top, {@code viewTop}/{@code viewHeight} the
     * visible part (hits outside it are dropped).
     *
     * @return the content's height (for scrolling)
     */
    int draw(GuiGraphics g, int mouseX, int mouseY, int x, int top, int w, int viewTop, int viewHeight, float a,
             Craftable weapon, Build build, Consumer<Build> edit) {
        hits.clear();
        SprayCatalog catalog = SprayCatalog.get();
        int limit = catalog.limits().perWeapon;
        List<Spray> placed = build.sprays();
        if (selected >= placed.size()) selected = placed.isEmpty() ? -1 : placed.size() - 1;
        int y = top;

        // header: count and "add"
        String count = Component.translatable("blockland.craft.spray.placed", placed.size(), limit).getString();
        Draw.text(g, count, x + 2, y + 4, 1f, ColorMath.fade(MUTED, a), false);
        String add = Component.translatable(placed.size() < limit ? "blockland.craft.spray.add" : "blockland.craft.spray.full").getString();
        int addW = UiFont.width(add) + 12;
        boolean canAdd = placed.size() < limit;
        button(g, mouseX, mouseY, x + w - addW, y, addW, 16, add, canAdd ? GO : DISABLED, canAdd, a, viewTop, viewHeight,
                () -> addSpray(weapon, build, null, edit));
        y += 22;

        // the placed sprays
        if (placed.isEmpty()) {
            for (var line : Draw.wrap(Component.translatable("blockland.craft.spray.empty").getString(), w - 8)) {
                Draw.text(g, line, x + 4, y + 2, ColorMath.fade(DIM, a));
                y += 11;
            }
            y += 4;
        }
        for (int i = 0; i < placed.size(); i++) {
            Spray spray = placed.get(i);
            SprayCatalog.Def def = catalog.get(spray.id());
            boolean chosen = i == selected;
            boolean over = visible(y, 22, viewTop, viewHeight) && inside(mouseX, mouseY, x, y, w, 22);
            float hv = spring("row" + i, over ? 1 : 0).get();
            Draw.rect(g, x, y, w, 22, ColorMath.fade(ColorMath.mix(ColorMath.mix(CARD, CARD_HOVER, hv), CARD_SELECTED, chosen ? 1 : 0), a));
            if (chosen) Draw.outline(g, x, y, w, 22, ColorMath.fade(ACCENT, a * 0.6f));
            if (def != null) thumbnail(g, def, x + 3, y + 3, 16, a);
            String name = sprayName(spray.id(), def);
            String sideTag = Component.translatable("blockland.craft.spray.side." + spray.side()).getString();
            Draw.text(g, Draw.fit(name, w - 70), x + 24, y + 7, 1f, ColorMath.fade(TEXT, a), false);
            Draw.text(g, sideTag, x + w - 30 - UiFont.width(sideTag) * 0.75f, y + 8, 0.75f, ColorMath.fade(MUTED, a), false);
            int index = i;
            // remove (×) at the right end, the rest of the row selects
            boolean overX = inside(mouseX, mouseY, x + w - 20, y + 3, 16, 16);
            Draw.rect(g, x + w - 20, y + 3, 16, 16, ColorMath.fade(overX ? CANCEL : 0xFF2A3446, a));
            Draw.text(g, "×", x + w - 12 - UiFont.width("×") / 2f, y + 7, 1f, ColorMath.fade(TEXT, a), false);
            addHit(x + w - 20, y + 3, 16, 16, viewTop, viewHeight, () -> {
                edit.accept(build.withoutSpray(index));
                if (selected == index) selected = -1;
                else if (selected > index) selected--;
                checked = null;
            });
            addHit(x, y, w - 22, 22, viewTop, viewHeight, () -> {
                selected = index;
                side = spray.side();
            });
            y += 25;
        }

        Spray current = selectedSpray(build);
        y += 4;
        g.fill(x, y, x + w, y + 1, ColorMath.fade(LINE, a));
        y += 6;

        // palette: picks the selected spray's pattern (or the pattern of the next one added)
        Draw.text(g, Component.translatable("blockland.craft.spray.pick").getString(), x + 2, y, 0.75f, ColorMath.fade(DIM, a), false);
        y += 11;
        int perRow = Math.max(1, (w + 3) / (THUMB + 3));
        List<SprayCatalog.Def> palette = catalog.forWeapon(weapon);
        for (int i = 0; i < palette.size(); i++) {
            SprayCatalog.Def def = palette.get(i);
            int tx = x + (i % perRow) * (THUMB + 3), ty = y + (i / perRow) * (THUMB + 3);
            boolean chosen = current != null ? def.id.equals(current.id()) : def.id.equals(lastPicked);
            boolean over = visible(ty, THUMB, viewTop, viewHeight) && inside(mouseX, mouseY, tx, ty, THUMB, THUMB);
            float hv = spring("pal" + def.id, over ? 1 : 0).get();
            Draw.rect(g, tx, ty, THUMB, THUMB, ColorMath.fade(ColorMath.mix(CARD, CARD_HOVER, hv), a));
            thumbnail(g, def, tx + 2, ty + 2, THUMB - 4, a);
            int frame = "legendary".equals(def.rarity) ? GOLD : chosen ? ACCENT : 0;
            if (frame != 0) Draw.outline(g, tx, ty, THUMB, THUMB, ColorMath.fade(frame, a * (chosen ? 1 : 0.55f)));
            if (chosen && frame == GOLD) Draw.outline(g, tx + 1, ty + 1, THUMB - 2, THUMB - 2, ColorMath.fade(ACCENT, a));
            String id = def.id;
            addHit(tx, ty, THUMB, THUMB, viewTop, viewHeight, () -> {
                lastPicked = id;
                Spray spray = selectedSpray(build);
                if (spray != null) edit.accept(build.withSpray(selected, new Spray(id, spray.side(), spray.u(), spray.v(), spray.size(), spray.rotation())));
                else if (build.sprays().size() < limit) addSpray(weapon, build, id, edit);
            });
        }
        y += ((palette.size() + perRow - 1) / perRow) * (THUMB + 3) + 6;

        // side, size and angle of the selected spray
        String sideNow = shownSide(build);
        int half = (w - 4) / 2;
        for (int k = 0; k < 2; k++) {
            String s = k == 0 ? Spray.LEFT : Spray.RIGHT;
            String label = Component.translatable("blockland.craft.spray.side." + s).getString();
            boolean on = s.equals(sideNow);
            button(g, mouseX, mouseY, x + k * (half + 4), y, half, 16, label, on ? 0xFF35507A : 0xFF2A3446, true, a, viewTop, viewHeight,
                    () -> setSide(build, s, edit));
        }
        y += 22;
        if (current != null) {
            SprayCatalog.Limits limits = catalog.limits();
            float sizeShare = (current.size() - limits.minSize) / Math.max(1e-4f, limits.maxSize - limits.minSize);
            y = stepper(g, mouseX, mouseY, x, y, w, a, viewTop, viewHeight, "blockland.craft.spray.size", sizeShare,
                    String.format("%.1f cm", current.size() * 100),
                    () -> resize(build, -SIZE_STEP, edit), () -> resize(build, SIZE_STEP, edit));
            y = stepper(g, mouseX, mouseY, x, y, w, a, viewTop, viewHeight, "blockland.craft.spray.rotation",
                    (current.rotation() + 180) / 360f, Math.round(current.rotation()) + "°",
                    () -> rotate(build, -ROTATION_STEP, edit), () -> rotate(build, ROTATION_STEP, edit));
            SprayScene scene = SprayScene.of(weapon, build);
            if (scene != null && !lands(scene, current)) {
                for (var line : Draw.wrap(Component.translatable("blockland.craft.spray.no_surface").getString(), w - 4)) {
                    Draw.text(g, line, x + 2, y, ColorMath.fade(ERROR, a));
                    y += 11;
                }
            }
        }
        y += 4;
        for (var line : Draw.wrap(Component.translatable("blockland.craft.spray.hint").getString(), w - 4)) {
            Draw.text(g, line, x + 2, y, ColorMath.fade(DIM, a));
            y += 11;
        }
        return y - top;
    }

    /** Label, a share bar and − / + buttons; returns the next row's y. */
    private int stepper(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a, int viewTop, int viewHeight,
                        String key, float share, String value, Runnable less, Runnable more) {
        String label = Component.translatable(key).getString();
        Draw.text(g, label, x + 2, y + 5, 1f, ColorMath.fade(MUTED, a), false);
        int valueW = 40;
        int bx = x + 6 + Math.max(28, UiFont.width(label)) + 4;
        int barW = Math.max(16, w - (bx - x) - valueW - 40);
        button(g, mouseX, mouseY, bx, y, 16, 16, "−", 0xFF2A3446, true, a, viewTop, viewHeight, less);
        g.fill(bx + 20, y + 7, bx + 20 + barW, y + 9, ColorMath.fade(0x30FFFFFF, a));
        g.fill(bx + 20, y + 7, bx + 20 + Math.round(barW * Math.max(0, Math.min(1, share))), y + 9, ColorMath.fade(ACCENT, a));
        button(g, mouseX, mouseY, bx + 24 + barW, y, 16, 16, "+", 0xFF2A3446, true, a, viewTop, viewHeight, more);
        Draw.text(g, value, x + w - 2 - UiFont.width(value), y + 5, 1f, ColorMath.fade(TEXT, a), false);
        return y + 20;
    }

    private void button(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int h, String label, int color,
                        boolean enabled, float a, int viewTop, int viewHeight, Runnable action) {
        boolean over = enabled && visible(y, h, viewTop, viewHeight) && inside(mouseX, mouseY, x, y, w, h);
        float hv = spring("btn" + x + "_" + y, over ? 1 : 0).get();
        Draw.rect(g, x, y, w, h, ColorMath.fade(ColorMath.lighten(color, 0.1f * hv), a));
        String fitted = Draw.fit(label, w - 4);
        Draw.text(g, fitted, x + (w - UiFont.width(fitted)) / 2f, y + (h - 8) / 2f, 1f, ColorMath.fade(enabled ? TEXT : MUTED, a), false);
        if (enabled) addHit(x, y, w, h, viewTop, viewHeight, action);
    }

    private static void thumbnail(GuiGraphics g, SprayCatalog.Def def, int x, int y, int size, float a) {
        try {
            g.blit(RenderPipelines.GUI_TEXTURED, BlockLand.id(def.texture), x, y, 0, 0, size, size, 256, 256, 256, 256,
                    ColorMath.fade(0xFFFFFFFF, a));
        } catch (RuntimeException e) {
            Draw.rect(g, x, y, size, size, ColorMath.fade(DISABLED, a));
        }
    }

    private static String sprayName(String id, @Nullable SprayCatalog.Def def) {
        return com.blockland.craft.CraftCatalog.text("blockland.spray." + id, def == null ? id : def.name).getString();
    }

    // ---- edits ----------------------------------------------------------------------------------

    private void addSpray(Craftable weapon, Build build, @Nullable String id, Consumer<Build> edit) {
        Spray spray = newSpray(weapon, build, id);
        if (spray == null) return;
        SprayScene scene = SprayScene.of(weapon, build);
        add(build, scene == null ? spray : findSpot(scene, spray), edit);
    }

    private void add(Build build, Spray spray, Consumer<Build> edit) {
        edit.accept(build.withSpray(build.sprays().size(), spray));
        selected = build.sprays().size();
        lastPicked = spray.id();
        checked = null;
    }

    /** A new spray on the side shown, centred, default size; null when the weapon is full or no spray fits it. */
    @Nullable
    private Spray newSpray(Craftable weapon, Build build, @Nullable String id) {
        SprayCatalog catalog = SprayCatalog.get();
        if (build.sprays().size() >= catalog.limits().perWeapon) return null;
        List<SprayCatalog.Def> palette = catalog.forWeapon(weapon);
        String pattern = id != null ? id : lastPicked != null && catalog.get(lastPicked) != null ? lastPicked
                : palette.isEmpty() ? null : palette.get(0).id;
        if (pattern == null) return null;
        return new Spray(pattern, side, 0.5f, 0.5f, defaultSize(SprayScene.of(weapon, build)), 0);
    }

    private static float defaultSize(@Nullable SprayScene scene) {
        SprayCatalog.Limits limits = SprayCatalog.get().limits();
        float size = scene == null ? 0.03f : Math.min(scene.frameHeight(), scene.frameLength()) * 0.35f;
        size = Math.round(size / 0.005f) * 0.005f;
        return Math.max(limits.minSize, Math.min(limits.maxSize, size));
    }

    /** The centre of the side if paint lands there, else the nearest spot that takes it (rings around the centre). */
    private static Spray findSpot(SprayScene scene, Spray spray) {
        if (SprayProjector.lands(scene, spray)) return spray;
        for (int ring = 1; ring <= 4; ring++) {
            for (int k = 0; k < 8 * ring; k++) {
                double angle = Math.PI * 2 * k / (8 * ring);
                float u = 0.5f + (float) Math.cos(angle) * 0.11f * ring, v = 0.5f + (float) Math.sin(angle) * 0.11f * ring;
                Spray moved = spray.moved(clamp01(u), clamp01(v));
                if (SprayProjector.lands(scene, moved)) return moved;
            }
        }
        return spray;
    }

    private void setSide(Build build, String next, Consumer<Build> edit) {
        side = next;
        Spray spray = selectedSpray(build);
        // the same spot along the weapon, on the other face (u runs the other way there)
        if (spray != null && !spray.side().equals(next)) {
            edit.accept(build.withSpray(selected, new Spray(spray.id(), next, 1 - spray.u(), spray.v(), spray.size(), spray.rotation())));
        }
    }

    private void resize(Build build, float delta, Consumer<Build> edit) {
        Spray spray = selectedSpray(build);
        if (spray == null) return;
        SprayCatalog.Limits limits = SprayCatalog.get().limits();
        float size = Math.max(limits.minSize, Math.min(limits.maxSize, spray.size() + delta));
        edit.accept(build.withSpray(selected, spray.resized(size)));
    }

    private void rotate(Build build, float delta, Consumer<Build> edit) {
        Spray spray = selectedSpray(build);
        if (spray == null) return;
        float angle = spray.rotation() + delta;
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        edit.accept(build.withSpray(selected, spray.rotated(angle)));
    }

    private boolean lands(SprayScene scene, Spray spray) {
        if (!spray.equals(checked)) {
            checked = spray;
            checkedLands = SprayProjector.lands(scene, spray);
        }
        return checkedLands;
    }

    // ---- the preview ----------------------------------------------------------------------------

    /**
     * A press on the preview: puts the selected spray where the weapon was clicked (or adds one there). Returns whether
     * it was taken (a miss on empty space keeps the spray where it is).
     */
    boolean previewPressed(double mouseX, double mouseY, int px, int py, int pw, int ph, Craftable weapon, Build build,
                           Consumer<Build> edit) {
        Vector2f uv = pick(mouseX, mouseY, px, py, pw, ph, weapon, build);
        if (uv == null) return false;
        placing = true;
        Spray spray = selectedSpray(build);
        if (spray == null) {
            Spray fresh = newSpray(weapon, build, null);
            if (fresh != null) add(build, fresh.moved(uv.x, uv.y), edit);
            return true;
        }
        edit.accept(build.withSpray(selected, spray.moved(uv.x, uv.y)));
        return true;
    }

    void previewDragged(double mouseX, double mouseY, int px, int py, int pw, int ph, Craftable weapon, Build build,
                        Consumer<Build> edit) {
        if (!placing) return;
        Spray spray = selectedSpray(build);
        Vector2f uv = pick(mouseX, mouseY, px, py, pw, ph, weapon, build);
        if (spray != null && uv != null) edit.accept(build.withSpray(selected, spray.moved(uv.x, uv.y)));
    }

    void released() {
        placing = false;
    }

    boolean isPlacing() {
        return placing;
    }

    /** Mouse wheel over the preview: size, or angle with shift. Returns whether a spray took it. */
    boolean previewScrolled(double scrollY, boolean shift, Build build, Consumer<Build> edit) {
        if (selectedSpray(build) == null || scrollY == 0) return false;
        if (shift) rotate(build, (float) Math.signum(scrollY) * ROTATION_STEP, edit);
        else resize(build, (float) Math.signum(scrollY) * SIZE_STEP, edit);
        return true;
    }

    /** (u, v) of the weapon surface under the mouse, seen from the side shown, or null over empty space. */
    @Nullable
    private Vector2f pick(double mouseX, double mouseY, int px, int py, int pw, int ph, Craftable weapon, Build build) {
        WeaponPreviewRenderer.Camera camera = WeaponPreviewRenderer.lastCamera();
        SprayScene scene = SprayScene.of(weapon, build);
        if (camera == null || scene == null || !camera.weaponId().equals(weapon.id()) || pw <= 0 || ph <= 0) return null;
        float ndcX = (float) ((mouseX - px) / pw * 2 - 1), ndcY = (float) (1 - (mouseY - py) / ph * 2);
        float tan = (float) Math.tan(Math.toRadians(camera.fov()) / 2);
        // view space → the preview's rest space → the weapon root's frame, where the scene lives
        Matrix4f toRoot = scene.rootRest().invert().mul(new Matrix4f(camera.view()).invert());
        Vector3f origin = toRoot.transformPosition(new Vector3f());
        Vector3f dir = toRoot.transformDirection(new Vector3f(ndcX * tan * camera.aspect(), ndcY * tan, -1)).normalize();
        SprayScene.Hit hit = scene.pick(origin, dir);
        return hit == null ? null : scene.uv(shownSide(build), hit.point());
    }

    /** Marks the selected spray's square on the preview (dotted edges, red when it lands on nothing). */
    void drawMarker(GuiGraphics g, int px, int py, int pw, int ph, Craftable weapon, Build build, float a) {
        Spray spray = selectedSpray(build);
        WeaponPreviewRenderer.Camera camera = WeaponPreviewRenderer.lastCamera();
        SprayScene scene = spray == null ? null : SprayScene.of(weapon, build);
        if (scene == null || camera == null || !camera.weaponId().equals(weapon.id())) return;
        boolean lands = lands(scene, spray);
        float depth = lands ? SprayProjector.depth(scene, spray) : 0;
        Vector3f dir = SprayScene.direction(spray.side());
        Vector3f centre = scene.centre(spray);
        if (!lands) centre.x = scene.sidePlane(spray.side());
        else centre.add(new Vector3f(dir).mul(depth));
        Vector3f right = SprayScene.right(spray.side()), up = new Vector3f(0, 1, 0);
        double angle = Math.toRadians(spray.rotation());
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle), h = spray.size() / 2;
        Vector3f across = new Vector3f(right).mul(c).add(new Vector3f(up).mul(s)).mul(h);
        Vector3f upward = new Vector3f(right).mul(-s).add(new Vector3f(up).mul(c)).mul(h);
        Vector3f[] corners = {
                new Vector3f(centre).sub(across).add(upward), new Vector3f(centre).add(across).add(upward),
                new Vector3f(centre).add(across).sub(upward), new Vector3f(centre).sub(across).sub(upward)};
        float tan = (float) Math.tan(Math.toRadians(camera.fov()) / 2);
        Vector2f[] screen = new Vector2f[4];
        Matrix4f toView = new Matrix4f(camera.view()).mul(scene.rootRest());
        for (int i = 0; i < 4; i++) {
            Vector3f v = toView.transformPosition(corners[i], new Vector3f());
            if (v.z > -1e-3f) return;
            screen[i] = new Vector2f(px + (v.x / -v.z / (tan * camera.aspect()) * 0.5f + 0.5f) * pw,
                    py + (0.5f - v.y / -v.z / tan * 0.5f) * ph);
        }
        int color = ColorMath.fade(lands ? ACCENT : ERROR, a * 0.9f);
        for (int i = 0; i < 4; i++) {
            Vector2f p0 = screen[i], p1 = screen[(i + 1) % 4];
            int dots = Math.max(4, Math.round(p0.distance(p1) / 3));
            for (int k = 0; k <= dots; k += 2) {
                float t = k / (float) dots;
                int x = Math.round(p0.x + (p1.x - p0.x) * t), y = Math.round(p0.y + (p1.y - p0.y) * t);
                g.fill(x, y, x + 1, y + 1, color);
            }
        }
    }

    // ---- input ----------------------------------------------------------------------------------

    boolean mouseClicked(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (hit.h > 0 && inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        return false;
    }

    private void addHit(int x, int y, int w, int h, int viewTop, int viewHeight, Runnable action) {
        int top = Math.max(y, viewTop), bottom = Math.min(y + h, viewTop + viewHeight);
        if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, action));
    }

    private Spring spring(String key, float target) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(0).config(320, 24));
        spring.setTarget(target);
        return spring;
    }

    private static boolean visible(int y, int h, int viewTop, int viewHeight) {
        return y + h > viewTop && y < viewTop + viewHeight;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
