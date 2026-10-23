package com.blockland.server.session;

import com.blockland.network.packet.LobbyPackets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is connected and how to reach them, wherever they are: in the lobby (the configuration phase, where no world
 * is loaded and no {@link ServerPlayer} exists) or in the world for a match. Lobby services address players by id
 * through this and never assume an entity.
 */
public interface PlayerDirectory {

    boolean isOnline(UUID id);

    /** The connected player's name, null when offline. */
    @Nullable
    String name(UUID id);

    /** A connected player by name, ignoring case. */
    Optional<UUID> byName(String name);

    /** Everyone connected, lobby and world. */
    Collection<UUID> online();

    /** Players waiting in the lobby right now. */
    Collection<UUID> inLobby();

    /** The player's entity while they are in the world; null in the lobby or offline. */
    @Nullable
    ServerPlayer inWorld(UUID id);

    /** Operator level (0 = none), also known in the lobby. */
    int permissionLevel(UUID id);

    /** A message for the player: a toast in the lobby, a chat line in the world. */
    void notify(UUID id, LobbyPackets.Level level, Component message);

    void kick(UUID id, Component reason);
}
