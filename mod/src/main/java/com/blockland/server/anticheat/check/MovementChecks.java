package com.blockland.server.anticheat.check;

import com.blockland.server.anticheat.api.MotionAllowance;
import com.blockland.server.anticheat.api.MotionBudget;
import com.blockland.server.anticheat.predict.MoveEnvelope;
import com.blockland.server.anticheat.predict.MoveInput;
import com.blockland.server.anticheat.predict.MovePredictor;
import com.blockland.server.anticheat.predict.Physics;
import com.blockland.server.anticheat.rules.RuleSet;
import com.blockland.server.anticheat.track.MovingBlocks;
import com.blockland.server.anticheat.track.PlayerTrack;
import com.blockland.server.anticheat.track.ServerVelocity;
import com.blockland.server.anticheat.track.StateWindow;
import com.blockland.server.anticheat.track.Surroundings;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The movement checks (anti-cheat spec §3, §6), judged per movement packet before vanilla handles it: horizontal speed
 * and vertical rise against the predictor's envelope ({@code movement.speed}, {@code movement.vertical}), claimed ground
 * against the server's collision ({@code movement.ground}, whose verdict vanilla then uses), knockback the client
 * acknowledged but did not take ({@code movement.velocity}), hovering in mid-air between packets, impossible rotations
 * ({@code packet.invalid}) and two moves in one client tick ({@code packet.timer}). A violation the rules want undone
 * sets the player back to the last accepted position. Ground is blocks and the entities a client stands on (boats,
 * shulkers), the latter as late as the client sees them; moves next to a piston that just moved are not judged.
 */
public final class MovementChecks {
    /** Ticks a client may go without a movement packet in mid-air before it hovers (it always moves when airborne). */
    private static final int HOVER_TICKS = 5;
    /** Moves after leaving a liquid that still carry its push and the hop out. */
    private static final int LIQUID_EXIT_MOVES = 6;

    private final Judge judge;
    private final List<MotionAllowance> allowances;
    private final MotionBudget budget = new MotionBudget();
    private final Surroundings end = new Surroundings();
    private final MovingBlocks pistons;

    public MovementChecks(Judge judge, List<MotionAllowance> allowances, MovingBlocks pistons) {
        this.judge = judge;
        this.allowances = allowances;
        this.pistons = pistons;
    }

    /** @return false to drop the packet (the player was set back or kicked) */
    public boolean move(ServerPlayer player, PlayerTrack t, ServerboundMovePlayerPacket packet, boolean awaitingTeleport, int serverTick) {
        UUID id = player.getUUID();
        RuleSet rules = judge.rules(id);
        if (packet.hasRotation() && rules.on(Checks.PACKET_INVALID)) {
            // vanilla clamps the pitch silently; its own client never sends more than ±90°
            float pitch = packet.getXRot(0);
            if (Float.isFinite(pitch) && Math.abs(pitch) > 90.001f
                    && judge.flag(id, player, t, Checks.PACKET_INVALID, 1, String.format(Locale.ROOT, "俯仰角 %.1f°", pitch)) == Judge.Outcome.KICKED) {
                return false;
            }
        }
        int ticks = t.tickEndsSinceMove;
        t.tickEndsSinceMove = 0;
        t.silentAirTicks = 0;
        boolean claimed = packet.isOnGround();
        t.groundPacket = packet;
        t.groundVerified = claimed;
        if (awaitingTeleport) {
            // vanilla ignores moves until the client confirms its teleport; start over from there
            t.teleportPending = true;
            return true;
        }
        // the server moved the player itself (a teleport the client confirmed before sending a move, a respawn, vanilla
        // correcting a move, a dismount): start over from where the server has them
        boolean moved = t.anchored && Math.abs(player.getX() - t.x) + Math.abs(player.getY() - t.y) + Math.abs(player.getZ() - t.z) > 1e-4;
        if (t.entityId != player.getId() || !t.anchored || t.teleportPending || moved) {
            t.entityId = player.getId();
            t.teleportPending = false;
            t.velocities.clear();
            // where the server has them, standing only on what the server sees (the client's flag may be stale)
            t.anchor(player.getX(), player.getY(), player.getZ(),
                    Surroundings.supported(player.level(), player, player.getX(), player.getY(), player.getZ()));
            t.settleMoves = 2;
        }
        double nx = packet.getX(player.getX()), ny = packet.getY(player.getY()), nz = packet.getZ(player.getZ());
        double dx = nx - t.x, dy = ny - t.y, dz = nz - t.z;
        int window = judge.window(player, t, rules);
        boolean exempt = exempt(player, t, serverTick, window);
        if (exempt || !rules.enabled) {
            t.velocities.clear();
            accept(t, nx, ny, nz, dx, dy, dz, claimed, false, false, false, Double.MAX_VALUE, Double.MAX_VALUE);
            t.wasExempt = exempt;
            return true;
        }
        if (t.wasExempt) {
            t.wasExempt = false;
            t.settleMoves = Math.max(t.settleMoves, 2);
        }
        ServerLevel level = player.level();
        boolean supported = claimed && resting(level, player, t, nx, ny, nz);
        // on a boat or a shulker: the client stands on its own copy, which shows a moving one some ticks late
        if (claimed && !supported) supported = platformAt(level, player, t, nx, ny, nz, window) != null;
        if (supported) {
            t.restTick = serverTick;
            t.restY = ny;
        } else if (claimed && serverTick - t.restTick <= window && Math.abs(ny - t.restY) < 1e-3) {
            // still standing where a block was a moment ago: it was removed (a cage opening, a block broken under them)
            // and the client has not heard yet
            supported = true;
        }
        boolean groundCheck = rules.on(Checks.GROUND);
        boolean verified = claimed && (supported || !groundCheck);
        // what the physics assumes, whatever the ground check's setting: only a ground the server sees under the feet
        boolean groundNow = claimed && supported;
        t.groundVerified = verified;
        t.around.sample(level, player, t.x, t.y, t.z, player.getBlockPosBelowThatAffectsMyMovement());
        end.sample(level, player, nx, ny, nz, null);
        boolean liquid = t.around.water || t.around.lava || end.water || end.lava;
        boolean heldUp = end.water || end.lava || end.climbable || end.stuck || end.bubbleColumn;
        if (t.settleMoves > 0) {
            t.settleMoves--;
            accept(t, nx, ny, nz, dx, dy, dz, groundNow, liquid, heldUp, end.bouncy, Double.MAX_VALUE, Double.MAX_VALUE);
            return true;
        }
        Judge.Outcome outcome = Judge.Outcome.NONE;
        if (ticks == 0 && rules.on(Checks.TIMER)) {
            outcome = judge.flag(id, player, t, Checks.TIMER, 1, "一个客户端 tick 内两个移动包");
            if (outcome == Judge.Outcome.KICKED) return false;
        }

        MoveInput in = fill(player, t, rules, window, Math.max(1, ticks), dx, dy, dz, supported);
        // the blocks of a piston that just moved carry players along (a slime block launches them), on the client too
        boolean pushed = pistons.near(level, nx, ny, nz, serverTick, window) || pistons.near(level, t.x, t.y, t.z, serverTick, window);
        if (pushed) {
            in.exemptHorizontal = true;
            in.exemptVertical = true;
        }
        MoveEnvelope env = t.envelope;
        MovePredictor.envelope(in, env);
        double over = env.horizontalExcess();
        if (over > 0 && rules.on(Checks.SPEED)) {
            // lookups are only made when the cheap envelope was not enough: a ceiling over a jump, other entities pushing
            if (in.groundPrev && Math.abs(dy) <= 1e-4 && !level.noCollision(player, player.getBoundingBox().move(dx, 0.1, dz))) {
                in.ceiling = true;
                MovePredictor.envelope(in, env);
                over = env.horizontalExcess();
            }
            int pushers = over > 0 ? pushers(level, player) : 0;
            if (pushers > 0) {
                in.pushSlack = Physics.ENTITY_PUSH * Math.min(3, pushers);
                MovePredictor.envelope(in, env);
                over = env.horizontalExcess();
            }
            if (over > 0) outcome = outcome.max(judge.flag(id, player, t, Checks.SPEED, amount(over), describe(env, in, true)));
        }
        double rise = env.verticalExcess();
        if (rise > 0 && !claimed && resting(level, player, t, nx, ny, nz)) {
            // a jump that stepped onto a stair or a slab: at rest on its top, though the client (moving up) says airborne
            in.restingNow = true;
            MovePredictor.envelope(in, env);
            rise = env.verticalExcess();
        }
        if (rise > 0) {
            // a boat or a shulker at either end: the client stood on, or stepped onto, its own late copy of it (vanilla steps
            // onto a boat's 0.5625 from the ground, or while landing beside it)
            Entity platform = platformAt(level, player, t, nx, ny, nz, window);
            boolean onEnd = platform != null;
            if (platform == null) platform = Surroundings.platform(level, player, t.x, t.y, t.z, window);
            if (platform != null) {
                if (onEnd) in.restingNow = true;
                in.platformRise = Surroundings.platformSlack(platform, window);
                MovePredictor.envelope(in, env);
                rise = env.verticalExcess();
            }
        }
        if (rise > 0 && rules.on(Checks.VERTICAL) && outcome != Judge.Outcome.KICKED) {
            outcome = outcome.max(judge.flag(id, player, t, Checks.VERTICAL, amount(rise), describe(env, in, false)));
        }
        if (claimed && !supported && groundCheck && outcome != Judge.Outcome.KICKED) {
            String entities = Surroundings.platformsNear(level, player, nx, ny, nz);
            outcome = outcome.max(judge.flag(id, player, t, Checks.GROUND, 1, String.format(Locale.ROOT,
                    "声称着地，脚下没有可站立的方块（%.3f, %.3f, %.3f；脚下 %s%s）", nx, ny, nz, Surroundings.below(level, player, nx, ny, nz),
                    entities.isEmpty() ? "" : "；附近实体 " + entities)));
        }
        if (outcome != Judge.Outcome.KICKED) {
            boolean immune = budget.horizontal > 0 || budget.exemptHorizontal || liquid || t.around.stuck || end.stuck
                    || t.around.climbable || end.climbable || pushed;
            outcome = outcome.max(velocities(player, t, rules, env, dx, dy, dz, immune));
        }
        if (outcome == Judge.Outcome.KICKED) return false;
        if (outcome == Judge.Outcome.MITIGATE) {
            setback(player, t);
            return false;
        }
        accept(t, nx, ny, nz, dx, dy, dz, groundNow, liquid, heldUp, end.bouncy,
                env.checkHorizontal ? env.rawHorizontal : Double.MAX_VALUE, env.checkVertical ? env.rawUp : Double.MAX_VALUE);
        return true;
    }

    /**
     * A client tick ended without a movement packet since the last one (server thread). Airborne, a vanilla client moves
     * every tick (gravity), so a run of silent ticks in the air is a hover.
     */
    public void silentTick(ServerPlayer player, PlayerTrack t, int serverTick) {
        if (t.tickEndsSinceMove < 2 || !t.anchored || t.teleportPending || t.groundPrev || t.heldUp || t.settleMoves > 0) return;
        RuleSet rules = judge.rules(player.getUUID());
        if (!rules.on(Checks.VERTICAL)) return;
        int window = judge.window(player, t, rules);
        if (exempt(player, t, serverTick, window) || t.window.any(StateWindow.LEVITATION | StateWindow.NO_GRAVITY, window)) return;
        budget.reset();
        for (MotionAllowance allowance : allowances) allowance.allow(player, budget, window);
        if (budget.exemptVertical) return;
        t.silentAirTicks++;
        if (t.silentAirTicks <= HOVER_TICKS * rules.tolerance(Checks.VERTICAL)) return;
        if (Surroundings.supported(player.level(), player, t.x, t.y, t.z)
                || Surroundings.platform(player.level(), player, t.x, t.y, t.z, window) != null) {
            // standing after all (the ground check was off, or a block appeared under them)
            t.groundPrev = true;
            return;
        }
        judge.flag(player.getUUID(), player, t, Checks.VERTICAL, 1,
                String.format(Locale.ROOT, "空中悬停 %d 个客户端 tick", t.silentAirTicks));
    }

    private MoveInput fill(ServerPlayer player, PlayerTrack t, RuleSet rules, int window, int ticks, double dx, double dy, double dz,
                           boolean restingNow) {
        MoveInput in = t.input;
        in.prevDx = t.prevDx;
        in.prevDy = t.prevDy;
        in.prevDz = t.prevDz;
        in.carryGround = t.carryGround;
        in.carrySlip = t.carrySlip;
        in.groundPrev = t.groundPrev;
        in.restingNow = restingNow;
        in.ceiling = false;
        in.platformRise = 0;
        in.slipPrev = t.around.slip;
        in.slipAlt = t.around.slipAlt;
        in.ticks = ticks;
        in.inWater = t.around.water || end.water;
        in.inLava = t.around.lava || end.lava;
        in.recentLiquid = t.liquidAge <= LIQUID_EXIT_MOVES;
        in.climbable = t.around.climbable || end.climbable;
        in.bounce = t.bounce;
        in.honeySlide = t.around.honey || end.honey;
        StateWindow w = t.window;
        in.speed = w.maxSpeed(window);
        in.jumpStrength = w.maxJumpStrength(window);
        in.jumpBoost = w.maxJumpBoost(window);
        in.stepHeight = w.maxStepHeight(window);
        in.gravity = w.minGravity(window);
        in.waterEfficiency = w.maxWaterEfficiency(window);
        in.dolphinsGrace = w.any(StateWindow.DOLPHINS_GRACE, window);
        in.sneaking = w.any(StateWindow.SNEAKING, window);
        in.exemptHorizontal = false;
        in.exemptVertical = w.any(StateWindow.LEVITATION | StateWindow.NO_GRAVITY, window)
                || t.around.stuck || end.stuck || t.around.bubbleColumn || end.bubbleColumn;
        in.clearVelocities();
        for (ServerVelocity v : t.velocities) {
            if (v.additive) {
                in.addVx += v.x;
                in.addVy += v.y;
                in.addVz += v.z;
            } else if (!in.hasSetVelocity || v.horizontal() > Math.hypot(in.setVx, in.setVz) || v.y > in.setVy) {
                // several set velocities in flight: any of them may be the one in effect, allow the largest of each axis
                in.setVx = Math.abs(v.x) > Math.abs(in.setVx) ? v.x : in.setVx;
                in.setVz = Math.abs(v.z) > Math.abs(in.setVz) ? v.z : in.setVz;
                in.setVy = in.hasSetVelocity ? Math.max(in.setVy, v.y) : v.y;
                in.hasSetVelocity = true;
            }
        }
        budget.reset();
        for (MotionAllowance allowance : allowances) allowance.allow(player, budget, window);
        in.budgetHorizontal = budget.horizontal;
        in.budgetUp = budget.up;
        in.exemptHorizontal |= budget.exemptHorizontal;
        in.exemptVertical |= budget.exemptVertical;
        in.pushSlack = 0;
        in.tolerance = rules.tolerance(Checks.SPEED);
        in.toleranceVertical = rules.tolerance(Checks.VERTICAL);
        in.dx = dx;
        in.dy = dy;
        in.dz = dz;
        return in;
    }

    /**
     * Follows the server's velocities through the moves: each remembers how far the moves went along it; one move after
     * the client acknowledged it, it must have been taken (unless a wall or a ceiling was in the way, or the module's own
     * motion was in charge).
     */
    private Judge.Outcome velocities(ServerPlayer player, PlayerTrack t, RuleSet rules, MoveEnvelope env, double dx, double dy, double dz,
                                     boolean immune) {
        Judge.Outcome outcome = Judge.Outcome.NONE;
        for (Iterator<ServerVelocity> it = t.velocities.iterator(); it.hasNext(); ) {
            ServerVelocity v = it.next();
            v.observe(dx, dy, dz);
            if (immune) v.immune = true;
            if (v.acknowledged && v.movesAfterAck >= 1) {
                it.remove();
                if (!v.immune && rules.on(Checks.VELOCITY)) outcome = outcome.max(judgeVelocity(player, t, rules, env, v));
            } else if (v.moves > 400) {
                it.remove();
            }
        }
        return outcome;
    }

    private Judge.Outcome judgeVelocity(ServerPlayer player, PlayerTrack t, RuleSet rules, MoveEnvelope env, ServerVelocity v) {
        double tol = rules.tolerance(Checks.VELOCITY);
        double h = v.horizontal();
        ServerLevel level = player.level();
        Judge.Outcome outcome = Judge.Outcome.NONE;
        if (h >= 0.15) {
            double required = h * (1 - 0.35 * tol) - env.accel * 1.5 - 0.03 * tol - (v.additive ? v.carried * Physics.AIR_FRICTION : 0);
            if (required > 0.05 && v.bestAlong < required && level.noCollision(player, player.getBoundingBox().move(v.x, 0, v.z))) {
                outcome = judge.flag(player.getUUID(), player, t, Checks.VELOCITY, Math.min(5, 1 + 5 * (required - v.bestAlong) / h),
                        String.format(Locale.ROOT, "击退水平 %.3f，应至少移动 %.3f，实际 %.3f", h, required, Math.max(0, v.bestAlong)));
            }
        }
        if (!v.additive && v.y > 0.1 && outcome != Judge.Outcome.KICKED) {
            double required = v.y * (1 - 0.35 * tol) - 0.03 * tol;
            if (v.bestRise < required && level.noCollision(player, player.getBoundingBox().move(0, v.y, 0))) {
                outcome = outcome.max(judge.flag(player.getUUID(), player, t, Checks.VELOCITY, 1,
                        String.format(Locale.ROOT, "击退竖直 %.3f，应至少上升 %.3f，实际 %.3f", v.y, required, v.bestRise)));
            }
        }
        return outcome;
    }

    /**
     * Remembers the move as the new start. The step carried into the next move is capped at what the envelope explained
     * ({@code rawHorizontal}, {@code rawUp}; unlimited for moves that were not judged), so a step that got away with the
     * tolerance does not raise the next one's bound.
     */
    private static void accept(PlayerTrack t, double nx, double ny, double nz, double dx, double dy, double dz, boolean ground,
                               boolean liquid, boolean heldUp, boolean bouncy, double rawHorizontal, double rawUp) {
        // a landing on slime or a bed throws the next move up as fast as this one came down
        t.bounce = ground && !t.groundPrev && dy < 0 && bouncy ? Math.max(-dy, -Physics.fall(t.prevDy, Physics.DEFAULT_GRAVITY)) : 0;
        t.x = nx;
        t.y = ny;
        t.z = nz;
        double step = Math.sqrt(dx * dx + dz * dz);
        double scale = step > rawHorizontal && step > 0 ? rawHorizontal / step : 1;
        t.prevDx = dx * scale;
        t.prevDy = Math.min(dy, rawUp);
        t.prevDz = dz * scale;
        t.carryGround = t.groundPrev;
        t.carrySlip = Math.max(t.around.slip, t.around.slipAlt);
        t.groundPrev = ground;
        t.liquidAge = liquid ? 0 : Math.min(1000, t.liquidAge + 1);
        t.heldUp = heldUp;
    }

    /**
     * Whether the move ends on the ground as vanilla decides it: a move collides vertically first, in the column it starts
     * in, and only then slides sideways — so walking off a stair or a slab edge still ends "on the ground" for that tick,
     * a hair past the edge. Supported at the end, or in the starting column at the end height.
     */
    private static boolean resting(ServerLevel level, ServerPlayer player, PlayerTrack t, double nx, double ny, double nz) {
        return Surroundings.supported(level, player, nx, ny, nz) || Surroundings.supported(level, player, t.x, ny, t.z);
    }

    /** A platform entity under the box at the end, or in the starting column at the end height (as {@link #resting}). */
    private static Entity platformAt(ServerLevel level, ServerPlayer player, PlayerTrack t, double nx, double ny, double nz, int window) {
        Entity platform = Surroundings.platform(level, player, nx, ny, nz, window);
        return platform != null ? platform : Surroundings.platform(level, player, t.x, ny, t.z, window);
    }

    /** Back to the last accepted position (vanilla's teleport, confirmed by the client before anything else counts). */
    public static void setback(ServerPlayer player, PlayerTrack t) {
        t.velocities.clear();
        t.teleportPending = true;
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    /** Whether the physics cannot judge this player now (or within their latency window). */
    public static boolean exempt(ServerPlayer player, PlayerTrack t, int serverTick, int window) {
        return !player.isAlive() || player.isSpectator() || player.isCreative() || player.getAbilities().flying
                || player.isPassenger() || player.isSleeping() || player.isFallFlying() || player.isAutoSpinAttack()
                || player.isChangingDimension() || player.noPhysics || t.exemptUntil > serverTick
                || t.window.any(StateWindow.EXEMPT, window);
    }

    private static int pushers(ServerLevel level, ServerPlayer player) {
        return level.getEntities(player, player.getBoundingBox().inflate(0.35), e -> e.isPushable() && !e.isSpectator()).size();
    }

    /** One clear violation is 1; every 0.1 blocks beyond adds 1, up to 5. */
    private static double amount(double excess) {
        return Math.min(5, 1 + excess * 10);
    }

    private static String describe(MoveEnvelope env, MoveInput in, boolean horizontal) {
        String where = in.inWater || in.inLava ? "液体" : in.climbable ? "攀爬" : in.groundPrev ? "地面" : "空中";
        if (horizontal) {
            return String.format(Locale.ROOT, "水平 %.3f > 上限 %.3f（%s，速度属性 %.3f，上一步 %.3f）", env.horizontal, env.maxHorizontal,
                    where, in.speed, Math.hypot(in.prevDx, in.prevDz));
        }
        return String.format(Locale.ROOT, "上升 %.3f > 上限 %.3f（%s，上一步 %.3f）", env.up, env.maxUp, where, in.prevDy);
    }
}
