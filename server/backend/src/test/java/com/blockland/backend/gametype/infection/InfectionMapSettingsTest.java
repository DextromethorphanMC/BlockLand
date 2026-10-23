package com.blockland.backend.gametype.infection;

import com.blockland.backend.gametype.infection.InfectionMapSettings.Role;
import com.blockland.backend.gametype.infection.InfectionMapSettings.Spawn;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InfectionMapSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesValidSpawns() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = InfectionMapSettings.normalize(mapper, mapper.readTree("""
                {"spawns": [{"x": 1, "y": 2, "z": 3, "yaw": 90, "role": "human"},
                            {"x": 4, "y": 2, "z": 3, "role": "MONSTER"},
                            {"x": 5, "y": 2, "z": 3, "yaw": -45}],
                 "junk": true}"""), errors);
        assertThat(errors).isEmpty();
        assertThat(InfectionMapSettings.read(out)).containsExactly(
                new Spawn(1, 2, 3, 90, Role.HUMAN), new Spawn(4, 2, 3, 0, Role.MONSTER), new Spawn(5, 2, 3, -45, Role.BOTH));
        assertThat(out.has("junk")).isFalse();
        assertThat(InfectionMapSettings.normalize(mapper, null, errors).get("spawns")).isEmpty();
    }

    @Test
    void reportsBrokenSpawns() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = InfectionMapSettings.normalize(mapper, mapper.readTree("""
                {"spawns": [{"x": 1, "y": 2, "z": 3, "role": "zombie"},
                            {"x": "a", "y": 2, "z": 3},
                            {"x": 1, "y": 2, "z": 3, "yaw": "north"},
                            {"x": 1.5, "y": 2, "z": 3}]}"""), errors);
        assertThat(errors).containsExactly(
                "出生点 1: 角色必须是 HUMAN（人类）、MONSTER（怪物）或 BOTH（通用）",
                "出生点 2: 坐标必须是数字",
                "出生点 3: 朝向必须是数字",
                "出生点坐标重复: (1.5, 2, 3)");
        assertThat(InfectionMapSettings.read(out)).hasSize(2);

        errors.clear();
        InfectionMapSettings.normalize(mapper, mapper.readTree("{\"spawns\": {}}"), errors);
        assertThat(errors).containsExactly("出生点列表必须是数组");
    }

    @Test
    void markersOfAFileBecomeBothPointsAndKeepPreviousRoles() {
        ObjectNode previous = InfectionMapSettings.toJson(mapper, List.of(new Spawn(10, 5, 10, 45, Role.MONSTER), new Spawn(2, 5, 2, 0, Role.HUMAN)));
        // centre (10.5, 10.5): the new marker at block (18, 10) faces west (yaw 90)
        MapFileAnalyzer.Result analysis = new MapFileAnalyzer.Result("nbt", new GameMap.Size(21, 10, 21), List.of(),
                List.of(new MapFileAnalyzer.Block(10, 5, 10), new MapFileAnalyzer.Block(18, 5, 10)), null);
        List<Spawn> spawns = InfectionMapSettings.read(InfectionMapSettings.fromFile(mapper, analysis, previous));
        assertThat(spawns).containsExactly(new Spawn(10, 5, 10, 45, Role.MONSTER), new Spawn(18, 5, 10, 90, Role.BOTH));

        MapFileAnalyzer.Result noMarkers = new MapFileAnalyzer.Result("nbt", new GameMap.Size(20, 10, 20), List.of(), List.of(), null);
        assertThat(InfectionMapSettings.read(InfectionMapSettings.fromFile(mapper, noMarkers, previous))).hasSize(2);
        assertThat(InfectionMapSettings.read(InfectionMapSettings.fromFile(mapper, noMarkers, null))).isEmpty();
    }

    @Test
    void ownSpawnsWinOverTeamSpawns() {
        List<GameMap.TeamSpawn> teams = List.of(
                new GameMap.TeamSpawn("A", List.of(new GameMap.SpawnPoint(1, 1, 1, 0))),
                new GameMap.TeamSpawn("B", List.of(new GameMap.SpawnPoint(2, 1, 2, 0))),
                new GameMap.TeamSpawn("C", List.of(new GameMap.SpawnPoint(3, 1, 3, 0))));
        GameMap fallback = map(teams, null);
        assertThat(InfectionMapSettings.effectiveSpawns(fallback)).extracting(Spawn::role).containsExactly(Role.HUMAN, Role.MONSTER);

        GameMap single = map(teams.subList(0, 1), null);
        assertThat(InfectionMapSettings.effectiveSpawns(single)).extracting(Spawn::role).containsExactly(Role.BOTH);

        GameMap own = map(teams, InfectionMapSettings.toJson(mapper, List.of(new Spawn(5, 1, 5, 0, Role.BOTH))));
        assertThat(InfectionMapSettings.effectiveSpawns(own)).containsExactly(new Spawn(5, 1, 5, 0, Role.BOTH));
    }

    private static GameMap map(List<GameMap.TeamSpawn> teams, ObjectNode settings) {
        return new GameMap("m", "m", InfectionDefinition.ID, "nbt", "m.nbt", "", 0, new GameMap.Size(10, 5, 10), null, teams, -5, settings, null, Instant.now());
    }
}
