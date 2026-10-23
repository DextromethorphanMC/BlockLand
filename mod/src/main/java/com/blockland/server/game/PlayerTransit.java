package com.blockland.server.game;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Moves players between the lobby, where no world is loaded, and the game world. Matches are formed while their
 * players wait in the lobby; a game brings each one in once it has somewhere to put them.
 */
public interface PlayerTransit {

    /**
     * Puts a player into the world. {@code arrived} runs on the server thread: right away when they are already in
     * the world, otherwise once they have left the lobby and loaded in (where they land first does not matter, the
     * callback places them). Nothing happens for a player who disconnects first.
     */
    void bring(UUID id, Consumer<ServerPlayer> arrived);

    /** The player leaves the world and waits in the lobby again. */
    void toLobby(ServerPlayer player);
}
