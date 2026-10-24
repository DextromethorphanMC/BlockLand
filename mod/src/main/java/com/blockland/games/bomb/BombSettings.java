package com.blockland.games.bomb;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonObject;

/**
 * 枪战爆破 mode settings: the shared team arena settings (always two teams: team spawn group 1 attacks, group 2 defends),
 * the bomb timings and block rules. The mode's time limit is the time the attackers have to plant. Mirrors the
 * backend's {@code BombDefinition}.
 */
public record BombSettings(TeamArenaSettings team, float plantSeconds, float defuseSeconds, int fuseSeconds,
                           boolean allowBlockBreak, boolean allowBlockPlace) {

    public static BombSettings parse(JsonObject json) {
        TeamArenaSettings parsed = TeamArenaSettings.parse(json);
        TeamArenaSettings team = new TeamArenaSettings(parsed.teamSize(), 2, parsed.mapIds(), parsed.countdownSeconds(),
                parsed.endingSeconds());
        return new BombSettings(team,
                floatOr(json, "plantSeconds", 3.2f, 1, 15),
                floatOr(json, "defuseSeconds", 7, 1, 30),
                TeamArenaSettings.intOr(json, "fuseSeconds", 40, 10, 120),
                TeamArenaSettings.boolOr(json, "allowBlockBreak", false),
                TeamArenaSettings.boolOr(json, "allowBlockPlace", false));
    }

    public int plantTicks() {
        return Math.max(1, Math.round(plantSeconds * 20));
    }

    public int defuseTicks() {
        return Math.max(1, Math.round(defuseSeconds * 20));
    }

    public int fuseTicks() {
        return fuseSeconds * 20;
    }

    private static float floatOr(JsonObject json, String key, float fallback, float min, float max) {
        float value;
        try {
            value = json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsFloat() : fallback;
        } catch (Exception e) {
            value = fallback;
        }
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
