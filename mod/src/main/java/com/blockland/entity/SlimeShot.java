package com.blockland.entity;

import com.blockland.BlockLand;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * A glob of slime: flies in a slight arc, splats on whatever it hits and covers a hit creature in its payload effect
 * (the Slime Licker's 粘液缠身). Never hits its owner or the owner's allies. Server authoritative like any projectile.
 */
public class SlimeShot extends ThrowableItemProjectile implements EffectPayload {
    private ResourceLocation effect = BlockLand.id("slimed");
    private int ticks = 60;
    private int amplifier;

    public SlimeShot(EntityType<? extends SlimeShot> type, Level level) {
        super(type, level);
    }

    @Override
    public void setPayload(ResourceLocation effect, int ticks, int amplifier) {
        this.effect = effect;
        this.ticks = ticks;
        this.amplifier = amplifier;
    }

    @Override
    protected Item getDefaultItem() {
        return Items.SLIME_BALL;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.02;
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        Entity owner = getOwner();
        return super.canHitEntity(target) && (owner == null || target != owner && !owner.isAlliedTo(target));
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (level() instanceof ServerLevel && hit.getEntity() instanceof LivingEntity living) {
            BuiltInRegistries.MOB_EFFECT.get(effect).ifPresent(holder ->
                    living.addEffect(new MobEffectInstance(holder, ticks, amplifier, false, true, true), getOwner()));
            level().playSound(null, living.getX(), living.getEyeY(), living.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 0.8F);
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (level() instanceof ServerLevel server) {
            server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.SLIME_BALL)), getX(), getY(), getZ(), 14,
                    0.15, 0.15, 0.15, 0.08);
            server.playSound(null, getX(), getY(), getZ(), SoundEvents.SLIME_BLOCK_BREAK, SoundSource.PLAYERS, 0.8F, 1.2F);
            discard();
        }
    }
}
