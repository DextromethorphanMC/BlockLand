package com.blockland.server.melee;

import com.blockland.server.anticheat.api.AntiCheatSignals;
import com.blockland.server.anticheat.api.Suspicion;
import com.blockland.server.spectate.Watchers;
import com.blockland.network.packet.ViewmodelPackets;
import com.blockland.BlockLand;
import com.blockland.combat.HitRecords;
import com.blockland.effect.ActionLocks;
import com.blockland.melee.MeleeContent;
import com.blockland.melee.MeleeData;
import com.blockland.melee.MeleeTrace;
import com.blockland.movement.action.BodyActions;
import com.blockland.network.BLNetwork;
import com.blockland.network.MeleePacketListeners;
import com.blockland.network.packet.MeleePackets;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server authority for knives: validates every attack (selected slot, knife held, cooldown), lets the blade connect
 * after the attack's wind-up (the {@code impact} time in knives.json), traces it with the shared {@link MeleeTrace},
 * applies damage (backstabs hit harder) and tells the attacker and everyone watching. Friendly fire and lobby
 * protection stay with the existing damage rules.
 */
public final class MeleeService implements MeleePacketListeners.Server {
    /** Attacks may arrive this much early (client clock, packets batched per tick). */
    private static final long EARLY_NANOS = 90_000_000L;

    private final MinecraftServer server;
    private final Map<UUID, Fighter> fighters = new HashMap<>();

    private static final class Fighter {
        long readyAt;
        @Nullable
        Strike strike;
    }

    /** An attack whose blade connects at {@code impactTick}. */
    private record Strike(int slot, String knifeId, boolean heavy, int combo, int impactTick) {
    }

    public MeleeService(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onAttack(ServerPlayer player, MeleePackets.C2SAttack attack) {
        if (!player.isAlive() || player.isSpectator() || attack.slot() != player.getInventory().getSelectedSlot()) return;
        if (ActionLocks.isLocked(player)) return;
        MeleeData.Knife knife = MeleeContent.knifeOf(player.getMainHandItem()).orElse(null);
        if (knife == null) return;
        Fighter fighter = fighters.computeIfAbsent(player.getUUID(), id -> new Fighter());
        long now = System.nanoTime();
        if (fighter.strike != null || now < fighter.readyAt - EARLY_NANOS) {
            // the client times the cooldown itself: an attack well before it is over did not come from its timing
            if (now < fighter.readyAt - 2 * EARLY_NANOS) {
                Suspicion.report(player, AntiCheatSignals.MELEE_RATE, 1, () -> String.format(Locale.ROOT, "%s 冷却还剩 %.0f 毫秒时出刀",
                        knife.id, (fighter.readyAt - now) / 1e6));
            }
            return;
        }
        MeleeData.Attack data = knife.attack(attack.heavy());
        fighter.readyAt = now + (long) (data.cooldown * 1e9);
        int ticks = Math.max(1, Math.round(data.impact * 20));
        fighter.strike = new Strike(attack.slot(), knife.id, attack.heavy(), attack.combo(), server.getTickCount() + ticks);
        // third person: everyone else sees the arm swing and hears the blade; spectators in its eyes see the swing itself
        player.swing(InteractionHand.MAIN_HAND, true);
        Watchers.action(player, ViewmodelPackets.Kind.MELEE, attack.heavy() ? 1 : 0, attack.combo());
        play(player.level(), player, player.getEyePosition(), data.swingSound, attack.heavy() ? 0.9F : 0.7F);
    }

    public void tick() {
        if (fighters.isEmpty()) return;
        Iterator<Map.Entry<UUID, Fighter>> it = fighters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Fighter> entry = it.next();
            Strike strike = entry.getValue().strike;
            if (strike == null || server.getTickCount() < strike.impactTick) continue;
            entry.getValue().strike = null;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            strike(player, strike);
        }
    }

    private void strike(ServerPlayer player, Strike strike) {
        MeleeData.Knife knife = MeleeContent.knifeOf(player.getMainHandItem()).orElse(null);
        // switching away or dying during the wind-up cancels the attack
        if (knife == null || !knife.id.equals(strike.knifeId) || !player.isAlive() || player.isSpectator()
                || player.getInventory().getSelectedSlot() != strike.slot) return;
        MeleeData.Attack attack = knife.attack(strike.heavy);
        ServerLevel level = player.level();
        MeleeTrace.Hit hit = MeleeTrace.trace(level, player, BodyActions.eyePosition(player), player.getLookAngle(), attack);
        switch (hit.type()) {
            case ENTITY -> {
                damage(level, player, knife, attack, hit.entity());
                play(level, player, hit.point(), knife.sounds.hitFlesh, 1.0F);
                BLNetwork.sendToTracking(player, new MeleePackets.S2CImpact(player.getId(), strike.heavy, strike.combo,
                        MeleePackets.Target.ENTITY, hit.entity().getId(), hit.point().x, hit.point().y, hit.point().z, -1));
            }
            case BLOCK -> {
                BlockState state = level.getBlockState(hit.pos());
                play(level, player, hit.point(), knife.sounds.hitBlock, 0.8F);
                level.playSound(player, hit.pos(), state.getSoundType(level, hit.pos(), player).getHitSound(), SoundSource.BLOCKS, 0.5F, 0.8F);
                BLNetwork.sendToTracking(player, new MeleePackets.S2CImpact(player.getId(), strike.heavy, strike.combo,
                        MeleePackets.Target.BLOCK, -1, hit.point().x, hit.point().y, hit.point().z, hit.face().ordinal()));
            }
            case MISS -> { }
        }
    }

    private void damage(ServerLevel level, ServerPlayer attacker, MeleeData.Knife knife, MeleeData.Attack attack, Entity target) {
        boolean backstab = attack.backstabMultiplier > 1 && MeleeTrace.isBehind(attacker, target);
        float amount = attack.damage * (backstab ? attack.backstabMultiplier : 1);
        DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(MeleeContent.KNIFE),
                attacker, attacker);
        // the attack cooldown is the knife's own; vanilla's hurt cooldown would swallow fast combos
        if (target instanceof LivingEntity living) {
            living.invulnerableTime = 0;
            HitRecords.record(living, attacker, BlockLand.id(knife.id).toString(), backstab);
        }
        boolean wasSpectator = target.isSpectator();
        if (!target.hurtServer(level, source, amount)) return;
        if (target instanceof LivingEntity living && attack.knockback > 0) {
            living.knockback(attack.knockback, attacker.getX() - target.getX(), attacker.getZ() - target.getZ());
        }
        // minigames cancel player deaths and turn the victim into a spectator instead
        boolean killed = !target.isAlive() || target instanceof LivingEntity living && living.isDeadOrDying()
                || !wasSpectator && target.isSpectator();
        MeleePackets.HitKind kind = killed ? (backstab ? MeleePackets.HitKind.BACKSTAB_KILL : MeleePackets.HitKind.KILL)
                : backstab ? MeleePackets.HitKind.BACKSTAB : MeleePackets.HitKind.BODY;
        BLNetwork.sendTo(attacker, new MeleePackets.S2CHit(kind, amount));
        if (backstab) BlockLand.LOGGER.debug("[BlockLand] Backstab: {} -> {} ({})", attacker.getGameProfile().getName(), target.getName().getString(), amount);
    }

    /** A sound for everyone near {@code at} except the attacker, who plays it locally without delay. */
    private static void play(ServerLevel level, ServerPlayer attacker, Vec3 at, String soundId, float volume) {
        SoundEvent sound = MeleeContent.sound(soundId);
        if (sound != null) {
            level.playSound(attacker, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, 0.95F + level.random.nextFloat() * 0.1F);
        }
    }

    public void forget(ServerPlayer player) {
        fighters.remove(player.getUUID());
    }

    public void clear() {
        fighters.clear();
    }
}
