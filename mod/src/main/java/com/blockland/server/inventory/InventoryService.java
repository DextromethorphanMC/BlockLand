package com.blockland.server.inventory;

import com.blockland.BlockLand;
import com.blockland.network.packet.ProfilePackets;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.profile.CosmeticsService;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 库存: what a player owns — their active entitlements plus every item type's free keys — shown through the types
 * registered in {@link InventoryCatalog}, and putting owned items into cosmetics slots. Ownership is the backend's
 * (entitlements with an end date, permanent when none), so this only reads it; nothing here is trusted from the client.
 */
public final class InventoryService {
    private final BackendClient backend;
    private final PlayerProfiles profiles;
    private final CosmeticsService cosmetics;

    /** Backend JSON of one registry line ({@code POST /api/server/registry/inventory}). */
    private record RegistryItem(String key, String category, String name, String rarity, boolean free) {
    }

    public InventoryService(BackendClient backend, PlayerProfiles profiles, CosmeticsService cosmetics) {
        this.backend = backend;
        this.profiles = profiles;
        this.cosmetics = cosmetics;
    }

    /** Everything the player owns, by type (in registration order), then as the entitlements came. */
    public List<ProfilePackets.Item> inventory(UUID player) {
        Map<String, Long> owned = new LinkedHashMap<>();
        for (InventoryCatalog.ItemType type : InventoryCatalog.types()) {
            for (String key : type.freeKeys()) owned.put(key, 0L);
        }
        // a granted copy of a free item changes nothing (it is permanent either way)
        profiles.entitlements(player).forEach(owned::putIfAbsent);
        Map<String, String> worn = cosmetics.all(player);
        List<ProfilePackets.Item> items = new ArrayList<>();
        for (InventoryCatalog.ItemType type : InventoryCatalog.types()) {
            for (Map.Entry<String, Long> e : owned.entrySet()) {
                if (InventoryCatalog.typeOf(e.getKey()) == type) items.add(item(e.getKey(), e.getValue(), type, worn));
            }
        }
        for (Map.Entry<String, Long> e : owned.entrySet()) {
            if (InventoryCatalog.typeOf(e.getKey()) == null) items.add(item(e.getKey(), e.getValue(), null, worn));
        }
        return items;
    }

    private static ProfilePackets.Item item(String key, long until, @Nullable InventoryCatalog.ItemType type, Map<String, String> worn) {
        InventoryCatalog.Display display = type == null ? null : type.display(key);
        List<String> equipped = new ArrayList<>();
        worn.forEach((slot, k) -> {
            if (k.equals(key)) equipped.add(slot);
        });
        if (display == null) {
            // a key this server does not know (another server's item, a perk): still listed, plainly
            return new ProfilePackets.Item(key, type == null ? "blockland.inventory.category.other" : type.categoryKey(),
                    Component.literal(key), Component.empty(), "", "common", until, List.of(), equipped);
        }
        return new ProfilePackets.Item(key, type.categoryKey(), display.name(), display.description(), display.icon(), display.rarity(),
                until, type.slots(), equipped);
    }

    /** Whether the player owns the item: a free key of its type, or an active entitlement. */
    public boolean owns(UUID player, String key) {
        InventoryCatalog.ItemType type = InventoryCatalog.typeOf(key);
        if (type != null && type.freeKeys().contains(key)) return true;
        return profiles.entitlementUntil(player, key) >= 0;
    }

    /**
     * Puts an owned item into a slot of its type, or empties the slot ({@code key} empty).
     *
     * @return null when done, otherwise why not (for the player)
     */
    @Nullable
    public Component equip(UUID player, String slot, String key) {
        if (key.isEmpty()) {
            if (cosmetics.get(player, slot) != null) cosmetics.set(player, slot, null);
            return null;
        }
        InventoryCatalog.ItemType type = InventoryCatalog.typeOf(key);
        if (type == null || !type.slots().contains(slot)) return Component.translatable("blockland.inventory.error.slot");
        if (!owns(player, key)) return Component.translatable("blockland.inventory.error.not_owned");
        cosmetics.set(player, slot, key);
        return null;
    }

    /** Tells the backend which items this server offers (the admin's item picker). */
    public void publishRegistry() {
        List<RegistryItem> items = new ArrayList<>();
        for (InventoryCatalog.ItemType type : InventoryCatalog.types()) {
            List<String> free = type.freeKeys();
            for (String key : type.keys()) {
                InventoryCatalog.Display display = type.display(key);
                if (display == null) continue;
                items.add(new RegistryItem(key, type.categoryKey(), display.name().getString(), display.rarity(), free.contains(key)));
            }
        }
        backend.post("/api/server/registry/inventory", items, Void.class)
                .whenComplete((v, error) -> {
                    if (error != null) BlockLand.LOGGER.warn("[BlockLand] Inventory registry not published: {}", error.getMessage());
                    else BlockLand.LOGGER.info("[BlockLand] Inventory registry published: {} items", items.size());
                });
    }
}
