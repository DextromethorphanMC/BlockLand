package com.blockland.backend.gamemode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Brings stored mode settings to their game type's current format on startup, so a changed settings contract needs no
 * hand-written migration: keys a type no longer knows disappear and new keys get their defaults. Modes that already
 * match are not written. Runs before the demo seeds.
 */
@Component
@Order(0)
public class GameModeSettingsUpgrade implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(GameModeSettingsUpgrade.class);

    private final GameModeService modes;

    public GameModeSettingsUpgrade(GameModeService modes) {
        this.modes = modes;
    }

    @Override
    public void run(ApplicationArguments args) {
        int changed = modes.revalidateAll();
        if (changed > 0) LOG.info("Updated the settings of {} game mode(s) to the current format", changed);
    }
}
