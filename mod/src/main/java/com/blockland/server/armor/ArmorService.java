package com.blockland.server.armor;

import com.blockland.armor.ArmorContent;
import com.blockland.armor.ArmorRules;
import com.blockland.armor.VestState;
import com.blockland.combat.HitRecords;
import com.blockland.network.ArmorPacketListeners;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.ArmorPackets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Body armour (防弹衣 / 防弹板, asset spec §50), server side. Every hurt of an entity wearing a plate carrier comes here
 * once (the hurt event, before vanilla's own armour): of a covered kind of hit its share wears the plates down first —
 * the outermost first, a plate breaking when its durability runs out —, the carrier cuts what gets past them, and the
 * wearer learns what happened (a crack when a plate breaks, for everyone around). Plates go in on a timer: a player
 * holding a plate and wearing a carrier starts it with a left-click, it is in after the insert time if the plate is still
 * in hand and the carrier still on. Rules in {@code data/blockland/armor/armor.json}.
 */
public final class ArmorService implements ArmorPacketListeners.Server {
    private record Insertion(int slot, int doneAt) {
    }

    private final MinecraftServer server;
    private final Map<UUID, Insertion> inserting = new HashMap<>();

    public ArmorService(MinecraftServer server) {
        this.server = server;
    }

    // ---- protection ---------------------------------------------------------------------------------

    /**
     * A hurt about to be applied ({@code amount} after the game's own changes): returns what gets through the armour (the
     * same amount when the victim wears none or the hit is not covered); the carrier's plates are updated.
     */
    public float onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (amount <= 0 || !(victim.level() instanceof ServerLevel level)) return amount;
        ItemStack vest = ArmorContent.wornVest(victim);
        if (vest.isEmpty()) return amount;
        ArmorRules rules = ArmorRules.get();
        float share = rules.cover(source);
        if (share <= 0) return amount;
        if (rules.headshotsBypass) {
            HitRecords.Hit hit = HitRecords.last(victim);
            if (hit != null && hit.headshot() && hit.gameTime() == level.getGameTime()) return amount;
        }
        VestState before = ArmorContent.stateOf(vest);
        ArmorRules.Absorbed result = rules.absorb(before, amount * share);
        if (!result.state().equals(before)) vest.set(ArmorContent.VEST_STATE.get(), result.state());
        if (result.soaked() > 0) {
            level.playSound(null, victim.getX(), victim.getY() + 1.1, victim.getZ(), ArmorContent.sound("armor.plate_hit"),
                    SoundSource.PLAYERS, 0.8f, 0.9f + victim.getRandom().nextFloat() * 0.2f);
        }
        if (result.broken() > 0) {
            level.playSound(null, victim.getX(), victim.getY() + 1.1, victim.getZ(), ArmorContent.sound("armor.plate_break"),
                    SoundSource.PLAYERS, 1.0f, 0.95f + victim.getRandom().nextFloat() * 0.1f);
        }
        if (victim instanceof ServerPlayer player && (result.soaked() > 0 || result.broken() > 0)) {
            BLNetwork.sendTo(player, new ArmorPackets.S2CHit(result.soaked(), result.broken(), result.state().count()));
        }
        return amount * (1 - share) + result.passed();
    }

    // ---- inserting plates ---------------------------------------------------------------------------

    @Override
    public void onInsert(ServerPlayer player, ArmorPackets.C2SInsert insert) {
        if (!player.isAlive() || player.isSpectator() || insert.slot() != player.getInventory().getSelectedSlot()) return;
        if (inserting.containsKey(player.getUUID()) || !ArmorContent.isPlate(player.getMainHandItem())) return;
        ArmorRules rules = ArmorRules.get();
        ItemStack vest = ArmorContent.wornVest(player);
        if (vest.isEmpty()) {
            answer(player, ArmorPackets.Result.NO_VEST, 0, 0, "blockland.armor.no_vest");
            return;
        }
        int plates = ArmorContent.stateOf(vest).count();
        if (rules.placeFor(ArmorContent.stateOf(vest)) == ArmorRules.NO_ROOM) {
            answer(player, ArmorPackets.Result.FULL, plates, 0, "blockland.armor.full");
            return;
        }
        inserting.put(player.getUUID(), new Insertion(insert.slot(), server.getTickCount() + rules.insertTicks()));
        answer(player, ArmorPackets.Result.STARTED, plates, rules.insertSeconds, null);
    }

    /** Every server tick: insertions whose time is up go in (or are dropped when the plate or carrier is gone). */
    public void tick() {
        if (inserting.isEmpty()) return;
        int now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, Insertion>> it = inserting.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Insertion> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Insertion insertion = e.getValue();
            if (player == null) {
                it.remove();
                continue;
            }
            ItemStack held = player.getMainHandItem();
            ItemStack vest = ArmorContent.wornVest(player);
            ArmorRules rules = ArmorRules.get();
            boolean holding = player.isAlive() && !player.isSpectator() && player.getInventory().getSelectedSlot() == insertion.slot()
                    && ArmorContent.isPlate(held);
            if (!holding || vest.isEmpty() || rules.placeFor(ArmorContent.stateOf(vest)) == ArmorRules.NO_ROOM) {
                it.remove();
                answer(player, ArmorPackets.Result.CANCELLED, ArmorContent.stateOf(vest).count(), 0, null);
                continue;
            }
            if (now < insertion.doneAt()) continue;
            it.remove();
            // a worn plate is swapped for the fresh one, else it goes into a free slot
            VestState before = ArmorContent.stateOf(vest);
            boolean swapped = rules.placeFor(before) >= 0;
            VestState state = rules.withPlate(before);
            vest.set(ArmorContent.VEST_STATE.get(), state);
            if (!player.getAbilities().instabuild) held.shrink(1);
            player.level().playSound(null, player.getX(), player.getY() + 1.1, player.getZ(), ArmorContent.sound("armor.plate_insert"),
                    SoundSource.PLAYERS, 0.9f, 0.95f + player.getRandom().nextFloat() * 0.1f);
            answer(player, ArmorPackets.Result.DONE, state.count(), 0, null);
            player.displayClientMessage(Component.translatable(swapped ? "blockland.armor.replaced" : "blockland.armor.inserted",
                    state.count(), rules.maxPlates), true);
        }
    }

    private static void answer(ServerPlayer player, ArmorPackets.Result result, int plates, float seconds, String message) {
        BLNetwork.sendTo(player, new ArmorPackets.S2CInsert(result, plates, seconds));
        if (message != null) player.displayClientMessage(Component.translatable(message), true);
    }

    public void forget(ServerPlayer player) {
        inserting.remove(player.getUUID());
    }

    public void clear() {
        inserting.clear();
    }
}
