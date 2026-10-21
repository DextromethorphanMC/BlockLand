package com.blockland.backend.gametype.skywars;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.gametype.skywars.loot.LootTable;
import com.blockland.backend.storage.ContentVersion;
import com.blockland.backend.storage.JsonFileRepository;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** SkyWars keeps its own data under {@code data/skywars/}. */
@Configuration
public class SkyWarsStorageConfig {

    @Bean
    @DependsOn("dataMigrations")
    public Repository<LootTable> skyWarsLootTables(ObjectMapper mapper, BlockLandProperties properties, ContentVersion version) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("skywars").resolve("loot-tables.json"),
                LootTable.class, LootTable::id, version);
    }
}
