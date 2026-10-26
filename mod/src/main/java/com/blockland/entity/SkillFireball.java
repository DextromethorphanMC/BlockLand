package com.blockland.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * A vanilla ghast fireball — the same look, flight (speeding up as it goes) and redirecting with a punch — whose landing
 * is decided by whoever fired it ({@link Impact}): it never does vanilla's explosion or direct hit, so it breaks no
 * blocks and hurts only whom the impact says. Without an impact (e.g. summoned, or reloaded from a save) it puffs out
 * harmlessly where it lands. It fizzles out after {@link #setLifetime its lifetime}. Server authoritative.
 */
public class SkillFireball extends LargeFireball {
    /** What happens where a fireball lands (server side). */
    public interface Impact {
        /** Whether the fireball stops at {@code target} (false = it flies through, e.g. the owner's allies). */
        boolean canHit(SkillFireball ball, Entity target);

        /** It landed at {@code at} (in front of the block face it struck, or its centre in the creature it hit). */
        void land(SkillFireball ball, Vec3 at, @Nullable Entity hit);
    }

    @Nullable
    private Impact impact;
    private int lifetime = 200;

    public SkillFireball(EntityType<? extends SkillFireball> type, Level level) {
        super(type, level);
    }

    public void setImpact(@Nullable Impact impact) {
        this.impact = impact;
    }

    /** Ticks it flies before fizzling out. */
    public void setLifetime(int ticks) {
        lifetime = Math.max(1, ticks);
    }

    @Override
    public void tick() {
        super.tick();
        if (isAlive() && level() instanceof ServerLevel server && tickCount > lifetime) fizzle(server);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && (impact == null || impact.canHit(this, target));
    }

    @Override
    protected void onHit(HitResult hit) {
        if (!(level() instanceof ServerLevel server) || !isAlive()) return;
        if (impact == null) {
            fizzle(server);
            return;
        }
        Vec3 at;
        Entity target = null;
        if (hit instanceof BlockHitResult block) {
            at = block.getLocation().add(block.getDirection().getUnitVec3().scale(0.3));
        } else {
            at = getBoundingBox().getCenter();
            if (hit instanceof EntityHitResult entity) target = entity.getEntity();
        }
        discard();
        impact.land(this, at, target);
    }

    private void fizzle(ServerLevel server) {
        Vec3 c = getBoundingBox().getCenter();
        server.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 8, 0.2, 0.2, 0.2, 0.02);
        discard();
    }
}
