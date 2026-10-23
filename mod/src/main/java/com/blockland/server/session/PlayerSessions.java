package com.blockland.server.session;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.server.game.PlayerTransit;
import com.blockland.server.lobby.LobbyWorld;
import com.blockland.server.util.PlayerUtil;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.world.level.GameType;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Every player connected to this server, wherever they are. The lobby is the configuration phase: after logging in a
 * player waits there with no world loaded on either side (the {@link LobbyGate} task never finishes on its own) and
 * only enters the world when a match brings them in; when it ends they are switched back to configuration.
 * <p>
 * The Forge login/logout events therefore only mean "entered / left the world" now; joining and leaving the network
 * is reported to the {@link Listener} instead.
 */
public final class PlayerSessions implements PlayerDirectory, PlayerTransit {

    /** Network-level events, on the server thread. */
    public interface Listener {
        /** A player connected; they wait in the lobby. */
        void joined(UUID id, String name);

        /** The player is in the lobby now: right after joining, or back from the world. */
        void enteredLobby(UUID id);

        /**
         * The player disconnected.
         *
         * @param entity the player's entity when they left from the world (still usable for cleanup), else null
         */
        void left(UUID id, String name, @Nullable ServerPlayer entity);
    }

    private enum Where {
        /** configuration phase, no world */
        LOBBY,
        /** told to load into the world, not there yet */
        ENTERING,
        /** in the world (a ServerPlayer exists) */
        WORLD,
        /**
         * still in the world, reset for the lobby: the switch to configuration waits one tick. Until the client has
         * acknowledged the switch, vanilla keeps ticking the player, and any play packet that tick produces (the health,
         * food and inventory the reset just changed) would reach a client already in configuration and disconnect it —
         * so the reset is sent in play first.
         */
        SETTLING,
        /** told to leave the world, the client has not switched to configuration yet */
        LEAVING
    }

    private static final class Session {
        final GameProfile profile;
        final Connection connection;
        Where where = Where.LOBBY;
        /** The login's {@link LobbyGate} is still the current task: finishing it is how this player enters the world. */
        boolean gate;
        /**
         * No configuration-phase lobby for this player (an integrated server adds no gate): they use the lobby screen
         * from the staging spot in the world, as before; {@code lobbyView} says whether they are "in the lobby" now.
         */
        boolean inWorldLobby;
        boolean lobbyView;
        /** Server tick the player started settling; switched on a later tick. */
        int settleTick;
        final List<Consumer<ServerPlayer>> arrivals = new ArrayList<>();

        Session(GameProfile profile, Connection connection) {
            this.profile = profile;
            this.connection = connection;
        }

        UUID id() {
            return profile.getId();
        }

        String name() {
            return profile.getName();
        }
    }

    /**
     * Vanilla pauses a server whose player list stays empty ({@code pause-when-empty-seconds}), and lobby players are
     * not in that list: while anyone is connected the counter is reset so matchmaking, parties and lobby updates keep
     * running. Null when the field cannot be reached (then the property must be 0).
     */
    @Nullable
    private static final Field EMPTY_TICKS = emptyTicksField();

    private final MinecraftServer server;
    private final LobbyWorld staging;
    private final Map<UUID, Session> byId = new LinkedHashMap<>();
    private final Map<Connection, Session> byConnection = new IdentityHashMap<>();
    /** Entered the world without anyone asking for it: sent back next tick. */
    private final List<UUID> strays = new ArrayList<>();
    private Listener listener = new Listener() {
        @Override public void joined(UUID id, String name) { }
        @Override public void enteredLobby(UUID id) { }
        @Override public void left(UUID id, String name, @Nullable ServerPlayer entity) { }
    };

    public PlayerSessions(MinecraftServer server, LobbyWorld staging) {
        this.server = server;
        this.staging = staging;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The configuration task that keeps a new login in the lobby. */
    public ConfigurationTask gateTask() {
        return new LobbyGate(this);
    }

    /** Lobby players have no ServerPlayer: their packets are attributed through their connection. */
    @Nullable
    public UUID idOf(Connection connection) {
        Session session = byConnection.get(connection);
        return session == null ? null : session.id();
    }

    /**
     * Where to send a player's packets. Null while they switch phase: a payload encoded for one protocol and decoded
     * with the other would disconnect them, and they get a full update on arrival anyway.
     */
    @Nullable
    public Connection connectionOf(UUID id) {
        Session session = byId.get(id);
        if (session == null || session.where == Where.ENTERING || session.where == Where.LEAVING) return null;
        return session.connection;
    }

    // ---- phase changes --------------------------------------------------------------------------

    /** A login reached the lobby gate. Configuration tasks start on a network thread: the lobby is the server thread's. */
    void gateStarted(Connection connection) {
        server.execute(() -> admit(connection));
    }

    private void admit(Connection connection) {
        if (!connection.isConnected() || !(connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl config)) return;
        // tasks still run on a paused server: wake it up for the lobby
        keepAwake();
        GameProfile profile = config.getOwner();
        Session old = byId.get(profile.getId());
        if (old != null) {
            // the same account logged in again: like vanilla, the older connection goes
            disconnect(old, Component.translatable("multiplayer.disconnect.duplicate_login"));
            end(old, null);
        }
        // vanilla only counts players in the world against max-players; the lobby counts too
        if (byId.size() >= server.getMaxPlayers() && !server.getPlayerList().canBypassPlayerLimit(profile)) {
            config.disconnect(Component.translatable("multiplayer.disconnect.server_full"));
            return;
        }
        Session session = new Session(profile, connection);
        session.gate = true;
        byId.put(session.id(), session);
        byConnection.put(connection, session);
        BlockLand.LOGGER.info("[BlockLand] {} joined the lobby", session.name());
        listener.joined(session.id(), session.name());
        listener.enteredLobby(session.id());
    }

    @Override
    public void bring(UUID id, Consumer<ServerPlayer> arrived) {
        Session session = byId.get(id);
        if (session == null) return;
        session.lobbyView = false;
        switch (session.where) {
            case WORLD, SETTLING -> {
                // still in the world (a return to the lobby not done yet is called off)
                session.where = Where.WORLD;
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) arrived.accept(player);
            }
            case ENTERING, LEAVING -> session.arrivals.add(arrived);
            case LOBBY -> {
                session.arrivals.add(arrived);
                enter(session);
            }
        }
    }

    private void enter(Session session) {
        if (!(session.connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl config)) return;
        session.where = Where.ENTERING;
        if (session.gate) {
            session.gate = false;
            config.finishCurrentTask(LobbyGate.TYPE);
        } else {
            config.returnToWorld();
        }
    }

    @Override
    public void toLobby(ServerPlayer player) {
        Session session = byId.get(player.getUUID());
        if (session == null || session.where != Where.WORLD) return;
        // leave a clean player behind in the empty staging dimension: that is where the next world entry loads first
        LobbyWorld.Spot spot = staging.spot();
        PlayerUtil.reset(player, GameType.ADVENTURE);
        if (player.level() != spot.level()) PlayerUtil.teleport(player, spot.level(), spot.pos(), spot.yaw(), 0);
        if (session.inWorldLobby) {
            session.lobbyView = true;
            listener.enteredLobby(session.id());
            return;
        }
        session.where = Where.SETTLING;
        session.settleTick = server.getTickCount();
    }

    /** Forge login event: the player is in the world now. */
    public void enteredWorld(ServerPlayer player) {
        Session session = byId.get(player.getUUID());
        if (session == null) {
            // no lobby gate ran (integrated server): this player keeps the old lobby inside the world
            session = new Session(player.getGameProfile(), player.connection.getConnection());
            session.where = Where.WORLD;
            session.inWorldLobby = true;
            byId.put(session.id(), session);
            byConnection.put(session.connection, session);
            listener.joined(session.id(), session.name());
            toLobby(player);
            return;
        }
        session.where = Where.WORLD;
        session.gate = false;
        List<Consumer<ServerPlayer>> arrivals = List.copyOf(session.arrivals);
        session.arrivals.clear();
        if (arrivals.isEmpty()) {
            strays.add(session.id());
            return;
        }
        for (Consumer<ServerPlayer> arrived : arrivals) arrived.accept(player);
    }

    /**
     * Forge logout event: the player left the world.
     *
     * @return true when they disconnected, false when they are on their way to the lobby
     */
    public boolean leftWorld(ServerPlayer player) {
        Session session = byId.get(player.getUUID());
        if (session == null) return true;
        if (session.where == Where.LEAVING) return false;
        end(session, player);
        return true;
    }

    public void tick() {
        if (!byId.isEmpty()) keepAwake();
        if (!strays.isEmpty()) {
            for (UUID id : List.copyOf(strays)) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) toLobby(player);
            }
            strays.clear();
        }
        List<Session> gone = new ArrayList<>();
        for (Session session : byId.values()) {
            if (session.where == Where.WORLD) continue;
            if (session.where == Where.SETTLING) {
                // the player's own tick has sent the reset in play by now: switch to configuration
                ServerPlayer player = server.getPlayerList().getPlayer(session.id());
                if (player != null && server.getTickCount() > session.settleTick) {
                    session.where = Where.LEAVING;
                    player.connection.switchToConfig();
                }
                continue;
            }
            if (!session.connection.isConnected()) {
                gone.add(session);
            } else if (session.where == Where.LEAVING && session.connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl) {
                // the client acknowledged the switch: back in the lobby (or straight on to the next match)
                session.where = Where.LOBBY;
                if (session.arrivals.isEmpty()) listener.enteredLobby(session.id());
                else enter(session);
            }
        }
        for (Session session : gone) end(session, null);
    }

    private void keepAwake() {
        if (EMPTY_TICKS == null) return;
        try {
            EMPTY_TICKS.setInt(server, 0);
        } catch (IllegalAccessException ignored) {
            // reported once when the field was looked up
        }
    }

    @Nullable
    private static Field emptyTicksField() {
        try {
            Field field = MinecraftServer.class.getDeclaredField("emptyTicks");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot keep the server awake for lobby players; set pause-when-empty-seconds=0 ({})", e.toString());
            return null;
        }
    }

    public void clear() {
        byId.clear();
        byConnection.clear();
        strays.clear();
    }

    private void end(Session session, @Nullable ServerPlayer entity) {
        if (byId.get(session.id()) != session) return;
        byId.remove(session.id());
        byConnection.remove(session.connection);
        BlockLand.LOGGER.info("[BlockLand] {} left the network", session.name());
        listener.left(session.id(), session.name(), entity);
    }

    private static void disconnect(Session session, Component reason) {
        if (session.connection.getPacketListener() instanceof ServerCommonPacketListenerImpl listener) listener.disconnect(reason);
        else session.connection.disconnect(reason);
    }

    // ---- PlayerDirectory ------------------------------------------------------------------------

    @Override
    public boolean isOnline(UUID id) {
        return byId.containsKey(id);
    }

    @Nullable
    @Override
    public String name(UUID id) {
        Session session = byId.get(id);
        return session == null ? null : session.name();
    }

    @Override
    public Optional<UUID> byName(String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return byId.values().stream().filter(s -> s.name().toLowerCase(Locale.ROOT).equals(wanted)).map(Session::id).findFirst();
    }

    @Override
    public Collection<UUID> online() {
        return List.copyOf(byId.keySet());
    }

    @Override
    public Collection<UUID> inLobby() {
        return byId.values().stream().filter(s -> s.where == Where.LOBBY || s.lobbyView).map(Session::id).toList();
    }

    @Nullable
    @Override
    public ServerPlayer inWorld(UUID id) {
        Session session = byId.get(id);
        return session == null || session.where != Where.WORLD && session.where != Where.SETTLING ? null : server.getPlayerList().getPlayer(id);
    }

    @Override
    public int permissionLevel(UUID id) {
        Session session = byId.get(id);
        return session == null ? 0 : server.getProfilePermissions(session.profile);
    }

    @Override
    public void notify(UUID id, LobbyPackets.Level level, Component message) {
        ServerPlayer player = inWorld(id);
        if (player != null) {
            player.sendSystemMessage(message.copy().withStyle(switch (level) {
                case INFO -> ChatFormatting.GRAY;
                case SUCCESS -> ChatFormatting.GREEN;
                case WARNING -> ChatFormatting.YELLOW;
                case ERROR -> ChatFormatting.RED;
            }));
        } else {
            BLNetwork.sendTo(id, new LobbyPackets.S2CNotice(level, message));
        }
    }

    @Override
    public void kick(UUID id, Component reason) {
        Session session = byId.get(id);
        if (session != null) disconnect(session, reason);
    }
}
