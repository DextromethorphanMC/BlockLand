package com.blockland.games.fastpvp;

import com.blockland.games.common.TeamArenaGame;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchRoster;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** FastPVP on top of the shared team arena flow: everyone gets the mode's kit (in their own arrangement). */
final class FastPvpGame extends TeamArenaGame {
    private final FastPvpSettings settings;

    FastPvpGame(GameServices services, GameModeDef mode, FastPvpSettings settings, MapDef map, MatchRoster players) {
        super(services, mode, settings.team(), map, players);
        this.settings = settings;
    }

    @Override
    protected String gameName() {
        return "快速PVP";
    }

    @Override
    protected void onPlayerSpawned(ServerPlayer player) {
        services.kits().apply(player, mode);
    }

    /** Kits are identical for everyone; dropping them only clutters the arena. */
    @Override
    protected boolean dropsItemsOnElimination() {
        return false;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return settings.allowBlockBreak() && super.allowBlockBreak(player, pos);
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return settings.allowBlockPlace() && super.allowBlockPlace(player, pos);
    }
}
