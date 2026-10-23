package com.blockland.equipment;

import com.blockland.combat.HitRecords;
import com.blockland.combat.HitShapes;
import com.blockland.equipment.effect.EquipmentEffects;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A thrown piece of equipment: a grenade, molotov or throwing knife in flight, at rest, stuck in a wall, or — once it
 * went off — an active area (smoke, fire) for as long as the effect lasts. Server authoritative: the server runs the
 * flight (gravity, drag, bounces with the item's {@code bounce} / {@code friction}), the fuse and the knife's hits; the
 * client only follows the synced position and phase, renders the item (with its 工匠 skin, carried by the synced stack)
 * and draws what the effects module adds. What happens when it goes off is {@link EquipmentEffects} (server, entity
 * event {@value #DETONATED} on the client), so this class knows nothing about blasts, smoke or fire.
 */
public class ThrownEquipment extends Entity {
    /** Entity event sent when the item goes off (the client calls the effects' {@code detonated}). */
    public static final byte DETONATED = 60;

    public enum Phase { FLYING, RESTING, ACTIVE, STUCK }

    private static final EntityDataAccessor<ItemStack> DATA_ITEM = SynchedEntityData.defineId(ThrownEquipment.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(ThrownEquipment.class, EntityDataSerializers.BYTE);

    /** Below this speed (blocks per tick) a bouncing item on the ground lies still. */
    private static final double REST_SPEED = 0.04;
    /** Ticks the owner cannot be hit by (or pick up) what they just threw. */
    private static final int OWNER_GRACE = 6;
    /** How far from its hit point the item stays off a surface (its radius). */
    private static final double SKIN = 0.08;
    /** Despawn time of an item that never goes off (a knife lying on the ground), ticks. */
    private static final int MAX_LIFE = 20 * 60;

    /** What the client module does with the effects (set by the client at start-up; no-ops on a server). */
    public interface ClientHooks {
        ClientHooks NONE = new ClientHooks() {
            @Override public void detonated(ThrownEquipment thrown, EquipmentData.Equipment item) { }
            @Override public void tick(ThrownEquipment thrown, EquipmentData.Equipment item, int activeTicks) { }
        };

        void detonated(ThrownEquipment thrown, EquipmentData.Equipment item);

        /** Every client tick; {@code activeTicks} is -1 until the item went off, then counts up. */
        void tick(ThrownEquipment thrown, EquipmentData.Equipment item, int activeTicks);
    }

    private static volatile ClientHooks clientHooks = ClientHooks.NONE;

    /**
     * Client: positions from the server are applied at the next tick (not on arrival, which would land after the old
     * position was taken and leave nothing to interpolate between frames).
     */
    private final InterpolationHandler interpolation = new InterpolationHandler(this, 1);

    @Nullable
    private UUID ownerId;
    /** Ticks until it goes off, -1 = no fuse (impact only, or never). */
    private int fuse = -1;
    /** Ticks the active area still lasts. */
    private int activeLeft;
    /** Client: ticks since it went off, -1 before. */
    private int activeTicks = -1;
    /** A knife that already struck someone (it only falls now). */
    private boolean spent;
    private int stuckTicks;
    /** Server: spin of the launch (degrees per tick), slowed by bounces; decides nothing but the sound of it. */
    private float spin;
    /** Client: the tumble angle the renderer turns the item by (degrees), this tick and the previous one. */
    private float tumble, tumbleO;

    public ThrownEquipment(EntityType<? extends ThrownEquipment> type, Level level) {
        super(type, level);
    }

    public static void setClientHooks(@Nullable ClientHooks hooks) {
        clientHooks = hooks == null ? ClientHooks.NONE : hooks;
    }

    /**
     * Sets up a freshly created entity before it is added to the level.
     *
     * @param fuseSeconds seconds left on the fuse (≤ 0 with a fuse: goes off at once), ignored without a fuse
     */
    public void launch(ItemStack stack, @Nullable LivingEntity owner, Vec3 position, Vec3 velocity, float fuseSeconds) {
        ItemStack one = stack.copyWithCount(1);
        entityData.set(DATA_ITEM, one);
        ownerId = owner == null ? null : owner.getUUID();
        setPos(position);
        xo = position.x;
        yo = position.y;
        zo = position.z;
        setDeltaMovement(velocity);
        EquipmentData.Equipment item = item();
        if (item != null && item.fuse > 0) fuse = Math.max(0, Math.round(fuseSeconds * 20));
        faceVelocity(velocity);
        if (owner != null) setYRot(owner.getYRot());
    }

    // ---- state ----------------------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM, ItemStack.EMPTY);
        builder.define(DATA_PHASE, (byte) Phase.FLYING.ordinal());
    }

    public ItemStack stack() {
        return entityData.get(DATA_ITEM);
    }

    @Nullable
    public EquipmentData.Equipment item() {
        return EquipmentContent.equipmentOf(stack()).orElse(null);
    }

    public Phase phase() {
        int index = entityData.get(DATA_PHASE);
        return index >= 0 && index < Phase.values().length ? Phase.values()[index] : Phase.FLYING;
    }

    private void setPhase(Phase phase) {
        entityData.set(DATA_PHASE, (byte) phase.ordinal());
    }

    /** Client: ticks since it went off, -1 before. */
    public int activeTicks() {
        return activeTicks;
    }

    @Nullable
    public LivingEntity owner() {
        if (ownerId == null || !(level() instanceof ServerLevel server)) return null;
        return server.getEntity(ownerId) instanceof LivingEntity living ? living : null;
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    /** Client: degrees the item has tumbled in flight, interpolated for rendering. */
    public float tumble(float partialTick) {
        return tumbleO + (tumble - tumbleO) * partialTick;
    }

    // ---- ticking --------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        EquipmentData.Equipment item = item();
        if (level().isClientSide) {
            clientTick(item);
            return;
        }
        if (item == null) {
            if (tickCount > 2) discard();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        switch (phase()) {
            case FLYING, RESTING -> {
                if (phase() == Phase.FLYING) fly(level, item);
                else if (!onSolidGround()) setPhase(Phase.FLYING);
                if (isRemoved()) return;
                if (fuse > 0) fuse--;
                if (shouldGoOff(item)) goOff(level, item);
                else pickUp(level, item);
            }
            case ACTIVE -> {
                LivingEntity owner = owner();
                EquipmentEffects.tickActive(level, this, item.kind, effect(item), owner, activeLeft);
                if (--activeLeft <= 0) discard();
            }
            case STUCK -> {
                pickUp(level, item);
                if (!isRemoved() && --stuckTicks <= 0) discard();
            }
        }
        if (!isRemoved() && tickCount > MAX_LIFE && phase() != Phase.ACTIVE) discard();
    }

    private void clientTick(@Nullable EquipmentData.Equipment item) {
        if (interpolation.hasActiveInterpolation()) interpolation.interpolate();
        if (phase() == Phase.ACTIVE) activeTicks++;
        tumbleO = tumble;
        if (phase() == Phase.FLYING) {
            Vec3 moved = position().subtract(xo, yo, zo);
            if (moved.lengthSqr() > 1e-6) faceVelocity(moved);
            // knives turn end over end at a steady rate, grenades tumble with their speed
            boolean knife = item != null && EquipmentData.KNIFE.equals(item.kind);
            tumble += knife ? 48 : (float) Math.min(40, moved.length() * 34);
        }
        if (item != null) clientHooks.tick(this, item, activeTicks);
    }

    private boolean shouldGoOff(EquipmentData.Equipment item) {
        if (fuse < 0 || fuse > 0) return false;
        // smoke only pours out once it lies still (or soon after, if it keeps rolling)
        if (EquipmentData.SMOKE.equals(item.kind)) return phase() == Phase.RESTING || tickCount > item.fuse * 20 + 40;
        return true;
    }

    /** One tick of flight: gravity, entity hits (knives), block bounces, drag. */
    private void fly(ServerLevel level, EquipmentData.Equipment item) {
        EquipmentData.Throw t = item.throwing;
        Vec3 velocity = getDeltaMovement().add(0, -t.gravity, 0);
        Vec3 from = position();
        double remaining = 1;
        for (int step = 0; step < 4 && remaining > 1e-4 && !isRemoved(); step++) {
            Vec3 to = from.add(velocity.scale(remaining));
            BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
            if (!spent && hitEntity(level, item, from, end, velocity)) return;
            if (block.getType() == HitResult.Type.MISS) {
                from = to;
                break;
            }
            double travelled = from.distanceTo(block.getLocation()) / Math.max(1e-6, velocity.length());
            remaining = Math.max(0, remaining - travelled);
            Direction face = block.getDirection();
            Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            from = block.getLocation().add(normal.scale(SKIN));
            if (item.shatters()) {
                setPos(from);
                fuse = 0;
                goOff(level, item);
                return;
            }
            if (EquipmentData.KNIFE.equals(item.kind) && !spent) {
                stick(level, block, velocity);
                return;
            }
            velocity = bounce(level, item, velocity, normal);
        }
        setPos(from);
        velocity = velocity.scale(t.drag);
        setDeltaMovement(velocity);
        if (velocity.lengthSqr() > 1e-6) faceVelocity(velocity);
        if (onSolidGround() && velocity.lengthSqr() < REST_SPEED * REST_SPEED) {
            setDeltaMovement(Vec3.ZERO);
            setPhase(Phase.RESTING);
        }
    }

    /** Reflects the velocity off a surface: {@code bounce} of the speed into it comes back, {@code friction} of the rest stays. */
    private Vec3 bounce(ServerLevel level, EquipmentData.Equipment item, Vec3 velocity, Vec3 normal) {
        double into = velocity.dot(normal);
        Vec3 along = velocity.subtract(normal.scale(into));
        Vec3 out = along.scale(item.throwing.friction).subtract(normal.scale(into * item.throwing.bounce));
        float impact = (float) Math.abs(into);
        if (impact > 0.08) {
            play(level, "equipment.bounce", Math.min(1, impact * 1.6f), 0.9f + level.random.nextFloat() * 0.25f);
        }
        spin *= 0.6f;
        hasImpulse = true;
        return out;
    }

    /** A knife (that has not struck anyone) meets a block: it sticks in, point first. */
    private void stick(ServerLevel level, BlockHitResult block, Vec3 velocity) {
        faceVelocity(velocity);
        setPos(block.getLocation().subtract(velocity.normalize().scale(0.12)));
        setDeltaMovement(Vec3.ZERO);
        setPhase(Phase.STUCK);
        EquipmentData.Equipment item = item();
        stuckTicks = Math.round((item == null ? 30 : item.effectFloat("stickSeconds", 30)) * 20);
        play(level, "equipment.knife_hit_block", 1, 0.95f + level.random.nextFloat() * 0.1f);
        hasImpulse = true;
    }

    /**
     * The flight segment against living things: a knife strikes (head hits by {@link HitShapes}), grenades glance off,
     * a molotov breaks on them.
     *
     * @return true when the flight ended this tick
     */
    private boolean hitEntity(ServerLevel level, EquipmentData.Equipment item, Vec3 from, Vec3 to, Vec3 velocity) {
        AABB sweep = new AABB(from, to).inflate(0.6);
        LivingEntity owner = owner();
        Entity best = null;
        HitShapes.Hit bestHit = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : level.getEntities(this, sweep, e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
            if (entity == owner && tickCount < OWNER_GRACE) continue;
            HitShapes.Hit hit = HitShapes.clip(entity, from, to, 0.08);
            if (hit == null) continue;
            double distance = from.distanceToSqr(hit.point());
            if (distance < bestDistance) {
                best = entity;
                bestHit = hit;
                bestDistance = distance;
            }
        }
        if (best == null) return false;
        setPos(bestHit.point());
        if (EquipmentData.KNIFE.equals(item.kind)) {
            strike(level, item, (LivingEntity) best, bestHit, owner);
            // the knife drops away from the body
            spent = true;
            setDeltaMovement(velocity.scale(-0.08).add(0, 0.12, 0));
            hasImpulse = true;
            return true;
        }
        if (item.shatters()) {
            fuse = 0;
            goOff(level, item);
            return true;
        }
        // a grenade glances off a body and drops
        setDeltaMovement(velocity.scale(-0.22).add(0, 0.05, 0));
        spin *= 0.5f;
        play(level, "equipment.bounce", 0.6f, 0.75f);
        hasImpulse = true;
        return true;
    }

    private void strike(ServerLevel level, EquipmentData.Equipment item, LivingEntity target, HitShapes.Hit hit, @Nullable LivingEntity owner) {
        float amount = item.effectFloat("damage", 12) * (hit.head() ? item.effectFloat("headshotMultiplier", 2) : 1);
        DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(EquipmentContent.THROWING_KNIFE), this, owner);
        String weapon = BuiltInRegistries.ITEM.getKey(stack().getItem()).toString();
        HitRecords.record(target, owner, weapon, hit.head());
        target.invulnerableTime = 0;
        boolean wasSpectator = target.isSpectator();
        if (!target.hurtServer(level, source, amount)) return;
        float knockback = item.effectFloat("knockback", 0.25f);
        Vec3 push = getDeltaMovement();
        if (knockback > 0 && push.horizontalDistanceSqr() > 1e-6) target.knockback(knockback, -push.x, -push.z);
        play(level, "equipment.knife_hit_flesh", 1, 0.95f + level.random.nextFloat() * 0.1f);
        boolean killed = !target.isAlive() || target.isDeadOrDying() || !wasSpectator && target.isSpectator();
        if (owner instanceof ServerPlayer player) hitListener.accept(new Hit(player, hit.head(), killed, amount));
    }

    /** A thrown knife's hit, for the thrower's hit marker (the server service sends it). */
    public record Hit(ServerPlayer thrower, boolean head, boolean killed, float damage) {
    }

    private static volatile Consumer<Hit> hitListener = hit -> { };

    /** Server: who hears about knife hits (the equipment service, for the thrower's hit marker). */
    public static void setHitListener(@Nullable Consumer<Hit> listener) {
        hitListener = listener == null ? hit -> { } : listener;
    }

    /** Goes off where it is: the effect decides whether it lingers as an active area. */
    private void goOff(ServerLevel level, EquipmentData.Equipment item) {
        fuse = -1;
        setDeltaMovement(Vec3.ZERO);
        level.broadcastEntityEvent(this, DETONATED);
        int active = EquipmentEffects.detonate(level, this, item.kind, effect(item), owner());
        if (active > 0) {
            activeLeft = active;
            setPhase(Phase.ACTIVE);
        } else {
            discard();
        }
    }

    /** Server: makes it go off now (cooked too long in the hand). */
    public void goOffNow() {
        EquipmentData.Equipment item = item();
        if (item != null && level() instanceof ServerLevel level && phase() != Phase.ACTIVE) goOff(level, item);
    }

    /** A knife lying around or stuck in a wall goes back into the inventory of whoever walks over it. */
    private void pickUp(ServerLevel level, EquipmentData.Equipment item) {
        if (!EquipmentData.KNIFE.equals(item.kind) || phase() == Phase.FLYING && !spent) return;
        if (item.effect != null && item.effect.has("pickup") && !item.effect.get("pickup").getAsBoolean()) return;
        for (Player player : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(1.1, 0.8, 1.1))) {
            if (!player.isAlive() || player.isSpectator() || player.getUUID().equals(ownerId) && tickCount < OWNER_GRACE * 3) continue;
            ItemStack back = stack().copyWithCount(1);
            if (!player.getInventory().add(back)) continue;
            play(level, "equipment.equip", 0.6f, 1.3f);
            discard();
            return;
        }
    }

    private boolean onSolidGround() {
        BlockPos below = BlockPos.containing(getX(), getY() - SKIN - 0.02, getZ());
        return !level().getBlockState(below).getCollisionShape(level(), below).isEmpty();
    }

    /** Turns the entity to its direction of travel (the knife flies point first; renderers read yaw / pitch). */
    private void faceVelocity(Vec3 velocity) {
        double horizontal = velocity.horizontalDistance();
        if (velocity.lengthSqr() < 1e-8) return;
        setYRot((float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z)));
        setXRot((float) Math.toDegrees(-Math.atan2(velocity.y, horizontal)));
        if (spin == 0 && tickCount == 0) spin = (float) (velocity.length() * 38);
    }

    private static JsonObject effect(EquipmentData.Equipment item) {
        return item.effect == null ? new JsonObject() : item.effect;
    }

    private void play(ServerLevel level, String name, float volume, float pitch) {
        SoundEvent sound = EquipmentContent.own(name);
        if (sound != null) level.playSound(null, getX(), getY(), getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    // ---- client ---------------------------------------------------------------------------------------

    @Override
    public void handleEntityEvent(byte id) {
        if (id == DETONATED) {
            activeTicks = 0;
            EquipmentData.Equipment item = item();
            if (item != null) clientHooks.detonated(this, item);
            return;
        }
        super.handleEntityEvent(id);
    }

    @Override
    public InterpolationHandler getInterpolation() {
        return interpolation;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 128 * 128;
    }

    // ---- vanilla plumbing -----------------------------------------------------------------------------

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** Matches are temporary: thrown items are never written to disk. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }
}
