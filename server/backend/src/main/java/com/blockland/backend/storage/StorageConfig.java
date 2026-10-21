package com.blockland.backend.storage;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.map.GameMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** Wires the JSON file repositories under {@code blockland.data-dir}. */
@Configuration
public class StorageConfig {

    @Bean
    public ContentVersion contentVersion(BlockLandProperties properties) {
        return new ContentVersion(properties.dataPath().resolve("version.txt"));
    }

    @Bean
    @DependsOn("dataMigrations")
    public Repository<GameMode> gameModeRepository(ObjectMapper mapper, BlockLandProperties properties, ContentVersion version) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("game-modes.json"), GameMode.class, GameMode::id, version);
    }

    @Bean
    @DependsOn("dataMigrations")
    public Repository<GameMap> mapRepository(ObjectMapper mapper, BlockLandProperties properties, ContentVersion version) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("maps.json"), GameMap.class, GameMap::id, version);
    }
}
