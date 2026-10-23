package com.blockland.backend.registry;

import com.blockland.backend.config.BlockLandProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GameTypeRegistryDataTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsTheLastPublishedDataPerTypeAcrossRestarts(@TempDir Path dir) throws Exception {
        BlockLandProperties properties = new BlockLandProperties(dir.toString(), null, null, null, false, null);
        GameTypeRegistryData data = new GameTypeRegistryData(mapper, properties);
        assertThat(data.get("INFECTION")).isEmpty();

        data.replace("infection", (ObjectNode) mapper.readTree("{\"monsters\": [{\"id\": \"zombie\"}]}"));
        data.replace("INFECTION", (ObjectNode) mapper.readTree("{\"monsters\": [{\"id\": \"zombie\"}, {\"id\": \"slime_licker\"}]}"));
        data.replace("SKYWARS", (ObjectNode) mapper.readTree("{\"x\": 1}"));
        assertThat(data.get("INFECTION").path("monsters")).hasSize(2);

        // changing a returned copy does not change the stored data
        data.get("SKYWARS").put("x", 2);
        assertThat(data.get("SKYWARS").path("x").asInt()).isEqualTo(1);

        GameTypeRegistryData reloaded = new GameTypeRegistryData(mapper, properties);
        assertThat(reloaded.get("infection").path("monsters").get(1).path("id").asText()).isEqualTo("slime_licker");
        assertThat(reloaded.get("SKYWARS").path("x").asInt()).isEqualTo(1);
    }

    @Test
    void validatesTypeIds() {
        assertThat(GameTypeRegistryData.isValidTypeId("INFECTION")).isTrue();
        assertThat(GameTypeRegistryData.isValidTypeId("infection")).isTrue();
        assertThat(GameTypeRegistryData.isValidTypeId("../x")).isFalse();
        assertThat(GameTypeRegistryData.isValidTypeId("")).isFalse();
        assertThat(GameTypeRegistryData.isValidTypeId(null)).isFalse();
    }
}
