package com.blockland.backend.map;

import com.blockland.backend.gametype.GameTypeRegistry;
import com.blockland.backend.seed.DemoSeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The maps that ship with the backend ({@link ShippedMaps}): each one reads, holds up in the arena, and a fresh install —
 * every seed run on an empty data directory, as on first start — stores exactly the shipped file, metadata and cover.
 */
@SpringBootTest(properties = "blockland.seed-demo-data=true")
class ShippedMapsTest {
    @TempDir static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("blockland.data-dir", () -> data.toString());
    }

    @Autowired ShippedMaps shipped;
    @Autowired MapService maps;
    @Autowired GameTypeRegistry types;
    @Autowired MapFileAnalyzer analyzer;
    @Autowired List<DemoSeed> seeds;
    @Autowired ObjectMapper mapper;

    /** Every shipped map id, from the files on the test class path. */
    static List<String> ids() throws IOException, URISyntaxException {
        Path dir = Path.of(ShippedMaps.class.getResource(ShippedMaps.ROOT).toURI());
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length())).sorted().toList();
        }
    }

    @Test
    void everyShippedMapReadsAndHoldsUpInTheArena() throws Exception {
        List<String> ids = ids();
        assertThat(ids).hasSizeGreaterThanOrEqualTo(15);
        Map<String, List<String>> problems = new LinkedHashMap<>();
        for (String id : ids) {
            ShippedMaps.Shipped map = shipped.get(id);
            assertThat(types.find(map.meta().gameType())).as(id + " game type").isPresent();
            MapFileAnalyzer.Result result = analyzer.analyze(map.data(), map.format(), map.meta().spawnMarkerBlock());
            assertThat(result.size().x()).as(id + " size").isPositive();
            if (map.format().equals("nbt")) {
                List<String> found = StructureSupport.problems(map.data());
                if (!found.isEmpty()) problems.put(id, found.subList(0, Math.min(5, found.size())));
            }
        }
        assertThat(problems).as("shipped maps with blocks over the void").isEmpty();
        System.out.println("shipped maps: " + ids.size() + " read, analysed, nothing over the void");
    }

    @Test
    void aFreshInstallStoresTheShippedMaps() throws Exception {
        // the seeds ran on start-up (seed-demo-data); each map a seed installs must be the shipped one, byte for byte
        List<String> mismatches = new ArrayList<>();
        for (String id : ids()) {
            ShippedMaps.Shipped map = shipped.get(id);
            assertThat(maps.exists(id)).as(id + " installed by a seed").isTrue();
            GameMap stored = maps.get(id);
            if (!Arrays.equals(Files.readAllBytes(maps.file(id)), map.data())) mismatches.add(id + " file");
            if (!stored.name().equals(map.meta().name())) mismatches.add(id + " name");
            if (!stored.gameType().equals(map.meta().gameType())) mismatches.add(id + " game type");
            if (!stored.voidY().equals(map.meta().voidY())) mismatches.add(id + " void height");
            if (!stored.teamSpawns().equals(map.meta().teamSpawns())) mismatches.add(id + " spawns");
            if (!json(stored.settings()).equals(json(map.meta().settings()))) {
                mismatches.add(id + " settings " + json(stored.settings()) + " != " + json(map.meta().settings()));
            }
            if ((stored.coverVersion() != null) != (map.cover() != null)) mismatches.add(id + " cover");
        }
        assertThat(mismatches).isEmpty();
        // installing again changes nothing, and a refresh of an unchanged map is a no-op
        var before = maps.list();
        for (String id : ids()) {
            assertThat(shipped.install(id)).isFalse();
            assertThat(shipped.refresh(id)).isFalse();
        }
        assertThat(maps.list()).isEqualTo(before);
        System.out.println("fresh install: " + ids().size() + " shipped maps stored exactly; " + seeds.size() + " seeds");
    }

    private JsonNode json(JsonNode node) throws IOException {
        return node == null ? NullNode.getInstance() : mapper.readTree(mapper.writeValueAsString(node));
    }
}
