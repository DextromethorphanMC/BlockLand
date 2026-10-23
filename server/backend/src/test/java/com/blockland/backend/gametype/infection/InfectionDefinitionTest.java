package com.blockland.backend.gametype.infection;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.infection.InfectionMapSettings.Role;
import com.blockland.backend.gametype.infection.InfectionMapSettings.Spawn;
import com.blockland.backend.kit.KitItem;
import com.blockland.backend.kit.KitValidator;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.registry.GameTypeRegistryData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InfectionDefinitionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GameTypeDefinition.Context noMaps = new GameTypeDefinition.Context(List.of());
    private GameTypeRegistryData registry;
    private InfectionDefinition definition;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        registry = new GameTypeRegistryData(mapper, new BlockLandProperties(dir.toString(), null, null, null, false, null));
        definition = new InfectionDefinition(mapper, registry);
    }

    @Test
    void missingSettingsBecomeDefaults() {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(null, noMaps, errors);
        assertThat(errors).isEmpty();
        assertThat(out).isEqualTo(definition.defaultSettings());
        assertThat(out.get("players").asInt()).isEqualTo(8);
        assertThat(out.get("startMonsters").asInt()).isEqualTo(1);
        assertThat(out.get("rounds").asInt()).isEqualTo(3);
        assertThat(out.get("roundBreakSeconds").asInt()).isEqualTo(6);
        assertThat(out.get("monsters")).isEmpty();
        assertThat(out.has("motherRatio")).isFalse();
    }

    @Test
    void clampsNumbersAndReportsErrors() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(mapper.readTree("""
                {"players": 1, "rounds": 20, "roundBreakSeconds": 1, "motherHealthMultiplier": 9, "monsterDamage": "abc",
                 "infectionDelaySeconds": "soon", "selectSeconds": 99, "motherRatio": 4,
                 "monsters": [" Zombie ", "zombie", "", "slime_licker", "bad id!"]}"""), noMaps, errors);
        assertThat(out.get("players").asInt()).isEqualTo(2);
        assertThat(out.get("rounds").asInt()).isEqualTo(15);
        assertThat(out.get("roundBreakSeconds").asInt()).isEqualTo(3);
        assertThat(out.get("motherHealthMultiplier").asDouble()).isEqualTo(5.0);
        assertThat(out.get("monsterDamage").asDouble()).isEqualTo(6.0);
        assertThat(out.get("infectionDelaySeconds").asInt()).isEqualTo(15);
        assertThat(out.get("selectSeconds").asInt()).isEqualTo(30);
        assertThat(out.has("motherRatio")).isFalse();
        assertThat(mapper.convertValue(out.get("monsters"), List.class)).containsExactly("zombie", "slime_licker");
        assertThat(errors).containsExactlyInAnyOrder(
                "每局人数 必须在 2 到 32 之间",
                "回合数 必须在 1 到 15 之间",
                "回合间隔 必须在 3 到 30 之间",
                "母体生命倍率 必须在 1 到 5 之间",
                "怪物伤害 必须是数字",
                "母体出现时间 必须是整数",
                "怪物选择时间 必须在 3 到 30 之间",
                "怪物 ID 只能包含小写字母、数字和下划线: bad id!");
    }

    @Test
    void startMonstersMustLeaveAHuman() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(mapper.readTree("{\"players\": 4, \"startMonsters\": 4}"), noMaps, errors);
        assertThat(errors).containsExactly("开局怪物人数 必须小于每局人数 4");
        assertThat(out.get("startMonsters").asInt()).isEqualTo(3);

        errors.clear();
        out = definition.normalizeSettings(mapper.readTree("{\"players\": 2, \"startMonsters\": 1}"), noMaps, errors);
        assertThat(errors).isEmpty();
        assertThat(out.get("startMonsters").asInt()).isEqualTo(1);
    }

    @Test
    void keepsOnlyExistingInfectionMaps() throws Exception {
        GameTypeDefinition.Context context = new GameTypeDefinition.Context(List.of(
                map("infection_demo", InfectionDefinition.ID, List.of()), map("demo_arena", "FASTPVP", List.of())));
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(mapper.readTree("{\"mapIds\": [\"infection_demo\", \"demo_arena\", \"missing\"]}"), context, errors);
        assertThat(mapper.convertValue(out.get("mapIds"), List.class)).containsExactly("infection_demo");
        assertThat(errors).containsExactlyInAnyOrder("地图 demo_arena 不属于该游戏类型", "地图不存在: missing");
    }

    @Test
    void unknownMonstersAreOnlyReportedOnceTheServerPublishedItsMonsters() throws Exception {
        ObjectNode settings = definition.defaultSettings();
        settings.putArray("monsters").add("zombie").add("witch");
        GameMode mode = mode(settings, null, List.of());
        assertThat(definition.warnings(mode, noMaps)).containsExactly(
                "没有可用的地图",
                "未设置存活时间，每回合按 180 秒计算（时间结束时仍有人类存活则人类赢下该回合）",
                "没有配置开局装备，人类将空手进入对局");

        registry.replace(InfectionDefinition.ID, (ObjectNode) mapper.readTree("""
                {"monsters": [{"id": "zombie", "name": "僵尸"}, {"id": "slime_licker", "name": "粘液舔舐者"}]}"""));
        assertThat(definition.warnings(mode, noMaps)).endsWith("游戏服务器没有怪物「witch」，模组会忽略它");
        assertThat(definition.warnings(mode, noMaps)).noneMatch(w -> w.contains("zombie"));
    }

    @Test
    void warnsAboutRoleSpawns() {
        GameTypeDefinition.Context context = new GameTypeDefinition.Context(List.of(
                map("few_humans", InfectionDefinition.ID, List.of(spawn(1, Role.HUMAN), spawn(2, Role.HUMAN), spawn(3, Role.BOTH))),
                map("no_monsters", InfectionDefinition.ID, List.of(spawn(1, Role.HUMAN), spawn(2, Role.HUMAN))),
                map("monsters_only", InfectionDefinition.ID, List.of(spawn(1, Role.MONSTER)))));
        ObjectNode settings = definition.defaultSettings();
        settings.put("players", 2);
        List<String> warnings = definition.warnings(mode(settings, 180, definition.defaultKit()), context);
        assertThat(warnings).containsExactly(
                "地图「no_monsters」没有怪物复活点，怪物将在人类出生点复活",
                "地图「monsters_only」没有人类可用的出生点（角色为人类或通用）");

        settings.put("players", 8);
        assertThat(definition.warnings(mode(settings, 180, definition.defaultKit()), context)).contains(
                "地图「few_humans」只有 3 个人类可用的出生点，少于每局人数 8，玩家会共用出生位置");

        GameTypeDefinition.Context onlyMonsters = new GameTypeDefinition.Context(List.of(map("m", InfectionDefinition.ID, List.of(spawn(1, Role.MONSTER)))));
        assertThat(definition.warnings(mode(settings, 180, definition.defaultKit()), onlyMonsters))
                .startsWith("当前没有任何地图满足该模式，玩家无法匹配");
    }

    @Test
    void mapsWithoutRoleSpawnsFallBackToTeamSpawns() {
        GameMap oneGroup = teamMap("one", List.of(4));
        GameMap twoGroups = teamMap("two", List.of(8, 3));
        GameMap none = teamMap("none", List.of());
        List<String> warnings = definition.warnings(mode(definition.defaultSettings(), 180, definition.defaultKit()),
                new GameTypeDefinition.Context(List.of(oneGroup, twoGroups, none)));
        // one group = BOTH (no missing monster points), two groups = HUMAN + MONSTER
        assertThat(warnings).containsExactly(
                "地图「one」只有 4 个人类可用的出生点，少于每局人数 8，玩家会共用出生位置",
                "地图「none」没有人类可用的出生点（角色为人类或通用）");
    }

    @Test
    void defaultKitFitsTheKitRules() {
        List<KitItem> kit = definition.defaultKit();
        assertThat(definition.usesKit()).isTrue();
        assertThat(kit).allSatisfy(k -> {
            assertThat(k.count()).isBetween(1, 64);
            assertThat(KitValidator.isValidSlot(k.slot())).isTrue();
        });
        assertThat(kit.stream().map(KitItem::slot).distinct()).hasSize(kit.size());
        assertThat(kit.stream().filter(k -> k.item().equals("blockland:pistol_ammo")).mapToInt(KitItem::count).sum()).isEqualTo(120);
    }

    private static Spawn spawn(int x, Role role) {
        return new Spawn(x, 1, 0, 0, role);
    }

    private GameMap map(String id, String type, List<Spawn> spawns) {
        return new GameMap(id, id, type, "nbt", id + ".nbt", "", 0, new GameMap.Size(10, 5, 10), null, List.of(), -5,
                InfectionMapSettings.toJson(mapper, spawns), null, Instant.now());
    }

    /** A map with only team spawns (one group per entry, holding that many points). */
    private static GameMap teamMap(String id, List<Integer> groupSizes) {
        List<GameMap.TeamSpawn> spawns = new ArrayList<>();
        for (int size : groupSizes) {
            List<GameMap.SpawnPoint> points = new ArrayList<>();
            for (int i = 0; i < size; i++) points.add(new GameMap.SpawnPoint(i, 1, spawns.size(), 0));
            spawns.add(new GameMap.TeamSpawn("组 " + (spawns.size() + 1), points));
        }
        return new GameMap(id, id, InfectionDefinition.ID, "nbt", id + ".nbt", "", 0, new GameMap.Size(10, 5, 10), null, spawns, -5, null, null, Instant.now());
    }

    private static GameMode mode(ObjectNode settings, Integer timeLimit, List<KitItem> kit) {
        return new GameMode("infection_test", "枪战感染", InfectionDefinition.ID, "", true, timeLimit, null, settings, kit, null, Instant.now());
    }
}
