package com.blockland.backend.anticheat;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.JsonFileRepository;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Anti-cheat cases are runtime data: writing them does not change the game configuration version. */
@Configuration
public class AntiCheatStorageConfig {

    @Bean
    public Repository<AntiCheatCase> antiCheatCases(ObjectMapper mapper, BlockLandProperties properties) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("anticheat-cases.json"), AntiCheatCase.class,
                AntiCheatCase::id, null);
    }
}
