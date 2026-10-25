package com.blockland.backend.anticheat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The anti-cheat part of every mode's settings ({@code settings.antiCheat}, ANTICHEAT_SPEC §4) and the per-check override
 * fields shared with the presets of the global config. A mode only picks a preset, may scale its tolerance and override
 * single checks; whatever is not written inherits (the mod resolves it). Must stay in sync with the mod's
 * {@code RuleResolver}.
 */
public final class AntiCheatSettings {
    public static final String KEY = "antiCheat";
    public static final String DEFAULT_PRESET = "standard";
    public static final Pattern PRESET_ID = Pattern.compile("^[a-z0-9_]{1,32}$");
    public static final Pattern CHECK_ID = Pattern.compile("^[a-z0-9_.]{1,48}$");
    /** Most checks one preset or mode may override. */
    static final int MAX_OVERRIDES = 64;
    static final double MIN_TOLERANCE = 0.1, MAX_TOLERANCE = 5;

    /** A number field of a check override and its range. */
    private record Range(String field, String label, double min, double max) {
    }

    private static final List<Range> NUMBERS = List.of(
            new Range("tolerance", "宽限倍率", MIN_TOLERANCE, MAX_TOLERANCE),
            new Range("setbackVl", "回退 VL", 0, 1000),
            new Range("kickVl", "踢出 VL", 0, 10000),
            new Range("decay", "衰减", 0, 100),
            new Range("weight", "权重", 0, 1));
    private static final List<String> FLAGS = List.of("enabled", "punish");

    private AntiCheatSettings() {
    }

    /**
     * The mode's {@code antiCheat} object written into the type's normalised settings: {@code enabled} (default on),
     * {@code preset} (the type's default when missing or malformed), {@code toleranceScale} (null = the preset's, else
     * 0.1–5) and {@code checks} (only valid override fields are kept, the rest is dropped silently).
     */
    public static ObjectNode normalizeMode(JsonNode raw, String defaultPreset) {
        JsonNode in = raw != null && raw.isObject() ? raw : JsonNodeFactory.instance.objectNode();
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        JsonNode enabled = in.get("enabled");
        out.put("enabled", enabled == null || !enabled.isBoolean() || enabled.asBoolean());
        String preset = in.path("preset").asText("");
        out.put("preset", PRESET_ID.matcher(preset).matches() ? preset : defaultPreset);
        JsonNode scale = in.get("toleranceScale");
        if (scale != null && scale.isNumber() && within(scale.asDouble(), MIN_TOLERANCE, MAX_TOLERANCE)) {
            out.put("toleranceScale", scale.asDouble());
        } else {
            out.putNull("toleranceScale");
        }
        out.set("checks", normalizeOverrides(in.get("checks"), null, null));
        return out;
    }

    /**
     * Per-check overrides ({@code {"movement.speed": {"tolerance": 1.2}}}): known fields within their ranges are kept,
     * empty overrides disappear. With {@code errors}, everything dropped is reported (prefixed with {@code where}).
     */
    public static ObjectNode normalizeOverrides(JsonNode raw, String where, List<String> errors) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (raw == null || raw.isNull()) return out;
        if (!raw.isObject()) {
            report(errors, where, "检测覆盖必须是对象");
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = raw.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String id = entry.getKey();
            if (!CHECK_ID.matcher(id).matches()) {
                report(errors, where, "无效的检测 ID: " + id);
                continue;
            }
            if (out.size() >= MAX_OVERRIDES) {
                report(errors, where, "检测覆盖不能超过 " + MAX_OVERRIDES + " 项");
                break;
            }
            ObjectNode override = normalizeOverride(entry.getValue(), (where == null ? "" : where + " ") + id, errors);
            if (!override.isEmpty()) out.set(id, override);
        }
        return out;
    }

    private static ObjectNode normalizeOverride(JsonNode raw, String where, List<String> errors) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (raw == null || !raw.isObject()) {
            report(errors, where, "必须是对象");
            return out;
        }
        for (String flag : FLAGS) {
            JsonNode value = raw.get(flag);
            if (value == null || value.isNull()) continue;
            if (value.isBoolean()) out.put(flag, value.asBoolean());
            else report(errors, where, flag + " 必须是 true 或 false");
        }
        for (Range range : NUMBERS) {
            JsonNode value = raw.get(range.field());
            if (value == null || value.isNull()) continue;
            if (value.isNumber() && within(value.asDouble(), range.min(), range.max())) {
                out.put(range.field(), value.asDouble());
            } else {
                report(errors, where, range.label() + "必须在 " + format(range.min()) + " 到 " + format(range.max()) + " 之间");
            }
        }
        return out;
    }

    static boolean within(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static void report(List<String> errors, String where, String message) {
        if (errors != null) errors.add(where == null || where.isBlank() ? message : where + ": " + message);
    }
}
