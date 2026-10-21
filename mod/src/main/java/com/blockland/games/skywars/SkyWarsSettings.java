package com.blockland.games.skywars;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonObject;

/**
 * SkyWars part of a game mode ({@code GameModeDef.settings}): the shared team arena settings plus
 * {@code chestRefillSeconds} (0 = chests are never refilled).
 */
public record SkyWarsSettings(TeamArenaSettings team, int chestRefillSeconds) {

    public static SkyWarsSettings parse(JsonObject json) {
        return new SkyWarsSettings(TeamArenaSettings.parse(json), TeamArenaSettings.intOr(json, "chestRefillSeconds", 0, 0, 3600));
    }
}
