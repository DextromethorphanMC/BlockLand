package com.blockland.server.graffiti;

import com.blockland.BlockLand;
import com.blockland.graffiti.GraffitiCatalog;
import com.blockland.graffiti.GraffitiData;
import com.blockland.server.inventory.InventoryCatalog;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Graffiti as inventory items ({@code graffiti:<id>}), equipped into the slots {@code graffiti.1} … {@code graffiti.4}. */
public final class GraffitiItems implements InventoryCatalog.ItemType {
    @Override
    public String prefix() {
        return GraffitiCatalog.PREFIX;
    }

    @Override
    public String categoryKey() {
        return "blockland.inventory.category.graffiti";
    }

    @Nullable
    @Override
    public InventoryCatalog.Display display(String key) {
        GraffitiData.Graffiti g = GraffitiCatalog.get().byKey(key);
        if (g == null) return null;
        String kind = g.special() ? "blockland.graffiti.kind." + (g.effect.isEmpty() ? "special" : g.effect) : "blockland.graffiti.kind.normal";
        return new InventoryCatalog.Display(Component.translatableWithFallback("graffiti.blockland." + g.id, g.name),
                Component.translatable(kind), BlockLand.MODID + ":" + g.iconTexture(), g.rarity);
    }

    @Override
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (GraffitiData.Graffiti g : GraffitiCatalog.get().all()) keys.add(g.key());
        return keys;
    }

    @Override
    public List<String> freeKeys() {
        List<String> keys = new ArrayList<>();
        for (GraffitiData.Graffiti g : GraffitiCatalog.get().free()) keys.add(g.key());
        return keys;
    }

    @Override
    public List<String> slots() {
        List<String> slots = new ArrayList<>(GraffitiCatalog.SLOTS);
        for (int i = 1; i <= GraffitiCatalog.SLOTS; i++) slots.add(GraffitiCatalog.slot(i));
        return slots;
    }
}
