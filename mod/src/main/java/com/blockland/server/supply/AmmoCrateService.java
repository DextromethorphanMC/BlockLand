package com.blockland.server.supply;

import com.blockland.gun.GunContent;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.blockland.network.BLNetwork;
import com.blockland.network.SupplyPacketListeners;
import com.blockland.network.packet.SupplyPackets;
import com.blockland.supply.AmmoCrateBlockEntity;
import com.blockland.supply.SupplyContent;
import com.blockland.supply.SupplyRules;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ammo crates (弹药箱, asset spec §46), server side: a player using a crate with a gun in hand gets that gun's ammunition
 * topped up — loose rounds of its ammo type into the inventory, up to {@link SupplyRules#magazines} spare magazines on
 * top of a full gun (the reload the client plays after its resupply fills the gun from them, through the gun service as
 * any reload). The crate's lid opens for everyone around, the player's arm reaches for everyone who sees it (an off-hand
 * swing), and the player gets the result. Rules in {@code data/blockland/supply/ammo_crate.json}.
 */
public final class AmmoCrateService implements SupplyPacketListeners.Server {
    private record CrateKey(ResourceKey<Level> level, BlockPos pos) {
    }

    private final MinecraftServer server;
    /** Server tick from which each player may use a crate again. */
    private final Map<UUID, Integer> readyAt = new HashMap<>();
    /** Server tick until which each crate's lid is open (its open sound plays only when it was shut). */
    private final Map<CrateKey, Integer> openUntil = new HashMap<>();

    public AmmoCrateService(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onUse(ServerPlayer player, SupplyPackets.C2SUse use) {
        if (!player.isAlive() || player.isSpectator() || use.slot() != player.getInventory().getSelectedSlot()) return;
        ServerLevel level = player.level();
        BlockPos pos = use.pos();
        if (!level.isLoaded(pos) || !level.getBlockState(pos).is(SupplyContent.AMMO_CRATE.get())) return;
        SupplyRules rules = SupplyRules.get();
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > rules.reach + 1.0) return;
        int now = server.getTickCount();
        if (now < readyAt.getOrDefault(player.getUUID(), 0)) return;

        ItemStack stack = player.getMainHandItem();
        GunState state = GunContent.stateOf(stack);
        Optional<GunStats> resolved = GunContent.statsOf(stack);
        if (state == null || resolved.isEmpty()) {
            player.displayClientMessage(Component.translatable("blockland.supply.hint"), true);
            return;
        }
        GunStats stats = resolved.get();
        Item ammo = GunContent.ammoItem(stats.ammo().id);
        if (ammo == null) return;
        int capacity = stats.capacity();
        int missing = Math.max(0, capacity - state.ammo());
        int reserve = player.getInventory().clearOrCountMatchingItems(s -> s.is(ammo), 0, player.inventoryMenu.getCraftSlots());
        int give = Math.max(0, rules.magazines * capacity + missing - reserve);
        Component ammoName = new ItemStack(ammo).getHoverName();
        if (give <= 0 && missing == 0) {
            player.displayClientMessage(Component.translatable("blockland.supply.full", ammoName), true);
            return;
        }
        readyAt.put(player.getUUID(), now + Math.max(1, Math.round(rules.cooldown * 20)));
        int left = give;
        while (left > 0) {
            ItemStack rounds = new ItemStack(ammo, Math.min(left, ammo.getDefaultMaxStackSize()));
            left -= rounds.getCount();
            if (!player.getInventory().add(rounds) && !rounds.isEmpty()) player.drop(rounds, false);
        }
        player.inventoryMenu.broadcastChanges();
        open(level, pos, now, rules);
        level.playSound(null, pos, SupplyContent.sound("supply.rummage"), SoundSource.BLOCKS, 0.8f, 1.0f);
        // the arm reaching into the crate, as everyone around sees it
        player.swing(InteractionHand.OFF_HAND, true);
        BLNetwork.sendTo(player, new SupplyPackets.S2CResupplied(pos, use.slot(), give, missing > 0));
        player.displayClientMessage(give > 0
                ? Component.translatable("blockland.supply.resupplied", give, ammoName)
                : Component.translatable("blockland.supply.reloading"), true);
    }

    /** Opens the crate's lid (or keeps it open) for everyone tracking it. */
    private void open(ServerLevel level, BlockPos pos, int now, SupplyRules rules) {
        CrateKey key = new CrateKey(level.dimension(), pos.immutable());
        boolean shut = now >= openUntil.getOrDefault(key, Integer.MIN_VALUE);
        openUntil.put(key, now + rules.openTicks());
        level.blockEvent(pos, SupplyContent.AMMO_CRATE.get(), AmmoCrateBlockEntity.EVENT_OPEN, rules.openTicks());
        if (shut) level.playSound(null, pos, SupplyContent.sound("supply.crate_open"), SoundSource.BLOCKS, 0.9f, 1.0f);
    }

    public void tick() {
        int now = server.getTickCount();
        if (!openUntil.isEmpty() && now % 100 == 0) openUntil.values().removeIf(until -> until < now);
        if (!readyAt.isEmpty() && now % 100 == 0) readyAt.values().removeIf(at -> at < now);
    }

    public void forget(ServerPlayer player) {
        readyAt.remove(player.getUUID());
    }

    public void clear() {
        readyAt.clear();
        openUntil.clear();
    }
}
