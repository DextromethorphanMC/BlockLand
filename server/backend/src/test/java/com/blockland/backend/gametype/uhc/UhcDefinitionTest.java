package com.blockland.backend.gametype.uhc;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.seed.CoverArt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UhcDefinitionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UhcDefinition definition = new UhcDefinition(mapper);
    private final GameTypeDefinition.Context noMaps = new GameTypeDefinition.Context(List.of());

    @Test
    void missingSettingsBecomeDefaults() {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(null, noMaps, errors);
        assertThat(errors).isEmpty();
        assertThat(out).isEqualTo(definition.defaultSettings());
        assertThat(out.get("players").asInt()).isEqualTo(16);
        assertThat(out.get("borderRadius").asInt()).isEqualTo(500);
        assertThat(out.get("normalPhaseSeconds").asInt()).isEqualTo(1200);
        assertThat(out.get("finalRadius").asInt()).isEqualTo(150);
        assertThat(out.get("finalEndRadius").asInt()).isEqualTo(15);
        assertThat(out.get("naturalRegeneration").asBoolean()).isFalse();
        assertThat(out.get("allowSlide").asBoolean()).isFalse();
    }

    @Test
    void clampsRangesAndKeepsTheCirclesNested() throws Exception {
        List<String> errors = new ArrayList<>();
        ObjectNode out = definition.normalizeSettings(mapper.readTree("""
                {"players": 500, "borderRadius": 200, "finalRadius": 400, "finalEndRadius": 300, "borderDamage": -1}"""), noMaps, errors);
        assertThat(out.get("players").asInt()).isEqualTo(100);
        assertThat(out.get("borderRadius").asInt()).isEqualTo(200);
        assertThat(out.get("finalRadius").asInt()).isEqualTo(200);
        assertThat(out.get("finalEndRadius").asInt()).isEqualTo(200);
        assertThat(out.get("borderDamage").asDouble()).isEqualTo(0);
        assertThat(errors).hasSize(4);
    }

    @Test
    void warnsWhenSpawnsCannotKeepTheirDistance() {
        ObjectNode settings = definition.defaultSettings();
        assertThat(definition.warnings(mode(settings), noMaps)).isEmpty();
        settings.put("borderRadius", 100);
        settings.put("spawnSpacing", 500);
        assertThat(definition.warnings(mode(settings), noMaps)).hasSize(1);
    }

    @Test
    void coverArtRenders() {
        assertThat(CoverArt.uhc(false, 1)).isNotEmpty();
        assertThat(CoverArt.uhc(true, 2)).isNotEmpty();
    }

    private GameMode mode(ObjectNode settings) {
        return new GameMode("u", "u", UhcDefinition.ID, "", true, null, null, settings, List.of(), null, Instant.now());
    }
}
