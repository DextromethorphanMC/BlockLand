package com.blockland.backend.announcement;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.ContentVersion;
import com.blockland.backend.storage.JsonFileRepository;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** Announcements are game content: changing them bumps the content version so servers re-sync. */
@Configuration
public class AnnouncementStorageConfig {

    @Bean
    @DependsOn("dataMigrations")
    public Repository<Announcement> announcements(ObjectMapper mapper, BlockLandProperties properties, ContentVersion version) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("announcements.json"), Announcement.class, Announcement::id, version);
    }
}
