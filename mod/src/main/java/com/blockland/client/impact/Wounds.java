package com.blockland.client.impact;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Wounds that go on bleeding a moment (rendering spec §12, blood): a living target hit by a round or a blade lets drops
 * fall from the wound over the next seconds ({@code blood.bleed}, {@code bleedRate}), fewer and fewer, moving with it —
 * a short trail of stains where it runs; under water the wound seeps small plumes instead. The wound sits on the body in
 * the body's own frame (turned with its yaw), so the drops come from where it was hit however the target turns.
 */
final class Wounds {
    /** Wounds bleeding at once; the oldest stops when a new one opens. */
    private static final int MAX = 48;

    private static final class Wound {
        int entity;
        /** Where on the body, in its frame: across, up, along its yaw (blocks from its feet). */
        double across, up, along;
        int ticks, total;
        /** Drops per tick at the start, and the drops owed (less than one a tick). */
        float rate, owed, size;
        int rgb;
        ImpactData.Blood blood;
        float amount;
    }

    private final List<Wound> wounds = new ArrayList<>();

    /** A wound at {@code at} on {@code target}: it bleeds {@code amount} (the blood setting × a head hit's more) of the data's. */
    void open(Entity target, Vec3 at, ImpactData.Blood blood, int rgb, float amount) {
        if (blood.bleed <= 0 || blood.bleedRate <= 0 || amount <= 0) return;
        Vec3 offset = at.subtract(target.position());
        double rad = Math.toRadians(yaw(target)), sin = Math.sin(rad), cos = Math.cos(rad);
        Wound w = new Wound();
        w.entity = target.getId();
        w.across = offset.x * cos + offset.z * sin;
        w.up = offset.y;
        w.along = -offset.x * sin + offset.z * cos;
        w.total = w.ticks = Math.max(1, Math.round(blood.bleed * 20 * Math.min(1.5f, amount)));
        w.rate = blood.bleedRate / 20 * Math.min(2, amount);
        w.size = 0.8f + 0.2f * Math.min(2, amount);
        w.rgb = rgb;
        w.blood = blood;
        w.amount = amount;
        if (wounds.size() >= MAX) wounds.remove(0);
        wounds.add(w);
    }

    /** One tick: every open wound lets its drops fall (or seep into the water round it). */
    void tick(ClientLevel level, ImpactParticles pool, int budget, float stains, boolean inWater,
              @Nullable TextureAtlasSprite plain, RandomSource random) {
        for (Iterator<Wound> it = wounds.iterator(); it.hasNext(); ) {
            Wound w = it.next();
            Entity target = level.getEntity(w.entity);
            if (target instanceof LivingEntity living && living.isDeadOrDying() && !target.isSpectator()) {
                // it died of it: what is left runs out where it lies, into a pool (rendering spec §19)
                bleedOut(level, pool, w, target, budget, stains, inWater, plain, random);
                it.remove();
                continue;
            }
            if (target == null || !target.isAlive() || target.isSpectator() || --w.ticks <= 0) {
                it.remove();
                continue;
            }
            // fewer and fewer as it closes
            float left = w.ticks / (float) w.total;
            w.owed += w.rate * left * left;
            if (w.owed < 1) continue;
            double rad = Math.toRadians(yaw(target)), sin = Math.sin(rad), cos = Math.cos(rad);
            Vec3 at = target.position().add(w.across * cos - w.along * sin, w.up, w.across * sin + w.along * cos);
            Vec3 motion = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
            BlockPos pos = BlockPos.containing(at);
            int light = LevelRenderer.getLightColor(level, pos);
            boolean wet = level.getFluidState(pos).is(FluidTags.WATER);
            pool.source(w.entity);
            while (w.owed >= 1) {
                w.owed -= 1;
                if (wet) {
                    if (inWater) ImpactSpawner.seep(pool, at, w.blood, light, random);
                } else {
                    ImpactSpawner.bleed(pool, at, motion, w.blood, w.rgb, light, w.size, stains, budget, plain, random);
                }
            }
            pool.source(-1);
        }
    }

    /** A wounded body that died: its data's {@code deathPool} drops fall out of it close to the ground where it lies. */
    private static void bleedOut(ClientLevel level, ImpactParticles pool, Wound w, Entity target, int budget, float stains,
                                 boolean inWater, @Nullable TextureAtlasSprite plain, RandomSource random) {
        int count = Math.round(w.blood.deathPool * Math.min(1.5f, Math.max(0.5f, w.amount)));
        if (count <= 0) return;
        Vec3 base = target.position();
        BlockPos pos = BlockPos.containing(base.x, base.y + 0.3, base.z);
        int light = LevelRenderer.getLightColor(level, pos);
        boolean wet = level.getFluidState(pos).is(FluidTags.WATER);
        pool.source(w.entity);
        for (int i = 0; i < count; i++) {
            Vec3 at = base.add((random.nextFloat() - 0.5) * 0.35, 0.25 + random.nextFloat() * 0.25, (random.nextFloat() - 0.5) * 0.35);
            if (wet) {
                if (inWater) ImpactSpawner.seep(pool, at, w.blood, light, random);
            } else {
                ImpactSpawner.bleed(pool, at, Vec3.ZERO, w.blood, w.rgb, light, w.size * 1.4f, stains, budget, plain, random);
            }
        }
        pool.source(-1);
    }

    void clear() {
        wounds.clear();
    }

    boolean isEmpty() {
        return wounds.isEmpty();
    }

    private static float yaw(Entity target) {
        return target instanceof LivingEntity living ? living.yBodyRot : target.getYRot();
    }
}
