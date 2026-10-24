package com.blockland.server.profile;

import com.blockland.BlockLand;
import com.blockland.server.backend.BackendClient;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The backend copy of what players have put on ({@link Cosmetics}): loaded when a player joins, kept here while they
 * are online, changed through {@link #set} (the inventory checks ownership first) — the local copy at once, then the
 * backend. Fails open: without the backend everyone simply wears their defaults.
 */
public final class CosmeticsService implements Cosmetics.Store {
    private final MinecraftServer server;
    private final BackendClient backend;
    private final Map<UUID, Map<String, String>> slots = new ConcurrentHashMap<>();
    private Consumer<UUID> onChanged = id -> { };

    /** Backend JSON: {@code {"slots": {"card": "card:x"}}}. */
    private static final class Body {
        Map<String, String> slots;
    }

    private record Change(String slot, @Nullable String key) {
    }

    public CosmeticsService(MinecraftServer server, BackendClient backend) {
        this.server = server;
        this.backend = backend;
    }

    /** Called on the server thread when a player's slots arrived or changed (their tabs can be refreshed). */
    public void setChangeListener(Consumer<UUID> listener) {
        this.onChanged = listener;
    }

    /** The player connected: their slots are fetched. */
    public void onJoin(UUID player) {
        slots.putIfAbsent(player, new ConcurrentHashMap<>());
        reload(player);
    }

    private void reload(UUID player) {
        backend.<Body>get("/api/server/players/" + player + "/cosmetics", Body.class)
                .whenComplete((body, error) -> server.execute(() -> {
                    if (error != null) {
                        BlockLand.LOGGER.warn("[BlockLand] Cosmetics of {} not loaded: {}", player, error.getMessage());
                        return;
                    }
                    apply(player, body);
                }));
    }

    public void forget(UUID player) {
        slots.remove(player);
    }

    public void clear() {
        slots.clear();
    }

    @Nullable
    @Override
    public String get(UUID player, String slot) {
        Map<String, String> chosen = slots.get(player);
        return chosen == null ? null : chosen.get(slot);
    }

    @Override
    public Map<String, String> all(UUID player) {
        Map<String, String> chosen = slots.get(player);
        return chosen == null ? Map.of() : new LinkedHashMap<>(chosen);
    }

    /** Puts {@code key} into the slot (null = empties it); the caller has checked the player may. */
    public void set(UUID player, String slot, @Nullable String key) {
        Map<String, String> chosen = slots.computeIfAbsent(player, id -> new ConcurrentHashMap<>());
        if (key == null) chosen.remove(slot);
        else chosen.put(slot, key);
        onChanged.accept(player);
        backend.<Body>put("/api/server/players/" + player + "/cosmetics", new Change(slot, key), Body.class)
                .whenComplete((body, error) -> server.execute(() -> {
                    if (error != null) {
                        // refused (not owned by the backend's account) or unreachable: back to what the backend has
                        BlockLand.LOGGER.warn("[BlockLand] Could not save the {} slot of {}: {}", slot, player, error.getMessage());
                        if (slots.containsKey(player)) reload(player);
                        return;
                    }
                    if (body != null && body.slots != null) apply(player, body);
                }));
    }

    private void apply(UUID player, @Nullable Body body) {
        if (!slots.containsKey(player)) return;
        Map<String, String> chosen = new ConcurrentHashMap<>();
        if (body != null && body.slots != null) {
            body.slots.forEach((slot, key) -> {
                if (slot != null && key != null && !key.isEmpty()) chosen.put(slot, key);
            });
        }
        slots.put(player, chosen);
        onChanged.accept(player);
    }
}
