package com.blockland.games.skywars;

import com.blockland.games.skywars.loot.LootTableDef;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.MapDef;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the SkyWars-only parts of the configuration: loot tables (sent as SkyWars type data)
 * and the chest list stored in a map's settings. Mirrors the backend's {@code SkyWarsMapSettings}.
 */
final class SkyWarsData {
    private static final Gson GSON = new Gson();

    private SkyWarsData() {
    }

    static final class MapSettings {
        List<Chest> chests = new ArrayList<>();
        String defaultLootTableId;
    }

    static final class Chest {
        int x;
        int y;
        int z;
        String lootTableId;
    }

    private static final class TypeData {
        List<LootTableDef> lootTables = new ArrayList<>();
    }

    static MapSettings mapSettings(MapDef map) {
        MapSettings settings = map.settings == null ? null : GSON.fromJson(map.settings, MapSettings.class);
        if (settings == null) settings = new MapSettings();
        if (settings.chests == null) settings.chests = new ArrayList<>();
        return settings;
    }

    static Optional<LootTableDef> lootTable(ContentSnapshot content, String id) {
        if (id == null) return Optional.empty();
        return lootTables(content).stream().filter(t -> Objects.equals(t.id, id)).findFirst();
    }

    private static List<LootTableDef> lootTables(ContentSnapshot content) {
        Optional<JsonElement> data = content.typeData(SkyWarsGameType.ID);
        if (data.isEmpty()) return List.of();
        TypeData parsed = GSON.fromJson(data.get(), TypeData.class);
        return parsed == null || parsed.lootTables == null ? List.of() : parsed.lootTables;
    }
}
