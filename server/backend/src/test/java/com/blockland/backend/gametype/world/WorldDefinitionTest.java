package com.blockland.backend.gametype.world;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldDefinitionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorldDefinition definition = new WorldDefinition(mapper);
    private final GameTypeDefinition.Context noMaps = new GameTypeDefinition.Context(List.of());

    @Test
    void missingSettingsBecomeDefaults() {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(null, noMaps, errors);
        assertThat(errors).isEmpty();
        assertThat(out).isEqualTo(definition.defaultSettings());
        assertThat(out.get("dimension").asText()).isEqualTo("blockland:world");
        assertThat(out.get("gameMode").asText()).isEqualTo("SURVIVAL");
        assertThat(out.get("pvp").asBoolean()).isTrue();
        assertThat(out.get("keepInventory").asBoolean()).isFalse();
        assertThat(out.get("maxPlayers").asInt()).isEqualTo(50);
        assertThat(out.get("allowSlide").asBoolean()).isFalse();
    }

    @Test
    void validatesDimensionAndGameMode() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(mapper.readTree("""
                {"dimension": "Minecraft:Overworld", "gameMode": "creative", "maxPlayers": 999}"""), noMaps, errors);
        assertThat(out.get("dimension").asText()).isEqualTo("minecraft:overworld");
        assertThat(out.get("gameMode").asText()).isEqualTo("CREATIVE");
        assertThat(out.get("maxPlayers").asInt()).isEqualTo(200);
        assertThat(errors).containsExactly("最大人数 必须在 1 到 200 之间");

        errors.clear();
        out = definition.normalizeSettings(mapper.readTree("""
                {"dimension": "no spaces allowed", "gameMode": "SPECTATOR"}"""), noMaps, errors);
        assertThat(out.get("dimension").asText()).isEqualTo("blockland:world");
        assertThat(out.get("gameMode").asText()).isEqualTo("SURVIVAL");
        assertThat(errors).hasSize(2);
    }

    @Test
    void noMapWarnings() {
        GameMode mode = new GameMode("w", "w", WorldDefinition.ID, "", true, null, null, definition.defaultSettings(),
                definition.defaultKit(), null, Instant.now());
        assertThat(definition.warnings(mode, noMaps)).isEmpty();
    }
}
