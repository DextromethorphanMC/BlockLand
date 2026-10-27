package com.blockland.vehicle;

import com.blockland.BlockLand;
import com.blockland.combat.HitRecords;
import com.blockland.combat.RoundTraits;
import com.blockland.equipment.effect.EquipmentEffects;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * A vehicle cannon's shell (vehicle spec §6): flies and falls (its gravity synced so clients draw the same arc), strikes
 * what it meets first — never its own vehicle or its riders — and goes off: what it struck directly takes the weapon's
 * damage (piercing armour), then the blast of the weapon's {@code explosion} hurts bodies (the frag grenade's blast, the
 * equipment module's public API) and, by its {@code hullDamage}, the hulls round it ({@code combat/Blasts}). Credited to the gunner and the vehicle. Server
 * authoritative; the client draws the glowing round and, through {@link Vehicles.Client#shellExploded}, the explosion.
 */
public class VehicleShell extends ThrowableItemProjectile {
    public static final byte EXPLODED = 60;
    public static final ResourceKey<DamageType> SHELL = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("vehicle_shell"));
    /** How much more a shell's direct hit wears armour than a rifle round (RoundTraits.armorDamage). */
    private static final float PIERCE = 4;
    private static final int LIFE = 200;

    private static final EntityDataAccessor<Float> DATA_GRAVITY = SynchedEntityData.defineId(VehicleShell.class, EntityDataSerializers.FLOAT);

    private VehicleData.Explosion explosion = new VehicleData.Explosion();
    private float damage;
    private int vehicleId = -1;
    private String weapon = "";

    public VehicleShell(EntityType<? extends VehicleShell> type, Level level) {
        super(type, level);
    }

    /** Server: a fresh shell from {@code vehicle}'s weapon, leaving {@code from} along {@code dir}; add it afterwards. */
    public void launch(VehicleEntity vehicle, VehicleData.Weapon w, @Nullable LivingEntity gunner, Vec3 from, Vec3 dir) {
        setOwner(gunner);
        setPos(from);
        vehicleId = vehicle.getId();
        weapon = BlockLand.id(vehicle.data.id).toString();
        damage = w.damage;
        explosion = w.explosion != null ? w.explosion : new VehicleData.Explosion();
        entityData.set(DATA_GRAVITY, Math.max(0, w.shellGravity) / 400f);
        setDeltaMovement(dir.scale(w.shellSpeed / 20f).add(vehicle.getDeltaMovement()));
        updateRotation();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_GRAVITY, 0.015f);
    }

    @Override
    protected double getDefaultGravity() {
        return entityData.get(DATA_GRAVITY);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.FIRE_CHARGE;
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (target.getId() == vehicleId) return false;
        Entity vehicle = level().getEntity(vehicleId);
        if (vehicle != null && vehicle.hasIndirectPassenger(target)) return false;
        return super.canHitEntity(target);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && isAlive() && tickCount >= LIFE && level() instanceof ServerLevel server) explode(server, position(), null);
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (level() instanceof ServerLevel server) explode(server, hit.getLocation(), hit.getEntity());
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if (level() instanceof ServerLevel server) explode(server, hit.getLocation(), null);
    }

    private void explode(ServerLevel level, Vec3 at, @Nullable Entity struck) {
        LivingEntity owner = getOwner() instanceof LivingEntity living ? living : null;
        if (struck != null && damage > 0) {
            if (struck instanceof LivingEntity living) {
                HitRecords.record(living, owner, weapon, false);
                living.invulnerableTime = 0;
            }
            DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(SHELL), this, owner);
            RoundTraits.during(new RoundTraits.Traits("shell", PIERCE, 0), () -> struck.hurtServer(level, source, damage));
        }
        // the blast centres a little back along the flight, in the room the shell was in
        Vec3 v = getDeltaMovement();
        Vec3 back = v.lengthSqr() > 1e-6 ? v.normalize().scale(-0.3) : Vec3.ZERO;
        setPos(at.add(back).subtract(0, 0.3, 0));
        setDeltaMovement(Vec3.ZERO);
        level.broadcastEntityEvent(this, EXPLODED);
        JsonObject params = new JsonObject();
        params.addProperty("radius", explosion.radius);
        params.addProperty("innerRadius", explosion.innerRadius);
        params.addProperty("damage", explosion.damage);
        params.addProperty("minDamage", explosion.minDamage);
        params.addProperty("knockback", explosion.knockback);
        params.addProperty("hullDamage", explosion.hullDamage);
        params.addProperty("weapon", weapon);
        // bodies and, through combat/Blasts, vehicle hulls (by hullDamage)
        EquipmentEffects.detonate(level, this, "frag", params, owner);
        discard();
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EXPLODED) {
            Vehicles.client().shellExploded(this);
            return;
        }
        super.handleEntityEvent(id);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 256 * 256;
    }
}
