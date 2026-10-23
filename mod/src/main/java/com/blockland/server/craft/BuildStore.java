package com.blockland.server.craft;

import com.blockland.craft.Build;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Where players' 工匠 builds are kept between sessions (the backend's player data in production). The craft service
 * only ever stores builds it has validated.
 */
public interface BuildStore {

    /** The player's saved builds, weapon id → build; empty when none are known (yet). */
    Map<String, Build> builds(UUID player);

    /** Saves (or with {@code null} forgets) the player's build of one weapon. */
    void save(UUID player, String weaponId, @Nullable Build build);
}
