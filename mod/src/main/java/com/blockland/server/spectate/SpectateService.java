package com.blockland.server.spectate;

import com.blockland.network.BLNetwork;
import com.blockland.network.SpectatePacketListeners;
import com.blockland.network.packet.SpectatePackets;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameManager;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.profile.Cosmetics;
import com.blockland.server.ranked.Ranks;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spectating after death (观察者画面), for every game type at once: whoever is in a match and in spectator mode — the
 * game put them there when they went down — watches the match. By default the player who killed them (vanilla's
 * spectator camera on that player, so they see through their eyes; the client may orbit it in third person), cycling
 * through whoever the game lets them watch, or — when the mode allows it ({@code spectateFreeCam}) — a free camera that
 * flies over the match but never out of the game's {@link GameInstance#spectateBounds bounds}. A player who will
 * respawn watches until then (the game's {@link GameInstance#respawnTicks countdown}); one who is out watches until the
 * match ends, or for {@code spectateSeconds} and then goes back to the lobby. The game decides nothing here; this
 * service only moves cameras and tells each spectator what they are looking at. Modes can switch it off
 * ({@code spectate}: the game's own overview stays).
 */
public final class SpectateService implements SpectatePacketListeners.Server {
    /** Ticks between two refreshes of a spectator's HUD (any change is sent at once). */
    private static final int REFRESH = 20;

    private final MinecraftServer server;
    private final GameManager games;
    private final PlayerProfiles profiles;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Who last killed each player (recorded at every death, before the game turns it into spectating). */
    private final Map<UUID, UUID> killers = new HashMap<>();

    private static final class Session {
        final UUID game;
        @Nullable
        UUID target;
        boolean free;
        int elapsed;
        /** The longest respawn wait seen in this session (the HUD's bar runs over it). */
        int respawnTotal;
        @Nullable
        UUID killer;
        String killerName = "";
        boolean dirty = true;
        int lastTarget = -2;

        Session(UUID game) {
            this.game = game;
        }
    }

    public SpectateService(MinecraftServer server, GameManager games, PlayerProfiles profiles) {
        this.server = server;
        this.games = games;
        this.profiles = profiles;
    }

    /** Every death, before a game cancels it: remembers who to show the victim first. */
    public void onDeath(LivingEntity victim, DamageSource source) {
        if (!(victim instanceof ServerPlayer player)) return;
        ServerPlayer killer = source.getEntity() instanceof ServerPlayer p ? p
                : victim.getKillCredit() instanceof ServerPlayer p ? p : null;
        if (killer != null && killer != player) killers.put(player.getUUID(), killer.getUUID());
        else killers.remove(player.getUUID());
    }

    // ---- input ----------------------------------------------------------------------------------------

    @Override
    public void onControl(ServerPlayer player, SpectatePackets.Control action) {
        Session session = sessions.get(player.getUUID());
        GameInstance game = games.gameOf(player);
        if (session == null || game == null) return;
        switch (action) {
            case NEXT, PREVIOUS -> {
                List<ServerPlayer> targets = targets(game, player);
                if (targets.isEmpty()) return;
                int at = -1;
                for (int i = 0; i < targets.size(); i++) {
                    if (targets.get(i).getUUID().equals(session.target)) at = i;
                }
                int step = action == SpectatePackets.Control.NEXT ? 1 : -1;
                // leaving the free camera goes back to the player watched before (or the first)
                int next = session.free && at >= 0 ? at : Math.floorMod(at + step, targets.size());
                session.target = targets.get(next).getUUID();
                session.free = false;
            }
            case FREE -> {
                if (!freeAllowed(game)) return;
                session.free = true;
                // the free camera starts where the watched player stood (vanilla leaves the spectator there)
                player.setCamera(player);
            }
        }
        session.dirty = true;
    }

    // ---- ticking --------------------------------------------------------------------------------------

    public void tick() {
        Map<UUID, Session> live = new HashMap<>();
        for (GameInstance game : games.games()) {
            boolean enabled = game.mode().flag("spectate", true) && !game.isFinished();
            // a copy: a spectator whose time ran out leaves the match during the loop
            for (UUID id : List.copyOf(game.participants())) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null || !player.isSpectator() || !enabled) continue;
                Session session = sessions.get(id);
                if (session == null || !session.game.equals(game.id())) session = start(player, game);
                live.put(id, session);
                tickSession(player, game, session);
            }
        }
        // everyone whose watching is over (respawned, left, the match is gone): their own eyes again
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (live.containsKey(entry.getKey())) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                if (player.getCamera() != player) player.setCamera(player);
                BLNetwork.sendTo(player, SpectatePackets.S2CState.NONE);
            }
        }
        sessions.clear();
        sessions.putAll(live);
    }

    private Session start(ServerPlayer player, GameInstance game) {
        Session session = new Session(game.id());
        UUID killer = killers.remove(player.getUUID());
        if (killer != null && game.participants().contains(killer)) {
            session.killer = killer;
            ServerPlayer online = server.getPlayerList().getPlayer(killer);
            session.killerName = online == null ? "" : online.getGameProfile().getName();
            session.target = killer;
        }
        return session;
    }

    private void tickSession(ServerPlayer player, GameInstance game, Session session) {
        session.elapsed++;
        List<ServerPlayer> targets = targets(game, player);
        if (!session.free) {
            ServerPlayer target = find(targets, session.target);
            if (target == null && !targets.isEmpty()) {
                // the watched player went down or left: the next one (the killer first, if still standing)
                target = find(targets, session.killer);
                if (target == null) target = targets.get(0);
                session.target = target.getUUID();
                session.dirty = true;
            }
            if (target == null) {
                // nobody left to watch: the free camera when allowed, otherwise the game's overview
                if (freeAllowed(game)) session.free = true;
                if (player.getCamera() != player) player.setCamera(player);
            } else if (player.getCamera() != target) {
                player.setCamera(target);
            }
        } else if (player.getCamera() != player) {
            player.setCamera(player);
        }
        keepInside(player, game);

        int respawn = game.respawnTicks(player.getUUID());
        int limit = seconds(game);
        if (respawn < 0 && limit > 0 && session.elapsed >= limit * 20 && !game.isEnding()) {
            // watched long enough and not coming back: home
            player.setCamera(player);
            BLNetwork.sendTo(player, SpectatePackets.S2CState.NONE);
            games.leave(player, false);
            return;
        }
        if (respawn >= 0) session.respawnTotal = Math.max(session.respawnTotal, respawn);
        int targetId = session.free ? -1 : player.getCamera() == player ? -1 : player.getCamera().getId();
        if (targetId != session.lastTarget) session.dirty = true;
        if (session.dirty || session.elapsed % REFRESH == 1) {
            session.dirty = false;
            session.lastTarget = targetId;
            BLNetwork.sendTo(player, state(player, game, session, targets.size(), respawn, limit));
        }
    }

    /** The free camera stays over the match: outside the game's bounds (or in another world) it is put back. */
    private void keepInside(ServerPlayer player, GameInstance game) {
        AABB bounds = game.spectateBounds();
        if (bounds == null) return;
        Entity camera = player.getCamera();
        if (camera != player) return;
        GameInstance owner = games.gameOf(player);
        if (owner != game) return;
        Vec3 at = player.position();
        boolean outside = !bounds.contains(at);
        if (!outside) return;
        double x = Math.clamp(at.x, bounds.minX + 0.5, bounds.maxX - 0.5);
        double y = Math.clamp(at.y, bounds.minY + 0.5, bounds.maxY - 0.5);
        double z = Math.clamp(at.z, bounds.minZ + 0.5, bounds.maxZ - 0.5);
        net.minecraft.server.level.ServerLevel level = arenaLevel(game, player);
        if (level == null) return;
        PlayerUtil.teleport(player, level, new Vec3(x, y, z), player.getYRot(), player.getXRot());
    }

    /**
     * The world the match is played in: where its fighters are (a spectator that teleported elsewhere through vanilla's
     * spectator menu is brought back).
     */
    @Nullable
    private net.minecraft.server.level.ServerLevel arenaLevel(GameInstance game, ServerPlayer viewer) {
        for (UUID id : game.participants()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && p != viewer && !p.isSpectator()) return p.level();
        }
        return viewer.level();
    }

    private SpectatePackets.S2CState state(ServerPlayer player, GameInstance game, Session session, int targets, int respawn, int limit) {
        int ticksLeft = respawn >= 0 ? respawn : limit > 0 && !game.isEnding() ? Math.max(0, limit * 20 - session.elapsed) : -1;
        int total = respawn >= 0 ? Math.max(1, session.respawnTotal) : Math.max(1, limit * 20);
        boolean canFree = freeAllowed(game);
        Entity camera = player.getCamera();
        if (session.free || !(camera instanceof ServerPlayer watched) || camera == player) {
            return new SpectatePackets.S2CState(SpectatePackets.Mode.FREE, -1, "", "", "", 0, 0, 0, 0, 0, session.killerName,
                    ticksLeft, total, respawn >= 0, canFree, targets);
        }
        UUID id = watched.getUUID();
        Ranks.RankInfo rank = Ranks.get().rankOf(id);
        String card = Cosmetics.get().get(id, Cosmetics.CARD);
        String cardId = card == null ? "" : card.startsWith("card:") ? card.substring(5) : card;
        return new SpectatePackets.S2CState(SpectatePackets.Mode.TARGET, watched.getId(), watched.getGameProfile().getName(), cardId,
                rank.tier().id(), rank.score(), rank.position(), profiles.playtimeSeconds(id), watched.getHealth(), watched.getMaxHealth(),
                session.killerName, ticksLeft, total, respawn >= 0, canFree, targets);
    }

    /** The players a spectator may watch right now: the game's list, still fighting, in the spectator's match world. */
    private List<ServerPlayer> targets(GameInstance game, ServerPlayer viewer) {
        List<ServerPlayer> out = new ArrayList<>();
        net.minecraft.server.level.ServerLevel level = arenaLevel(game, viewer);
        for (UUID id : game.spectateTargets(viewer.getUUID())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || p == viewer || p.isSpectator() || !p.isAlive() || p.level() != level) continue;
            if (games.gameOf(p) != game) continue;
            out.add(p);
        }
        return out;
    }

    @Nullable
    private static ServerPlayer find(List<ServerPlayer> players, @Nullable UUID id) {
        if (id == null) return null;
        for (ServerPlayer p : players) {
            if (p.getUUID().equals(id)) return p;
        }
        return null;
    }

    private static boolean freeAllowed(GameInstance game) {
        return game.mode().flag("spectateFreeCam", true) && game.spectateBounds() != null;
    }

    /** {@code spectateSeconds}: 0 = no limit. */
    private static int seconds(GameInstance game) {
        try {
            var value = game.mode().settings.get("spectateSeconds");
            return value == null || !value.isJsonPrimitive() ? 0 : Math.max(0, Math.min(600, value.getAsInt()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public void forget(ServerPlayer player) {
        sessions.remove(player.getUUID());
        killers.remove(player.getUUID());
    }

    public void clear() {
        sessions.clear();
        killers.clear();
    }
}
