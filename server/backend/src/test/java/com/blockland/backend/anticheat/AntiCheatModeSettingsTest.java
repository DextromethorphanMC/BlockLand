package com.blockland.backend.anticheat;

import com.blockland.backend.gamemode.GenericModeSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AntiCheatModeSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode antiCheat(String raw, String typePreset) throws Exception {
        ObjectNode typeSettings = (ObjectNode) mapper.readTree("{\"teamSize\": 1}");
        return GenericModeSettings.apply(mapper.readTree(raw), typeSettings, typePreset).get("antiCheat");
    }

    @Test
    void everyModeGetsTheTypesDefaultPreset() throws Exception {
        JsonNode defaults = antiCheat("{}", "strict");
        assertThat(defaults.get("enabled").asBoolean()).isTrue();
        assertThat(defaults.get("preset").asText()).isEqualTo("strict");
        assertThat(defaults.get("toleranceScale").isNull()).isTrue();
        assertThat(defaults.get("checks").isObject()).isTrue();
        assertThat(defaults.get("checks")).isEmpty();
        // the two-argument form (types that never chose) is the standard preset
        ObjectNode typeSettings = (ObjectNode) mapper.readTree("{}");
        assertThat(GenericModeSettings.apply(mapper.readTree("{}"), typeSettings).path("antiCheat").path("preset").asText())
                .isEqualTo("standard");
    }

    @Test
    void validChoicesAreKeptAndBrokenOnesDropped() throws Exception {
        JsonNode settings = antiCheat("""
                {"antiCheat": {"enabled": false, "preset": "observe", "toleranceScale": 1.3,
                  "checks": {"movement.speed": {"enabled": false, "tolerance": 1.5, "weight": 7, "colour": "red"},
                             "movement.velocity": {"kickVl": -1},
                             "Bad Id": {"enabled": true},
                             "combat.reach": {"setbackVl": 2, "punish": "yes"}}}}
                """, "standard");
        assertThat(settings.get("enabled").asBoolean()).isFalse();
        assertThat(settings.get("preset").asText()).isEqualTo("observe");
        assertThat(settings.get("toleranceScale").asDouble()).isEqualTo(1.3);
        JsonNode checks = settings.get("checks");
        assertThat(checks.has("Bad Id")).isFalse();
        // nothing valid left in it: the override disappears
        assertThat(checks.has("movement.velocity")).isFalse();
        assertThat(checks.get("movement.speed").get("enabled").asBoolean()).isFalse();
        assertThat(checks.get("movement.speed").get("tolerance").asDouble()).isEqualTo(1.5);
        assertThat(checks.get("movement.speed").has("weight")).isFalse();
        assertThat(checks.get("movement.speed").has("colour")).isFalse();
        assertThat(checks.get("combat.reach").get("setbackVl").asDouble()).isEqualTo(2);
        assertThat(checks.get("combat.reach").has("punish")).isFalse();
    }

    @Test
    void aMalformedPresetOrScaleFallsBack() throws Exception {
        JsonNode settings = antiCheat("{\"antiCheat\": {\"preset\": \"Not Valid\", \"toleranceScale\": 50, \"enabled\": \"no\"}}", "relaxed");
        assertThat(settings.get("preset").asText()).isEqualTo("relaxed");
        assertThat(settings.get("toleranceScale").isNull()).isTrue();
        assertThat(settings.get("enabled").asBoolean()).isTrue();
        // and a mode saved without it keeps a normalised object after an upgrade
        assertThat(antiCheat("{\"antiCheat\": 5}", "standard").get("preset").asText()).isEqualTo("standard");
    }
}
