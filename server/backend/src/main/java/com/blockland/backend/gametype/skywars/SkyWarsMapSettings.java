package com.blockland.backend.gametype.skywars;

import java.util.List;

/**
 * SkyWars part of a map ({@code GameMap.settings}): every container by coordinates and its loot table.
 *
 * @param defaultLootTableId used by chests without their own table and by containers not listed here
 */
public record SkyWarsMapSettings(List<Chest> chests, String defaultLootTableId) {

    public SkyWarsMapSettings {
        if (chests == null) chests = List.of();
    }

    /** {@code lootTableId} null means the map's default loot table. */
    public record Chest(int x, int y, int z, String lootTableId, String label) {
    }
}
