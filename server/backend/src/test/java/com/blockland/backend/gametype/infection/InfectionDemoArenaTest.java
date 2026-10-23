package com.blockland.backend.gametype.infection;

import com.blockland.backend.gametype.infection.InfectionMapSettings.Role;
import com.blockland.backend.gametype.infection.InfectionMapSettings.Spawn;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InfectionDemoArenaTest {

    @Test
    void generatesArenaWithHumanAndMonsterSpawnsOnOppositeSides() {
        InfectionDemoArena.Result arena = InfectionDemoArena.generate();
        MapFileAnalyzer.Result result = new MapFileAnalyzer().analyze(arena.data(), "nbt", null);

        assertThat(result.size()).isEqualTo(new GameMap.Size(InfectionDemoArena.SIZE_X, InfectionDemoArena.SIZE_Y, InfectionDemoArena.SIZE_Z));
        assertThat(result.containers()).isEmpty();

        List<Spawn> humans = arena.roleSpawns().stream().filter(s -> s.role() == Role.HUMAN).toList();
        List<Spawn> monsters = arena.roleSpawns().stream().filter(s -> s.role() == Role.MONSTER).toList();
        List<Spawn> both = arena.roleSpawns().stream().filter(s -> s.role() == Role.BOTH).toList();
        assertThat(humans).hasSizeGreaterThanOrEqualTo(8);
        assertThat(monsters).hasSizeGreaterThanOrEqualTo(4);
        assertThat(both).hasSize(2);
        assertThat(humans).allSatisfy(p -> assertThat(p.z()).isLessThan(InfectionDemoArena.SIZE_Z / 4.0));
        assertThat(monsters).allSatisfy(p -> assertThat(p.z()).isGreaterThan(InfectionDemoArena.SIZE_Z * 3 / 4.0));
        assertThat(both).allSatisfy(p -> assertThat(p.z()).isBetween(InfectionDemoArena.SIZE_Z / 4.0, InfectionDemoArena.SIZE_Z * 3 / 4.0));

        // the team spawn fallback holds the same camp / infected zone points
        assertThat(arena.spawns()).hasSize(2);
        assertThat(arena.spawns().get(0).points()).hasSize(humans.size());
        assertThat(arena.spawns().get(1).points()).hasSize(monsters.size());

        // every spawn stands on the floor under open sky (nothing above the floor block in that column)
        MapFileAnalyzer.TopView top = result.topView();
        for (Spawn p : arena.roleSpawns()) assertThat(top.heights()[(int) p.x() + (int) p.z() * top.width()]).isEqualTo((int) p.y() - 1);
    }
}
