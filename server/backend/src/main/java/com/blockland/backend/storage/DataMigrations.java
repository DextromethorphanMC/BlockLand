package com.blockland.backend.storage;

import com.blockland.backend.config.BlockLandProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Upgrades data files written by older versions before any repository reads them
 * (repositories declare {@code @DependsOn("dataMigrations")}).
 */
@Component("dataMigrations")
public class DataMigrations {
    private static final Logger LOG = LoggerFactory.getLogger(DataMigrations.class);

    public DataMigrations(ObjectMapper mapper, BlockLandProperties properties) {
        Path dataDir = properties.dataPath();
        try {
            moveLootTablesIntoSkyWars(dataDir);
            moveSkyWarsMapFieldsIntoSettings(mapper, dataDir.resolve("maps.json"));
            moveShopImagesIntoMedia(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Data migration failed", e);
        }
    }

    /** v0.1: loot tables were generic ({@code data/loot-tables.json}); they belong to SkyWars now. */
    private static void moveLootTablesIntoSkyWars(Path dataDir) throws IOException {
        Path legacy = dataDir.resolve("loot-tables.json");
        Path target = dataDir.resolve("skywars").resolve("loot-tables.json");
        if (Files.isRegularFile(legacy) && !Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Migrated {} -> {}", legacy, target);
        }
    }

    /** v0.3: product images lived in {@code data/shop/images}; all images now share {@code data/media}. */
    private static void moveShopImagesIntoMedia(Path dataDir) throws IOException {
        Path legacy = dataDir.resolve("shop").resolve("images");
        if (!Files.isDirectory(legacy)) return;
        Path target = dataDir.resolve("media").resolve("shop");
        Files.createDirectories(target);
        try (var files = Files.list(legacy)) {
            for (Path file : files.toList()) {
                Files.move(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.deleteIfExists(legacy);
        LOG.info("Migrated product images {} -> {}", legacy, target);
    }

    /** v0.1: maps had top-level {@code chests}/{@code defaultLootTableId}; they are SkyWars map settings now. */
    private static void moveSkyWarsMapFieldsIntoSettings(ObjectMapper mapper, Path mapsFile) throws IOException {
        if (!Files.isRegularFile(mapsFile)) return;
        JsonNode root = mapper.readTree(mapsFile.toFile());
        if (!(root instanceof ArrayNode maps)) return;
        boolean changed = false;
        for (JsonNode node : maps) {
            if (!(node instanceof ObjectNode map) || map.has("settings")) continue;
            if (!map.has("chests") && !map.has("defaultLootTableId")) continue;
            ObjectNode settings = map.putObject("settings");
            settings.set("chests", map.has("chests") ? map.get("chests") : mapper.createArrayNode());
            settings.set("defaultLootTableId", map.get("defaultLootTableId"));
            map.remove("chests");
            map.remove("defaultLootTableId");
            changed = true;
        }
        if (changed) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(mapsFile.toFile(), maps);
            LOG.info("Migrated SkyWars chest settings in {}", mapsFile);
        }
    }
}
