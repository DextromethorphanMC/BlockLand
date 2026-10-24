package com.blockland.games.fastpvp;

import com.blockland.games.common.TeamArenaGame;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchRoster;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.List;

/**
 * FastPVP on top of the shared team arena flow: everyone gets the mode's kit (in their own arrangement). With
 * {@code infiniteBlocks} every stack of blocks is kept full; with {@code knockbackOnly} hits deal no damage (their knock
 * back still lands), so only the void decides — Rush.
 */
final class FastPvpGame extends TeamArenaGame {
    private final FastPvpSettings settings;

    FastPvpGame(GameServices services, GameModeDef mode, FastPvpSettings settings, MapDef map, MatchRoster players) {
        super(services, mode, settings.team(), map, players);
        this.settings = settings;
    }

    @Override
    protected Component gameName() {
        return Component.translatable("blockland.game.fastpvp");
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
    protected GameType playingGameMode() {
        return settings.allowBlockBreak() || settings.allowBlockPlace() ? GameType.SURVIVAL : GameType.ADVENTURE;
    }

    @Override
    public void tick() {
        super.tick();
        if (settings.infiniteBlocks() && phase() == Phase.PLAYING) forEachOnline(FastPvpGame::refillBlocks);
    }

    /** Puts every block stack back to full (checked each tick, so a stack never runs out between two placements). */
    private static void refillBlocks(ServerPlayer player) {
        if (player.isSpectator()) return;
        Inventory inventory = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() instanceof BlockItem && stack.getCount() < stack.getMaxStackSize()) {
                stack.setCount(stack.getMaxStackSize());
                changed = true;
            }
        }
        if (changed) player.containerMenu.broadcastChanges();
    }

    @Override
    public float modifyDamage(ServerPlayer victim, DamageSource source, float amount) {
        return settings.knockbackOnly() ? 0 : amount;
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
