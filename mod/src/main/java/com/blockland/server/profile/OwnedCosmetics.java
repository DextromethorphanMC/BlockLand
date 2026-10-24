package com.blockland.server.profile;

import com.blockland.server.inventory.InventoryService;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The {@link Cosmetics} other modules read: the stored slots, but only items the player still owns — a timed item that
 * ran out simply stops being worn (its slot shows the default) without anyone having to clear it.
 */
public final class OwnedCosmetics implements Cosmetics.Store {
    private final CosmeticsService stored;
    private final InventoryService inventory;

    public OwnedCosmetics(CosmeticsService stored, InventoryService inventory) {
        this.stored = stored;
        this.inventory = inventory;
    }

    @Nullable
    @Override
    public String get(UUID player, String slot) {
        String key = stored.get(player, slot);
        return key != null && inventory.owns(player, key) ? key : null;
    }

    @Override
    public Map<String, String> all(UUID player) {
        Map<String, String> owned = new LinkedHashMap<>();
        stored.all(player).forEach((slot, key) -> {
            if (inventory.owns(player, key)) owned.put(slot, key);
        });
        return owned;
    }
}
