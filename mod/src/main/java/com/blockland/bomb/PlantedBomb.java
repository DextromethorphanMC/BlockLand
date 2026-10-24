package com.blockland.bomb;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import javax.annotation.Nullable;

/**
 * A planted bomb lying on the floor of a site. A passive mirror of the match: the 枪战爆破 game (server) owns the timer,
 * the defusing and the explosion and writes the outcome here; the entity carries it to every client in range as synced
 * data in game ticks (when it goes off, whether and until when someone is defusing it), which the client bomb module
 * turns into the blinking light, the beep and the HUD. Nothing on a client decides anything.
 */
public class PlantedBomb extends Entity {
    public enum State { TICKING, DEFUSED, EXPLODED }

    private static final EntityDataAccessor<Long> DATA_EXPLODE_AT = SynchedEntityData.defineId(PlantedBomb.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DATA_FUSE = SynchedEntityData.defineId(PlantedBomb.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_DEFUSE_END = SynchedEntityData.defineId(PlantedBomb.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DATA_DEFUSE_TICKS = SynchedEntityData.defineId(PlantedBomb.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(PlantedBomb.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<String> DATA_SITE = SynchedEntityData.defineId(PlantedBomb.class, EntityDataSerializers.STRING);

    /** What the client module does every tick (beeps, the light); set by the client at start-up, no-op on a server. */
    public interface ClientHooks {
        ClientHooks NONE = bomb -> { };

        void tick(PlantedBomb bomb);
    }

    private static volatile ClientHooks clientHooks = ClientHooks.NONE;

    public PlantedBomb(EntityType<? extends PlantedBomb> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    public static void setClientHooks(@Nullable ClientHooks hooks) {
        clientHooks = hooks == null ? ClientHooks.NONE : hooks;
    }

    // ---- written by the game (server) -----------------------------------------------------------------

    /** Arms a freshly created bomb: it goes off {@code fuseTicks} from now (level game time). */
    public void arm(long gameTime, int fuseTicks, String site) {
        entityData.set(DATA_FUSE, Math.max(1, fuseTicks));
        entityData.set(DATA_EXPLODE_AT, gameTime + Math.max(1, fuseTicks));
        entityData.set(DATA_SITE, site == null ? "" : site);
    }

    /** Someone started defusing: done at {@code endTime} (game time) unless they stop. */
    public void startDefuse(long endTime, int ticks) {
        entityData.set(DATA_DEFUSE_TICKS, Math.max(1, ticks));
        entityData.set(DATA_DEFUSE_END, endTime);
    }

    public void stopDefuse() {
        entityData.set(DATA_DEFUSE_END, 0L);
    }

    public void setState(State state) {
        entityData.set(DATA_STATE, (byte) state.ordinal());
        if (state != State.TICKING) stopDefuse();
    }

    // ---- read by everyone -----------------------------------------------------------------------------

    public State state() {
        byte b = entityData.get(DATA_STATE);
        return b >= 0 && b < State.values().length ? State.values()[b] : State.TICKING;
    }

    public String site() {
        return entityData.get(DATA_SITE);
    }

    public int fuseTicks() {
        return entityData.get(DATA_FUSE);
    }

    public long explodeAt() {
        return entityData.get(DATA_EXPLODE_AT);
    }

    /** Seconds until it goes off, from the level clock (smooth between ticks with {@code partialTick}). */
    public float secondsLeft(float partialTick) {
        if (state() != State.TICKING) return 0;
        return Math.max(0, (explodeAt() - level().getGameTime() - partialTick) / 20f);
    }

    public boolean isDefusing() {
        return state() == State.TICKING && entityData.get(DATA_DEFUSE_END) > 0;
    }

    /** 0..1 how far the current defuse is (0 when nobody defuses). */
    public float defuseProgress(float partialTick) {
        long end = entityData.get(DATA_DEFUSE_END);
        if (end <= 0 || state() != State.TICKING) return 0;
        int ticks = entityData.get(DATA_DEFUSE_TICKS);
        float left = (end - level().getGameTime() - partialTick) / Math.max(1f, ticks);
        return Math.max(0, Math.min(1, 1 - left));
    }

    // ---- entity ---------------------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_EXPLODE_AT, 0L);
        builder.define(DATA_FUSE, 1);
        builder.define(DATA_DEFUSE_END, 0L);
        builder.define(DATA_DEFUSE_TICKS, 1);
        builder.define(DATA_STATE, (byte) State.TICKING.ordinal());
        builder.define(DATA_SITE, "");
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) clientHooks.tick(this);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 160 * 160;
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
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    /** Matches are temporary: a bomb is never written to disk. */
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
