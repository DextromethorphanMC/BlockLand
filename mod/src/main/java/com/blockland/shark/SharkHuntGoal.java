package com.blockland.shark;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * How the shark hunts (shark spec §3): it circles its prey at {@code circleRadius} for a few seconds — the warning: a
 * fin cutting round a swimmer —, charges at {@code chargeSpeed} and bites once in reach, then turns away for
 * {@code retreatSeconds} and comes round again (shorter each time). Fish get a short circle. It gives up the moment the
 * prey is no longer fair prey ({@link Shark#isPrey}: out of the water, in a boat, standing in the shallows).
 */
final class SharkHuntGoal extends Goal {
    private enum Phase { CIRCLE, CHARGE, RETREAT }

    private final Shark shark;
    private Phase phase = Phase.CIRCLE;
    private int left;
    private float angle, direction = 1;
    private int passes;
    private long nextBite;

    SharkHuntGoal(Shark shark) {
        this.shark = shark;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity prey = shark.getTarget();
        return prey != null && shark.isInWater() && shark.level() instanceof ServerLevel level && shark.isPrey(prey, level);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        LivingEntity prey = shark.getTarget();
        passes = 0;
        direction = shark.getRandom().nextBoolean() ? 1 : -1;
        if (prey != null) angle = (float) Math.atan2(shark.getZ() - prey.getZ(), shark.getX() - prey.getX());
        circle(prey);
    }

    @Override
    public void stop() {
        shark.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void circle(LivingEntity prey) {
        SharkData d = SharkData.get();
        phase = Phase.CIRCLE;
        float min = d.circleTicksMin(), max = d.circleTicksMax();
        float ticks = min + shark.getRandom().nextFloat() * (max - min);
        // fish are snapped up quickly; a swimmer gets less warning on every pass
        if (prey instanceof AbstractFish) ticks = Math.min(ticks, 15);
        else ticks *= (float) Math.pow(0.7, passes);
        left = Math.max(10, Math.round(ticks));
    }

    @Override
    public void tick() {
        LivingEntity prey = shark.getTarget();
        if (prey == null || !(shark.level() instanceof ServerLevel level)) return;
        SharkData d = SharkData.get();
        Vec3 at = prey.position().add(0, prey.getBbHeight() * 0.4, 0);
        switch (phase) {
            case CIRCLE -> {
                // round the prey a little under it, about a full turn every few seconds
                float radius = d.circleRadius;
                angle += direction * (float) (1.0 / Math.max(2, radius)) * 0.9f / 20f * 6;
                Vec3 to = new Vec3(at.x + Math.cos(angle) * radius, at.y - 0.6, at.z + Math.sin(angle) * radius);
                shark.getMoveControl().setWantedPosition(to.x, to.y, to.z, 1.0);
                shark.getLookControl().setLookAt(to.x, to.y, to.z);
                if (--left <= 0) phase = Phase.CHARGE;
            }
            case CHARGE -> {
                shark.getMoveControl().setWantedPosition(at.x, at.y, at.z, d.chargeSpeed);
                shark.getLookControl().setLookAt(prey, 30, 30);
                double reach = shark.getBbWidth() / 2 + prey.getBbWidth() / 2 + 1.4;
                if (shark.distanceToSqr(at) <= reach * reach && level.getGameTime() >= nextBite) {
                    shark.doHurtTarget(level, prey);
                    nextBite = level.getGameTime() + Math.round(d.biteCooldown * 20);
                    phase = Phase.RETREAT;
                    left = Math.max(10, Math.round(d.retreatSeconds * 20));
                    passes++;
                }
            }
            case RETREAT -> {
                // away from the prey and round, ready for the next pass
                Vec3 away = shark.position().subtract(at).multiply(1, 0, 1);
                if (away.lengthSqr() < 1e-4) away = Vec3.directionFromRotation(0, shark.getYRot());
                away = away.normalize();
                Vec3 to = shark.position().add(away.scale(6)).add(0, Mth.clamp(at.y - 1 - shark.getY(), -1, 1), 0);
                shark.getMoveControl().setWantedPosition(to.x, to.y, to.z, 1.2);
                if (--left <= 0) {
                    angle = (float) Math.atan2(shark.getZ() - prey.getZ(), shark.getX() - prey.getX());
                    circle(prey);
                }
            }
        }
    }
}
