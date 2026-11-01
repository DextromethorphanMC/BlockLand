package com.blockland.backend.checkin;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.JsonFileRepository;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Check-ins are runtime data: writing them does not change the game configuration version. */
@Configuration
public class CheckInStorageConfig {

    @Bean
    public Repository<CheckInRecord> checkInRecords(ObjectMapper mapper, BlockLandProperties properties) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("checkin-records.json"), CheckInRecord.class, CheckInRecord::uuid, null);
    }
}
