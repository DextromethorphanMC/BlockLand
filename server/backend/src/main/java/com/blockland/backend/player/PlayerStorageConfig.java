package com.blockland.backend.player;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.JsonFileRepository;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Player data is runtime data: writing it does not change the game configuration version. */
@Configuration
public class PlayerStorageConfig {

    @Bean
    public Repository<PlayerAccount> playerAccounts(ObjectMapper mapper, BlockLandProperties properties) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("players.json"), PlayerAccount.class, PlayerAccount::uuid, null);
    }
}
