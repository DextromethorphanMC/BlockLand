package com.blockland.equipment.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Reads an item's {@code effect} parameters (equipment.json) with defaults, so a missing or odd value never breaks. */
public final class Params {
    private Params() {
    }

    public static float f(JsonObject params, String key, float fallback) {
        JsonElement e = params == null ? null : params.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        float v = e.getAsFloat();
        return Float.isFinite(v) ? v : fallback;
    }

    public static int i(JsonObject params, String key, int fallback) {
        return Math.round(f(params, key, fallback));
    }

    public static boolean b(JsonObject params, String key, boolean fallback) {
        JsonElement e = params == null ? null : params.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }
}
