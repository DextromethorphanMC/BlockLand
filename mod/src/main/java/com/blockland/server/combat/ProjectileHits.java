package com.blockland.server.combat;

import com.blockland.BlockLand;
import com.blockland.server.util.PlayerUtil;
import com.google.gson.Gson;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Knockback for vanilla projectiles that do nothing to players (snowballs, eggs, fishing bobbers deal 0 damage, which
 * vanilla's player hurt ignores whole), as listed in {@code data/blockland/combat/projectile_hits.json}. Whether a hit
 * counts is the caller's: the mode must turn it on and its damage rules must let the hit through. A hit deals no damage:
 * it pushes the victim away along the projectile's flight, shows the hurt flash and makes the thrower the last attacker,
 * so a fall into the void is their kill.
 */
public final class ProjectileHits {
    private static final String RESOURCE = "/data/blockland/combat/projectile_hits.json";

    private final Data data = load();
    private final BiPredicate<ServerPlayer, DamageSource> counts;

    /** @param counts whether a hit on this player with this (thrown) source counts in their match */
    public ProjectileHits(BiPredicate<ServerPlayer, DamageSource> counts) {
        this.counts = counts;
    }

    /** A projectile hit something (server side, before the projectile's own impact logic). */
    public void onImpact(Projectile projectile, HitResult hit) {
        if (!(hit instanceof EntityHitResult entityHit) || !(entityHit.getEntity() instanceof ServerPlayer victim)) return;
        if (!(projectile.level() instanceof ServerLevel level) || victim.isPassenger()) return;
        Entry entry = data.projectiles.get(BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString());
        if (entry == null || entry.knockback <= 0 || projectile.getOwner() == victim) return;
        DamageSource source = level.damageSources().thrown(projectile, projectile.getOwner());
        if (!counts.test(victim, source)) return;

        Vec3 flight = projectile.getDeltaMovement();
        if (flight.horizontalDistanceSqr() < 1e-6) flight = victim.position().subtract(projectile.position());
        if (flight.horizontalDistanceSqr() < 1e-6) return;
        victim.knockback(entry.knockback, -flight.x, -flight.z);
        victim.hurtMarked = true;
        level.broadcastDamageEvent(victim, source);
        if (projectile.getOwner() instanceof ServerPlayer thrower) {
            victim.setLastHurtByPlayer(thrower, data.memorySeconds * 20);
            PlayerUtil.sound(thrower, SoundEvents.ARROW_HIT_PLAYER, 0.6f, 1f);
        }
    }

    private static Data load() {
        try (InputStream in = ProjectileHits.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Data data = new Gson().fromJson(reader, Data.class);
                if (data == null) data = new Data();
                if (data.projectiles == null) data.projectiles = new HashMap<>();
                return data;
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the projectile hits", e);
            return new Data();
        }
    }

    /** projectile_hits.json (read by Gson; field names = JSON keys). */
    private static final class Data {
        int memorySeconds = 5;
        Map<String, Entry> projectiles = new HashMap<>();
    }

    private static final class Entry {
        double knockback;
    }
}
