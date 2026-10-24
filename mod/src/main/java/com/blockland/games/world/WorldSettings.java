package com.blockland.games.world;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 开放世界 mode settings: which dimension is the world ({@code dimension}, default {@code blockland:world}), the game
 * mode players have in it, whether players can hurt each other ({@code pvp}), whether death keeps the inventory, and
 * how many may be in it at once. Mirrors the backend's {@code WorldDefinition}.
 */
public record WorldSettings(@Nullable ResourceLocation dimension, GameType gameMode, boolean pvp, boolean keepInventory, int maxPlayers) {
    public static final String DEFAULT_DIMENSION = "blockland:world";

    public static WorldSettings parse(JsonObject json) {
        return new WorldSettings(
                ResourceLocation.tryParse(stringOr(json, "dimension", DEFAULT_DIMENSION)),
                gameMode(stringOr(json, "gameMode", "SURVIVAL")),
                TeamArenaSettings.boolOr(json, "pvp", true),
                TeamArenaSettings.boolOr(json, "keepInventory", false),
                TeamArenaSettings.intOr(json, "maxPlayers", 50, 1, 200));
    }

    private static GameType gameMode(String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "ADVENTURE" -> GameType.ADVENTURE;
            case "CREATIVE" -> GameType.CREATIVE;
            default -> GameType.SURVIVAL;
        };
    }

    private static String stringOr(JsonObject json, String key, String fallback) {
        try {
            return json != null && json.has(key) && json.get(key).isJsonPrimitive() && !json.get(key).getAsString().isBlank()
                    ? json.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
