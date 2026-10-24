package com.blockland.games.battleroyale;

import com.blockland.network.BattleRoyalePacketListeners;
import com.blockland.server.game.GameInstance;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Function;

/** Routes a player's 大逃杀 inputs (jumping out of the plane) to the match they are in, if any. */
public final class BattleRoyaleActions implements BattleRoyalePacketListeners.Server {
    private final Function<ServerPlayer, GameInstance> gameOf;

    public BattleRoyaleActions(Function<ServerPlayer, GameInstance> gameOf) {
        this.gameOf = gameOf;
    }

    @Override
    public void onJump(ServerPlayer player) {
        if (gameOf.apply(player) instanceof BattleRoyaleGame game) game.onJump(player);
    }
}
