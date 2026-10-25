package com.blockland.server.anticheat.check;

import com.blockland.BlockLand;
import com.blockland.server.anticheat.rules.CheckSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every check the anti-cheat knows (anti-cheat spec §3), read once from {@code data/blockland/anticheat/checks.json}: id,
 * category, the admin's name and description, whether latency affects it, whether other modules report it, and its
 * default settings. Indices follow the file order and stay fixed for the process; the server publishes the file to the
 * backend so the admin UI shows exactly these checks.
 */
public final class CheckCatalog {
    private static final String RESOURCE = "/data/blockland/anticheat/checks.json";
    private static CheckCatalog instance;

    /** One check of the file. */
    public record Entry(int index, String id, String category, String name, String description, boolean latencySensitive,
                        boolean signal, CheckSettings defaults) {
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final JsonObject raw;

    private CheckCatalog(JsonObject raw) {
        this.raw = raw;
        List<Entry> list = new ArrayList<>();
        Map<String, Entry> map = new HashMap<>();
        JsonObject checks = raw.has("checks") && raw.get("checks").isJsonObject() ? raw.getAsJsonObject("checks") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : checks.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            JsonObject d = o.has("defaults") && o.get("defaults").isJsonObject() ? o.getAsJsonObject("defaults") : new JsonObject();
            CheckSettings defaults = new CheckSettings(true, 1, 0, 0, 0.3, 0.5, false).with(d);
            Entry entry = new Entry(list.size(), e.getKey(), string(o, "category"), string(o, "name"), string(o, "description"),
                    o.has("latencySensitive") && o.get("latencySensitive").getAsBoolean(),
                    o.has("signal") && o.get("signal").getAsBoolean(), defaults);
            list.add(entry);
            map.put(entry.id(), entry);
        }
        this.entries = Collections.unmodifiableList(list);
        this.byId = map;
        BlockLand.LOGGER.info("[BlockLand] Anti-cheat catalogue: {} checks", entries.size());
    }

    public static synchronized CheckCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static CheckCatalog load() {
        try (InputStream in = CheckCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return new CheckCatalog(new JsonObject());
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                return new CheckCatalog(parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject());
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the anti-cheat catalogue", e);
            return new CheckCatalog(new JsonObject());
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    @Nullable
    public Entry byId(String id) {
        return byId.get(id);
    }

    /** The check's index, -1 when the file does not have it (the check then never fires). */
    public int index(String id) {
        Entry entry = byId.get(id);
        if (entry == null) BlockLand.LOGGER.warn("[BlockLand] Anti-cheat check {} is not in checks.json", id);
        return entry == null ? -1 : entry.index();
    }

    public Entry at(int index) {
        return entries.get(index);
    }

    /** The registry the backend keeps for the admin UI ({@code POST /api/server/registry/anticheat}). */
    public JsonObject registry() {
        JsonArray list = new JsonArray();
        JsonObject checks = raw.has("checks") && raw.get("checks").isJsonObject() ? raw.getAsJsonObject("checks") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : checks.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject().deepCopy();
            o.addProperty("id", e.getKey());
            list.add(o);
        }
        JsonObject body = new JsonObject();
        body.add("checks", list);
        return body;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
