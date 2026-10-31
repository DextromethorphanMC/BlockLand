package com.blockland.backend.gametype.uhc;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.common.MovementSettings;
import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 极限生存 (UHC): no map — every match is played on fresh terrain the mod generates (its UHC dimension), inside a square
 * border; everyone spawns at a random spot, gathers, crafts and fights, the last one alive wins. Free for all
 * ({@code players} one-player teams, 2–100). Settings: {@code countdownSeconds}, {@code endingSeconds},
 * {@code borderRadius} (half the border's side, 100–2000), {@code spawnSpacing} (blocks between spawns),
 * {@code normalPhaseSeconds} (the normal circle; when nobody has won by then the final circle begins),
 * {@code pvpGraceSeconds} (no PVP at the start), {@code finalRadius} (the middle square the survivors are teleported
 * into), {@code finalDelaySeconds}, {@code finalShrinkSeconds} and {@code finalEndRadius} (how the final border closes
 * in), {@code borderDamage} and {@code borderDamagePerBlock} (per second outside the border),
 * {@code naturalRegeneration} (off: the classic UHC rule), {@code projectileKnockback} and the movement rules. The kit
 * is what everyone starts with (empty by default); the mode's {@code timeLimitSeconds} is an optional hard end (more
 * than one left = draw). Must stay in sync with the mod's {@code UhcSettings}.
 */
@Component
public class UhcDefinition implements GameTypeDefinition {
    public static final String ID = "UHC";
    private static final boolean ALLOW_SLIDE = false;

    private final ObjectMapper mapper;

    public UhcDefinition(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "极限生存";
    }

    @Override
    public String description() {
        return "全新生成的生存世界，四周有边界；所有人随机出生，采集、合成、战斗，最后存活的玩家获胜。普通圈限时内未分胜负则开启决赛圈：传送到中央并逐渐缩圈。";
    }

    @Override
    public ObjectNode defaultSettings() {
        ObjectNode node = mapper.createObjectNode();
        node.put("players", 16);
        node.put("countdownSeconds", 15);
        node.put("endingSeconds", 10);
        node.put("borderRadius", 500);
        node.put("spawnSpacing", 100);
        node.put("normalPhaseSeconds", 1200);
        node.put("pvpGraceSeconds", 0);
        node.put("finalRadius", 150);
        node.put("finalDelaySeconds", 30);
        node.put("finalShrinkSeconds", 300);
        node.put("finalEndRadius", 15);
        node.put("borderDamage", 1.0);
        node.put("borderDamagePerBlock", 0.2);
        node.put("naturalRegeneration", false);
        node.put("projectileKnockback", true);
        MovementSettings.putDefaults(node, ALLOW_SLIDE);
        return node;
    }

    @Override
    public ObjectNode normalizeSettings(JsonNode raw, Context context, List<String> errors) {
        JsonNode in = raw == null || !raw.isObject() ? mapper.createObjectNode() : raw;
        ObjectNode out = defaultSettings();
        out.put("players", TeamArenaSettings.intField(in, "players", 16, 2, 100, "每局人数", errors));
        out.put("countdownSeconds", TeamArenaSettings.intField(in, "countdownSeconds", 15, 0, 120, "开局倒计时", errors));
        out.put("endingSeconds", TeamArenaSettings.intField(in, "endingSeconds", 10, 1, 60, "结算时间", errors));
        int border = TeamArenaSettings.intField(in, "borderRadius", 500, 100, 2000, "边界半径", errors);
        out.put("borderRadius", border);
        out.put("spawnSpacing", TeamArenaSettings.intField(in, "spawnSpacing", 100, 0, 1000, "出生点间距", errors));
        out.put("normalPhaseSeconds", TeamArenaSettings.intField(in, "normalPhaseSeconds", 1200, 60, 7200, "普通圈时长", errors));
        out.put("pvpGraceSeconds", TeamArenaSettings.intField(in, "pvpGraceSeconds", 0, 0, 1800, "和平期", errors));
        int finalRadius = TeamArenaSettings.intField(in, "finalRadius", 150, 20, 1000, "决赛圈半径", errors);
        if (finalRadius > border) {
            errors.add("决赛圈半径不能大于边界半径（" + border + "）");
            finalRadius = border;
        }
        out.put("finalRadius", finalRadius);
        out.put("finalDelaySeconds", TeamArenaSettings.intField(in, "finalDelaySeconds", 30, 0, 600, "缩圈前等待", errors));
        out.put("finalShrinkSeconds", TeamArenaSettings.intField(in, "finalShrinkSeconds", 300, 10, 3600, "缩圈时长", errors));
        int endRadius = TeamArenaSettings.intField(in, "finalEndRadius", 15, 2, 500, "最终边界半径", errors);
        if (endRadius > finalRadius) {
            errors.add("最终边界半径不能大于决赛圈半径（" + finalRadius + "）");
            endRadius = finalRadius;
        }
        out.put("finalEndRadius", endRadius);
        out.put("borderDamage", TeamArenaSettings.doubleField(in, "borderDamage", 1.0, 0, 20, "边界外伤害", errors));
        out.put("borderDamagePerBlock", TeamArenaSettings.doubleField(in, "borderDamagePerBlock", 0.2, 0, 5, "每格额外伤害", errors));
        out.put("naturalRegeneration", TeamArenaSettings.boolField(in, "naturalRegeneration", false));
        out.put("projectileKnockback", TeamArenaSettings.boolField(in, "projectileKnockback", true));
        MovementSettings.normalizeInto(in, out, ALLOW_SLIDE);
        return out;
    }

    /** The world is generated by the mod: only settings that make little sense are pointed out. */
    @Override
    public List<String> warnings(GameMode mode, Context context) {
        List<String> warnings = new ArrayList<>();
        JsonNode settings = mode.settings();
        if (settings == null) return warnings;
        int players = settings.path("players").asInt(16);
        int border = settings.path("borderRadius").asInt(500);
        int spacing = settings.path("spawnSpacing").asInt(100);
        // a square of side 2r holds about (2r / spacing)² spawns that far apart
        double fits = Math.pow(2.0 * (border - 24) / Math.max(1, spacing) + 1, 2);
        if (spacing > 0 && fits < players) {
            warnings.add("边界内放不下 " + players + " 个相距 " + spacing + " 格的出生点，开局时会自动缩小间距");
        }
        if (settings.path("borderDamage").asDouble(1) <= 0 && settings.path("borderDamagePerBlock").asDouble(0.2) <= 0) {
            warnings.add("边界外没有伤害：缩圈无法逼迫玩家交战");
        }
        return warnings;
    }

    /** Everyone starts with what the kit holds — nothing unless an admin adds something. */
    @Override
    public boolean usesKit() {
        return true;
    }
}
