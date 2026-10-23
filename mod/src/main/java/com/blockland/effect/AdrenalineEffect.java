package com.blockland.effect;

import com.blockland.BlockLand;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 肾上腺素 and the 疲劳 after it: movement speed ±{@link #STEP} per level (the level carries the data's speed, so one
 * effect serves any injector), and while the rush lasts a slow heal. When the rush runs out the fatigue follows on its
 * own. The client reads the effect (and its time left) for the screen rush.
 */
public final class AdrenalineEffect extends MobEffect {
    /** Speed change per effect level (a share of the base speed). */
    public static final double STEP = 0.05;

    /** What one injection does after its rush: heal per second during it, then the fatigue (ticks, level). */
    private record Course(float healPerSecond, int fatigueTicks, int fatigueLevel) {
    }

    private static final Map<LivingEntity, Course> COURSES = new WeakHashMap<>();

    private final boolean fatigue;
    /** Remaining duration of the instance being ticked (set right before {@link #applyEffectTick}). */
    private int ticking = Integer.MAX_VALUE;

    AdrenalineEffect(boolean fatigue) {
        super(fatigue ? MobEffectCategory.HARMFUL : MobEffectCategory.BENEFICIAL, fatigue ? 0x5A6E9C : 0xE8343C);
        this.fatigue = fatigue;
        addAttributeModifier(Attributes.MOVEMENT_SPEED, BlockLand.id(fatigue ? "adrenaline_fatigue" : "adrenaline"),
                fatigue ? -STEP : STEP, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    /**
     * Gives the rush (replacing a running one and any fatigue).
     *
     * @param speed        extra movement speed (0.35 = +35 %)
     * @param heal         health healed over the whole rush
     * @param fatigueSlow  speed lost while fatigued (0.15 = -15 %)
     */
    public static void apply(LivingEntity entity, float seconds, float speed, float heal, float fatigueSeconds, float fatigueSlow) {
        int ticks = Math.max(20, Math.round(seconds * 20));
        COURSES.put(entity, new Course(heal / Math.max(1, seconds), Math.round(fatigueSeconds * 20), level(fatigueSlow)));
        entity.removeEffect(BLEffects.adrenalineFatigue());
        entity.removeEffect(BLEffects.adrenaline());
        entity.addEffect(new MobEffectInstance(BLEffects.adrenaline(), ticks, level(speed) - 1, false, false, true));
    }

    private static int level(float change) {
        return Math.max(1, Math.min(40, (int) Math.round(change / STEP)));
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        if (fatigue) return false;
        ticking = duration;
        return duration <= 1 || duration % 20 == 0;
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        Course course = COURSES.get(entity);
        if (course == null) return true;
        if (ticking % 20 == 0 && course.healPerSecond() > 0 && entity.getHealth() < entity.getMaxHealth()) entity.heal(course.healPerSecond());
        if (ticking <= 1) {
            COURSES.remove(entity);
            // added after the effect loop (adding while vanilla iterates the effects would skip the others this tick)
            if (course.fatigueTicks() > 0) level.getServer().schedule(new TickTask(level.getServer().getTickCount(), () -> {
                if (entity.isAlive()) entity.addEffect(new MobEffectInstance(BLEffects.adrenalineFatigue(), course.fatigueTicks(),
                        course.fatigueLevel() - 1, false, false, true));
            }));
        }
        return true;
    }
}
