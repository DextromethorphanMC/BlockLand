package com.blockland.entity;

import com.blockland.combat.HitRecords;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * A fake player for 枪械瞄准练习: drawn with the player model and a default skin, the size of a player (crouching makes it
 * as small as a crouching player), hit through the same {@code HitShapes} as an upright player, so head and body hits
 * count exactly like in a fight. It never attacks; the game that spawned it gives it a lane and a drill (strafing with a
 * random rhythm, peeking from cover, crouch-spamming or standing still) and hears about every hit it takes. Not saved
 * with the world: the course and its targets only exist while the practice runs.
 */
public class PracticeTarget extends PathfinderMob {
    /** Which of the default player skins it wears (client picks the texture). */
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(PracticeTarget.class, EntityDataSerializers.INT);
    private static final EntityDimensions CROUCHING = EntityDimensions.scalable(0.6F, 1.5F).withEyeHeight(1.27F);
    /** Blocks per tick: walking and strafing at a player's pace (sprint 0.28). */
    private static final double WALK = 0.215, CROUCH_WALK = 0.065;

    public enum Drill { STRAFE, PEEK, CROUCH, STILL }

    /** Told about hits (server side). */
    public interface Listener {
        void onHit(PracticeTarget target, ServerPlayer attacker, float amount, boolean headshot, boolean killed);
    }

    @Nullable
    private Listener listener;
    private Drill drill = Drill.STILL;
    /** Lane: where it stands (in cover for PEEK), how far it may move along X either way, the way it faces. */
    private Vec3 home = Vec3.ZERO;
    private double halfWidth;
    private double peekOffset;
    private float faceYaw;

    // drill state
    private double goalX;
    private double speed;
    private int stateTicks;
    private boolean exposed;

    public PracticeTarget(EntityType<? extends PracticeTarget> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN, 0);
    }

    public int skin() {
        return entityData.get(SKIN);
    }

    public void setSkin(int skin) {
        entityData.set(SKIN, skin);
    }

    /**
     * Sets the lane and drill.
     *
     * @param home       where it stands (behind its cover for {@link Drill#PEEK})
     * @param halfWidth  how far it strafes from {@code home} along X
     * @param peekOffset for PEEK: how far to step out sideways (sign = which side of the cover)
     * @param faceYaw    the way it faces (towards the firing line)
     */
    public void setDrill(Drill drill, Vec3 home, double halfWidth, double peekOffset, float faceYaw, float health) {
        this.drill = drill;
        this.home = home;
        this.halfWidth = halfWidth;
        this.peekOffset = peekOffset;
        this.faceYaw = faceYaw;
        this.goalX = home.x;
        this.stateTicks = 10 + random.nextInt(20);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        setHealth(health);
        setYRot(faceYaw);
        setYBodyRot(faceYaw);
        setYHeadRot(faceYaw);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    // ---- drill ---------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (!level().isClientSide && isAlive()) drill();
        super.tick();
    }

    private void drill() {
        boolean crouch = false;
        switch (drill) {
            case STILL -> goalX = home.x;
            case STRAFE -> {
                // A-D strafing with a broken rhythm: short and long legs, the odd stop, the odd jump
                if (--stateTicks <= 0 || Math.abs(getX() - goalX) < 0.08) {
                    double roll = random.nextDouble();
                    goalX = roll < 0.15 ? getX() : home.x + (random.nextDouble() * 2 - 1) * halfWidth;
                    speed = random.nextDouble() < 0.3 ? 0.28 : WALK * (0.75 + random.nextDouble() * 0.35);
                    stateTicks = 6 + random.nextInt(roll < 0.15 ? 14 : 26);
                    if (onGround() && random.nextDouble() < 0.08) jumpFromGround();
                }
            }
            case PEEK -> {
                // step out of cover, hold, step back in, wait
                if (--stateTicks <= 0) {
                    exposed = !exposed;
                    stateTicks = exposed ? 12 + random.nextInt(22) : 16 + random.nextInt(30);
                    // sometimes the other side, if there is room
                    if (exposed && random.nextDouble() < 0.25) peekOffset = -peekOffset;
                }
                goalX = exposed ? home.x + peekOffset : home.x;
                speed = 0.24;
            }
            case CROUCH -> {
                // crouch-spam while drifting along a short stretch
                if (--stateTicks <= 0) {
                    exposed = !exposed;
                    stateTicks = 4 + random.nextInt(9);
                    if (random.nextDouble() < 0.35) goalX = home.x + (random.nextDouble() * 2 - 1) * halfWidth;
                }
                crouch = !exposed;
                speed = crouch ? CROUCH_WALK : WALK * 0.6;
            }
        }
        setPose(crouch ? Pose.CROUCHING : Pose.STANDING);

        double dx = goalX - getX();
        double step = Math.abs(dx) < 0.02 ? 0 : Math.copySign(Math.min(Math.abs(dx), speed), dx);
        // back into the lane if pushed out of it
        double dz = Mth.clamp(home.z - getZ(), -0.1, 0.1);
        Vec3 motion = getDeltaMovement();
        setDeltaMovement(step, motion.y, Math.abs(home.z - getZ()) < 0.03 ? 0 : dz);

        setYRot(faceYaw);
        setYBodyRot(faceYaw);
        // the head follows the shooter (the mob's look control turns it, within its neck's reach)
        Player watcher = level().getNearestPlayer(this, 128);
        if (watcher != null) getLookControl().setLookAt(watcher, 30, 30);
    }

    // ---- being shot ----------------------------------------------------------------------------

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && listener != null && source.getEntity() instanceof ServerPlayer attacker) {
            HitRecords.Hit hit = HitRecords.last(this);
            boolean head = hit != null && hit.headshot() && hit.gameTime() == level.getGameTime();
            listener.onHit(this, attacker, amount, head, isDeadOrDying());
        }
        return hurt;
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        return 0;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return pose == Pose.CROUCHING ? CROUCHING : super.getDefaultDimensions(pose);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }
}
