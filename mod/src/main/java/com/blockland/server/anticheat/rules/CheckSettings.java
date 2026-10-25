package com.blockland.server.anticheat.rules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * How one check behaves in a mode (anti-cheat spec §4): on or off, how lenient, when it undoes the violation, kicks,
 * how fast its violation level wears off, how much it counts towards the confidence and whether it may lead to a ban.
 */
public record CheckSettings(boolean enabled, double tolerance, double setbackVl, double kickVl, double decay, double weight,
                            boolean punish) {
    public static final CheckSettings OFF = new CheckSettings(false, 1, 0, 0, 1, 0, false);

    /** These settings with the fields {@code overrides} names (out-of-range values clamped, broken ones ignored). */
    public CheckSettings with(JsonObject overrides) {
        if (overrides == null || overrides.isEmpty()) return this;
        return new CheckSettings(
                bool(overrides, "enabled", enabled),
                number(overrides, "tolerance", tolerance, 0.1, 5),
                number(overrides, "setbackVl", setbackVl, 0, 1000),
                number(overrides, "kickVl", kickVl, 0, 10000),
                number(overrides, "decay", decay, 0, 100),
                number(overrides, "weight", weight, 0, 1),
                bool(overrides, "punish", punish));
    }

    static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return fallback;
        return value.getAsBoolean();
    }

    static double number(JsonObject object, String key, double fallback, double min, double max) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return fallback;
        double d = value.getAsDouble();
        return Double.isFinite(d) ? Math.max(min, Math.min(max, d)) : fallback;
    }
}
