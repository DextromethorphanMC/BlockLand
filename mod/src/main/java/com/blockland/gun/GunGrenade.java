package com.blockland.gun;

import com.blockland.BlockLand;
import com.blockland.combat.HitRecords;
import com.blockland.common.Sounds;
import com.blockland.equipment.effect.EquipmentEffects;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * A grenade launched by a gun whose ammunition is a {@link GunData.Projectile} (the hand cannon's 40 mm round): it flies
 * and falls (vanilla's throwable flight; its gravity is synced so the clients predict the same arc), trails a little
 * smoke, and where it strikes — once past its arming distance — it goes off as the frag grenade's blast
 * ({@link EquipmentEffects}, the equipment module's public API; each game's damage rules apply as for any blast),
 * credited to the shooter and their gun. Short of the arming distance it only strikes: what it hits takes the round's
 * {@code damage} and the dud drops. It goes off on its own after its life. Not saved with the world. Server
 * authoritative; the client draws it and, through {@link #setClientHooks}, the explosion.
 */
public class GunGrenade extends ThrowableItemProjectile {
    /** Entity event sent when it goes off (the client's explosion). */
    public static final byte EXPLODED = 60;

    private static final EntityDataAccessor<Float> DATA_GRAVITY = SynchedEntityData.defineId(GunGrenade.class, EntityDataSerializers.FLOAT);
    private static final String DUD_SOUND = "gun.handcannon.dud";

    /** What the client does when one goes off (set by the client's gun module; nothing on a server). */
    private static volatile Consumer<GunGrenade> exploded = g -> { };

    private float armDistanceSqr;
    private int lifeTicks = 160;
    private JsonObject blast = new JsonObject();
    private float impactDamage;
    private String weapon = "";
    @Nullable
    private Vec3 muzzle;

    public GunGrenade(EntityType<? extends GunGrenade> type, Level level) {
        super(type, level);
    }

    public static void setClientHooks(@Nullable Consumer<GunGrenade> onExploded) {
        exploded = onExploded == null ? g -> { } : onExploded;
    }

    /**
     * Server: a fresh round leaving {@code from} along {@code dir} (unit), fired by {@code shooter} with {@code gun}
     * loading {@code ammo}; add it to the level afterwards.
     */
    public void launch(ServerPlayer shooter, GunData.Gun gun, GunData.Ammo ammo, Vec3 from, Vec3 dir, ItemStack round) {
        GunData.Projectile p = ammo.projectile;
        setOwner(shooter);
        setItem(round.copyWithCount(1));
        setPos(from);
        muzzle = from;
        entityData.set(DATA_GRAVITY, Math.max(0, p.gravity) / 400f);
        armDistanceSqr = p.armDistance * p.armDistance;
        lifeTicks = Math.max(1, Math.round(p.life * 20));
        blast = p.blast == null ? new JsonObject() : p.blast.deepCopy();
        impactDamage = ammo.damage;
        weapon = BlockLand.id(gun.id).toString();
        // the shooter's own motion carries over, as it does for anything thrown
        Vec3 velocity = dir.scale(p.speed / 20f).add(shooter.getDeltaMovement().multiply(1, shooter.onGround() ? 0 : 1, 1));
        setDeltaMovement(velocity);
        updateRotation();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_GRAVITY, 0.0245f);
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
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            // a thin smoke trail behind the round
            Vec3 v = getDeltaMovement();
            for (int i = 0; i < 2; i++) {
                double f = i * 0.5;
                level().addParticle(ParticleTypes.SMOKE, getX() - v.x * f, getY() - v.y * f, getZ() - v.z * f, 0, 0.01, 0);
            }
            return;
        }
        if (isAlive() && tickCount >= lifeTicks && level() instanceof ServerLevel server) explode(server, position());
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (!(level() instanceof ServerLevel server)) return;
        if (armed()) {
            explode(server, hit.getLocation());
            return;
        }
        // a dud strikes like a thrown stone
        if (hit.getEntity() instanceof LivingEntity living && impactDamage > 0) {
            Entity owner = getOwner();
            if (owner instanceof LivingEntity shooter) HitRecords.record(living, shooter, weapon, false);
            living.invulnerableTime = 0;
            DamageSource source = new DamageSource(server.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(GunContent.BULLET),
                    this, owner);
            living.hurtServer(server, source, impactDamage);
        }
        dud(server);
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if (!(level() instanceof ServerLevel server)) return;
        if (armed()) explode(server, hit.getLocation());
        else dud(server);
    }

    /** Far enough from the muzzle for the fuze to have armed (a round without a known muzzle is armed). */
    private boolean armed() {
        return muzzle == null || position().distanceToSqr(muzzle) >= armDistanceSqr;
    }

    /** Goes off at {@code at} (backed off the struck surface along the flight, so the blast sees the room it is in). */
    private void explode(ServerLevel level, Vec3 at) {
        Vec3 v = getDeltaMovement();
        Vec3 back = v.lengthSqr() > 1e-6 ? v.normalize().scale(-0.2) : Vec3.ZERO;
        setPos(at.add(back).subtract(0, 0.3, 0));   // the frag blast centres 0.3 above its entity
        setDeltaMovement(Vec3.ZERO);
        level.broadcastEntityEvent(this, EXPLODED);
        JsonObject params = blast.deepCopy();
        params.addProperty("weapon", weapon);
        Entity owner = getOwner();
        EquipmentEffects.detonate(level, this, "frag", params, owner instanceof LivingEntity living ? living : null);
        discard();
    }

    /** Struck too close to arm: a clunk, and it drops dead. */
    private void dud(ServerLevel level) {
        ResourceLocation sound = BlockLand.id(DUD_SOUND);
        level.playSound(null, getX(), getY(), getZ(), Sounds.byId(sound), SoundSource.PLAYERS, 1.0f, 0.9f + level.random.nextFloat() * 0.2f);
        discard();
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EXPLODED) {
            exploded.accept(this);
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
        return distance < 160 * 160;
    }
}
