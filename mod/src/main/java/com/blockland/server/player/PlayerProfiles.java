package com.blockland.server.player;

import com.blockland.BlockLand;
import com.blockland.server.backend.BackendClient;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Network-wide player data mirrored from the backend: online time (reported in heartbeats),
 * bans, mutes and entitlements. Fails open: if the backend is unreachable players can still play.
 */
public final class PlayerProfiles {
    private static final int HEARTBEAT_TICKS = 15 * 20;
    private static final Type STATUS_LIST = new TypeToken<List<Status>>() { }.getType();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final MinecraftServer server;
    private final BackendClient backend;
    private Runnable onChanged = () -> { };
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private int tickCounter;

    // ---- backend JSON (mirrors PlayerService.PlayerStatus) ------------------------------------

    static final class Status {
        String uuid;
        String name;
        long totalOnlineSeconds;
        Restriction ban;
        Restriction mute;
        List<Entitlement> entitlements = new ArrayList<>();
    }

    static final class Restriction {
        String reason;
        String until;
    }

    static final class Entitlement {
        String key;
        String until;
    }

    private record Report(String uuid, String name, long seconds) {
    }

    private record Login(String uuid, String name) {
    }

    private static final class Profile {
        volatile Status status;
        /** Last time this player's online time was reported to the backend. */
        volatile long reportedAt = System.currentTimeMillis();
    }

    public PlayerProfiles(MinecraftServer server, BackendClient backend) {
        this.server = server;
        this.backend = backend;
    }

    /** Called on the server thread whenever a player's status (playtime, entitlements, ...) was updated. */
    public void setChangeListener(Runnable listener) {
        this.onChanged = listener;
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    public void onLogin(ServerPlayer player) {
        Profile profile = new Profile();
        profiles.put(player.getUUID(), profile);
        backend.<Status>post("/api/server/players/login", new Login(player.getUUID().toString(), player.getGameProfile().getName()), Status.class)
                .whenComplete((status, error) -> server.execute(() -> {
                    if (error != null) {
                        BlockLand.LOGGER.warn("[BlockLand] Player login sync failed for {}: {}", player.getGameProfile().getName(), error.getMessage());
                        return;
                    }
                    apply(player.getUUID(), status);
                }));
    }

    public void onLogout(ServerPlayer player) {
        Profile profile = profiles.remove(player.getUUID());
        if (profile == null) return;
        backend.post("/api/server/players/logout", report(player, profile, System.currentTimeMillis()), Void.class)
                .exceptionally(error -> null);
    }

    public void tick() {
        if (++tickCounter % HEARTBEAT_TICKS != 0) return;
        heartbeat(server.getPlayerList().getPlayers());
    }

    /** Re-reads a player's status (e.g. right after a purchase); completes on the server thread. */
    public CompletableFuture<Void> refresh(ServerPlayer player) {
        return heartbeat(List.of(player));
    }

    public void flushAll() {
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Profile profile = profiles.get(player.getUUID());
            if (profile != null) backend.post("/api/server/players/logout", report(player, profile, now), Void.class).exceptionally(e -> null);
        }
    }

    private CompletableFuture<Void> heartbeat(List<ServerPlayer> players) {
        long now = System.currentTimeMillis();
        List<Report> reports = new ArrayList<>();
        for (ServerPlayer player : players) {
            Profile profile = profiles.get(player.getUUID());
            if (profile != null) reports.add(report(player, profile, now));
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (reports.isEmpty()) {
            done.complete(null);
            return done;
        }
        backend.<List<Status>>post("/api/server/players/heartbeat", Map.of("players", reports), STATUS_LIST)
                .whenComplete((statuses, error) -> server.execute(() -> {
                    if (error == null && statuses != null) {
                        for (Status status : statuses) apply(UUID.fromString(status.uuid), status);
                    }
                    done.complete(null);
                }));
        return done;
    }

    private Report report(ServerPlayer player, Profile profile, long now) {
        long seconds = (now - profile.reportedAt) / 1000L;
        profile.reportedAt += seconds * 1000L;
        return new Report(player.getUUID().toString(), player.getGameProfile().getName(), seconds);
    }

    private void apply(UUID id, Status status) {
        Profile profile = profiles.get(id);
        if (profile == null || status == null) return;
        profile.status = status;
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null && status.ban != null) {
            player.connection.disconnect(Component.literal("§c你已被封禁\n§7原因: §f" + status.ban.reason
                    + "\n§7解封时间: §f" + formatUntil(status.ban.until)));
            return;
        }
        onChanged.run();
    }

    // ---- queries ----------------------------------------------------------------------------------

    /** Total online time including the part of the current session not reported yet. */
    public long playtimeSeconds(UUID id) {
        Profile profile = profiles.get(id);
        if (profile == null) return 0;
        long reported = profile.status == null ? 0 : profile.status.totalOnlineSeconds;
        return reported + (System.currentTimeMillis() - profile.reportedAt) / 1000L;
    }

    public boolean hasEntitlement(UUID id, String key) {
        Profile profile = profiles.get(id);
        if (profile == null || profile.status == null || profile.status.entitlements == null) return false;
        Instant now = Instant.now();
        return profile.status.entitlements.stream()
                .anyMatch(e -> key.equals(e.key) && (e.until == null || Instant.parse(e.until).isAfter(now)));
    }

    /** @return epoch millis the entitlement ends, 0 = permanent, -1 = not owned */
    public long entitlementUntil(UUID id, String key) {
        Profile profile = profiles.get(id);
        if (profile == null || profile.status == null || profile.status.entitlements == null) return -1;
        Instant now = Instant.now();
        for (Entitlement e : profile.status.entitlements) {
            if (!key.equals(e.key)) continue;
            if (e.until == null) return 0;
            Instant until = Instant.parse(e.until);
            if (until.isAfter(now)) return until.toEpochMilli();
        }
        return -1;
    }

    /** @return the mute message to show, or null when the player may chat */
    public String muteMessage(UUID id) {
        Profile profile = profiles.get(id);
        if (profile == null || profile.status == null || profile.status.mute == null) return null;
        Restriction mute = profile.status.mute;
        if (mute.until != null && Instant.parse(mute.until).isBefore(Instant.now())) return null;
        return "§c你已被禁言 §7原因: §f" + mute.reason + " §7解除时间: §f" + formatUntil(mute.until);
    }

    private static String formatUntil(String until) {
        return until == null ? "永久" : TIME.format(Instant.parse(until));
    }
}
