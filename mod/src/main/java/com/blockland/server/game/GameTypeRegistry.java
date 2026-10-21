package com.blockland.server.game;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** All known game types. New minigames register here and need no changes elsewhere. */
public final class GameTypeRegistry {
    private final Map<String, GameType> types = new LinkedHashMap<>();

    public GameTypeRegistry register(GameType type) {
        types.put(type.id().toUpperCase(Locale.ROOT), type);
        return this;
    }

    public Optional<GameType> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(types.get(id.toUpperCase(Locale.ROOT)));
    }

    public Collection<GameType> all() {
        return Collections.unmodifiableCollection(types.values());
    }
}
