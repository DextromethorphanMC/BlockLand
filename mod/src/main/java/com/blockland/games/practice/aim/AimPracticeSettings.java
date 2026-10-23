package com.blockland.games.practice.aim;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonObject;

/**
 * 枪械瞄准练习 mode settings. Mirrors the backend's {@code AimPracticeDefinition}.
 *
 * @param targetCount    fake players on the range at once
 * @param targetHealth   their health (a player has 20)
 * @param moving         whether they strafe, peek and crouch (false = they stand still)
 * @param respawnSeconds how long after a kill a new target steps in
 * @param armor          targets wear iron armour (body shots do less, like an armoured enemy)
 */
public record AimPracticeSettings(int targetCount, int targetHealth, boolean moving, float respawnSeconds, boolean armor) {

    public static AimPracticeSettings parse(JsonObject json) {
        return new AimPracticeSettings(
                TeamArenaSettings.intOr(json, "targetCount", 8, 1, 24),
                TeamArenaSettings.intOr(json, "targetHealth", 20, 1, 200),
                TeamArenaSettings.boolOr(json, "moving", true),
                seconds(json),
                TeamArenaSettings.boolOr(json, "armor", false));
    }

    private static float seconds(JsonObject json) {
        try {
            double value = json != null && json.has("respawnSeconds") && !json.get("respawnSeconds").isJsonNull()
                    ? json.get("respawnSeconds").getAsDouble() : 1.5;
            return (float) Math.max(0, Math.min(30, value));
        } catch (Exception e) {
            return 1.5f;
        }
    }
}
