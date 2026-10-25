package com.blockland.backend.gametype;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.kit.KitItem;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Backend side of a minigame type. Adding a new minigame = one new implementation (a Spring bean);
 * game modes, maps, the sync API and the admin UI pick it up through {@link GameTypeRegistry}.
 * Everything a type needs beyond the generic model (e.g. SkyWars loot tables) stays inside its own package.
 */
public interface GameTypeDefinition {

    /** Same id as the mod's {@code GameType#id()}, e.g. {@code SKYWARS}. */
    String id();

    String displayName();

    String description();

    ObjectNode defaultSettings();

    /** Returns cleaned mode settings with defaults applied; adds human readable problems to {@code errors}. */
    ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors);

    /** Problems that do not block saving but make the mode unplayable (shown in the admin UI). */
    List<String> warnings(GameMode mode, Context context);

    // ---- optional capabilities --------------------------------------------------------------

    /** Whether modes of this type hand out a starting kit (and players can arrange it in the client). */
    default boolean usesKit() {
        return false;
    }

    default List<KitItem> defaultKit() {
        return List.of();
    }

    /**
     * Map settings derived from a freshly uploaded (or replaced) map file.
     *
     * @param previous the map's current settings when the file is replaced, otherwise null
     */
    default ObjectNode mapSettingsFromFile(MapFileAnalyzer.Result analysis, ObjectNode previous) {
        return previous != null ? previous : JsonNodeFactory.instance.objectNode();
    }

    /** Validates map settings edited in the admin UI. */
    default ObjectNode normalizeMapSettings(JsonNode raw, List<String> errors) {
        return raw != null && raw.isObject() ? (ObjectNode) raw : JsonNodeFactory.instance.objectNode();
    }

    /**
     * The anti-cheat preset a mode of this type uses unless it picks another ({@code settings.antiCheat.preset}, see
     * ANTICHEAT_SPEC §4): competitive types are stricter, casual ones more lenient.
     */
    default String antiCheatPreset() {
        return "standard";
    }

    /** Extra type specific content sent to Minecraft servers (e.g. loot tables); null when there is none. */
    default Object syncData() {
        return null;
    }

    record Context(List<GameMap> maps) {
    }
}
