package com.blockland.server.craft;

import com.blockland.craft.Build;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Builds kept in memory only (development without a backend; lost on restart). */
public final class MemoryBuildStore implements BuildStore {
    private final Map<UUID, Map<String, Build>> builds = new ConcurrentHashMap<>();

    @Override
    public Map<String, Build> builds(UUID player) {
        Map<String, Build> saved = builds.get(player);
        return saved == null ? Map.of() : Map.copyOf(saved);
    }

    @Override
    public void save(UUID player, String weaponId, @Nullable Build build) {
        Map<String, Build> saved = builds.computeIfAbsent(player, id -> new LinkedHashMap<>());
        synchronized (saved) {
            if (build == null) saved.remove(weaponId);
            else saved.put(weaponId, build);
        }
    }
}
