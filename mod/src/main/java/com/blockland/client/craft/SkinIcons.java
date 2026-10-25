package com.blockland.client.craft;

import com.blockland.BlockLand;
import com.blockland.client.profile.ItemIcons;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.blockland.craft.SkinKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * How a skin item ({@code skin:<weapon>/<skin>}, {@code skin:<skin>} — {@link SkinKeys}) looks wherever owned items are
 * listed (库存, the shop): the weapon's 2D item icon in that skin ({@code textures/item/<weapon>_<skin>.png}), the plain
 * weapon icon when the skin has none, the inventory's default tile when neither exists. A shared skin is shown on the
 * first weapon it joins. Which textures exist is looked up once per key until the next resource reload.
 */
final class SkinIcons implements ItemIcons.Painter {
    /** key → the texture to draw ({@link #NONE} = none: the default tile) */
    private static final Map<String, ResourceLocation> textures = new HashMap<>();
    private static final ResourceLocation NONE = BlockLand.id("none");

    static void clear() {
        textures.clear();
    }

    @Override
    public void draw(GuiGraphics g, String id, int x, int y, int w, int h, float time) {
        ResourceLocation texture = textures.computeIfAbsent(id, SkinIcons::find);
        if (texture == NONE) {
            // nothing to draw the skin with: the inventory's plain tile
            ItemIcons.draw(g, "", id, x, y, w, h, time);
            return;
        }
        int side = Math.min(w, h);
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x + (w - side) / 2, y + (h - side) / 2, 0, 0, side, side, side, side);
    }

    private static ResourceLocation find(String id) {
        String[] parts = SkinKeys.parse(SkinKeys.PREFIX + id);
        String weaponId = parts[0] != null ? parts[0] : firstWeaponOf(parts[1]);
        if (weaponId == null) return NONE;
        ResourceLocation skinned = BlockLand.id("textures/item/" + weaponId + "_" + parts[1] + ".png");
        if (exists(skinned)) return skinned;
        ResourceLocation plain = BlockLand.id("textures/item/" + weaponId + ".png");
        return exists(plain) ? plain : NONE;
    }

    @Nullable
    private static String firstWeaponOf(String skinId) {
        for (Craftable weapon : CraftCatalog.all()) {
            CraftData.Skin skin = weapon.skins().get(skinId);
            if (skin != null && SkinKeys.isShared(skin)) return weapon.id();
        }
        return null;
    }

    private static boolean exists(ResourceLocation texture) {
        return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
    }
}
