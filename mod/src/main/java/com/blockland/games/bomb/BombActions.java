package com.blockland.games.bomb;

import com.blockland.bomb.BombItem;
import com.blockland.network.BombPacketListeners;
import com.blockland.server.game.GameInstance;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.function.Function;

/** Routes a player's bomb inputs (plant, defuse, tossing the bomb) to the 枪战爆破 match they are in, if any. */
public final class BombActions implements BombPacketListeners.Server, BombItem.DropHandler {
    private final Function<ServerPlayer, GameInstance> gameOf;

    public BombActions(Function<ServerPlayer, GameInstance> gameOf) {
        this.gameOf = gameOf;
    }

    @Override
    public void onPlant(ServerPlayer player, boolean holding) {
        if (gameOf.apply(player) instanceof BombGame game) game.onPlant(player, holding);
    }

    @Override
    public void onDefuse(ServerPlayer player, boolean holding) {
        if (gameOf.apply(player) instanceof BombGame game) game.onDefuse(player, holding);
    }

    @Override
    public boolean tossed(Player player) {
        return player instanceof ServerPlayer server && gameOf.apply(server) instanceof BombGame game && game.onToss(server);
    }
}
