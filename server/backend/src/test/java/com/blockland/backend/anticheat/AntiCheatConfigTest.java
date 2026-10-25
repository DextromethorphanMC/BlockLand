package com.blockland.backend.anticheat;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.ContentVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AntiCheatConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void theBuiltInConfigHasTheFourPresetsAndNoAutoBan() {
        AntiCheatConfig config = AntiCheatConfig.DEFAULT;
        assertThat(config.enabled()).isTrue();
        assertThat(config.defaultPreset()).isEqualTo("standard");
        assertThat(config.presets()).containsOnlyKeys("strict", "standard", "relaxed", "observe");
        assertThat(config.autoBan().enabled()).isFalse();

        AntiCheatConfig.Preset strict = config.presets().get("strict");
        assertThat(strict.toleranceScale()).isEqualTo(0.85);
        assertThat(strict.ping()).isEqualTo(new AntiCheatConfig.Ping(600, 2.0, 2, 300));
        assertThat(strict.confidence()).isEqualTo(new AntiCheatConfig.Confidence(0.5, 0.7, 0.97));
        assertThat(strict.actions()).isEqualTo(new AntiCheatConfig.Actions(true, true, true));
        assertThat(config.presets().get("relaxed").actions()).isEqualTo(new AntiCheatConfig.Actions(true, false, false));
        assertThat(config.presets().get("observe").actions()).isEqualTo(new AntiCheatConfig.Actions(false, false, false));
        assertThat(config.presets().get("standard").checks()).isEmpty();
    }

    @Test
    void missingValuesFallBackToTheBuiltInOnes() throws Exception {
        List<String> errors = new ArrayList<>();
        AntiCheatConfig config = AntiCheatConfig.normalize(json("""
                {"autoBan": {"enabled": true},
                 "presets": {"standard": {"toleranceScale": 1.2},
                             "ranked": {"name": "排位", "checks": {"movement.speed": {"tolerance": 0.8, "punish": false}}}}}
                """), errors);
        assertThat(errors).isEmpty();
        assertThat(config.autoBan().enabled()).isTrue();
        assertThat(config.autoBan().reason()).isEqualTo("反作弊系统自动封禁");
        assertThat(config.presets()).containsOnlyKeys("standard", "ranked");
        AntiCheatConfig.Preset standard = config.presets().get("standard");
        assertThat(standard.toleranceScale()).isEqualTo(1.2);
        assertThat(standard.ping().highPingMs()).isEqualTo(400);
        // a preset of its own takes the standard preset's values for what it leaves out
        AntiCheatConfig.Preset ranked = config.presets().get("ranked");
        assertThat(ranked.name()).isEqualTo("排位");
        assertThat(ranked.confidence()).isEqualTo(AntiCheatConfig.DEFAULT.presets().get("standard").confidence());
        assertThat(ranked.checks().path("movement.speed").path("tolerance").asDouble()).isEqualTo(0.8);
        assertThat(ranked.checks().path("movement.speed").path("punish").asBoolean(true)).isFalse();
    }

    @Test
    void badValuesAreReported() throws Exception {
        List<String> errors = new ArrayList<>();
        AntiCheatConfig.normalize(json("""
                {"defaultPreset": "nope",
                 "presets": {"Bad-Id": {}, "strict": {"toleranceScale": 9, "ping": {"highPingMs": 10},
                             "confidence": {"alert": 2}, "checks": {"movement.speed": {"tolerance": 0, "weight": 3}}}},
                 "evidence": {"maxPerCase": 0}, "suspicion": {"halfLifeDays": 0}}
                """), errors);
        assertThat(errors).anyMatch(e -> e.contains("Bad-Id"));
        assertThat(errors).anyMatch(e -> e.contains("不能删除标准预设"));
        assertThat(errors).anyMatch(e -> e.contains("默认预设不存在"));
        assertThat(errors).anyMatch(e -> e.contains("宽限倍率"));
        assertThat(errors).anyMatch(e -> e.contains("高延迟阈值"));
        assertThat(errors).anyMatch(e -> e.contains("提醒置信度"));
        assertThat(errors).anyMatch(e -> e.contains("movement.speed") && e.contains("权重"));
        assertThat(errors).anyMatch(e -> e.contains("证据条数"));
        assertThat(errors).anyMatch(e -> e.contains("半衰期"));
    }

    @Test
    void savingValidatesStoresAndBumpsTheContentVersion(@TempDir Path dir) throws Exception {
        BlockLandProperties properties = new BlockLandProperties(dir.toString(), "a", "s", null, false, List.of());
        ContentVersion version = new ContentVersion(dir.resolve("version.txt"));
        AntiCheatConfigStore store = new AntiCheatConfigStore(mapper, properties, version);
        assertThat(store.get()).isEqualTo(AntiCheatConfig.DEFAULT);

        long before = version.current();
        assertThatThrownBy(() -> store.save(json("{\"presets\": {\"strict\": {}}}"))).isInstanceOf(ApiException.class);
        assertThat(version.current()).isEqualTo(before);

        AntiCheatConfig saved = store.save(json("{\"enabled\": false, \"autoBan\": {\"enabled\": true, \"durationMinutes\": 1440}}"));
        assertThat(saved.enabled()).isFalse();
        assertThat(saved.presets()).containsOnlyKeys("strict", "standard", "relaxed", "observe");
        assertThat(version.current()).isEqualTo(before + 1);
        // read back after a restart
        AntiCheatConfigStore reloaded = new AntiCheatConfigStore(mapper, properties, version);
        assertThat(reloaded.get()).isEqualTo(saved);
        assertThat(reloaded.get().autoBan().durationMinutes()).isEqualTo(1440);

        // a broken file: the built-in config
        Files.writeString(dir.resolve("anticheat-config.json"), "{ not json");
        assertThat(new AntiCheatConfigStore(mapper, properties, version).get()).isEqualTo(AntiCheatConfig.DEFAULT);
    }
}
