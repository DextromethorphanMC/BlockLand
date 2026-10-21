package com.blockland.games.fastpvp;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonObject;

/** FastPVP mode settings: the shared team arena settings plus block rules. Mirrors the backend's {@code FastPvpDefinition}. */
public record FastPvpSettings(TeamArenaSettings team, boolean allowBlockBreak, boolean allowBlockPlace) {

    public static FastPvpSettings parse(JsonObject json) {
        return new FastPvpSettings(TeamArenaSettings.parse(json),
                TeamArenaSettings.boolOr(json, "allowBlockBreak", false),
                TeamArenaSettings.boolOr(json, "allowBlockPlace", false));
    }
}
