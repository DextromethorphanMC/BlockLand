package com.blockland.games.infection;

import com.blockland.network.InfectionPacketListeners;
import com.blockland.server.game.GameInstance;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Function;

/** Routes a player's infection inputs (monster choice, skill) to the infection match they are in, if any. */
public final class InfectionActions implements InfectionPacketListeners.Server {
    private final Function<ServerPlayer, GameInstance> gameOf;

    public InfectionActions(Function<ServerPlayer, GameInstance> gameOf) {
        this.gameOf = gameOf;
    }

    @Override
    public void onSelectMonster(ServerPlayer player, String monsterId) {
        if (gameOf.apply(player) instanceof InfectionGame game) game.onSelect(player, monsterId);
    }

    @Override
    public void onUseSkill(ServerPlayer player) {
        if (gameOf.apply(player) instanceof InfectionGame game) game.onSkill(player);
    }
}
