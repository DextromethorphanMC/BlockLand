package com.blockland.backend.seed;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.gamemode.GameModeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Applies every {@link DemoSeed} that has not been applied before. */
@Component
public class SeedRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SeedRunner.class);
    /** Seed of v0.1, applied by the old seeder before seeds were tracked. */
    private static final String LEGACY_SKYWARS_SEED = "skywars-demo";

    private final BlockLandProperties properties;
    private final List<DemoSeed> seeds;
    private final GameModeService modes;
    private final ObjectMapper mapper;

    public SeedRunner(BlockLandProperties properties, List<DemoSeed> seeds, GameModeService modes, ObjectMapper mapper) {
        this.properties = properties;
        this.seeds = seeds;
        this.modes = modes;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.seedDemoData()) return;
        Path file = properties.dataPath().resolve("seeds.json");
        Set<String> applied = new LinkedHashSet<>();
        if (Files.isRegularFile(file)) {
            applied.addAll(mapper.readValue(file.toFile(), new TypeReference<List<String>>() { }));
        } else if (!modes.list().isEmpty()) {
            applied.add(LEGACY_SKYWARS_SEED);
        }
        for (DemoSeed seed : seeds) {
            if (applied.contains(seed.id())) continue;
            try {
                LOG.info("Applying demo seed {}", seed.id());
                seed.apply();
                applied.add(seed.id());
            } catch (RuntimeException e) {
                LOG.error("Demo seed {} failed", seed.id(), e);
            }
        }
        Files.createDirectories(file.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), applied);
    }
}
