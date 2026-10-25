package com.blockland.server.craft;

import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.blockland.craft.SkinKeys;
import com.blockland.server.inventory.InventoryCatalog;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Skins that must be owned (asset spec §30) as inventory items: {@code skin:<weapon>/<skin>}, or {@code skin:<skin>} for
 * a shared skin on every weapon it joins ({@link SkinKeys}). They go into no cosmetics slot — the 工匠 puts them on a
 * weapon — so the inventory only shows what the player owns; the shop sells them by key.
 */
public final class SkinItems implements InventoryCatalog.ItemType {
    @Override
    public String prefix() {
        return SkinKeys.PREFIX;
    }

    @Override
    public String categoryKey() {
        return "blockland.inventory.category.skin";
    }

    @Nullable
    @Override
    public InventoryCatalog.Display display(String key) {
        String[] parts = SkinKeys.parse(key);
        Craftable weapon = parts[0] != null ? CraftCatalog.get(parts[0]).orElse(null) : firstWeaponOf(parts[1]).orElse(null);
        if (weapon == null) return null;
        CraftData.Skin skin = weapon.skins().get(parts[1]);
        if (skin == null || skin.isFree() || parts[0] == null && !SkinKeys.isShared(skin)) return null;
        // "AK-47 | 鎏金", or for a shared skin owned everywhere just its name with what it is for
        Component name = parts[0] != null
                ? Component.empty().append(weapon.displayName()).append(" | ").append(CraftCatalog.skinName(weapon, skin))
                : CraftCatalog.skinName(weapon, skin);
        String kind = skin.isSpecial() ? "blockland.craft.special_skin" : skin.hasEffects() ? "blockland.craft.shader_skin" : "blockland.craft.skin";
        Component description = Component.translatable(kind).append(" · ")
                .append(Component.translatable(parts[0] != null ? "blockland.inventory.skin.use" : "blockland.inventory.skin.use_all"));
        return new InventoryCatalog.Display(name, description, key, skin.rarity == null || skin.rarity.isEmpty() ? "common" : skin.rarity);
    }

    @Override
    public List<String> keys() {
        return SkinKeys.lockableKeys();
    }

    /** The first weapon a shared skin joins (whose name and icon stand for it). */
    private static Optional<Craftable> firstWeaponOf(String skinId) {
        for (Craftable weapon : CraftCatalog.all()) {
            CraftData.Skin skin = weapon.skins().get(skinId);
            if (skin != null && SkinKeys.isShared(skin)) return Optional.of(weapon);
        }
        return Optional.empty();
    }
}
