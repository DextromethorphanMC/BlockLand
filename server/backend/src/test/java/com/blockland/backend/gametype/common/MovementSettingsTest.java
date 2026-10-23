package com.blockland.backend.gametype.common;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.fastpvp.FastPvpDefinition;
import com.blockland.backend.gametype.infection.InfectionDefinition;
import com.blockland.backend.gametype.skywars.SkyWarsDefinition;
import com.blockland.backend.registry.GameTypeRegistryData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code allowSlide}: every type carries it with its own default, and a mode's choice survives normalisation. */
class MovementSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GameTypeDefinition.Context noMaps = new GameTypeDefinition.Context(List.of());

    @Test
    void everyTypeHasItsDefault(@TempDir Path dir) {
        assertThat(new FastPvpDefinition(mapper).defaultSettings().get("allowSlide").asBoolean()).isTrue();
        assertThat(infection(dir).defaultSettings().get("allowSlide").asBoolean()).isTrue();
        assertThat(new SkyWarsDefinition(mapper, null).defaultSettings().get("allowSlide").asBoolean()).isFalse();
    }

    @Test
    void missingValuesGetTheDefaultAndChoicesAreKept(@TempDir Path dir) throws Exception {
        List<GameTypeDefinition> types = List.of(new FastPvpDefinition(mapper), infection(dir), new SkyWarsDefinition(mapper, null));
        for (GameTypeDefinition type : types) {
            boolean fallback = type.defaultSettings().get("allowSlide").asBoolean();
            List<String> errors = new ArrayList<>();
            ObjectNode missing = type.normalizeSettings(mapper.readTree("{}"), noMaps, errors);
            assertThat(missing.get("allowSlide").asBoolean()).as(type.id()).isEqualTo(fallback);
            ObjectNode on = type.normalizeSettings(mapper.readTree("{\"allowSlide\": true}"), noMaps, errors);
            ObjectNode off = type.normalizeSettings(mapper.readTree("{\"allowSlide\": false}"), noMaps, errors);
            assertThat(on.get("allowSlide").asBoolean()).as(type.id()).isTrue();
            assertThat(off.get("allowSlide").asBoolean()).as(type.id()).isFalse();
            assertThat(errors).as(type.id()).isEmpty();
        }
    }

    private InfectionDefinition infection(Path dir) {
        return new InfectionDefinition(mapper, new GameTypeRegistryData(mapper, new BlockLandProperties(dir.toString(), null, null, null, false, null)));
    }
}
