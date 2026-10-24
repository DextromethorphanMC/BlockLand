package com.blockland.backend.gamemode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenericModeSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rankedSurvivesAnyTypeNormaliserAndDefaultsToOff() throws Exception {
        // what a type's normaliser returns knows nothing about ranked
        ObjectNode typeSettings = (ObjectNode) mapper.readTree("{\"teamSize\": 1}");
        ObjectNode raw = (ObjectNode) mapper.readTree("{\"teamSize\": 1, \"ranked\": true}");

        assertThat(GenericModeSettings.apply(raw, typeSettings.deepCopy()).get("ranked").asBoolean()).isTrue();
        assertThat(GenericModeSettings.apply(mapper.readTree("{}"), typeSettings.deepCopy()).get("ranked").asBoolean()).isFalse();
        assertThat(GenericModeSettings.ranked(GenericModeSettings.apply(raw, typeSettings.deepCopy()))).isTrue();
    }

    @Test
    void graffitiAndSpectatorRulesDefaultOnAndClampTheTimeLimit() throws Exception {
        ObjectNode typeSettings = (ObjectNode) mapper.readTree("{\"teamSize\": 1}");
        ObjectNode defaults = GenericModeSettings.apply(mapper.readTree("{}"), typeSettings.deepCopy());
        assertThat(defaults.get("allowGraffiti").asBoolean()).isTrue();
        assertThat(defaults.get("spectate").asBoolean()).isTrue();
        assertThat(defaults.get("spectateFreeCam").asBoolean()).isTrue();
        assertThat(defaults.get("spectateSeconds").asInt()).isZero();

        ObjectNode raw = (ObjectNode) mapper.readTree("{\"spectate\": false, \"spectateFreeCam\": false, \"spectateSeconds\": 9999}");
        ObjectNode applied = GenericModeSettings.apply(raw, typeSettings.deepCopy());
        assertThat(applied.get("spectate").asBoolean()).isFalse();
        assertThat(applied.get("spectateFreeCam").asBoolean()).isFalse();
        assertThat(applied.get("spectateSeconds").asInt()).isEqualTo(600);
    }
}
