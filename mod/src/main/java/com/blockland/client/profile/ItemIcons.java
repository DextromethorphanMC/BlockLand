package com.blockland.client.profile;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws the icon of an inventory item ({@code InventoryCatalog.Display#icon}) wherever items are listed. Built in: a
 * texture ({@code blockland:textures/….png}, fitted into the box) and name cards ({@code card:<id>}). Other modules
 * register how their own icons look by prefix (e.g. {@code graffiti:}), so the inventory never knows what they are;
 * anything unknown is a plain tile with the item's initial.
 */
public final class ItemIcons {
    /** How the icons of one prefix are drawn. */
    public interface Painter {
        /** @param id the icon without its prefix */
        void draw(GuiGraphics g, String id, int x, int y, int w, int h, float time);
    }

    private static final Map<String, Painter> painters = new LinkedHashMap<>();

    private ItemIcons() {
    }

    /** @param prefix with its colon, e.g. {@code graffiti:} */
    public static synchronized void register(String prefix, Painter painter) {
        painters.put(prefix, painter);
    }

    /**
     * @param name what the item is called (initial of the fallback tile; the player's name on a card icon)
     * @param time seconds, for animated icons
     */
    public static void draw(GuiGraphics g, String icon, String name, int x, int y, int w, int h, float time) {
        if (icon.startsWith("card:")) {
            // a card fills the box's width at the card's own proportions, centred
            int ch = Math.min(h, Math.round(w / NameCards.ASPECT));
            int cw = Math.round(ch * NameCards.ASPECT);
            NameCards.draw(g, icon.substring(5), x + (w - cw) / 2, y + (h - ch) / 2, cw, ch, name, null, time);
            return;
        }
        Painter painter;
        String id;
        synchronized (ItemIcons.class) {
            painter = null;
            id = "";
            for (Map.Entry<String, Painter> e : painters.entrySet()) {
                if (icon.startsWith(e.getKey())) {
                    painter = e.getValue();
                    id = icon.substring(e.getKey().length());
                    break;
                }
            }
        }
        if (painter != null) {
            painter.draw(g, id, x, y, w, h, time);
            return;
        }
        if (icon.endsWith(".png")) {
            ResourceLocation texture = ResourceLocation.tryParse(icon);
            if (texture != null) {
                int side = Math.min(w, h);
                g.blit(RenderPipelines.GUI_TEXTURED, texture, x + (w - side) / 2, y + (h - side) / 2, 0, 0, side, side, side, side);
                return;
            }
        }
        Draw.rect(g, x, y, w, h, 0xFF1F2836);
        Draw.outline(g, x, y, w, h, 0x22FFFFFF);
        String initial = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
        float scale = Math.max(1, h / 18f);
        Draw.text(g, initial, x + (w - UiFont.width(initial) * scale) / 2f, y + (h - UiFont.LINE_HEIGHT * scale) / 2f, scale,
                ColorMath.withAlpha(0xFFFFFF, 160), false);
    }
}
