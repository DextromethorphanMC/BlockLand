package com.blockland.server.combat;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.DamagePackets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Where each hurt a player takes came from (UI spec §3.5): once a hurt has really taken something off them — health
 * (absorption hearts included) or the body armour's plates — the player is told where it came from, for the HUD's damage
 * direction arrows. A hurt is followed through Forge's two events: the hurt event, where the armour soaks its share (what
 * it took is told to {@link #soaked} by whoever wires the armour in; this class does not know the armour), and the damage
 * event, where what reaches the health is settled. A hurt the game's rules or a vehicle's hull turn away never gets
 * that far and shows nothing; so does one with nowhere to point at (a fall, drowning, burning).
 */
public final class DamageDirections {
    private record Pending(float plates, float absorption) {
    }

    /** What the plates soaked of the hurt now going on, per victim (only ever for the length of one hurt). */
    private final Map<LivingEntity, Float> soaked = new IdentityHashMap<>();
    /** Hurts that got through the hurt event and wait for the damage event: the plates' share, the absorption before. */
    private final Map<LivingEntity, Pending> pending = new IdentityHashMap<>();

    /** The body armour soaked {@code amount} of the hurt {@code victim} is taking now. */
    public void soaked(LivingEntity victim, float amount) {
        if (amount > 0 && victim instanceof ServerPlayer) soaked.merge(victim, amount, Float::sum);
    }

    /**
     * After every listener of a hurt event, cancelled or not: {@code amount} goes on to the health (0 when the hurt was
     * stopped). A hurt stopped here may still have worn the plates down.
     */
    public void afterHurt(LivingEntity victim, DamageSource source, float amount) {
        if (!(victim instanceof ServerPlayer player)) return;
        Float plates = soaked.remove(victim);
        float p = plates == null ? 0 : plates;
        BlockLand.LOGGER.debug("[BlockLand] Damage arrows: {} hurt by {} — {} through, {} to the plates",
                player.getScoreboardName(), source.getMsgId(), amount, p);
        if (amount > 0) {
            pending.put(victim, new Pending(p, victim.getAbsorptionAmount()));
            return;
        }
        pending.remove(victim);
        send(player, source, 0, p);
    }

    /** After every other listener of a damage event: {@code amount} comes off the health now. */
    public void afterDamage(LivingEntity victim, DamageSource source, float amount) {
        Pending p = pending.remove(victim);
        if (victim instanceof ServerPlayer) {
            BlockLand.LOGGER.debug("[BlockLand] Damage arrows: {} damaged {} ({})", victim.getScoreboardName(), amount,
                    p == null ? "no hurt waiting" : "after its hurt");
        }
        if (p == null || !(victim instanceof ServerPlayer player)) return;
        // vanilla has already taken the absorption hearts' share before this event
        float absorbed = Math.max(0, p.absorption() - victim.getAbsorptionAmount());
        send(player, source, Math.max(0, amount) + absorbed, p.plates());
    }

    private static void send(ServerPlayer player, DamageSource source, float health, float plates) {
        if (health + plates <= 1e-4f) return;
        Entity cause = source.getEntity(), direct = source.getDirectEntity();
        Vec3 at;
        int follow = 0;
        if (source.sourcePositionRaw() != null) {
            at = source.sourcePositionRaw();
        } else if (source.is(DamageTypeTags.IS_EXPLOSION) && direct != null && direct != cause && direct != player) {
            // a grenade or a shell: the blast, not whoever fired it
            at = direct.position();
        } else if (cause != null && cause != player) {
            at = cause.getEyePosition();
            follow = cause.getId();
        } else if (direct != null && direct != player) {
            at = direct instanceof LivingEntity ? direct.getEyePosition() : direct.position();
            follow = direct.getId();
        } else {
            BlockLand.LOGGER.debug("[BlockLand] Damage arrows: {} hurt by {} — nowhere to point at", player.getScoreboardName(), source.getMsgId());
            return;
        }
        BLNetwork.sendTo(player, new DamagePackets.S2CDamage(follow, at.x, at.y, at.z, health, plates));
        BlockLand.LOGGER.debug("[BlockLand] Damage arrows: told {} of {} health, {} plates from {} (follow {})",
                player.getScoreboardName(), health, plates, at, follow);
    }
}
