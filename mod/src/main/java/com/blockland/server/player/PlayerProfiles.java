package com.blockland.server.player;

import com.blockland.BlockLand;
import com.blockland.craft.Build;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.craft.BuildStore;
import com.blockland.server.session.PlayerDirectory;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Network-wide player data mirrored from the backend: online time (reported in heartbeats), bans, mutes,
 * entitlements, and the player's own preferences — privacy (party invitations), which announcements they have read
 * and their 工匠 builds. Preferences change through this class only: the local copy updates at once and the backend
 * is told; heartbeats never overwrite them. Fails open: if the backend is unreachable players can still play.
 */
public final class PlayerProfiles implements BuildStore {
    private static final int HEARTBEAT_TICKS = 15 * 20;
    private static final Type STATUS_LIST = new TypeToken<List<Status>>() { }.getType();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final MinecraftServer server;
    private final BackendClient backend;
    private final PlayerDirectory players;
    private Runnable onChanged = () -> { };
    private Consumer<UUID> onPreferences = id -> { };
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
        Boolean allowPartyInvites;
        List<String> readAnnouncements;
        Map<String, Build> builds;
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

    private record SettingsBody(boolean allowPartyInvites) {
    }

    private record ReadBody(List<String> ids) {
    }

    private record BuildBody(String weapon, @Nullable Build build) {
    }

    private static final class Profile {
        final String name;
        volatile Status status;
        /** Last time this player's online time was reported to the backend. */
        volatile long reportedAt = System.currentTimeMillis();
        /** Preferences: loaded once with the login status, then owned here. */
        volatile boolean preferencesLoaded;
        volatile boolean allowInvites = true;
        final Set<String> read = ConcurrentHashMap.newKeySet();
        final Map<String, Build> builds = new ConcurrentHashMap<>();

        Profile(String name) {
            this.name = name;
        }
    }

    public PlayerProfiles(MinecraftServer server, BackendClient backend, PlayerDirectory players) {
        this.server = server;
        this.backend = backend;
        this.players = players;
    }

    /** Called on the server thread whenever a player's status (playtime, entitlements, preferences…) was updated. */
    public void setChangeListener(Runnable listener) {
        this.onChanged = listener;
    }

    /**
     * Called on the server thread when a player's preferences arrived from the backend (after joining, or after one
     * of our updates): what the lobby showed before that (read announcements, builds) is now known for sure.
     */
    public void setPreferencesListener(Consumer<UUID> listener) {
        this.onPreferences = listener;
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    /** The player connected (they are in the lobby). */
    public void onJoin(UUID id, String name) {
        profiles.put(id, new Profile(name));
        backend.<Status>post("/api/server/players/login", new Login(id.toString(), name), Status.class)
                .whenComplete((status, error) -> server.execute(() -> {
                    if (error != null) {
                        BlockLand.LOGGER.warn("[BlockLand] Player login sync failed for {}: {}", name, error.getMessage());
                        return;
                    }
                    apply(id, status, true);
                }));
    }

    /** The player disconnected. */
    public void onLeave(UUID id) {
        Profile profile = profiles.remove(id);
        if (profile == null) return;
        backend.post("/api/server/players/logout", report(id, profile, System.currentTimeMillis()), Void.class)
                .exceptionally(error -> null);
    }

    public void tick() {
        if (++tickCounter % HEARTBEAT_TICKS != 0) return;
        heartbeat(players.online());
    }

    /** Re-reads a player's status (e.g. right after a purchase); completes on the server thread. */
    public CompletableFuture<Void> refresh(UUID id) {
        return heartbeat(List.of(id));
    }

    public void flushAll() {
        long now = System.currentTimeMillis();
        for (UUID id : players.online()) {
            Profile profile = profiles.get(id);
            if (profile != null) backend.post("/api/server/players/logout", report(id, profile, now), Void.class).exceptionally(e -> null);
        }
    }

    private CompletableFuture<Void> heartbeat(Collection<UUID> ids) {
        long now = System.currentTimeMillis();
        List<Report> reports = new ArrayList<>();
        for (UUID id : ids) {
            Profile profile = profiles.get(id);
            if (profile != null) reports.add(report(id, profile, now));
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (reports.isEmpty()) {
            done.complete(null);
            return done;
        }
        backend.<List<Status>>post("/api/server/players/heartbeat", Map.of("players", reports), STATUS_LIST)
                .whenComplete((statuses, error) -> server.execute(() -> {
                    if (error == null && statuses != null) {
                        for (Status status : statuses) apply(UUID.fromString(status.uuid), status, false);
                    }
                    done.complete(null);
                }));
        return done;
    }

    private Report report(UUID id, Profile profile, long now) {
        long seconds = (now - profile.reportedAt) / 1000L;
        profile.reportedAt += seconds * 1000L;
        return new Report(id.toString(), profile.name, seconds);
    }

    /** @param preferences take the preferences from this status (the login answer, or the answer to our own update) */
    private void apply(UUID id, Status status, boolean preferences) {
        Profile profile = profiles.get(id);
        if (profile == null || status == null) return;
        profile.status = status;
        boolean preferencesArrived = preferences || !profile.preferencesLoaded;
        if (preferencesArrived) {
            profile.preferencesLoaded = true;
            profile.allowInvites = status.allowPartyInvites == null || status.allowPartyInvites;
            profile.read.clear();
            if (status.readAnnouncements != null) profile.read.addAll(status.readAnnouncements);
            profile.builds.clear();
            if (status.builds != null) status.builds.forEach((weapon, build) -> {
                if (weapon != null && build != null) profile.builds.put(weapon, build);
            });
        }
        if (status.ban != null) {
            players.kick(id, Component.translatable("blockland.profile.banned", status.ban.reason, formatUntil(status.ban.until)));
            return;
        }
        if (preferencesArrived) onPreferences.accept(id);
        onChanged.run();
    }

    /** Sends a preference change; the answer (the full status) replaces the local copy. */
    private void update(UUID id, String path, Object body) {
        backend.<Status>post("/api/server/players/" + id + path, body, Status.class)
                .whenComplete((status, error) -> server.execute(() -> {
                    if (error != null) {
                        BlockLand.LOGGER.warn("[BlockLand] Could not save {} for {}: {}", path, id, error.getMessage());
                        return;
                    }
                    apply(id, status, true);
                }));
    }

    // ---- preferences ------------------------------------------------------------------------------

    /** Privacy: whether the player accepts party invitations (default yes). */
    public boolean allowsInvites(UUID id) {
        Profile profile = profiles.get(id);
        return profile == null || profile.allowInvites;
    }

    public void setAllowInvites(UUID id, boolean allow) {
        Profile profile = profiles.get(id);
        if (profile == null || profile.allowInvites == allow) return;
        profile.allowInvites = allow;
        update(id, "/settings", new SettingsBody(allow));
        onChanged.run();
    }

    /** Ids of the announcements the player has marked as read. */
    public Set<String> readAnnouncements(UUID id) {
        Profile profile = profiles.get(id);
        return profile == null ? Set.of() : Set.copyOf(profile.read);
    }

    public void markRead(UUID id, Collection<String> announcementIds) {
        Profile profile = profiles.get(id);
        if (profile == null) return;
        Set<String> added = new LinkedHashSet<>(announcementIds);
        added.removeAll(profile.read);
        if (added.isEmpty()) return;
        profile.read.addAll(added);
        update(id, "/announcements/read", new ReadBody(List.copyOf(added)));
        onChanged.run();
    }

    @Override
    public Map<String, Build> builds(UUID player) {
        Profile profile = profiles.get(player);
        return profile == null ? Map.of() : new LinkedHashMap<>(profile.builds);
    }

    @Override
    public void save(UUID player, String weaponId, @Nullable Build build) {
        Profile profile = profiles.get(player);
        if (profile == null) return;
        if (build == null) profile.builds.remove(weaponId);
        else profile.builds.put(weaponId, build);
        update(player, "/builds", new BuildBody(weaponId, build));
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
    @Nullable
    public Component muteMessage(UUID id) {
        Profile profile = profiles.get(id);
        if (profile == null || profile.status == null || profile.status.mute == null) return null;
        Restriction mute = profile.status.mute;
        if (mute.until != null && Instant.parse(mute.until).isBefore(Instant.now())) return null;
        return Component.translatable("blockland.profile.muted", mute.reason, formatUntil(mute.until));
    }

    private static Component formatUntil(@Nullable String until) {
        return until == null ? Component.translatable("blockland.profile.forever") : Component.literal(TIME.format(Instant.parse(until)));
    }
}
