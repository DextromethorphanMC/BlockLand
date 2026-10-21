package com.blockland.backend.gametype;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class GameTypeRegistry {
    private final Map<String, GameTypeDefinition> types = new LinkedHashMap<>();

    public GameTypeRegistry(List<GameTypeDefinition> definitions) {
        for (GameTypeDefinition definition : definitions) {
            types.put(definition.id().toUpperCase(Locale.ROOT), definition);
        }
    }

    public Optional<GameTypeDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(types.get(id.toUpperCase(Locale.ROOT)));
    }

    public Collection<GameTypeDefinition> all() {
        return types.values();
    }
}
