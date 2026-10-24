package com.blockland.server.profile;

import com.blockland.profile.NameCardCatalog;
import com.blockland.server.inventory.InventoryCatalog;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;

/** Name cards as inventory items: {@code card:<id>}, worn in the {@link Cosmetics#CARD} slot, free cards owned by all. */
public final class CardItems implements InventoryCatalog.ItemType {
    @Override
    public String prefix() {
        return "card:";
    }

    @Override
    public String categoryKey() {
        return "blockland.inventory.category.card";
    }

    @Nullable
    @Override
    public InventoryCatalog.Display display(String key) {
        String id = NameCardCatalog.idOf(key);
        NameCardCatalog.Card card = NameCardCatalog.get().card(id);
        if (card == null) return null;
        Component description = Component.translatableWithFallback("blockland.card." + id + ".description", card.description);
        if (card.animated()) {
            description = Component.translatable("blockland.inventory.card.animated").append(" · ").append(description);
        }
        return new InventoryCatalog.Display(Component.translatableWithFallback("blockland.card." + id, card.name), description,
                "card:" + id, card.rarity);
    }

    @Override
    public List<String> keys() {
        return NameCardCatalog.get().all().stream().map(c -> NameCardCatalog.key(c.id)).toList();
    }

    @Override
    public List<String> freeKeys() {
        return NameCardCatalog.get().all().stream().filter(c -> c.free).map(c -> NameCardCatalog.key(c.id)).toList();
    }

    @Override
    public List<String> slots() {
        return List.of(Cosmetics.CARD);
    }
}
