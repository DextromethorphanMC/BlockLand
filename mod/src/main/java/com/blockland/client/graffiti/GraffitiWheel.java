package com.blockland.client.graffiti;

import com.blockland.BlockLand;
import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.graffiti.GraffitiCatalog;
import com.blockland.graffiti.GraffitiData;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;

import java.util.List;

import static com.blockland.client.ui.Tr.tr;

/**
 * The graffiti wheel: the four slots around the crosshair (1 up, 2 right, 3 down, 4 left) with their previews, the
 * picked one raised and outlined in its rarity's colour, its name and kind in the middle, and how to use the wheel.
 */
final class GraffitiWheel {
    private static final int RADIUS = 62;
    private static final int CELL = 46;
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;

    private GraffitiWheel() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientGraffiti.isOpen() || mc.options.hideGui || mc.player == null) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        float pop = Mth.clamp(ClientGraffiti.openSeconds() / 0.12f, 0, 1);
        float ease = 1 - (1 - pop) * (1 - pop);
        Draw.glow(g, cx, cy, RADIUS * 2.1f * ease, RADIUS * 2.1f * ease, ColorMath.fade(0x99000000, ease));
        List<String> slots = ClientGraffiti.slots();
        int selected = ClientGraffiti.selected();
        GraffitiCatalog catalog = GraffitiCatalog.get();
        for (int i = 0; i < GraffitiCatalog.SLOTS; i++) {
            double angle = Math.toRadians(i * 90 - 90);
            int x = cx + (int) Math.round(Math.cos(angle) * RADIUS * ease), y = cy + (int) Math.round(Math.sin(angle) * RADIUS * ease);
            boolean picked = i == selected;
            int size = picked ? CELL + 8 : CELL;
            int x0 = x - size / 2, y0 = y - size / 2;
            GraffitiData.Graffiti graffiti = i < slots.size() ? catalog.byId(slots.get(i)) : null;
            int accent = graffiti == null ? MUTED : rarityColor(graffiti.rarity);
            Draw.rect(g, x0, y0, size, size, picked ? 0xD0182030 : 0xA0101418);
            Draw.outline(g, x0, y0, size, size, picked ? accent : ColorMath.fade(accent, 0.45f));
            if (graffiti != null) {
                int pad = 4;
                net.minecraft.resources.ResourceLocation icon = BlockLand.id(graffiti.iconTexture());
                GraffitiRenderer.smooth(icon);
                g.blit(RenderPipelines.GUI_TEXTURED, icon, x0 + pad, y0 + pad, 0, 0,
                        size - pad * 2, size - pad * 2, 256, 256, 256, 256);
            } else {
                String empty = tr("blockland.graffiti.wheel.empty");
                Draw.text(g, empty, x - UiFont.width(empty) * 0.8f / 2f, y - 3, 0.8f, MUTED, true);
            }
            String number = String.valueOf(i + 1);
            Draw.text(g, number, x0 + 3, y0 + 2, 0.8f, picked ? TEXT : MUTED, true);
        }
        // the pick, in the middle
        GraffitiData.Graffiti picked = selected < slots.size() ? catalog.byId(slots.get(selected)) : null;
        if (picked != null) {
            String name = tr("graffiti.blockland." + picked.id);
            if (name.equals("graffiti.blockland." + picked.id)) name = picked.name;
            Draw.text(g, name, cx - UiFont.width(name) / 2f, cy - 9, 1f, rarityColor(picked.rarity), true);
            String kind = tr(picked.special() ? "blockland.graffiti.kind." + picked.effect : "blockland.graffiti.kind.normal");
            Draw.text(g, kind, cx - UiFont.width(kind) * 0.75f / 2f, cy + 3, 0.75f, MUTED, true);
        }
        String hint = tr("blockland.graffiti.wheel.hint");
        Draw.text(g, hint, cx - UiFont.width(hint) * 0.8f / 2f, cy + RADIUS + CELL / 2f + 8, 0.8f, ColorMath.fade(TEXT, ease), true);
    }

    static int rarityColor(String rarity) {
        return switch (rarity == null ? "" : rarity) {
            case "rare" -> 0xFF4FA3FF;
            case "epic" -> 0xFFB86BFF;
            case "legendary" -> 0xFFFFB13B;
            default -> 0xFFC8CED8;
        };
    }
}
