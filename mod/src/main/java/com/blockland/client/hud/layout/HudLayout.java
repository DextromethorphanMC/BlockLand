package com.blockland.client.hud.layout;

import com.blockland.BlockLand;
import com.blockland.client.settings.ClientSettings;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The movable HUD (UI spec §3): the registry of {@link HudElement}s, where each one is (its saved {@link HudSettings}
 * place, else its default), and the two overlay layers that draw them — with the hotbar, and late (with the chat).
 * Places are kept relative to one of nine {@link HudAnchor}s and always pulled back inside the screen. While the HUD
 * editor is open it draws the elements itself, and the layers stay quiet.
 */
public final class HudLayout {
    private static final List<HudElement> elements = new ArrayList<>();
    private static boolean editing;

    private HudLayout() {
    }

    public static void init(BusGroup modBus) {
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> {
            ForgeLayeredDraw draw = event.getLayeredDraw();
            draw.addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("hud_layout"), ForgeLayeredDraw.HOTBAR_AND_DECOS,
                    (g, delta) -> renderAll(g, delta, false));
            draw.addAbove(ForgeLayeredDraw.POST_SLEEP_STACK, BlockLand.id("hud_layout_late"), ForgeLayeredDraw.CHAT_OVERLAY,
                    (g, delta) -> renderAll(g, delta, true));
        });
    }

    /** Adds an element (once, at client start-up). */
    public static void register(HudElement element) {
        elements.removeIf(e -> e.id().equals(element.id()));
        elements.add(element);
    }

    public static List<HudElement> elements() {
        return Collections.unmodifiableList(elements);
    }

    public static HudSettings settings() {
        return ClientSettings.get().hud;
    }

    /** Whether BlockLand's own bars and chat replace vanilla's. */
    public static boolean replacesVanilla() {
        return settings().replaceVanilla;
    }

    public static boolean enabled(HudElement element) {
        return !settings().hidden.contains(element.id());
    }

    public static void setEnabled(HudElement element, boolean on) {
        settings().hidden.remove(element.id());
        if (!on) settings().hidden.add(element.id());
    }

    /** The HUD editor is open (it draws the elements; the layers do not). */
    public static void setEditing(boolean on) {
        editing = on;
    }

    public static boolean editing() {
        return editing;
    }

    /** The element's box's top-left corner on a screen of {@code screenW} × {@code screenH}, inside the screen. */
    public static int[] place(HudElement e, int screenW, int screenH) {
        HudSettings.Placement p = settings().places.get(e.id());
        HudAnchor anchor = p == null ? e.defaultAnchor() : HudAnchor.of(p.anchor, e.defaultAnchor());
        int dx = p == null ? e.defaultX() : p.x, dy = p == null ? e.defaultY() : p.y;
        int w = e.width(), h = e.height();
        int x = Math.round(screenW * anchor.fx + dx - w * anchor.fx);
        int y = Math.round(screenH * anchor.fy + dy - h * anchor.fy);
        return new int[]{clamp(x, 0, screenW - w), clamp(y, 0, screenH - h)};
    }

    /** Keeps the element with its top-left corner at ({@code x}, {@code y}), held by the anchor of where it now is. */
    public static void move(HudElement e, int x, int y, int screenW, int screenH) {
        int w = e.width(), h = e.height();
        HudAnchor anchor = HudAnchor.nearest(x + w / 2f, y + h / 2f, screenW, screenH);
        int dx = Math.round(x + w * anchor.fx - screenW * anchor.fx), dy = Math.round(y + h * anchor.fy - screenH * anchor.fy);
        settings().places.put(e.id(), new HudSettings.Placement(anchor, dx, dy));
    }

    /** The element's value of its option {@code o} (its first value until the player picks another). */
    public static String option(HudElement e, HudElement.Option o) {
        java.util.Map<String, String> chosen = settings().options.get(e.id());
        String v = chosen == null ? null : chosen.get(o.key());
        return v != null && o.values().contains(v) ? v : o.values().get(0);
    }

    /** Moves the element's option {@code o} on to its next value. */
    public static void cycleOption(HudElement e, HudElement.Option o) {
        setOption(e, o, o.values().get((o.values().indexOf(option(e, o)) + 1) % o.values().size()));
    }

    public static void setOption(HudElement e, HudElement.Option o, String value) {
        if (o.values().contains(value)) settings().options.computeIfAbsent(e.id(), k -> new java.util.LinkedHashMap<>()).put(o.key(), value);
    }

    /**
     * A resizable element's box ({@link HudElement#resize}): the size the player chose, kept within its limits (equal
     * sides when square), else its default. {width, height}; {0, 0} for an element that is not resizable.
     */
    public static int[] size(HudElement e) {
        HudElement.Resize r = e.resize();
        if (r == null) return new int[]{0, 0};
        int[] chosen = settings().sizes.get(e.id());
        return limit(r, chosen == null ? r.defaultW() : chosen[0], chosen == null ? r.defaultH() : chosen[1]);
    }

    /** Gives a resizable element the box {@code w} × {@code h} (kept within its limits); returns what it got. */
    public static int[] resize(HudElement e, int w, int h) {
        HudElement.Resize r = e.resize();
        if (r == null) return new int[]{e.width(), e.height()};
        int[] s = limit(r, w, h);
        settings().sizes.put(e.id(), s);
        return s;
    }

    private static int[] limit(HudElement.Resize r, int w, int h) {
        if (r.square()) w = h = Math.max(w, h);
        w = clamp(w, r.minW(), r.maxW());
        h = clamp(h, r.minH(), r.maxH());
        if (r.square()) w = h = Math.min(w, h);
        return new int[]{w, h};
    }

    /** Puts the element back at its default place and size. */
    public static void resetElement(HudElement e) {
        settings().places.remove(e.id());
        settings().sizes.remove(e.id());
    }

    public static void resetAll() {
        settings().places.clear();
        settings().hidden.clear();
        settings().options.clear();
        settings().sizes.clear();
    }

    private static void renderAll(GuiGraphics g, DeltaTracker delta, boolean late) {
        Minecraft mc = Minecraft.getInstance();
        if (editing || mc.player == null || mc.options.hideGui) return;
        int w = g.guiWidth(), h = g.guiHeight();
        for (HudElement e : elements) {
            if (e.late() != late || !enabled(e) || !e.visible()) continue;
            int[] at = place(e, w, h);
            e.render(g, delta, at[0], at[1], false);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }
}
