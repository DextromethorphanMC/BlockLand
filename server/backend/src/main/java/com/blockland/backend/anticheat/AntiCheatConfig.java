package com.blockland.backend.anticheat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The global anti-cheat configuration (ANTICHEAT_SPEC §4): the master switch, the presets modes choose from (severity
 * levels with their tolerance, ping compensation, confidence thresholds, actions and per-check overrides), auto-ban,
 * staff alerts, evidence keeping and how suspicion accumulates on a case. Sent to game servers with the bootstrap.
 *
 * @param defaultPreset used by players outside matches and by modes whose preset does not exist
 */
public record AntiCheatConfig(boolean enabled, String defaultPreset, Map<String, Preset> presets, AutoBan autoBan,
                              Alerts alerts, Evidence evidence, Suspicion suspicion) {
    static final int MAX_PRESETS = 32;
    static final long MAX_BAN_MINUTES = 10L * 365 * 24 * 60;

    public AntiCheatConfig {
        presets = presets == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(presets));
    }

    /**
     * A severity level.
     *
     * @param toleranceScale multiplies every check's tolerance (0.1–5, above 1 = more lenient)
     * @param checks         per-check overrides on top of the mod's defaults (only the fields that differ)
     */
    public record Preset(String name, String description, double toleranceScale, Ping ping, Confidence confidence,
                         Actions actions, ObjectNode checks) {
        public Preset {
            checks = checks == null ? JsonNodeFactory.instance.objectNode() : checks;
        }
    }

    /**
     * Latency compensation (§5): the window is {@code min(maxCompensationMs, rtt + jitterMultiplier × jitter)} plus
     * {@code extraTicks}; above {@code highPingMs} latency-sensitive evidence counts less and never auto-bans.
     */
    public record Ping(int maxCompensationMs, double jitterMultiplier, int extraTicks, int highPingMs) {
    }

    /** Confidence (0–1) from which staff are alerted, a case goes to review and a player may be auto-banned. */
    public record Confidence(double alert, double review, double autoBan) {
    }

    /** What the preset may do at all. */
    public record Actions(boolean setback, boolean kick, boolean autoBan) {
    }

    /** @param durationMinutes 0 = permanent */
    public record AutoBan(boolean enabled, int durationMinutes, String reason) {
    }

    public record Alerts(boolean staff, int cooldownSeconds) {
    }

    public record Evidence(int maxPerCase, int retentionDays, int reportCooldownSeconds) {
    }

    /** A case's suspicion halves every {@code halfLifeDays}; at {@code reviewScore} it goes to review. */
    public record Suspicion(double halfLifeDays, double reviewScore) {
    }

    // ---- built-in values ------------------------------------------------------------------------------

    private static final Ping STANDARD_PING = new Ping(1000, 2.0, 2, 400);

    static final Map<String, Preset> BUILT_IN_PRESETS = builtIns();

    public static final AntiCheatConfig DEFAULT = new AntiCheatConfig(true, AntiCheatSettings.DEFAULT_PRESET, BUILT_IN_PRESETS,
            new AutoBan(false, 0, "反作弊系统自动封禁"), new Alerts(true, 30), new Evidence(100, 60, 5), new Suspicion(7, 3.0));

    private static Map<String, Preset> builtIns() {
        Map<String, Preset> presets = new LinkedHashMap<>();
        presets.put("strict", new Preset("严格", "竞技 / 排位模式：宽限更小、延迟补偿上限更低，可自动封禁", 0.85,
                new Ping(600, 2.0, 2, 300), new Confidence(0.5, 0.7, 0.97), new Actions(true, true, true), null));
        presets.put("standard", new Preset("标准", "大多数模式：回退与踢出，不自动封禁（交给人工复核）", 1.0,
                STANDARD_PING, new Confidence(0.6, 0.8, 0.99), new Actions(true, true, false), null));
        presets.put("relaxed", new Preset("宽松", "休闲 / 练习 / 开放世界：宽限更大，只回退不踢出", 1.5,
                new Ping(1500, 2.5, 3, 600), new Confidence(0.75, 0.9, 1.0), new Actions(true, false, false), null));
        presets.put("observe", new Preset("仅记录", "只记录证据与提醒管理员，不回退、不踢出、不封禁", 1.0,
                STANDARD_PING, new Confidence(0.6, 0.8, 1.0), new Actions(false, false, false), null));
        return java.util.Collections.unmodifiableMap(presets);
    }

    // ---- validation -----------------------------------------------------------------------------------

    /**
     * The config an admin edited: missing values fall back to the built-in ones (a preset's to the built-in preset of the
     * same id, else {@code standard}); out-of-range values, bad ids and a missing {@code standard} preset are reported.
     */
    public static AntiCheatConfig normalize(JsonNode raw, List<String> errors) {
        JsonNode in = raw != null && raw.isObject() ? raw : JsonNodeFactory.instance.objectNode();
        Fields f = new Fields(errors);
        boolean enabled = f.bool(in, "enabled", DEFAULT.enabled(), "总开关");

        Map<String, Preset> presets = new LinkedHashMap<>();
        JsonNode rawPresets = in.get("presets");
        if (rawPresets == null || rawPresets.isNull()) {
            presets.putAll(BUILT_IN_PRESETS);
        } else if (!rawPresets.isObject()) {
            errors.add("presets 必须是对象");
            presets.putAll(BUILT_IN_PRESETS);
        } else {
            Iterator<Map.Entry<String, JsonNode>> it = rawPresets.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String id = entry.getKey();
                if (!AntiCheatSettings.PRESET_ID.matcher(id).matches()) {
                    errors.add("预设 ID 只能包含小写字母、数字和下划线（1-32 位）: " + id);
                    continue;
                }
                if (presets.size() >= MAX_PRESETS) {
                    errors.add("预设不能超过 " + MAX_PRESETS + " 个");
                    break;
                }
                presets.put(id, preset(id, entry.getValue(), f));
            }
            if (!presets.containsKey(AntiCheatSettings.DEFAULT_PRESET)) errors.add("不能删除标准预设 standard");
        }

        String defaultPreset = f.text(in, "defaultPreset", DEFAULT.defaultPreset(), "默认预设", 32);
        if (!presets.containsKey(defaultPreset)) errors.add("默认预设不存在: " + defaultPreset);

        JsonNode ban = in.path("autoBan");
        String reason = f.text(ban, "reason", DEFAULT.autoBan().reason(), "自动封禁原因", 200);
        AutoBan autoBan = new AutoBan(f.bool(ban, "enabled", DEFAULT.autoBan().enabled(), "自动封禁开关"),
                (int) f.number(ban, "durationMinutes", DEFAULT.autoBan().durationMinutes(), 0, MAX_BAN_MINUTES, "自动封禁时长（分钟）"),
                reason.isBlank() ? DEFAULT.autoBan().reason() : reason);
        JsonNode alertsIn = in.path("alerts");
        Alerts alerts = new Alerts(f.bool(alertsIn, "staff", DEFAULT.alerts().staff(), "管理员提醒开关"),
                (int) f.number(alertsIn, "cooldownSeconds", DEFAULT.alerts().cooldownSeconds(), 0, 3600, "提醒间隔（秒）"));
        JsonNode evidenceIn = in.path("evidence");
        Evidence evidence = new Evidence(
                (int) f.number(evidenceIn, "maxPerCase", DEFAULT.evidence().maxPerCase(), 1, 1000, "每个案件保留的证据条数"),
                (int) f.number(evidenceIn, "retentionDays", DEFAULT.evidence().retentionDays(), 1, 3650, "已关闭案件保留天数"),
                (int) f.number(evidenceIn, "reportCooldownSeconds", DEFAULT.evidence().reportCooldownSeconds(), 0, 3600, "同一检测的上报间隔（秒）"));
        JsonNode suspicionIn = in.path("suspicion");
        Suspicion suspicion = new Suspicion(
                f.number(suspicionIn, "halfLifeDays", DEFAULT.suspicion().halfLifeDays(), 0.1, 365, "可疑度半衰期（天）"),
                f.number(suspicionIn, "reviewScore", DEFAULT.suspicion().reviewScore(), 0.1, 1000, "待复核可疑度"));
        return new AntiCheatConfig(enabled, defaultPreset, presets, autoBan, alerts, evidence, suspicion);
    }

    private static Preset preset(String id, JsonNode raw, Fields f) {
        Preset base = BUILT_IN_PRESETS.getOrDefault(id, BUILT_IN_PRESETS.get(AntiCheatSettings.DEFAULT_PRESET));
        JsonNode in = raw != null && raw.isObject() ? raw : JsonNodeFactory.instance.objectNode();
        if (raw != null && !raw.isObject()) f.errors.add("预设 " + id + " 必须是对象");
        String label = "预设 " + id + " 的";
        String name = f.text(in, "name", base.name(), label + "名称", 32);
        if (name.isBlank()) f.errors.add(label + "名称不能为空");
        String description = f.text(in, "description", base.description(), label + "说明", 200);
        double scale = f.number(in, "toleranceScale", base.toleranceScale(), AntiCheatSettings.MIN_TOLERANCE,
                AntiCheatSettings.MAX_TOLERANCE, label + "宽限倍率");
        JsonNode pingIn = in.path("ping");
        Ping ping = new Ping(
                (int) f.number(pingIn, "maxCompensationMs", base.ping().maxCompensationMs(), 0, 5000, label + "延迟补偿上限（毫秒）"),
                f.number(pingIn, "jitterMultiplier", base.ping().jitterMultiplier(), 0, 10, label + "抖动倍数"),
                (int) f.number(pingIn, "extraTicks", base.ping().extraTicks(), 0, 40, label + "额外补偿 tick"),
                (int) f.number(pingIn, "highPingMs", base.ping().highPingMs(), 50, 5000, label + "高延迟阈值（毫秒）"));
        JsonNode confidenceIn = in.path("confidence");
        Confidence confidence = new Confidence(
                f.number(confidenceIn, "alert", base.confidence().alert(), 0, 1, label + "提醒置信度"),
                f.number(confidenceIn, "review", base.confidence().review(), 0, 1, label + "复核置信度"),
                f.number(confidenceIn, "autoBan", base.confidence().autoBan(), 0, 1, label + "自动封禁置信度"));
        JsonNode actionsIn = in.path("actions");
        Actions actions = new Actions(f.bool(actionsIn, "setback", base.actions().setback(), label + "回退开关"),
                f.bool(actionsIn, "kick", base.actions().kick(), label + "踢出开关"),
                f.bool(actionsIn, "autoBan", base.actions().autoBan(), label + "自动封禁开关"));
        ObjectNode checks = AntiCheatSettings.normalizeOverrides(in.get("checks"), "预设 " + id, f.errors);
        return new Preset(name, description, scale, ping, confidence, actions, checks);
    }

    /** Reads optional fields: missing (or null) = the fallback, present but wrong = an error and the fallback. */
    private record Fields(List<String> errors) {
        boolean bool(JsonNode in, String field, boolean fallback, String label) {
            JsonNode value = in.get(field);
            if (value == null || value.isNull()) return fallback;
            if (value.isBoolean()) return value.asBoolean();
            errors.add(label + "必须是 true 或 false");
            return fallback;
        }

        double number(JsonNode in, String field, double fallback, double min, double max, String label) {
            JsonNode value = in.get(field);
            if (value == null || value.isNull()) return fallback;
            if (value.isNumber() && AntiCheatSettings.within(value.asDouble(), min, max)) return value.asDouble();
            errors.add(label + "必须在 " + AntiCheatSettings.format(min) + " 到 " + AntiCheatSettings.format(max) + " 之间");
            return fallback;
        }

        String text(JsonNode in, String field, String fallback, String label, int maxLength) {
            JsonNode value = in.get(field);
            if (value == null || value.isNull()) return fallback;
            if (!value.isTextual()) {
                errors.add(label + "必须是文字");
                return fallback;
            }
            String text = value.asText().trim();
            if (text.length() > maxLength) {
                errors.add(label + "不能超过 " + maxLength + " 个字符");
                return text.substring(0, maxLength);
            }
            return text;
        }
    }
}
