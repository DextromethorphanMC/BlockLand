package com.blockland.server.media;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.MediaPackets;
import com.blockland.server.backend.BackendClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Relays images from the backend media store to game clients, caching each version once per server. */
public final class MediaRelay {
    private static final Set<String> NAMESPACES = Set.of(MediaPackets.SHOP, MediaPackets.MODE, MediaPackets.GROUP);
    private static final Pattern SAFE = Pattern.compile("^[a-z0-9_\\-]{1,64}$");

    private final MinecraftServer server;
    private final BackendClient backend;
    private final Map<String, CompletableFuture<byte[]>> cache = new ConcurrentHashMap<>();

    public MediaRelay(MinecraftServer server, BackendClient backend) {
        this.server = server;
        this.backend = backend;
    }

    public void send(ServerPlayer player, String namespace, String id, String version) {
        if (!NAMESPACES.contains(namespace) || !SAFE.matcher(id).matches() || !SAFE.matcher(version).matches()) return;
        String key = namespace + "/" + id + "@" + version;
        cache.computeIfAbsent(key, k -> backend.getBytes("/api/server/media/" + namespace + "/" + id))
                .whenComplete((png, error) -> server.execute(() -> {
                    if (error != null) {
                        cache.remove(key);
                        return;
                    }
                    BLNetwork.sendTo(player, new MediaPackets.S2CImage(namespace, id, version, png));
                }));
    }
}
