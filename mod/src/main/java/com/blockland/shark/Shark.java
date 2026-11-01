package com.blockland.shark;

import com.blockland.combat.HitShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.SmoothSwimmingLookControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.goal.TryFindWaterGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 鲨鱼 (shark spec §3): an ocean predator. It swims like a dolphin (smooth swimming control, water-bound paths), cannot
 * leave the water — on land it flops and slowly suffocates —, notices swimmers within {@code aggroRange} (a bleeding
 * one — below half health or just hurt — from {@code bloodRange}), circles them a few seconds, charges and bites
 * (damage by difficulty, knockback), turns away and comes round again ({@link SharkHuntGoal}). Riders — in a boat or
 * on a vehicle — and players standing in the shallows are left alone, never on peaceful; it hunts fish now and then.
 * Its hit shapes are boxes along its body, the head a head hit.
 */
public class Shark extends WaterAnimal implements HitShapes.Shaped {
    /** Entity event: it bit (the client opens the jaw). */
    public static final byte EVENT_BITE = 4;
    /** The body's axis over the entity's feet (the model's origin). */
    public static final double AXIS = 0.45;

    // client: the swimming animation (read by the renderer with the partial tick)
    public float swimPhase, swimPhaseO, sway, swayO, bend, bendO;
    public int biteTicks;

    private int swimSoundIn;

    public Shark(EntityType<? extends Shark> type, Level level) {
        super(type, level);
        this.moveControl = new SmoothSwimmingMoveControl(this, 85, 10, 0.02F, 0.1F, true);
        this.lookControl = new SmoothSwimmingLookControl(this, 10);
    }

    public static AttributeSupplier.Builder createAttributes() {
        SharkData d = SharkData.get();
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, d.health)
                .add(Attributes.ARMOR, d.armor)
                .add(Attributes.MOVEMENT_SPEED, d.speed)
                .add(Attributes.ATTACK_DAMAGE, d.damage.normal)
                .add(Attributes.ATTACK_KNOCKBACK, d.knockback)
                .add(Attributes.FOLLOW_RANGE, Math.max(d.aggroRange, d.bloodRange) + 4);
    }

    @Override
    protected void registerGoals() {
        SharkData d = SharkData.get();
        goalSelector.addGoal(0, new TryFindWaterGoal(this));
        goalSelector.addGoal(1, new SharkHuntGoal(this));
        goalSelector.addGoal(4, new RandomSwimmingGoal(this, 1.0, 40));
        goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, (target, level) -> isPrey(target, level)));
        if (d.huntFish) {
            targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractFish.class, 200, true, false, (target, level) -> isPrey(target, level)));
        }
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WaterBoundPathNavigation(this, level);
    }

    // ---- what it hunts ------------------------------------------------------------------------------

    /**
     * Whether {@code target} is fair prey now: in open water (water under its feet, not wading in the shallows), not
     * riding anything, within reach of the shark's senses (further when it bleeds); players never on peaceful.
     */
    boolean isPrey(LivingEntity target, ServerLevel level) {
        SharkData d = SharkData.get();
        if (!target.isAlive() || target.isSpectator()) return false;
        if (target instanceof Player player && (player.isCreative() || level.getDifficulty() == Difficulty.PEACEFUL)) return false;
        if (d.ignoreRiders && target.getVehicle() != null) return false;
        if (!target.isInWater() || !level.getFluidState(target.blockPosition().below()).is(FluidTags.WATER)) return false;
        double range = bleeding(target) ? d.bloodRange : d.aggroRange;
        return distanceToSqr(target) <= range * range;
    }

    /** Blood in the water: below {@code bloodBelowHealth} of its health, or hurt a moment ago. */
    static boolean bleeding(LivingEntity target) {
        return target.getHealth() < target.getMaxHealth() * SharkData.get().bloodBelowHealth || target.getLastDamageSource() != null;
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        AttributeInstance damage = getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) damage.setBaseValue(SharkData.get().damage.of(level.getDifficulty()));
        boolean hit = super.doHurtTarget(level, target);
        level.broadcastEntityEvent(this, EVENT_BITE);
        playSound(SharkContent.sound(SharkContent.BITE), 1.4f, 0.9f + random.nextFloat() * 0.2f);
        return hit;
    }

    // ---- movement -----------------------------------------------------------------------------------

    @Override
    public void travel(Vec3 input) {
        if (isInWater()) {
            moveRelative(getSpeed(), input);
            move(MoverType.SELF, getDeltaMovement());
            setDeltaMovement(getDeltaMovement().scale(0.9));
            if (getTarget() == null) setDeltaMovement(getDeltaMovement().add(0, -0.005, 0));
        } else {
            super.travel(input);
        }
    }

    @Override
    public void aiStep() {
        // out of the water it can only thrash about (and runs out of air: WaterAnimal)
        if (!isInWater() && onGround() && verticalCollision) {
            setDeltaMovement(getDeltaMovement().add((random.nextFloat() * 2 - 1) * 0.06, 0.28, (random.nextFloat() * 2 - 1) * 0.06));
            setYRot(getYRot() + (random.nextFloat() * 2 - 1) * 40);
            setOnGround(false);
            hasImpulse = true;
            playSound(SharkContent.sound(SharkContent.FLOP), 1.0f, 0.8f + random.nextFloat() * 0.2f);
        }
        super.aiStep();
        if (!level().isClientSide && isInWater() && --swimSoundIn <= 0) {
            swimSoundIn = 30 + random.nextInt(40);
            // the tail breaking the surface while it goes fast near the top
            boolean nearTop = !level().getFluidState(BlockPos.containing(getX(), getY() + getBbHeight() + 0.6, getZ())).is(FluidTags.WATER);
            if (nearTop && getDeltaMovement().horizontalDistance() > 0.12) playSound(SharkContent.sound(SharkContent.SWIM), 0.8f, 0.9f + random.nextFloat() * 0.2f);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) animate();
    }

    /** Client: the tail's beat follows the ground covered, the body bends into turns, the jaw closes after a bite. */
    private void animate() {
        SharkData.Animation a = SharkData.get().animation;
        swimPhaseO = swimPhase;
        swayO = sway;
        bendO = bend;
        double moved = new Vec3(getX() - xo, getY() - yo, getZ() - zo).length();
        float speed = (float) Mth.clamp(moved * 20 / 8, 0, 1);
        swimPhase += (float) (Math.PI * 2 * (moved * a.beatsPerBlock + a.idleBeatsPerSecond / 20f * (isInWater() ? 1 : 2.5f)));
        sway += (a.amplitude + a.speedAmplitude * speed - sway) * 0.1f;
        float turned = (float) Math.toRadians(Mth.wrapDegrees(yBodyRot - yBodyRotO));
        bend += (Mth.clamp(turned * a.turnBend * 6, -0.6f, 0.6f) - bend) * 0.15f;
        if (biteTicks > 0) biteTicks--;
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_BITE) {
            biteTicks = 10;
            return;
        }
        super.handleEntityEvent(id);
    }

    // ---- hit shapes ---------------------------------------------------------------------------------

    /** Boxes along the body (model z from the nose at −1.75 to the tail at +1.75): head, chest, hind body, tail. */
    private static final double[][] BOXES = {
            // z centre, half across, half up, half along, head
            {-1.30, 0.34, 0.34, 0.45, 1}, {-0.40, 0.46, 0.46, 0.48, 0}, {0.55, 0.36, 0.36, 0.48, 0}, {1.35, 0.12, 0.45, 0.35, 0}};

    @Override
    public List<HitShapes.Box> hitBoxes(double inflate) {
        float yaw = yBodyRot;
        Vec3 forward = Vec3.directionFromRotation(getXRot(), yaw);
        Vec3 axis = position().add(0, AXIS, 0);
        HitShapes.Box[] out = new HitShapes.Box[BOXES.length];
        for (int i = 0; i < BOXES.length; i++) {
            double[] b = BOXES[i];
            // model front is −Z: a point at model z lies −z along the forward direction
            Vec3 c = axis.add(forward.scale(-b[0]));
            out[i] = new HitShapes.Box(c, b[1] + inflate, b[2] + inflate, b[3] + inflate, yaw, b[4] > 0);
        }
        return List.of(out);
    }

    // ---- sounds and the rest ------------------------------------------------------------------------

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SharkContent.sound(SharkContent.HURT);
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SharkContent.sound(SharkContent.DEATH);
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        return SharkData.get().xp;
    }

    @Override
    public int getMaxHeadXRot() {
        return 1;
    }

    @Override
    public int getMaxHeadYRot() {
        return 1;
    }

    /**
     * Natural spawns: in water at least {@code minDepth} deep, a little below sea level, and only while fewer than
     * {@code maxNearby} sharks are around (spawn eggs, spawners and commands always work).
     */
    public static boolean checkSpawnRules(EntityType<Shark> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource random) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return false;
        if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION) return true;
        SharkData.Spawn s = SharkData.get().spawn;
        int sea = level.getSeaLevel();
        if (pos.getY() > sea - s.belowSeaLevel[0] || pos.getY() < sea - s.belowSeaLevel[1]) return false;
        // the whole water column at the spot (deep water, not a lagoon)
        int depth = 0;
        BlockPos.MutableBlockPos at = pos.mutable();
        while (depth < 64 && level.getFluidState(at).is(FluidTags.WATER)) {
            depth++;
            at.move(0, 1, 0);
        }
        at.set(pos).move(0, -1, 0);
        while (depth < 64 && level.getFluidState(at).is(FluidTags.WATER)) {
            depth++;
            at.move(0, -1, 0);
        }
        if (depth < s.minDepth) return false;
        int r = s.nearbyRadius;
        return level.getEntitiesOfClass(Shark.class, new AABB(pos).inflate(r)).size() < s.maxNearby;
    }
}
