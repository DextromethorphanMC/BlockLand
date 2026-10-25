package com.blockland.server.anticheat.rules;

import com.blockland.server.anticheat.check.CheckCatalog;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The mode rule engine (anti-cheat spec §4): turns the backend's global config ({@code ContentSnapshot#antiCheat}),
 * the mode's preset and the mode's own {@code settings.antiCheat} into a {@link RuleSet}. Order: {@code checks.json}
 * defaults ← preset {@code checks} ← mode {@code checks}; the preset's numbers, the mode may only rescale the tolerance.
 * A missing preset falls back to the default preset, then to the built-in standard numbers; a missing config (backend
 * never reached) means standard everywhere. Cached per mode until the configuration version changes.
 */
public final class RuleResolver {
    /** Global settings that are not per mode. */
    public record Global(boolean enabled, boolean autoBan, int autoBanMinutes, boolean staffAlerts, int alertCooldownSeconds,
                         int reportCooldownSeconds) {
    }

    private final Supplier<ContentSnapshot> content;
    private final Function<UUID, GameModeDef> modeOf;
    private final Map<String, RuleSet> cache = new HashMap<>();
    private long cachedVersion = Long.MIN_VALUE;
    private Global global = new Global(true, false, 0, true, 30, 5);

    /** @param modeOf the mode of the match a player is in, null outside matches */
    public RuleResolver(Supplier<ContentSnapshot> content, Function<UUID, GameModeDef> modeOf) {
        this.content = content;
        this.modeOf = modeOf;
    }

    public RuleSet rulesFor(UUID player) {
        return rules(modeOf.apply(player));
    }

    @Nullable
    public GameModeDef modeOf(UUID player) {
        return modeOf.apply(player);
    }

    public Global global() {
        refresh();
        return global;
    }

    /** The rules of a mode; null = outside matches (the default preset). */
    public RuleSet rules(@Nullable GameModeDef mode) {
        refresh();
        String key = mode == null ? "" : mode.id;
        RuleSet cached = cache.get(key);
        if (cached == null) {
            cached = resolve(config(), mode);
            cache.put(key, cached);
        }
        return cached;
    }

    private void refresh() {
        long version = content.get().version;
        if (version == cachedVersion) return;
        cachedVersion = version;
        cache.clear();
        JsonObject config = config();
        JsonObject autoBan = object(config, "autoBan");
        JsonObject alerts = object(config, "alerts");
        JsonObject evidence = object(config, "evidence");
        global = new Global(CheckSettings.bool(config, "enabled", true),
                CheckSettings.bool(autoBan, "enabled", false),
                (int) CheckSettings.number(autoBan, "durationMinutes", 0, 0, 10_000_000),
                CheckSettings.bool(alerts, "staff", true),
                (int) CheckSettings.number(alerts, "cooldownSeconds", 30, 0, 3600),
                (int) CheckSettings.number(evidence, "reportCooldownSeconds", 5, 0, 3600));
    }

    private JsonObject config() {
        JsonObject config = content.get().antiCheat;
        return config == null ? new JsonObject() : config;
    }

    private static RuleSet resolve(JsonObject config, @Nullable GameModeDef mode) {
        boolean enabled = CheckSettings.bool(config, "enabled", true);
        JsonObject modeRules = mode == null || mode.settings == null ? new JsonObject() : object(mode.settings, "antiCheat");
        if (mode != null && !CheckSettings.bool(modeRules, "enabled", true)) enabled = false;
        JsonObject presets = object(config, "presets");
        String defaultPreset = string(config, "defaultPreset", "standard");
        String presetId = mode == null ? defaultPreset : string(modeRules, "preset", defaultPreset);
        JsonObject preset = object(presets, presetId);
        if (preset.isEmpty()) {
            presetId = defaultPreset;
            preset = object(presets, presetId);
        }
        JsonObject ping = object(preset, "ping");
        JsonObject confidence = object(preset, "confidence");
        JsonObject actions = object(preset, "actions");
        double scale = CheckSettings.number(preset, "toleranceScale", 1, 0.1, 5);
        scale = CheckSettings.number(modeRules, "toleranceScale", scale, 0.1, 5);

        CheckCatalog catalog = CheckCatalog.get();
        CheckSettings[] checks = new CheckSettings[catalog.size()];
        JsonObject presetChecks = object(preset, "checks");
        JsonObject modeChecks = object(modeRules, "checks");
        for (CheckCatalog.Entry entry : catalog.entries()) {
            checks[entry.index()] = entry.defaults().with(object(presetChecks, entry.id())).with(object(modeChecks, entry.id()));
        }
        // the built-in standard preset's numbers stand in for whatever the config lacks
        return new RuleSet(enabled, presetId, scale,
                CheckSettings.number(ping, "maxCompensationMs", 1000, 0, 5000),
                CheckSettings.number(ping, "jitterMultiplier", 2, 0, 10),
                (int) CheckSettings.number(ping, "extraTicks", 2, 0, 40),
                CheckSettings.number(ping, "highPingMs", 400, 50, 5000),
                CheckSettings.number(confidence, "alert", 0.6, 0, 1),
                CheckSettings.number(confidence, "review", 0.8, 0, 1),
                CheckSettings.number(confidence, "autoBan", 0.99, 0, 1),
                CheckSettings.bool(actions, "setback", true),
                CheckSettings.bool(actions, "kick", true),
                CheckSettings.bool(actions, "autoBan", false),
                checks);
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject parent, String key, String fallback) {
        JsonElement value = parent == null ? null : parent.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        String s = value.getAsString();
        return s.isBlank() ? fallback : s;
    }
}
