package com.blockland.movement.action;

import com.blockland.BlockLand;
import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Reads an action's tuning from {@code data/blockland/movement/<id>.json} on the classpath (Gson; field names = JSON
 * keys) — the same file on client and server, so the prediction, the server's checks, the eye height shots leave from
 * and the hit boxes all agree. A missing or broken file keeps the defaults written in the class.
 */
final class ActionData {
    private ActionData() {
    }

    static <T> T load(String id, Class<T> type, Supplier<T> defaults) {
        String resource = "/data/blockland/movement/" + id + ".json";
        try (InputStream in = ActionData.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                T rules = new Gson().fromJson(reader, type);
                return rules == null ? defaults.get() : rules;
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load {} rules, using defaults", id, e);
            return defaults.get();
        }
    }
}
