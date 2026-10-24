package com.blockland.battleroyale;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * The 大逃杀 drop plane. Its route is synced data — a straight line from {@code start} to {@code end}, flown in
 * {@code duration} ticks from game time {@code departure} (0 = parked at the start) — so the server and every client
 * compute the same position from the level clock and the flight looks perfectly smooth. Players ride it as passengers
 * (any number, all in the cabin); they cannot get off by themselves: only the game {@link #release releases} them.
 */
public class BrPlane extends Entity {
    private static final EntityDataAccessor<Vector3f> DATA_START = SynchedEntityData.defineId(BrPlane.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_END = SynchedEntityData.defineId(BrPlane.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Long> DATA_DEPARTURE = SynchedEntityData.defineId(BrPlane.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DATA_DURATION = SynchedEntityData.defineId(BrPlane.class, EntityDataSerializers.INT);

    /** True only while the game itself takes someone off (every other dismount is refused, see {@link BattleRoyaleContent}). */
    private boolean releasing;

    public BrPlane(EntityType<? extends BrPlane> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    // ---- route (written by the game, read everywhere) -------------------------------------------------

    public void setRoute(Vec3 start, Vec3 end, int durationTicks) {
        entityData.set(DATA_START, new Vector3f((float) start.x, (float) start.y, (float) start.z));
        entityData.set(DATA_END, new Vector3f((float) end.x, (float) end.y, (float) end.z));
        entityData.set(DATA_DURATION, Math.max(1, durationTicks));
        entityData.set(DATA_DEPARTURE, 0L);
        place(0);
    }

    /** Starts flying now. */
    public void depart() {
        entityData.set(DATA_DEPARTURE, level().getGameTime());
    }

    public boolean departed() {
        return entityData.get(DATA_DEPARTURE) > 0;
    }

    /** 0..1 along the route (0 while parked), with the frame's partial tick on a client. */
    public float progress(float partialTick) {
        long departure = entityData.get(DATA_DEPARTURE);
        if (departure <= 0) return 0;
        return Mth.clamp((level().getGameTime() - departure + partialTick) / entityData.get(DATA_DURATION), 0, 1);
    }

    /** Ticks left until the end of the route (the whole flight while parked). */
    public int ticksLeft() {
        long departure = entityData.get(DATA_DEPARTURE);
        int duration = entityData.get(DATA_DURATION);
        return departure <= 0 ? duration : (int) Math.max(0, duration - (level().getGameTime() - departure));
    }

    public boolean arrived() {
        return departed() && progress(0) >= 1;
    }

    public Vec3 start() {
        Vector3f v = entityData.get(DATA_START);
        return new Vec3(v.x, v.y, v.z);
    }

    public Vec3 end() {
        Vector3f v = entityData.get(DATA_END);
        return new Vec3(v.x, v.y, v.z);
    }

    /** Where the plane is at a point of its route. */
    public Vec3 positionAt(float progress) {
        return start().lerp(end(), progress);
    }

    /** Blocks per tick along the route. */
    public Vec3 velocity() {
        return end().subtract(start()).scale(1.0 / entityData.get(DATA_DURATION));
    }

    private void place(float partialTick) {
        Vec3 at = positionAt(progress(partialTick));
        Vec3 dir = end().subtract(start());
        float yaw = (float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90;
        setPos(at.x, at.y, at.z);
        setYRot(yaw);
        yRotO = yaw;
    }

    // ---- passengers -----------------------------------------------------------------------------------

    /** Takes a passenger off (the only way off). */
    public void release(Entity passenger) {
        releasing = true;
        try {
            passenger.stopRiding();
        } finally {
            releasing = false;
        }
    }

    /** Whether a dismount of {@code passenger} is the plane's own (or unavoidable: the player is gone). */
    boolean allowsDismount(@Nullable Entity passenger) {
        if (releasing || passenger == null || !passenger.isAlive() || passenger.isRemoved()) return true;
        return passenger instanceof ServerPlayer player && player.hasDisconnected();
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return true;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        move.accept(passenger, getX(), getY() - 0.4, getZ());
    }

    @Override
    public boolean shouldRiderSit() {
        return true;
    }

    // ---- entity ---------------------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_START, new Vector3f());
        builder.define(DATA_END, new Vector3f(0, 0, 1));
        builder.define(DATA_DEPARTURE, 0L);
        builder.define(DATA_DURATION, 1);
    }

    @Override
    public void tick() {
        xo = getX();
        yo = getY();
        zo = getZ();
        super.tick();
        place(0);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 512 * 512;
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
    public boolean isPushable() {
        return false;
    }

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
