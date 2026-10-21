package com.blockland.server.kit;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Layouts are owned by the client (saved locally) and sent along when a player queues;
 * the server only keeps the validated layout in memory until the player leaves.
 */
public final class SessionKitLayouts implements KitLayoutStore {
    private final Map<UUID, Map<String, Layout>> layouts = new HashMap<>();

    @Override
    public Optional<Layout> load(UUID player, String modeId) {
        return Optional.ofNullable(layouts.getOrDefault(player, Map.of()).get(modeId));
    }

    @Override
    public void save(UUID player, String modeId, Layout layout) {
        layouts.computeIfAbsent(player, id -> new HashMap<>()).put(modeId, layout);
    }

    @Override
    public void remove(UUID player, String modeId) {
        Map<String, Layout> map = layouts.get(player);
        if (map != null) map.remove(modeId);
    }

    public void forget(UUID player) {
        layouts.remove(player);
    }
}
