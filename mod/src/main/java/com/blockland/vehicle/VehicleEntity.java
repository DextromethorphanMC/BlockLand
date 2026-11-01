package com.blockland.vehicle;

import com.blockland.BlockLand;
import com.blockland.combat.Blasts;
import com.blockland.combat.HitRecords;
import com.blockland.combat.HitShapes;
import com.blockland.combat.RoundTraits;
import com.blockland.common.Sounds;
import com.blockland.equipment.effect.EquipmentEffects;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every vehicle (vehicle spec): one class configured by its data ({@link VehicleData.Vehicle}, by entity type). Seats are
 * synced occupant ids (seat 0 the driver); riders are vanilla passengers placed at their seat. Movement is the server's:
 * the driver's numbered inputs drive {@link #simulate} there, and the driver's own client runs the same step at once on
 * its own input (prediction), matching itself to the server's state for each input the server reports
 * ({@link #reconcile}); everyone else interpolates. Gunners turn their weapons with their view (turn rates and limits),
 * fire through the server's vehicle service. Rounds, blades and blasts wear the hull down by its armour (a round's
 * {@link RoundTraits#armorDamage} pierces it); at no health it burns a moment and blows up.
 */
public class VehicleEntity extends Entity implements HitShapes.Shaped, Blasts.Target {
    /**
     * Entity events (client effects): a hit, the horn, destroyed (burning), the wreck exploding, a weapon fired (+ index).
     * Never 21, 35 or 63: vanilla's {@code ClientPacketListener.handleEntityEvent} takes those for any entity (guardian,
     * totem, sniffer) and casts it, which disconnects the client.
     */
    public static final byte EVENT_HIT = 60, EVENT_HORN = 61, EVENT_DESTROYED = 62, EVENT_EXPLODED = 64, EVENT_FIRE = 70;
    public static final ResourceKey<DamageType> CRASH = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("vehicle_crash"));
    /** Height of a rider's hips over its feet (the player model's leg pivot), so a seat's hips place the rider. */
    public static final float HIP = 12 / 16f * 0.9375f;
    private static final int WRECK_TICKS = 60;
    private static final int HISTORY = 100;

    private static final EntityDataAccessor<Float> DATA_HEALTH = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_WRECK = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_STEER = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_THROTTLE = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_PITCH = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_BOOST = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.BOOLEAN);
    @SuppressWarnings("unchecked")
    private static final EntityDataAccessor<Integer>[] DATA_SEATS = new EntityDataAccessor[VehicleCatalog.MAX_SEATS];
    @SuppressWarnings("unchecked")
    private static final EntityDataAccessor<Float>[] DATA_AIM_YAW = new EntityDataAccessor[VehicleCatalog.MAX_WEAPONS];
    @SuppressWarnings("unchecked")
    private static final EntityDataAccessor<Float>[] DATA_AIM_PITCH = new EntityDataAccessor[VehicleCatalog.MAX_WEAPONS];
    @SuppressWarnings("unchecked")
    private static final EntityDataAccessor<Integer>[] DATA_AMMO = new EntityDataAccessor[VehicleCatalog.MAX_WEAPONS];
    @SuppressWarnings("unchecked")
    private static final EntityDataAccessor<Integer>[] DATA_RELOAD = new EntityDataAccessor[VehicleCatalog.MAX_WEAPONS];

    static {
        for (int i = 0; i < DATA_SEATS.length; i++) DATA_SEATS[i] = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
        for (int i = 0; i < VehicleCatalog.MAX_WEAPONS; i++) {
            DATA_AIM_YAW[i] = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
            DATA_AIM_PITCH[i] = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
            DATA_AMMO[i] = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
            DATA_RELOAD[i] = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
        }
    }

    public final VehicleData.Vehicle data;
    private final InterpolationHandler interpolation = new InterpolationHandler(this, 3);

    // server: the driver's queued inputs
    private final ArrayDeque<VehicleControl> pending = new ArrayDeque<>();
    private VehicleControl last = VehicleControl.NONE;
    private int processedSeq;
    private final int[] cooldownUntil = new int[VehicleCatalog.MAX_WEAPONS];
    private final Map<Integer, Integer> crashCooldown = new HashMap<>();
    @Nullable
    private UUID lastAttacker;

    // client (the local driver): the inputs the server has not reported yet
    private final ArrayDeque<VehicleControl> history = new ArrayDeque<>();
    private int nextSeq;

    // both sides: the hull's tilt, the weapons' aim (the server's, or the local gunner's own prediction)
    private float pitch, roll;
    /** How far the turret (weapon 0) turned this tick: riders on it turn along. */
    private float turretTurned;
    private final float[] aimYaw = new float[VehicleCatalog.MAX_WEAPONS], aimPitch = new float[VehicleCatalog.MAX_WEAPONS];

    // client visuals (read by the renderer with the partial tick)
    public float pitchO, rollO, steerO, steer;
    public float wheelTurn, wheelTurnO, trackLeft, trackLeftO, trackRight, trackRightO;
    public final float[] aimYawO = new float[VehicleCatalog.MAX_WEAPONS], aimPitchO = new float[VehicleCatalog.MAX_WEAPONS];
    public final float[] shownYaw = new float[VehicleCatalog.MAX_WEAPONS], shownPitch = new float[VehicleCatalog.MAX_WEAPONS];
    /** Each weapon's recoil, 1 right after it fired, easing back to 0 (the renderer kicks the barrel back by it). */
    public final float[] recoil = new float[VehicleCatalog.MAX_WEAPONS];
    /** Aircraft: the rotors' turn (radians) and how fast they spin (0 stopped … 1 flying); the gear, 0 down … 1 up. */
    public float rotor, rotorO, rotorSpin, gear, gearO;

    public VehicleEntity(EntityType<? extends VehicleEntity> type, Level level) {
        super(type, level);
        VehicleData.Vehicle d = VehicleCatalog.get().vehicle(EntityType.getKey(type).getPath());
        this.data = d != null ? d : fallback();
        this.blocksBuilding = true;
        if (!level.isClientSide) {
            entityData.set(DATA_HEALTH, data.health);
            for (int i = 0; i < data.weapons.size(); i++) entityData.set(DATA_AMMO[i], data.weapons.get(i).magazine);
        }
    }

    private static VehicleData.Vehicle fallback() {
        VehicleData.Vehicle v = new VehicleData.Vehicle();
        v.id = "unknown";
        VehicleData.Seat seat = new VehicleData.Seat();
        seat.role = "driver";
        v.seats.add(seat);
        return v;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_HEALTH, 100f);
        builder.define(DATA_WRECK, 0);
        builder.define(DATA_SPEED, 0f);
        builder.define(DATA_STEER, 0f);
        builder.define(DATA_THROTTLE, 0f);
        builder.define(DATA_PITCH, 0f);
        builder.define(DATA_ROLL, 0f);
        builder.define(DATA_BOOST, false);
        for (EntityDataAccessor<Integer> seat : DATA_SEATS) builder.define(seat, -1);
        for (int i = 0; i < VehicleCatalog.MAX_WEAPONS; i++) {
            builder.define(DATA_AIM_YAW[i], 0f);
            builder.define(DATA_AIM_PITCH[i], 0f);
            builder.define(DATA_AMMO[i], 0);
            builder.define(DATA_RELOAD[i], 0);
        }
    }

    // ---- state ----------------------------------------------------------------------------------

    public float health() {
        return entityData.get(DATA_HEALTH);
    }

    /** Ticks since it was destroyed (burning), 0 while intact. */
    public int wreck() {
        return entityData.get(DATA_WRECK);
    }

    /** Signed speed along its front, blocks/s (the server's for everyone but the local driver). */
    public float speed() {
        return entityData.get(DATA_SPEED);
    }

    public float throttle() {
        return entityData.get(DATA_THROTTLE);
    }

    /** Whether the driver holds the boost (a jet's afterburner). */
    public boolean boosting() {
        return entityData.get(DATA_BOOST);
    }

    /** Speed through the air, blocks/s (the local driver's own, else the server's). */
    public float airspeed() {
        Player local = level().isClientSide ? Vehicles.client().localPlayer() : null;
        return local != null && driver() == local ? (float) getDeltaMovement().length() * 20 : Math.abs(speed());
    }

    public float hullPitch(float partialTick) {
        return Mth.lerp(partialTick, pitchO, pitch);
    }

    public float hullRoll(float partialTick) {
        return Mth.lerp(partialTick, rollO, roll);
    }

    public int ammo(int weapon) {
        return weapon < VehicleCatalog.MAX_WEAPONS ? entityData.get(DATA_AMMO[weapon]) : 0;
    }

    /** Ticks of the weapon's reload left (0 = loaded). */
    public int reloadLeft(int weapon) {
        return weapon < VehicleCatalog.MAX_WEAPONS ? entityData.get(DATA_RELOAD[weapon]) : 0;
    }

    public float[] aimYaws() {
        return aimYaw;
    }

    public float[] aimPitches() {
        return aimPitch;
    }

    /** The hull's rotation now (model space → world). */
    public Quaterniond hull() {
        return VehicleFrame.hull(getYRot(), pitch, roll);
    }

    // ---- seats ----------------------------------------------------------------------------------

    @Nullable
    public Entity occupant(int seat) {
        if (seat < 0 || seat >= data.seats.size()) return null;
        int id = entityData.get(DATA_SEATS[seat]);
        if (id < 0) return null;
        Entity e = level().getEntity(id);
        return e != null && hasPassenger(e) ? e : null;
    }

    @Nullable
    public Entity driver() {
        return occupant(0);
    }

    /** The seat a rider sits in (its order among the passengers until the seats are synced), -1 if not riding. */
    public int seatOf(Entity rider) {
        for (int s = 0; s < data.seats.size(); s++) if (entityData.get(DATA_SEATS[s]) == rider.getId()) return s;
        int index = getPassengers().indexOf(rider);
        return index < data.seats.size() ? index : -1;
    }

    @Nullable
    public VehicleData.Seat seat(Entity rider) {
        int s = seatOf(rider);
        return s < 0 ? null : data.seats.get(s);
    }

    /** Whether {@code entity} rides a vehicle in a seat inside its hull (rounds and blades do not reach it). */
    public static boolean isSheltered(Entity entity) {
        if (!(entity.getVehicle() instanceof VehicleEntity vehicle)) return false;
        VehicleData.Seat seat = vehicle.seat(entity);
        return seat != null && !seat.exposed;
    }

    /**
     * Whether a rider's hands are on the vehicle (its own weapons stay quiet): the driver and the gunners work the
     * vehicle's controls, anyone inside the hull cannot reach out; passengers in the open may use their own.
     */
    public static boolean handsBusy(Entity entity) {
        if (!(entity.getVehicle() instanceof VehicleEntity vehicle)) return false;
        VehicleData.Seat seat = vehicle.seat(entity);
        return seat != null && (!seat.exposed || !"passenger".equals(seat.role));
    }

    private int firstFreeSeat() {
        for (int s = 0; s < data.seats.size(); s++) if (occupant(s) == null) return s;
        return -1;
    }

    /** Server: moves a rider to another free seat; false when it is taken or does not exist. */
    public boolean moveTo(Entity rider, int seat) {
        if (level().isClientSide || seat < 0 || seat >= data.seats.size() || !hasPassenger(rider) || occupant(seat) != null) return false;
        int from = seatOf(rider);
        if (from >= 0 && entityData.get(DATA_SEATS[from]) == rider.getId()) entityData.set(DATA_SEATS[from], -1);
        entityData.set(DATA_SEATS[seat], rider.getId());
        if (seat == 0) pending.clear();
        return true;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (!level().isClientSide) {
            int s = firstFreeSeat();
            if (s >= 0) entityData.set(DATA_SEATS[s], passenger.getId());
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide) {
            for (EntityDataAccessor<Integer> seat : DATA_SEATS) if (entityData.get(seat) == passenger.getId()) entityData.set(seat, -1);
            if (entityData.get(DATA_SEATS[0]) < 0) pending.clear();
        }
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < data.seats.size() && wreck() == 0;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive() || hasPassenger(player) || !canAddPassenger(player)) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp && !Vehicles.server().mayUse(sp)) {
            sp.displayClientMessage(Component.translatable("blockland.vehicle.not_allowed"), true);
            return InteractionResult.FAIL;
        }
        return player.startRiding(this) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    @Override
    protected void positionRider(Entity rider, MoveFunction move) {
        if (!hasPassenger(rider)) return;
        int s = Math.max(0, seatOf(rider));
        VehicleData.Seat seat = data.seats.get(Math.min(s, data.seats.size() - 1));
        Quaterniond q = hull();
        Vec3 local = seatPoint(seat);
        Vec3 hips = VehicleFrame.toWorld(position(), q, local.x, local.y, local.z);
        Vec3 up = VehicleFrame.direction(q, 0, 1, 0);
        move.accept(rider, hips.x - up.x * HIP, hips.y - up.y * HIP, hips.z - up.z * HIP);
        // the rider turns with the hull (and with the turret on a seat on it — not the one aiming the turret: their view
        // is where the turret is going, turning it on as well would send the turret after it for ever)
        boolean aimsTurret = !data.weapons.isEmpty() && data.weaponIndexOf(s) == 0;
        // an aircraft's pilot looks where it should fly: the view stays theirs
        boolean flies = s == 0 && data.kind().flies();
        float turned = flies ? 0 : getYRot() - yRotO + (seat.onTurret() && !aimsTurret ? turretTurned : 0);
        rider.setYRot(rider.getYRot() + turned);
        rider.setYHeadRot(rider.getYHeadRot() + turned);
        clampRider(rider, seat);
    }

    @Override
    public void onPassengerTurned(Entity rider) {
        VehicleData.Seat seat = seat(rider);
        if (seat != null) clampRider(rider, seat);
    }

    /** A seat's hips in model space (a seat on the turret where the turret has turned it). */
    public Vec3 seatPoint(VehicleData.Seat seat) {
        if (!seat.onTurret() || data.weapons.isEmpty()) return new Vec3(seat.pos[0], seat.pos[1], seat.pos[2]);
        VehicleData.Weapon turret = data.weapons.get(0);
        double a = -Math.toRadians(aimYaw[0]), c = Math.cos(a), sn = Math.sin(a);
        // JOML's rotateY: x' = x cos + z sin, z' = -x sin + z cos
        return new Vec3(turret.pivot[0] + seat.pos[0] * c + seat.pos[2] * sn, turret.pivot[1] + seat.pos[1],
                turret.pivot[2] - seat.pos[0] * sn + seat.pos[2] * c);
    }

    private void clampRider(Entity rider, VehicleData.Seat seat) {
        rider.setYBodyRot(getYRot() + (seat.onTurret() ? aimYaw[0] : 0));
        if (seat.yawLimit >= 180) return;
        float off = Mth.wrapDegrees(rider.getYRot() - getYRot());
        float clamped = Mth.clamp(off, -seat.yawLimit, seat.yawLimit);
        rider.yRotO += clamped - off;
        rider.setYRot(rider.getYRot() + clamped - off);
        rider.setYHeadRot(rider.getYRot());
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity rider) {
        VehicleData.Seat seat = seat(rider);
        float yaw = getYRot();
        Vec3 right = VehicleFrame.right(yaw), forward = VehicleFrame.forward(yaw);
        double halfW = halfExtent(0) + rider.getBbWidth() / 2 + 0.25, halfL = halfExtent(2) + rider.getBbWidth() / 2 + 0.25;
        double side = seat == null || seat.pos[0] >= 0 ? 1 : -1;
        Vec3 base = seat == null ? position() : VehicleFrame.toWorld(position(), VehicleFrame.hull(yaw, 0, 0), 0, 0, seat.pos[2]);
        Vec3[] candidates = {base.add(right.scale(side * halfW)), base.add(right.scale(-side * halfW)),
                position().subtract(forward.scale(halfL)), position().add(forward.scale(halfL))};
        for (Vec3 c : candidates) {
            BlockPos pos = BlockPos.containing(c.x, getY() + 0.5, c.z);
            for (BlockPos at : new BlockPos[]{pos, pos.below(), pos.above()}) {
                double floor = level().getBlockFloorHeight(at);
                if (!DismountHelper.isBlockFloorValid(floor)) continue;
                Vec3 spot = new Vec3(c.x, at.getY() + floor, c.z);
                for (Pose pose : rider.getDismountPoses()) {
                    if (DismountHelper.canDismountTo(level(), spot, rider, pose)) {
                        rider.setPose(pose);
                        return spot;
                    }
                }
            }
        }
        return super.getDismountLocationForPassenger(rider);
    }

    /** Half the hull's first hit box along model axis {@code axis} (0 across, 2 along), or the entity box's. */
    private double halfExtent(int axis) {
        if (data.hitBoxes.isEmpty()) return getBbWidth() / 2;
        return data.hitBoxes.get(0).half[axis];
    }

    // ---- ticking --------------------------------------------------------------------------------

    @Override
    public void tick() {
        pitchO = pitch;
        rollO = roll;
        steerO = steer;
        wheelTurnO = wheelTurn;
        trackLeftO = trackLeft;
        trackRightO = trackRight;
        rotorO = rotor;
        gearO = gear;
        System.arraycopy(shownYaw, 0, aimYawO, 0, aimYawO.length);
        System.arraycopy(shownPitch, 0, aimPitchO, 0, aimPitchO.length);
        turretTurned = 0;
        super.tick();
        if (level().isClientSide) clientTick();
        else serverTick((ServerLevel) level());
    }

    private void serverTick(ServerLevel level) {
        int wreck = wreck();
        if (wreck > 0) {
            entityData.set(DATA_WRECK, wreck + 1);
            if (wreck + 1 >= WRECK_TICKS) {
                explodeWreck(level);
                return;
            }
        }
        VehicleControl control = nextControl();
        Entity driver = driver();
        if (driver == null || wreck > 0) control = new VehicleControl(control.seq(), 0, 0, driver == null, false, getYRot(), 0);
        simulate(control);
        for (int i = 0; i < data.weapons.size(); i++) {
            Entity gunner = occupant(data.weapons.get(i).seat);
            if (gunner != null) aim(i, gunner);
            int reload = entityData.get(DATA_RELOAD[i]);
            if (reload > 0) {
                entityData.set(DATA_RELOAD[i], reload - 1);
                if (reload == 1) entityData.set(DATA_AMMO[i], data.weapons.get(i).magazine);
            }
            entityData.set(DATA_AIM_YAW[i], aimYaw[i]);
            entityData.set(DATA_AIM_PITCH[i], aimPitch[i]);
        }
        crash(level);
        entityData.set(DATA_SPEED, data.kind().flies() ? (float) getDeltaMovement().length() * 20 : (float) forwardSpeed() * 20);
        entityData.set(DATA_BOOST, control.boost() && control.throttle() >= 0);
        entityData.set(DATA_STEER, control.steer());
        entityData.set(DATA_THROTTLE, control.throttle());
        entityData.set(DATA_PITCH, pitch);
        entityData.set(DATA_ROLL, roll);
        Vehicles.server().stepped(this);
    }

    private void clientTick() {
        Player local = Vehicles.client().localPlayer();
        if (local != null && driver() == local && wreck() == 0) {
            // the local driver: predicted at once from its own input (the server's state corrects it, see reconcile)
            interpolation.cancel();
            VehicleControl in = Vehicles.client().input(this);
            VehicleControl control = in.withSeq(++nextSeq).clamped();
            history.addLast(control);
            while (history.size() > HISTORY) history.pollFirst();
            Vehicles.client().send(this, control);
            simulate(control);
            steer += (control.steer() - steer) * 0.35f;
        } else {
            history.clear();
            interpolation.interpolate();
            pitch += (entityData.get(DATA_PITCH) - pitch) * 0.5f;
            roll += (entityData.get(DATA_ROLL) - roll) * 0.5f;
            steer += (entityData.get(DATA_STEER) - steer) * 0.35f;
        }
        // weapons: the local gunner turns them at once; the others are the server's
        for (int i = 0; i < data.weapons.size(); i++) {
            Entity gunner = occupant(data.weapons.get(i).seat);
            if (gunner != null && gunner == local) {
                aim(i, gunner);
            } else {
                float synced = entityData.get(DATA_AIM_YAW[i]);
                if (i == 0) turretTurned = Mth.wrapDegrees(synced - aimYaw[0]);
                aimYaw[i] = synced;
                aimPitch[i] = entityData.get(DATA_AIM_PITCH[i]);
            }
            shownYaw[i] += Mth.wrapDegrees(aimYaw[i] - shownYaw[i]);
            shownPitch[i] = aimPitch[i];
            recoil[i] *= 0.72f;
        }
        // wheels and tracks turn with the ground covered (the tracks differ while turning)
        float speed = local != null && driver() == local ? (float) forwardSpeed() * 20 : speed();
        float radius = Math.max(0.1f, data.movement.wheelRadius);
        wheelTurn += speed / 20f / radius;
        // turning right, the left track runs ahead of the right one
        float turned = (float) Math.toRadians(getYRot() - yRotO), halfTrack = (float) halfExtent(0);
        trackLeft += (speed / 20f + turned * halfTrack) / radius;
        trackRight += (speed / 20f - turned * halfTrack) / radius;
        if (data.kind().flies()) {
            // rotors spin up while someone flies it (and down after); a jet's gear comes up once it is well off the ground
            rotorSpin = Mth.approach(rotorSpin, driver() != null && wreck() == 0 ? 1 : 0, 0.01f);
            rotor += rotorSpin * 1.4f;
            boolean up = data.kind() == VehicleData.Kind.JET && !onGround() && heightAboveGround(4) >= 4;
            gear = Mth.approach(gear, up ? 1 : 0, 1 / 30f);
        }
    }

    @Override
    public InterpolationHandler getInterpolation() {
        return interpolation;
    }

    /** Server: queues an input of the driver (in order; a backlog is cut short). */
    public void queueControl(VehicleControl control) {
        if (control.seq() <= processedSeq) return;
        pending.addLast(control.clamped());
        while (pending.size() > 6) pending.pollFirst();
    }

    /** Server: the seq of the last input applied (the driver's client matches its prediction to it). */
    public int processedSeq() {
        return processedSeq;
    }

    private VehicleControl nextControl() {
        VehicleControl next = pending.pollFirst();
        if (next == null) return last.withSeq(processedSeq);
        last = next;
        processedSeq = next.seq();
        return next;
    }

    /**
     * Client (the local driver): the server's state after input {@code seq}. The inputs after it are replayed from that
     * state, so the vehicle stays where the server has it plus what the driver did since.
     */
    public void reconcile(int seq, Vec3 pos, float yaw, Vec3 velocity, float serverPitch, float serverRoll) {
        while (!history.isEmpty() && history.peekFirst().seq() <= seq) history.pollFirst();
        Vec3 before = position();
        setPos(pos);
        setYRot(yaw);
        setDeltaMovement(velocity);
        pitch = serverPitch;
        roll = serverRoll;
        for (VehicleControl control : history) simulate(control);
        // a tiny difference is kept smooth on screen (the renderer interpolates from where it was)
        if (position().distanceToSqr(before) < 0.04) {
            xo += position().x - before.x;
            yo += position().y - before.y;
            zo += position().z - before.z;
        }
    }

    /** Signed speed along the front, blocks/tick. */
    public double forwardSpeed() {
        Vec3 v = getDeltaMovement(), f = VehicleFrame.forward(getYRot());
        return v.x * f.x + v.z * f.z;
    }

    /**
     * One tick of movement from {@code control} (the same on the server and in the driver's prediction): thrust and
     * braking along the front, grip against the sideways slide, steering by speed (tracks turn on the spot), gravity,
     * buoyancy for boats, a land vehicle stalling in deep water; then the vanilla collision move with its step height.
     */
    void simulate(VehicleControl control) {
        VehicleData.Movement m = data.movement;
        VehicleData.Kind kind = m.kind();
        if (kind.flies()) {
            fly(control);
            return;
        }
        float yaw = getYRot();
        Vec3 v = getDeltaMovement();
        Vec3 f = VehicleFrame.forward(yaw), r = VehicleFrame.right(yaw);
        double vf = v.x * f.x + v.z * f.z, vl = v.x * r.x + v.z * r.z;
        double water = getFluidHeight(FluidTags.WATER);
        boolean boat = kind == VehicleData.Kind.BOAT;
        boolean traction = boat ? water > 0.02 : onGround() && water < m.wadeDepth;
        double maxF = m.maxSpeed / 20.0 * (control.boost() ? 1.2 : 1), maxR = m.reverseSpeed / 20.0;
        double acc = m.accel / 400.0, brk = m.brake / 400.0;
        float throttle = wreck() > 0 ? 0 : control.throttle();
        if (traction) {
            if (throttle > 0.01f) {
                if (vf < -0.01) vf = Math.min(0, vf + brk);
                else if (vf < maxF * throttle) vf = Math.min(maxF * throttle, vf + acc * throttle);
                else vf = Math.max(maxF * throttle, vf - acc * 0.5);
            } else if (throttle < -0.01f) {
                if (vf > 0.01) vf = Math.max(0, vf - brk);
                else if (vf > -maxR) vf = Math.max(-maxR, vf + acc * throttle);
            } else {
                double rolling = (boat ? 0.0025 : 0.0015) + Math.abs(vf) * (boat ? 0.012 : 0.006);
                vf = Math.abs(vf) <= rolling ? 0 : vf - Math.signum(vf) * rolling;
            }
            if (control.brake()) vf = Math.abs(vf) <= brk * 1.5 ? 0 : vf - Math.signum(vf) * brk * 1.5;
            vl *= 1 - Mth.clamp(m.grip, 0, 1);
            double share = Mth.clamp(Math.abs(vf) / Math.max(1e-3, m.maxSpeed / 20.0), 0, 1);
            double turn = m.turnRate / 20.0 * control.steer();
            turn *= switch (kind) {
                case TRACKED -> 1 - 0.45 * share;
                case BOAT -> Mth.clamp(Math.abs(vf) / (3 / 20.0), 0.25, 1) * (1 - 0.3 * share) * (vf < -0.005 ? -1 : 1);
                default -> Mth.clamp(Math.abs(vf) / (2.5 / 20.0), 0, 1) * (1 - 0.5 * share) * (vf < 0 ? -1 : 1);
            };
            if (wreck() > 0) turn = 0;
            yaw += (float) turn;
        } else {
            double drag = boat ? 0.6 : water >= m.wadeDepth ? 0.85 : 0.99;
            vf *= drag;
            vl *= drag;
        }
        double vy = v.y;
        if (boat && water > 0.02) {
            double draft = m.draft * (Math.abs(vf) > m.planing / 20.0 ? 0.55 : 1);
            vy += Mth.clamp((water - draft) * 0.12, -0.04, 0.09);
            vy *= 0.72;
        } else if (!boat && water > 0.6) {
            vy -= 0.02;
            vy *= 0.8;
        } else {
            vy -= 0.08;
            vy *= 0.98;
        }
        Vec3 nf = VehicleFrame.forward(yaw), nr = VehicleFrame.right(yaw);
        setYRot(yaw);
        setDeltaMovement(nf.x * vf + nr.x * vl, vy, nf.z * vf + nr.z * vl);
        move(MoverType.SELF, getDeltaMovement());
        tilt(control, vf);
    }

    /**
     * An aircraft's tick ({@link VehicleFlight}): its engine runs while someone flies it and it is not under water; then
     * the vanilla collision move. What it strikes on the way — the ground too fast, a wall, a ceiling — wears its hull
     * (aircraft take no fall damage: they land).
     */
    private void fly(VehicleControl control) {
        VehicleData.Movement m = data.movement;
        double water = getFluidHeight(FluidTags.WATER);
        boolean engine = driver() != null && wreck() == 0 && water < m.wadeDepth;
        boolean ground = onGround();
        VehicleFlight.Step step = data.kind() == VehicleData.Kind.JET
                ? VehicleFlight.jet(m, control, getDeltaMovement(), getYRot(), pitch, roll, ground, engine)
                : VehicleFlight.heli(m, control, getDeltaMovement(), getYRot(), pitch, roll, ground, engine);
        Vec3 v = step.velocity();
        if (water > 0.4) v = v.scale(0.8).add(0, -0.01, 0);
        setYRot(step.yaw());
        pitch = step.pitch();
        roll = step.roll();
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        // a helicopter sitting on its skids settles on the ground under them
        if (data.kind() == VehicleData.Kind.HELI && onGround() && !control.brake()) tilt(control, 0);
        if (level() instanceof ServerLevel level) impact(level, v, !ground);
    }

    /** Server: an aircraft striking something at speed (velocity before the move, blocks/tick) wears its hull. */
    private void impact(ServerLevel level, Vec3 before, boolean airborne) {
        Vec3 after = getDeltaMovement();
        double hit = 0;
        if (horizontalCollision) hit = (before.horizontalDistance() - after.horizontalDistance()) * 20 - 10;
        if (verticalCollisionBelow && airborne) {
            double sink = -before.y * 20 - (data.kind() == VehicleData.Kind.JET ? 6 : 7);
            // a jet put down on a wing or its nose
            if (data.kind() == VehicleData.Kind.JET && (Math.abs(roll) > 25 || pitch < -12)) sink = Math.max(sink, before.horizontalDistance() * 10);
            hit = Math.max(hit, sink);
        } else if (verticalCollision && !verticalCollisionBelow) {
            hit = Math.max(hit, before.y * 20 - 8);
        }
        if (hit > 0) wear(level, (float) (hit * 8 * Mth.clamp(data.mass / 5000f, 0.6f, 2f)));
    }

    /** The hull's tilt: the ground under its corners (land), a bike leaning into its turn, a boat's bow lifting at speed. */
    private void tilt(VehicleControl control, double vf) {
        VehicleData.Movement m = data.movement;
        VehicleData.Kind kind = m.kind();
        double share = Mth.clamp(Math.abs(vf) / Math.max(1e-3, m.maxSpeed / 20.0), 0, 1);
        float targetPitch = 0, targetRoll = 0;
        if (kind == VehicleData.Kind.BOAT) {
            targetPitch = (float) (Mth.clamp(Math.abs(vf) * 20 / Math.max(1, m.planing), 0, 1) * 5 - (share > 0.6 ? (share - 0.6) * 4 : 0));
            targetRoll = (float) (-control.steer() * 7 * share);
        } else if (onGround()) {
            double halfL = halfExtent(2) * 0.8, halfW = halfExtent(0) * 0.8;
            float yaw = getYRot();
            Vec3 f = VehicleFrame.forward(yaw).scale(halfL), r = VehicleFrame.right(yaw).scale(halfW);
            double front = groundAt(f), back = groundAt(f.scale(-1));
            targetPitch = (float) Math.toDegrees(Math.atan2(front - back, 2 * halfL));
            if (kind == VehicleData.Kind.BIKE) {
                targetRoll = (float) (-control.steer() * m.lean * Mth.clamp(share * 2, 0, 1));
            } else {
                double left = groundAt(r.scale(-1)), right = groundAt(r);
                targetRoll = (float) Math.toDegrees(Math.atan2(right - left, 2 * halfW));
            }
        } else if (kind == VehicleData.Kind.BIKE) {
            targetRoll = roll;
        }
        pitch += (Mth.clamp(targetPitch, -35, 35) - pitch) * 0.3f;
        roll += (Mth.clamp(targetRoll, -35, 35) - roll) * 0.3f;
    }

    /** The ground's height under the hull's centre moved by {@code offset} (its own height if none is near). */
    private double groundAt(Vec3 offset) {
        Vec3 top = position().add(offset).add(0, data.movement.stepHeight + 0.6, 0);
        BlockHitResult hit = level().clip(new ClipContext(top, top.subtract(0, data.movement.stepHeight * 2 + 2, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.MISS ? getY() : hit.getLocation().y;
    }

    /** How high the hull is over the ground under it (blocks), up to {@code max}. */
    public double heightAboveGround(double max) {
        Vec3 from = position();
        BlockHitResult hit = level().clip(new ClipContext(from, from.subtract(0, max, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        return hit.getType() == HitResult.Type.MISS ? max : from.y - hit.getLocation().y;
    }

    @Override
    public float maxUpStep() {
        return data.movement.stepHeight;
    }

    // ---- weapons --------------------------------------------------------------------------------

    /** Turns weapon {@code index} towards where its gunner looks, within its limits and turn rate. */
    private void aim(int index, Entity gunner) {
        float before = aimYaw[index];
        turnWeapon(index, gunner);
        if (index == 0) turretTurned = Mth.wrapDegrees(aimYaw[0] - before);
    }

    private void turnWeapon(int index, Entity gunner) {
        VehicleData.Weapon w = data.weapons.get(index);
        float base = getYRot() + (w.onTurret() && index != 0 ? aimYaw[0] : 0);
        float targetYaw = Mth.wrapDegrees(gunner.getYRot() - base);
        if (w.yawLimit != null && w.yawLimit.length == 2) targetYaw = Mth.clamp(targetYaw, w.yawLimit[0], w.yawLimit[1]);
        float targetPitch = Mth.clamp(-gunner.getXRot() - pitch, w.pitchLimit[0], w.pitchLimit[1]);
        float step = w.turnRate / 20f;
        aimYaw[index] = Mth.wrapDegrees(Mth.approachDegrees(aimYaw[index], targetYaw, step));
        aimPitch[index] = Mth.approach(aimPitch[index], targetPitch, step);
    }

    /** The muzzle of weapon {@code index} now and its direction: {muzzle, direction}. */
    public Vec3[] muzzle(int index) {
        // a pair of pods fires from each side by turns (the rounds left decide which, the same everywhere)
        boolean mirrored = data.weapons.get(index).mirror && (ammo(index) & 1) == 1;
        return VehicleFrame.muzzle(data, index, position(), hull(), aimYaw, aimPitch, mirrored);
    }

    /**
     * Server: whether weapon {@code index} may fire now (loaded, its interval passed); if so a round is taken and the
     * weapon's next shot timed. Starts the reload when the magazine runs dry.
     */
    public boolean tryFire(int index) {
        if (index < 0 || index >= data.weapons.size() || wreck() > 0 || entityData.get(DATA_RELOAD[index]) > 0) return false;
        if (tickCount < cooldownUntil[index]) return false;
        int ammo = entityData.get(DATA_AMMO[index]);
        if (ammo <= 0) {
            startReload(index);
            return false;
        }
        entityData.set(DATA_AMMO[index], ammo - 1);
        cooldownUntil[index] = tickCount + data.weapons.get(index).fireTicks();
        if (ammo - 1 <= 0) startReload(index);
        return true;
    }

    /** Ticks until weapon {@code index}'s next shot is allowed (0 = now). */
    public int cooldownLeft(int index) {
        return Math.max(0, cooldownUntil[index] - tickCount);
    }

    /** Server: reloads weapon {@code index} unless it is full or already reloading. */
    public void startReload(int index) {
        if (index < 0 || index >= data.weapons.size() || entityData.get(DATA_RELOAD[index]) > 0) return;
        VehicleData.Weapon w = data.weapons.get(index);
        if (entityData.get(DATA_AMMO[index]) >= w.magazine) return;
        entityData.set(DATA_RELOAD[index], w.reloadTicks());
        playSound("reload_" + w.id, 1.2f);
    }

    /** Server: plays the vehicle's sound {@code key} ({@code vehicle.<id>.<key>}) where it is, if the vehicle has it. */
    public void playSound(String key, float volume) {
        if (!data.sounds.contains(key)) return;
        SoundEvent sound = Sounds.byId(BlockLand.id(data.sound(key)));
        if (sound != null) level().playSound(null, getX(), getY() + 1, getZ(), sound, SoundSource.NEUTRAL, volume, 0.95f + random.nextFloat() * 0.1f);
    }

    /** Server: the horn (drivers of vehicles that have one). */
    public void horn() {
        if (!data.sounds.contains("horn")) return;
        playSound("horn", 2.5f);
        level().broadcastEntityEvent(this, EVENT_HORN);
    }

    // ---- damage ---------------------------------------------------------------------------------

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (isInvulnerableToBase(source) || isRemoved() || wreck() > 0) return false;
        Entity attacker = source.getEntity();
        if (attacker != null && hasIndirectPassenger(attacker) && !source.is(DamageTypeTags.IS_EXPLOSION)) return false;
        // a creative player's punch takes it away
        if (attacker instanceof Player player && player.getAbilities().instabuild && source.getDirectEntity() == player) {
            ejectPassengers();
            discard();
            return true;
        }
        float taken;
        if (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypeTags.IS_FIRE)) {
            taken = amount * (1 - data.armor * 0.5f);
        } else {
            float pierce = Math.max(0.1f, RoundTraits.current().armorDamage());
            taken = amount * (1 - Mth.clamp(data.armor / pierce, 0, 0.95f));
        }
        if (attacker instanceof LivingEntity living) lastAttacker = living.getUUID();
        wear(level, taken);
        return true;
    }

    /** Server: a blast's share for the hull (its armour halves it), credited to {@code owner}. */
    public void blast(ServerLevel level, float amount, @Nullable LivingEntity owner) {
        if (wreck() > 0 || isRemoved()) return;
        if (owner != null) lastAttacker = owner.getUUID();
        wear(level, amount * (1 - data.armor * 0.5f));
    }

    private void wear(ServerLevel level, float amount) {
        if (amount <= 0) return;
        float health = health() - amount;
        entityData.set(DATA_HEALTH, Math.max(0, health));
        level.broadcastEntityEvent(this, EVENT_HIT);
        if (amount >= 2) playSound("hit", 1f);
        if (health <= 0) destroy(level);
    }

    /** Server: destroyed — it burns a moment (riders bail out) and then blows up. */
    private void destroy(ServerLevel level) {
        entityData.set(DATA_WRECK, 1);
        level.broadcastEntityEvent(this, EVENT_DESTROYED);
        playSound("destroyed", 3f);
        ejectPassengers();
        Vehicles.server().destroyed(this);
    }

    private void explodeWreck(ServerLevel level) {
        JsonObject params = new JsonObject();
        float size = Math.max(getBbWidth(), getBbHeight());
        params.addProperty("radius", 2.5f + size);
        params.addProperty("innerRadius", 1.2f + size * 0.3f);
        params.addProperty("damage", 36);
        params.addProperty("minDamage", 5);
        params.addProperty("knockback", 1.4f);
        params.addProperty("weapon", BlockLand.id(data.id).toString());
        LivingEntity owner = lastAttacker == null ? null : level.getEntity(lastAttacker) instanceof LivingEntity l ? l : null;
        EquipmentEffects.detonate(level, this, "frag", params, owner);
        level.broadcastEntityEvent(this, EVENT_EXPLODED);
        discard();
    }

    /**
     * Server: a blast at {@code centre} (combat/Blasts: grenades, the bomb, shells, wrecks) wears the hull by its nearest
     * hull box's distance, falling off as the damage to bodies does; its armour halves it.
     */
    @Override
    public void takeBlast(ServerLevel level, Vec3 centre, float radius, float innerRadius, float damage, @Nullable LivingEntity owner) {
        double distance = Double.MAX_VALUE;
        for (HitShapes.Box b : hitBoxes(0)) {
            AABB bounds = b.bounds();
            Vec3 nearest = new Vec3(Mth.clamp(centre.x, bounds.minX, bounds.maxX), Mth.clamp(centre.y, bounds.minY, bounds.maxY),
                    Mth.clamp(centre.z, bounds.minZ, bounds.maxZ));
            distance = Math.min(distance, nearest.distanceTo(centre));
        }
        if (distance > radius) return;
        float t = distance <= innerRadius ? 0 : (float) ((distance - innerRadius) / Math.max(0.01, radius - innerRadius));
        blast(level, damage * (1 - t) * (1 - t), owner);
    }

    /** Server: what the moving hull runs into is hurt by its speed and weight and thrown aside. */
    private void crash(ServerLevel level) {
        double speed = Math.abs(forwardSpeed()) * 20;
        if (speed < 4) return;
        Entity driver = driver();
        crashCooldown.entrySet().removeIf(e -> e.getValue() <= tickCount);
        List<HitShapes.Box> boxes = hitBoxes(0.3);
        AABB sweep = boxes.isEmpty() ? getBoundingBox().inflate(0.3) : boxes.get(0).bounds();
        for (HitShapes.Box b : boxes) sweep = sweep.minmax(b.bounds());
        Vec3 forward = VehicleFrame.forward(getYRot()).scale(Math.signum(forwardSpeed()));
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, sweep,
                e -> e.isAlive() && !e.isSpectator() && !hasIndirectPassenger(e) && !crashCooldown.containsKey(e.getId()))) {
            boolean touching = false;
            for (HitShapes.Box b : boxes) if (b.bounds().intersects(victim.getBoundingBox())) touching = true;
            if (!touching) continue;
            crashCooldown.put(victim.getId(), tickCount + 10);
            float damage = (float) Math.min(40, (speed - 4) * Mth.clamp(data.mass / 1500f, 0.3f, 4f) * 1.2f);
            if (driver instanceof LivingEntity living) HitRecords.record(victim, living, BlockLand.id(data.id).toString(), false);
            DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(CRASH), this, driver);
            victim.invulnerableTime = 0;
            victim.hurtServer(level, source, damage);
            double push = Math.min(2.0, speed / 12);
            victim.push(forward.x * push, 0.25 + push * 0.15, forward.z * push);
            if (victim instanceof ServerPlayer player) player.hurtMarked = true;
        }
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
        if (data.kind().flies()) return false;
        if (fallDistance > 4 && level() instanceof ServerLevel level) {
            wear(level, (float) ((fallDistance - 4) * Mth.clamp(data.mass / 2000f, 0.5f, 6f) * 3));
            if (fallDistance > 10) propagateFallToPassengers(fallDistance - 6, multiplier, source);
        }
        return false;
    }

    // ---- hit boxes ------------------------------------------------------------------------------

    @Override
    public List<HitShapes.Box> hitBoxes(double inflate) {
        List<HitShapes.Box> boxes = new ArrayList<>(data.hitBoxes.size());
        float yaw = getYRot();
        Quaterniond turn = VehicleFrame.hull(yaw, 0, 0);
        for (VehicleData.HitBox b : data.hitBoxes) {
            Vec3 c = VehicleFrame.toWorld(position(), turn, b.center[0], b.center[1], b.center[2]);
            boxes.add(new HitShapes.Box(c, b.half[0] + inflate, b.half[1] + inflate, b.half[2] + inflate, yaw, false));
        }
        return boxes;
    }

    // ---- vanilla --------------------------------------------------------------------------------

    @Override
    public void handleEntityEvent(byte id) {
        if (id >= EVENT_HIT) {
            Vehicles.client().event(this, id);
            return;
        }
        super.handleEntityEvent(id);
    }

    @Override
    protected MovementEmission getMovementEmission() {
        return MovementEmission.NONE;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity entity) {
        return true;
    }

    @Override
    public boolean canCollideWith(Entity other) {
        return (other.canBeCollidedWith(this) || other.isPushable()) && !isPassengerOfSameVehicle(other);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 256 * 256;
    }

    @Override
    public ItemStack getPickResult() {
        return VehicleContent.item(data.id);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        entityData.set(DATA_HEALTH, input.getFloatOr("Health", data.health));
        for (int i = 0; i < data.weapons.size(); i++) {
            entityData.set(DATA_AMMO[i], input.getIntOr("Ammo" + i, data.weapons.get(i).magazine));
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putFloat("Health", health());
        for (int i = 0; i < data.weapons.size(); i++) output.putInt("Ammo" + i, entityData.get(DATA_AMMO[i]));
    }
}
