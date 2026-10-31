package com.blockland.games.uhc;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonObject;

/**
 * 极限生存 (UHC) mode settings: free for all ({@code players} single-player teams) in a fresh generated world inside a
 * square border of half-width {@code borderRadius}; spawns at least {@code spawnSpacing} blocks apart. The normal phase
 * lasts {@code normalPhaseSeconds} (the first {@code pvpGraceSeconds} without PVP); then the final phase teleports the
 * survivors into the middle square of half-width {@code finalRadius}, which starts shrinking after
 * {@code finalDelaySeconds} to {@code finalEndRadius} over {@code finalShrinkSeconds}. Outside the border a player takes
 * {@code borderDamage} plus {@code borderDamagePerBlock} per block beyond it every second; {@code naturalRegeneration}
 * off is the classic rule (health only from golden apples, potions and the like). The mode's time limit is an optional
 * hard end. Mirrors the backend's {@code UhcDefinition}.
 */
record UhcSettings(int players, int countdownSeconds, int endingSeconds, int borderRadius, int spawnSpacing,
                   int normalPhaseSeconds, int pvpGraceSeconds, int finalRadius, int finalDelaySeconds, int finalShrinkSeconds,
                   int finalEndRadius, float borderDamage, float borderDamagePerBlock, boolean naturalRegeneration) {

    static UhcSettings parse(JsonObject raw) {
        JsonObject json = raw == null ? new JsonObject() : raw;
        int border = TeamArenaSettings.intOr(json, "borderRadius", 500, 100, 2000);
        int finalRadius = Math.min(border, TeamArenaSettings.intOr(json, "finalRadius", 150, 20, 1000));
        return new UhcSettings(
                TeamArenaSettings.intOr(json, "players", 16, 2, 100),
                TeamArenaSettings.intOr(json, "countdownSeconds", 15, 0, 120),
                TeamArenaSettings.intOr(json, "endingSeconds", 10, 1, 60),
                border,
                TeamArenaSettings.intOr(json, "spawnSpacing", 100, 0, 1000),
                TeamArenaSettings.intOr(json, "normalPhaseSeconds", 1200, 60, 7200),
                TeamArenaSettings.intOr(json, "pvpGraceSeconds", 0, 0, 1800),
                finalRadius,
                TeamArenaSettings.intOr(json, "finalDelaySeconds", 30, 0, 600),
                TeamArenaSettings.intOr(json, "finalShrinkSeconds", 300, 10, 3600),
                Math.min(finalRadius, TeamArenaSettings.intOr(json, "finalEndRadius", 15, 2, 500)),
                floatOr(json, "borderDamage", 1, 0, 20),
                floatOr(json, "borderDamagePerBlock", 0.2f, 0, 5),
                TeamArenaSettings.boolOr(json, "naturalRegeneration", false));
    }

    private static float floatOr(JsonObject json, String key, float fallback, float min, float max) {
        float value;
        try {
            value = json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsFloat() : fallback;
        } catch (Exception e) {
            value = fallback;
        }
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
