package com.blockland.games.fastpvp;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonObject;

/**
 * FastPVP mode settings: the shared team arena settings plus block rules, {@code infiniteBlocks} (placed blocks are given
 * back at once) and {@code knockbackOnly} (hits only knock back; the void is the only way to die, e.g. Rush). Mirrors the
 * backend's {@code FastPvpDefinition}.
 */
public record FastPvpSettings(TeamArenaSettings team, boolean allowBlockBreak, boolean allowBlockPlace, boolean infiniteBlocks,
                              boolean knockbackOnly) {

    public static FastPvpSettings parse(JsonObject json) {
        return new FastPvpSettings(TeamArenaSettings.parse(json),
                TeamArenaSettings.boolOr(json, "allowBlockBreak", false),
                TeamArenaSettings.boolOr(json, "allowBlockPlace", false),
                TeamArenaSettings.boolOr(json, "infiniteBlocks", false),
                TeamArenaSettings.boolOr(json, "knockbackOnly", false));
    }
}
