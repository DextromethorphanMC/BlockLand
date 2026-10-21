package com.blockland.backend.modegroup;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.ContentVersion;
import com.blockland.backend.storage.JsonFileRepository;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** Groups are game configuration: changing them bumps the content version so servers re-sync. */
@Configuration
public class ModeGroupStorageConfig {

    @Bean
    @DependsOn("dataMigrations")
    public Repository<ModeGroup> modeGroups(ObjectMapper mapper, BlockLandProperties properties, ContentVersion version) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("mode-groups.json"), ModeGroup.class, ModeGroup::id, version);
    }
}
