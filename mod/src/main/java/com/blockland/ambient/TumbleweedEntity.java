package com.blockland.ambient;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * A tumbleweed (风滚草, rendering spec §29): a client-only ambient prop. The client's ambient module spawns it into the
 * client's level, moves it every tick ({@link #ticker}: wind, rolling, bounces, getting caught) and takes it away; the
 * server never has one (one that turns up there is removed at once). Never saved, never hit by anything, never pushed,
 * never in anyone's way — it only collides with blocks. Its fields are the state the client's physics and renderer
 * share; its box is its ball's size ({@link #radius}).
 */
public class TumbleweedEntity extends Entity {
    /** One tick of the client's physics. */
    @FunctionalInterface
    public interface Ticker {
        void tick(TumbleweedEntity weed);
    }

    /** Set by the client's ambient module; without it a tumbleweed removes itself. */
    @Nullable
    public static Ticker ticker;

    /** The ball's radius (blocks) and which model it uses. */
    public float radius = 0.4f;
    public int variant;
    /** Its turn (model → world) now and a tick ago, and how fast it spins (world axis × radians per tick). */
    public final Quaternionf rotation = new Quaternionf(), rotationO = new Quaternionf();
    public final Vector3f spin = new Vector3f();
    /** How far it has grown in (0 … 1) now and a tick ago: it grows in when it comes and shrinks away when it goes. */
    public float fade, fadeO;
    /** On its way out (shrinking away). */
    public boolean leaving;
    /** Ticks it has lived and may live; ticks it stays caught on a plant or in a corner. */
    public int age, life = 1200, caught;

    public TumbleweedEntity(EntityType<? extends TumbleweedEntity> type, Level level) {
        super(type, level);
    }

    /** Sets its size (its box follows). */
    public void setRadius(float r) {
        radius = r;
        refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(radius * 1.6f, radius * 1.5f);
    }

    @Override
    public void tick() {
        if (!level().isClientSide || ticker == null) {
            discard();
            return;
        }
        rotationO.set(rotation);
        fadeO = fade;
        age++;
        ticker.tick(this);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity entity) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    protected MovementEmission getMovementEmission() {
        return MovementEmission.NONE;
    }

    @Override
    public float maxUpStep() {
        return Math.min(0.6f, radius);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96 * 96;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }
}
