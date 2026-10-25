package com.blockland.server.anticheat.check;

import com.blockland.movement.action.BodyActions;
import com.blockland.server.anticheat.rules.RuleSet;
import com.blockland.server.anticheat.track.PlayerTrack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

/**
 * Vanilla melee attacks (swords, fists — BlockLand's knives and guns trace on the server already, anti-cheat spec §3):
 * reach against where the target stood when the attacker saw it ({@code combat.reach}, lag compensated over the
 * attacker's round trip and the client's interpolation), targets outside the field of view ({@code combat.attack_angle}),
 * several targets in one client tick ({@code combat.multi_target}) and inhuman click rates ({@code combat.cps}).
 */
public final class CombatChecks {
    /** Where an entity stood {@code ticks} server ticks ago (the server's hit history). */
    @FunctionalInterface
    public interface PositionHistory {
        Vec3 positionAgo(Entity entity, int ticks);
    }

    /** Ticks a client draws other entities behind the latest position it was sent (updates every 2–3, interpolated over 3). */
    private static final int VIEW_DELAY_TICKS = 6;
    private static final int MAX_REWIND = 19;
    /** Reach slack: hit boxes interpolate, rotations run a frame ahead. */
    private static final double REACH_SLACK = 0.3;
    /** Wider than this from the look direction (degrees) is outside any field of view. */
    private static final double MAX_ANGLE = 95;
    private static final double MAX_CPS = 20;

    private final Judge judge;
    private final PositionHistory history;

    public CombatChecks(Judge judge, PositionHistory history) {
        this.judge = judge;
        this.history = history;
    }

    /** @return true to cancel the attack */
    public boolean attack(ServerPlayer attacker, PlayerTrack t, Entity target) {
        UUID id = attacker.getUUID();
        RuleSet rules = judge.rules(id);
        if (!rules.enabled || attacker.isCreative() || attacker.isSpectator()) return false;
        Judge.Outcome outcome = Judge.Outcome.NONE;

        // a vanilla client's clicks within one tick all go to the entity under the crosshair
        if (rules.on(Checks.MULTI_TARGET) && t.attackTick == t.clientTicks && t.attackTarget != -1 && t.attackTarget != target.getId()) {
            outcome = outcome.max(judge.flag(id, attacker, t, Checks.MULTI_TARGET, 1, "同一客户端 tick 攻击了两个不同目标"));
        }
        t.attackTick = t.clientTicks;
        t.attackTarget = target.getId();

        long now = System.nanoTime();
        if (now - t.attackSecondStart >= 1_000_000_000L) {
            double limit = MAX_CPS * rules.tolerance(Checks.CPS);
            if (t.attacksThisSecond > limit && rules.on(Checks.CPS)) {
                outcome = outcome.max(judge.flag(id, attacker, t, Checks.CPS, Math.min(5, (t.attacksThisSecond - limit) / 2),
                        String.format(Locale.ROOT, "每秒攻击 %d 次", t.attacksThisSecond)));
            }
            t.attackSecondStart = now;
            t.attacksThisSecond = 0;
        }
        t.attacksThisSecond++;

        Vec3 eye = BodyActions.eyePosition(attacker);
        if (rules.on(Checks.REACH) && outcome != Judge.Outcome.KICKED) {
            double reach = closestReach(attacker, t, target, eye);
            double limit = attacker.entityInteractionRange() + REACH_SLACK * rules.tolerance(Checks.REACH);
            if (reach > limit) {
                outcome = outcome.max(judge.flag(id, attacker, t, Checks.REACH, Math.min(5, 1 + (reach - limit) * 4),
                        String.format(Locale.ROOT, "攻击距离 %.2f > 上限 %.2f（%s）", reach, limit, target.getName().getString())));
            }
        }
        if (rules.on(Checks.ATTACK_ANGLE) && outcome != Judge.Outcome.KICKED) {
            Vec3 to = closest(target.getBoundingBox(), eye).subtract(eye);
            double length = to.length();
            if (length > 1.0) {
                double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, attacker.getLookAngle().dot(to) / length))));
                double limit = Math.min(170, MAX_ANGLE * rules.tolerance(Checks.ATTACK_ANGLE));
                if (angle > limit) {
                    outcome = outcome.max(judge.flag(id, attacker, t, Checks.ATTACK_ANGLE, Math.min(5, 1 + (angle - limit) / 20),
                            String.format(Locale.ROOT, "攻击方向与视线相差 %.0f°（%s）", angle, target.getName().getString())));
                }
            }
        }
        return outcome != Judge.Outcome.NONE;
    }

    /**
     * The nearest the attacker's eye came to the target's box as the attacker could have seen it: where it is now and
     * where it stood from the attacker's round trip back to the client's interpolation delay more.
     */
    private double closestReach(ServerPlayer attacker, PlayerTrack t, Entity target, Vec3 eye) {
        AABB box = target.getBoundingBox();
        double best = Math.sqrt(box.distanceToSqr(eye));
        int trip = (int) Math.round(judge.rtt(attacker, t) / 50.0);
        int first = Math.min(MAX_REWIND, trip);
        int last = Math.min(MAX_REWIND, trip + VIEW_DELAY_TICKS);
        Vec3 now = target.position();
        for (int ago = first; ago <= last; ago++) {
            Vec3 then = history.positionAgo(target, ago);
            AABB moved = box.move(then.x - now.x, then.y - now.y, then.z - now.z);
            best = Math.min(best, Math.sqrt(moved.distanceToSqr(eye)));
        }
        return best;
    }

    private static Vec3 closest(AABB box, Vec3 point) {
        return new Vec3(Math.max(box.minX, Math.min(box.maxX, point.x)), Math.max(box.minY, Math.min(box.maxY, point.y)),
                Math.max(box.minZ, Math.min(box.maxZ, point.z)));
    }
}
