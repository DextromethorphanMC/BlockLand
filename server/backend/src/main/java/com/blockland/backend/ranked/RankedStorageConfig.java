package com.blockland.backend.ranked;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.JsonFileRepository;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ratings (one per player and mode) and counted matches are runtime data: writing them does not change the game
 * configuration version.
 */
@Configuration
public class RankedStorageConfig {

    @Bean
    public Repository<Rating> rankedRatings(ObjectMapper mapper, BlockLandProperties properties) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("ranked-ratings.json"), Rating.class, Rating::key, null);
    }

    @Bean
    public Repository<RankedMatch> rankedMatches(ObjectMapper mapper, BlockLandProperties properties) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("ranked-matches.json"), RankedMatch.class, RankedMatch::matchId, null);
    }
}
