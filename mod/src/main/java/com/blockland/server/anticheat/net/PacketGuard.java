package com.blockland.server.anticheat.net;

import com.blockland.BlockLand;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rate limits for BlockLand's own client packets (the packet security layer, anti-cheat spec §3 {@code packet.rate}):
 * a token bucket per player and packet class, sized by {@code data/blockland/anticheat/packets.json}. A packet without a
 * token is dropped before its handler runs, so floods never reach the services. Server thread only (the network layer
 * calls it from its main-thread consumers).
 */
public final class PacketGuard {
    private static final String RESOURCE = "/data/blockland/anticheat/packets.json";
    private static final String PACKAGE = "com.blockland.network.packet.";

    /** Sustained packets per second and the burst. */
    private record Limit(double perSecond, double burst) {
    }

    private final Limit fallback;
    private final Map<String, Limit> limits;
    private final Map<Class<?>, Limit> byClass = new HashMap<>();
    /** player → packet class → [tokens, last refill nanos] */
    private final Map<UUID, Map<Class<?>, double[]>> buckets = new HashMap<>();

    public PacketGuard() {
        Map<String, Limit> loaded = new HashMap<>();
        Limit def = new Limit(20, 40);
        try (InputStream in = PacketGuard.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("default")) def = limit(root.getAsJsonObject("default"), def);
                    JsonObject packets = root.has("packets") ? root.getAsJsonObject("packets") : new JsonObject();
                    for (Map.Entry<String, JsonElement> e : packets.entrySet()) {
                        if (e.getValue().isJsonObject()) loaded.put(e.getKey(), limit(e.getValue().getAsJsonObject(), def));
                    }
                }
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the packet limits", e);
        }
        this.fallback = def;
        this.limits = loaded;
    }

    private static Limit limit(JsonObject o, Limit def) {
        double rate = o.has("perSecond") ? o.get("perSecond").getAsDouble() : def.perSecond();
        double burst = o.has("burst") ? o.get("burst").getAsDouble() : def.burst();
        return new Limit(Math.max(0.1, rate), Math.max(1, burst));
    }

    /**
     * Takes a token for one packet.
     *
     * @param tolerance the check's tolerance (scales rate and burst)
     * @return false when the player is over the limit (drop the packet)
     */
    public boolean admit(UUID player, Class<?> type, double tolerance, long now) {
        Limit limit = byClass.computeIfAbsent(type, this::limitOf);
        double rate = limit.perSecond() * tolerance;
        double burst = limit.burst() * tolerance;
        double[] bucket = buckets.computeIfAbsent(player, id -> new HashMap<>()).computeIfAbsent(type, t -> new double[]{burst, now});
        bucket[0] = Math.min(burst, bucket[0] + (now - bucket[1]) / 1e9 * rate);
        bucket[1] = now;
        if (bucket[0] < 1) return false;
        bucket[0] -= 1;
        return true;
    }

    /** {@code GunPackets.C2SFire} for {@code com.blockland.network.packet.GunPackets$C2SFire}. */
    public static String key(Class<?> type) {
        String name = type.getName();
        if (name.startsWith(PACKAGE)) name = name.substring(PACKAGE.length());
        return name.replace('$', '.');
    }

    private Limit limitOf(Class<?> type) {
        return limits.getOrDefault(key(type), fallback);
    }

    public void forget(UUID player) {
        buckets.remove(player);
    }

    public void clear() {
        buckets.clear();
    }
}
