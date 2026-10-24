package com.blockland.backend.gametype.bomb;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BombDefinitionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BombDefinition definition = new BombDefinition(mapper);
    private final GameTypeDefinition.Context noMaps = new GameTypeDefinition.Context(List.of());

    @Test
    void missingSettingsBecomeDefaults() {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(null, noMaps, errors);
        assertThat(errors).isEmpty();
        assertThat(out).isEqualTo(definition.defaultSettings());
        assertThat(out.get("teamSize").asInt()).isEqualTo(5);
        assertThat(out.get("teamCount").asInt()).isEqualTo(2);
        assertThat(out.get("plantSeconds").asDouble()).isEqualTo(3.2);
        assertThat(out.get("defuseSeconds").asDouble()).isEqualTo(7);
        assertThat(out.get("fuseSeconds").asInt()).isEqualTo(40);
        assertThat(out.get("allowSlide").asBoolean()).isTrue();
        assertThat(out.get("allowBlockBreak").asBoolean()).isFalse();
        // kits allow 1-64 per entry, and hotbar slot 4 stays free for the bomb
        assertThat(definition.defaultKit()).allSatisfy(k -> assertThat(k.count()).isBetween(1, 64));
        assertThat(definition.defaultKit()).noneMatch(k -> "4".equals(k.slot()));
    }

    @Test
    void forcesTwoTeamsAndClampsTimings() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(mapper.readTree("""
                {"teamSize": 3, "teamCount": 4, "plantSeconds": 0.2, "defuseSeconds": "x", "fuseSeconds": 500}"""), noMaps, errors);
        assertThat(out.get("teamSize").asInt()).isEqualTo(3);
        assertThat(out.get("teamCount").asInt()).isEqualTo(2);
        assertThat(out.get("plantSeconds").asDouble()).isEqualTo(1);
        assertThat(out.get("defuseSeconds").asDouble()).isEqualTo(7);
        assertThat(out.get("fuseSeconds").asInt()).isEqualTo(120);
        assertThat(errors).contains("枪战爆破固定为 2 支队伍（进攻方 / 防守方）", "安放时间 必须在 1 到 15 之间", "拆除时间 必须是数字",
                "爆炸倒计时 必须在 10 到 120 之间");
    }

    @Test
    void warnsAboutMapsWithoutSites() {
        List<GameMap.TeamSpawn> teams = List.of(
                new GameMap.TeamSpawn("进攻方", List.of(new GameMap.SpawnPoint(1, 1, 1, 0))),
                new GameMap.TeamSpawn("防守方", List.of(new GameMap.SpawnPoint(8, 1, 8, 180))));
        GameMap noSites = map("a", "无包点", teams, null);
        GameMap outside = map("b", "越界", teams, BombMapSettings.toJson(mapper, List.of(new BombMapSettings.Site("A", 5, 1, 5, 12, 2, 6))));
        GameMap good = map("c", "正常", teams, BombMapSettings.toJson(mapper, List.of(new BombMapSettings.Site("A", 2, 1, 2, 4, 2, 4))));
        GameTypeDefinition.Context context = new GameTypeDefinition.Context(List.of(noSites, outside, good));
        GameMode mode = new GameMode("m", "m", BombDefinition.ID, "", true, 120, null, definition.defaultSettings(),
                definition.defaultKit(), null, Instant.now());
        List<String> warnings = definition.warnings(mode, context);
        assertThat(warnings).contains("地图「无包点」没有设置包点，无法用于枪战爆破", "地图「越界」的包点 A 超出了地图范围");
        assertThat(warnings).noneMatch(w -> w.contains("正常") && w.contains("包点"));

        GameMode untimed = new GameMode("m", "m", BombDefinition.ID, "", true, null, null, definition.defaultSettings(),
                definition.defaultKit(), null, Instant.now());
        assertThat(definition.warnings(untimed, context)).contains("未设置存活时间：进攻方安放炸弹没有时间限制");
    }

    @Test
    void demoArenaHasTwoSidesAndTwoSitesInsideTheMap() {
        BombDemoArena.Result arena = BombDemoArena.generate();
        MapFileAnalyzer.Result result = new MapFileAnalyzer().analyze(arena.data(), "nbt", null);
        GameMap.Size size = new GameMap.Size(BombDemoArena.SIZE_X, BombDemoArena.SIZE_Y, BombDemoArena.SIZE_Z);
        assertThat(result.size()).isEqualTo(size);
        assertThat(arena.spawns()).hasSize(2).allSatisfy(t -> assertThat(t.points()).hasSize(5));
        assertThat(arena.sites()).extracting(BombMapSettings.Site::name).containsExactly("A", "B");
        assertThat(arena.sites()).allSatisfy(s -> assertThat(s.leaves(size)).isFalse());
        List<String> errors = new ArrayList<>();
        assertThat(BombMapSettings.read(BombMapSettings.normalize(mapper, BombMapSettings.toJson(mapper, arena.sites()), errors)))
                .isEqualTo(arena.sites());
        assertThat(errors).isEmpty();
        // spawns stand on the floor under open sky
        MapFileAnalyzer.TopView top = result.topView();
        arena.spawns().forEach(t -> t.points().forEach(p ->
                assertThat(top.heights()[(int) p.x() + (int) p.z() * top.width()]).isEqualTo((int) p.y() - 1)));
    }

    private static GameMap map(String id, String name, List<GameMap.TeamSpawn> teams, ObjectNode settings) {
        return new GameMap(id, name, BombDefinition.ID, "nbt", id + ".nbt", "", 0, new GameMap.Size(10, 5, 10), null, teams, -5,
                settings, null, Instant.now());
    }
}
