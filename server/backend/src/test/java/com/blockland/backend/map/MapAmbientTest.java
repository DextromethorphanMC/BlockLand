package com.blockland.backend.map;

import com.blockland.backend.gametype.bomb.BombDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapAmbientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsTheAmbienceAroundAGameTypesOwnSettings() throws Exception {
        var raw = mapper.readTree("""
                {"sites": [{"name": "A", "x1": 1, "y1": 1, "z1": 1, "x2": 4, "y2": 3, "z2": 4}],
                 "ambient": {"tumbleweeds": true, "unknown": 3}}""");
        List<String> errors = new ArrayList<>();
        // the bomb type rebuilds its settings from the sites alone ...
        ObjectNode own = new BombDefinition(mapper).normalizeMapSettings(raw, errors);
        assertThat(own.has("ambient")).isFalse();
        // ... and the map keeps its ambience next to them, cleaned
        ObjectNode settings = MapAmbient.attach(own, MapAmbient.normalize(raw, errors));
        assertThat(errors).isEmpty();
        assertThat(settings.get("sites")).hasSize(1);
        assertThat(settings.get("ambient")).isEqualTo(mapper.readTree("{\"tumbleweeds\": true}"));
    }

    @Test
    void offOrMissingLeavesNoAmbienceAndBadValuesAreErrors() throws Exception {
        List<String> errors = new ArrayList<>();
        assertThat(MapAmbient.normalize(mapper.readTree("{\"ambient\": {\"tumbleweeds\": false}}"), errors)).isNull();
        assertThat(MapAmbient.normalize(mapper.readTree("{}"), errors)).isNull();
        assertThat(MapAmbient.normalize(null, errors)).isNull();
        assertThat(errors).isEmpty();
        assertThat(MapAmbient.normalize(mapper.readTree("{\"ambient\": {\"tumbleweeds\": \"yes\"}}"), errors)).isNull();
        assertThat(MapAmbient.normalize(mapper.readTree("{\"ambient\": 3}"), errors)).isNull();
        assertThat(errors).hasSize(2);
        ObjectNode settings = mapper.createObjectNode();
        settings.putObject("ambient").put("tumbleweeds", true);
        assertThat(MapAmbient.attach(settings, null).has("ambient")).isFalse();
    }
}
