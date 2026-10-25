import com.blockland.server.anticheat.predict.MoveEnvelope;
import com.blockland.server.anticheat.predict.MoveInput;
import com.blockland.server.anticheat.predict.MovePredictor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline check of the anti-cheat's movement envelope (anti-cheat spec §12): an independent copy of vanilla 1.21.8's
 * player physics on flat ground (aiStep jump, travel: moveRelative, move with the floor, gravity, drag, friction; slime,
 * bed and honey floors, a honey wall) plays legal sequences that must never leave the envelope, and cheat sequences that
 * must. Run by check_movement.py.
 */
public final class MovementCheck {
    private static final double BASE_SPEED = 0.1;
    /** What ActionAllowance grants (data defaults: slide 0.56, dive 0.6 + lift 0.14). */
    private static final double SLIDE_BUDGET = 0.56 * 1.05 + 0.02;
    private static final double DIVE_BUDGET = 0.6 * 1.05 + 0.02;
    private static final double DIVE_UP = 0.42 + 0.14 + 0.02;
    private static int failures;

    /** A vanilla client on a flat floor (no walls); raising the floor under it steps it up, as vanilla's step does. */
    static final class Client {
        double x, y, z, vx, vy, vz;
        boolean onGround = true;
        boolean sprinting;
        /** Height of the floor under the client. */
        double floor;
        double slip = 0.6;
        double speedMultiplier = 1;
        int jumpBoost = -1;
        double gravity = 0.08;
        boolean slowFalling;
        float yaw;
        /** The floor: slime (bounces fully, 0.8 slippery), bed (bounces at 0.528), honey (jumps at half strength). */
        boolean slime, bed, honey;
        /** Against the side of a honey block: an airborne fall is held at 0.05 before gravity (HoneyBlock.doSlideMovement). */
        boolean honeyWall;
        boolean sneaking;

        double speed() {
            return BASE_SPEED * speedMultiplier * (sprinting ? 1.3 : 1);
        }

        /** One tick: returns the displacement {dx, dy, dz}. */
        double[] tick(double forward, double strafe, boolean jump) {
            if (jump && onGround) {
                double power = (0.42 + (jumpBoost >= 0 ? 0.1 * (jumpBoost + 1) : 0)) * (honey ? 0.5 : 1);
                vy = Math.max(power, vy);
                if (sprinting) {
                    double r = Math.toRadians(yaw);
                    vx += -Math.sin(r) * 0.2;
                    vz += Math.cos(r) * 0.2;
                }
            }
            double slipNow = onGround ? (slime ? 0.8 : slip) : 1.0;
            double friction = slipNow * 0.91;
            double accel = onGround ? speed() * (0.21600002 / (slipNow * slipNow * slipNow)) : (sprinting ? 0.025999999 : 0.02);
            // moveRelative: input normalised when longer than 1, rotated by the yaw
            double len = Math.sqrt(forward * forward + strafe * strafe);
            if (len > 1e-7) {
                double f = forward, s = strafe;
                if (len > 1) {
                    f /= len;
                    s /= len;
                }
                f *= accel;
                s *= accel;
                double r = Math.toRadians(yaw);
                double sin = Math.sin(r), cos = Math.cos(r);
                vx += s * cos - f * sin;
                vz += f * cos + s * sin;
            }
            double dx = vx, dy = vy, dz = vz;
            boolean landed = false;
            if (y + dy <= floor) {
                dy = floor - y;
                landed = true;
            }
            x += dx;
            y += dy;
            z += dz;
            onGround = landed;
            if (landed) {
                // updateEntityMovementAfterFallOn: slime throws the fall back up, a bed at 0.66 × 0.8, other floors stop it
                boolean bounce = (slime || bed) && !sneaking && vy < 0;
                vy = bounce ? -vy * (slime ? 1.0 : 0.66 * 0.8) : 0;
            }
            double g = slowFalling && vy <= 0 ? Math.min(gravity, 0.01) : gravity;
            vy = (vy - g) * 0.98;
            vx *= friction;
            vz *= friction;
            // applyEffectsFromBlocks after travel: the honey wall sets the next fall
            if (honeyWall && !onGround && vy / 0.98 + 0.08 < -0.08) vy = (-0.05 - 0.08) * 0.98F;
            return new double[]{dx, dy, dz};
        }
    }

    /** What the server knows and the predictor judges, fed the way MovementChecks fills it. */
    static final class Judge {
        final MoveInput in = new MoveInput();
        final MoveEnvelope env = new MoveEnvelope();
        double prevDx, prevDy, prevDz;
        boolean groundPrev = true;
        boolean carryGround = true;
        /** What MovementChecks.accept remembers after a landing on slime or a bed. */
        double bounce;
        int violations, moves;
        double worstH, worstV;
        final List<String> log = new ArrayList<>();

        void setup(Client c) {
            in.slipPrev = c.slime ? 0.8 : c.slip;
            in.slipAlt = in.slipPrev;
            in.honeySlide = c.honeyWall;
            in.bounce = bounce;
            // the server's window: the attribute with the sprint; ×1.3 when it did not see the sprint
            in.speed = BASE_SPEED * c.speedMultiplier * 1.3;
            in.jumpBoost = c.jumpBoost;
            in.gravity = c.slowFalling ? 0.01 : c.gravity;
        }

        boolean judge(Client c, double[] d, int ticks) {
            return judge(c, d, ticks, c.onGround);
        }

        boolean judge(Client c, double[] d, int ticks, boolean groundNow) {
            return judge(c, d, ticks, groundNow, groundNow);
        }

        /**
         * @param groundNow the client's on-ground flag at the end (it starts the next move from the ground)
         * @param resting   the server sees the box resting on a block at the end
         */
        boolean judge(Client c, double[] d, int ticks, boolean groundNow, boolean resting) {
            setup(c);
            in.prevDx = prevDx;
            in.prevDy = prevDy;
            in.prevDz = prevDz;
            in.carryGround = carryGround;
            in.carrySlip = in.slipPrev;
            in.groundPrev = groundPrev;
            in.restingNow = resting;
            in.ticks = ticks;
            in.dx = d[0];
            in.dy = d[1];
            in.dz = d[2];
            MovePredictor.envelope(in, env);
            moves++;
            double h = env.horizontalExcess(), v = env.verticalExcess();
            worstH = Math.max(worstH, h);
            worstV = Math.max(worstV, v);
            boolean bad = h > 0 || v > 0;
            if (bad) {
                violations++;
                if (log.size() < 4) {
                    log.add(String.format(Locale.ROOT, "  move %d: h %.4f / %.4f, up %.4f / %.4f, ground %b -> %b", moves, env.horizontal,
                            env.maxHorizontal, env.up, env.maxUp, groundPrev, groundNow));
                }
            }
            // what MovementChecks keeps: the next move carries at most what this one explained
            bounce = groundNow && !groundPrev && d[1] < 0 && (c.slime || c.bed) ? Math.max(-d[1], -((prevDy - 0.08) * 0.98)) : 0;
            double step = Math.hypot(d[0], d[2]);
            double scale = step > env.rawHorizontal && step > 0 ? env.rawHorizontal / step : 1;
            prevDx = d[0] * scale;
            prevDy = Math.min(d[1], env.rawUp);
            prevDz = d[2] * scale;
            carryGround = groundPrev;
            groundPrev = groundNow;
            in.clearVelocities();
            in.budgetHorizontal = 0;
            in.budgetUp = 0;
            in.platformRise = 0;
            return bad;
        }
    }

    interface Script {
        void run(Client c, Judge j);
    }

    private static void legal(String name, Script script) {
        Client c = new Client();
        Judge j = new Judge();
        script.run(c, j);
        boolean ok = j.violations == 0;
        if (!ok) failures++;
        System.out.printf(Locale.ROOT, "%s legal  %-44s %4d moves, violations %d (worst h +%.4f, up +%.4f)%n", ok ? "PASS" : "FAIL", name,
                j.moves, j.violations, j.worstH, j.worstV);
        if (!ok) j.log.forEach(System.out::println);
    }

    private static void cheat(String name, double minShare, Script script) {
        Client c = new Client();
        Judge j = new Judge();
        script.run(c, j);
        double share = j.moves == 0 ? 0 : (double) j.violations / j.moves;
        boolean ok = share >= minShare;
        if (!ok) failures++;
        System.out.printf(Locale.ROOT, "%s cheat  %-44s %4d moves, flagged %.0f%% (need %.0f%%)%n", ok ? "PASS" : "FAIL", name, j.moves,
                share * 100, minShare * 100);
    }

    /** Starts the judge on a client already in the air (its first tick unjudged, like the moves after a teleport). */
    private static void airborne(Client c, Judge j) {
        double[] first = c.tick(0, 0, false);
        j.prevDx = first[0];
        j.prevDy = first[1];
        j.prevDz = first[2];
        j.groundPrev = false;
        j.carryGround = false;
    }

    private static void run(Client c, Judge j, int ticks, double forward, double strafe, boolean jump) {
        for (int i = 0; i < ticks; i++) j.judge(c, c.tick(forward, strafe, jump), 1);
    }

    public static void main(String[] args) {
        // ---- legal: none may leave the envelope ----------------------------------------------------------------------
        legal("walk", (c, j) -> run(c, j, 80, 1, 0, false));
        legal("sprint", (c, j) -> {
            c.sprinting = true;
            run(c, j, 80, 1, 0, false);
        });
        legal("sprint diagonal", (c, j) -> {
            c.sprinting = true;
            run(c, j, 80, 1, 1, false);
        });
        legal("sprint jump", (c, j) -> {
            c.sprinting = true;
            run(c, j, 300, 1, 0, true);
        });
        legal("sprint jump, Speed II + Jump Boost II", (c, j) -> {
            c.sprinting = true;
            c.speedMultiplier = 1.4;
            c.jumpBoost = 1;
            run(c, j, 300, 1, 0, true);
        });
        legal("sprint jump on ice", (c, j) -> {
            c.sprinting = true;
            c.slip = 0.98;
            run(c, j, 300, 1, 0, true);
        });
        legal("sprint jump on blue ice, Speed III", (c, j) -> {
            c.sprinting = true;
            c.slip = 0.989;
            c.speedMultiplier = 1.6;
            run(c, j, 300, 1, 0, true);
        });
        legal("sprint, turn around every 10 ticks", (c, j) -> {
            c.sprinting = true;
            for (int i = 0; i < 200; i++) {
                if (i % 10 == 0) c.yaw += 180;
                j.judge(c, c.tick(1, 0, i % 7 == 0), 1);
            }
        });
        legal("fall from 60 blocks, strafing", (c, j) -> {
            c.y = 60;
            c.onGround = false;
            airborne(c, j);
            run(c, j, 120, 0, 1, false);
        });
        legal("fall with Slow Falling", (c, j) -> {
            c.y = 30;
            c.onGround = false;
            c.slowFalling = true;
            airborne(c, j);
            run(c, j, 200, 1, 0, false);
        });
        legal("knockback while standing", (c, j) -> {
            run(c, j, 10, 0, 0, false);
            for (int hit = 0; hit < 5; hit++) {
                // the server sets the velocity; it may take effect on this move
                c.vx = 0.42;
                c.vy = 0.36;
                c.vz = -0.1;
                j.in.hasSetVelocity = true;
                j.in.setVx = 0.42;
                j.in.setVy = 0.36;
                j.in.setVz = -0.1;
                j.judge(c, c.tick(-1, 0, false), 1);
                run(c, j, 30, -1, 0, false);
            }
        });
        legal("knockback while sprint jumping at it", (c, j) -> {
            c.sprinting = true;
            for (int i = 0; i < 200; i++) {
                if (i % 25 == 12) {
                    c.vx = -0.4;
                    c.vy = 0.4;
                    c.vz = -0.4;
                    j.in.hasSetVelocity = true;
                    j.in.setVx = -0.4;
                    j.in.setVy = 0.4;
                    j.in.setVz = -0.4;
                }
                j.judge(c, c.tick(1, 0, true), 1);
            }
        });
        legal("explosion push (added)", (c, j) -> {
            c.sprinting = true;
            run(c, j, 20, 1, 0, false);
            c.vx += 1.1;
            c.vy += 0.7;
            j.in.addVx = 1.1;
            j.in.addVy = 0.7;
            j.judge(c, c.tick(1, 0, false), 1);
            run(c, j, 60, 1, 0, false);
        });
        legal("stand still (no packets), then sprint", (c, j) -> {
            c.sprinting = true;
            for (int i = 0; i < 10; i++) c.tick(0, 0, false);
            j.judge(c, c.tick(1, 0, false), 11);
            run(c, j, 40, 1, 0, true);
        });

        legal("slide and jump out of it, with adrenaline (+35 %)", (c, j) -> {
            c.sprinting = true;
            c.speedMultiplier = 1.35;
            for (int round = 0; round < 6; round++) {
                run(c, j, 25, 1, 0, round % 2 == 1);
                // the slide sets the horizontal velocity every tick (no ground friction); the jump keeps it
                for (int i = 0; i < 8; i++) {
                    c.vx = 0;
                    c.vz = 0.56;
                    j.in.budgetHorizontal = SLIDE_BUDGET;
                    j.judge(c, c.tick(1, 0, i == 7), 1);
                }
                run(c, j, 30, 1, 0, false);
            }
        });
        legal("dive out of a sprint jump", (c, j) -> {
            c.sprinting = true;
            run(c, j, 20, 1, 0, false);
            for (int round = 0; round < 4; round++) {
                j.judge(c, c.tick(1, 0, true), 1);
                // the dive: launch speed along the run, the lift on top of what still rose
                c.vx = 0;
                c.vz = 0.6;
                c.vy = Math.max(0, c.vy) + 0.14;
                for (int i = 0; i < 40 && !(i > 0 && c.onGround); i++) {
                    j.in.budgetHorizontal = DIVE_BUDGET;
                    j.in.budgetUp = DIVE_UP;
                    j.judge(c, c.tick(0, 0, false), 1);
                    c.vz = Math.max(c.vz, 0) * 0.985 / 0.91;
                }
                // landed prone: the slide on the chest keeps its allowance (ActionAllowance, landSlideTicks)
                for (int i = 0; i < 12; i++) {
                    c.vz = Math.max(c.vz, 0) * 0.84 / 0.546;
                    j.in.budgetHorizontal = DIVE_BUDGET;
                    j.judge(c, c.tick(0, 0, false), 1);
                }
                run(c, j, 30, 1, 0, false);
            }
        });

        legal("jump into stair and slab steps (steps up 0.5 while rising)", (c, j) -> {
            c.sprinting = true;
            for (int round = 0; round < 6; round++) {
                run(c, j, 10, 1, 0, false);
                // the jump meets a half-block edge and steps onto it: the client is moving up, so not on the ground
                j.judge(c, new double[]{0.0, 0.5, 0.3}, 1, false, true);
                c.y += 0.5;
                c.vy = (0.42 - 0.08) * 0.98;
                c.onGround = false;
                run(c, j, 20, 1, 0, false);
            }
        });

        legal("bounce on slime from 10 blocks until it settles", (c, j) -> {
            c.slime = true;
            c.y = 10;
            c.onGround = false;
            airborne(c, j);
            run(c, j, 300, 0, 0, false);
        });
        legal("sprint jump on slime, then on a bed", (c, j) -> {
            c.sprinting = true;
            c.slime = true;
            run(c, j, 150, 1, 0, true);
            c.slime = false;
            c.bed = true;
            c.y = 3;
            c.onGround = false;
            run(c, j, 150, 1, 0, true);
        });
        legal("jump on honey (half strength), walk on it", (c, j) -> {
            c.honey = true;
            run(c, j, 100, 1, 0, true);
        });
        legal("fall onto a honey wall and slide down it", (c, j) -> {
            c.y = 20;
            c.onGround = false;
            airborne(c, j);
            run(c, j, 8, 0, 0, false);
            c.honeyWall = true;
            run(c, j, 120, 1, 0, false);
        });
        legal("walk up snow layers (1/8 steps), jump onto 6 more", (c, j) -> {
            c.sprinting = true;
            for (int layer = 0; layer < 6; layer++) {
                run(c, j, 4, 1, 0, false);
                // the next layer: vanilla steps up its 1/8 while walking on
                c.floor += 0.125;
                j.judge(c, c.tick(1, 0, false), 1, true, true);
            }
            run(c, j, 4, 1, 0, false);
            double start = c.floor;
            j.judge(c, c.tick(1, 0, true), 1);
            j.judge(c, c.tick(1, 0, false), 1);
            // a drift 6 layers (0.75) higher: the jump has risen past its edge and comes down on it
            c.floor = start + 0.75;
            for (int i = 0; i < 30; i++) {
                j.judge(c, c.tick(1, 0, false), 1);
            }
        });
        legal("step onto a boat its client shows 0.05 higher", (c, j) -> {
            c.sprinting = true;
            for (int round = 0; round < 5; round++) {
                run(c, j, 10, 1, 0, false);
                // vanilla steps up the boat's 0.5625 side from the ground; the server's copy settled a hair lower
                c.floor += 0.5625 + 0.05;
                j.in.platformRise = 0.05;
                j.judge(c, c.tick(1, 0, false), 1, true, true);
                run(c, j, 10, 1, 0, false);
                c.floor -= 0.5625 + 0.05;
                run(c, j, 10, 1, 0, false);
            }
        });
        legal("jump off a boat the server has 0.15 lower (moving)", (c, j) -> {
            for (int round = 0; round < 5; round++) {
                run(c, j, 10, 0, 0, false);
                j.in.platformRise = 0.2;
                j.judge(c, new double[]{0.0, 0.42 + 0.15, 0.0}, 1, false, false);
                c.vy = (0.42 - 0.08) * 0.98;
                c.onGround = false;
                c.y += 0.42;
                run(c, j, 20, 0, 0, false);
            }
        });

        // ---- cheats: most of their moves must leave the envelope ------------------------------------------------------
        cheat("speed x1.3 (sprint)", 0.9, (c, j) -> {
            c.sprinting = true;
            run(c, j, 30, 1, 0, false);
            j.violations = 0;
            j.moves = 0;
            for (int i = 0; i < 100; i++) {
                double[] d = c.tick(1, 0, false);
                double[] fast = {d[0] * 1.3, d[1], d[2] * 1.3};
                c.x += d[0] * 0.3;
                c.z += d[2] * 0.3;
                j.judge(c, fast, 1);
            }
        });
        cheat("speed x1.15 (sprint jump)", 0.5, (c, j) -> {
            c.sprinting = true;
            run(c, j, 30, 1, 0, true);
            j.violations = 0;
            j.moves = 0;
            for (int i = 0; i < 150; i++) {
                double[] d = c.tick(1, 0, true);
                // the cheat keeps its extra speed (it moves the client that much further every tick)
                c.vx *= 1.15;
                c.vz *= 1.15;
                j.judge(c, d, 1);
            }
        });
        cheat("speed x1.12 (sprint, no jumps)", 0.9, (c, j) -> {
            c.sprinting = true;
            run(c, j, 30, 1, 0, false);
            j.violations = 0;
            j.moves = 0;
            for (int i = 0; i < 100; i++) {
                double[] d = c.tick(1, 0, false);
                j.judge(c, new double[]{d[0] * 1.12, d[1], d[2] * 1.12}, 1);
            }
        });
        cheat("hover in mid-air", 0.95, (c, j) -> {
            c.y = 5;
            c.onGround = false;
            j.groundPrev = false;
            j.carryGround = false;
            for (int i = 0; i < 40; i++) j.judge(c, new double[]{0.05, 0, 0}, 1, false);
        });
        cheat("slow glide (constant fall -0.05)", 0.9, (c, j) -> {
            c.y = 40;
            c.onGround = false;
            j.groundPrev = false;
            j.prevDy = -0.05;
            j.carryGround = false;
            for (int i = 0; i < 60; i++) j.judge(c, new double[]{0.1, -0.05, 0}, 1, false);
        });
        cheat("high jump (0.62)", 1.0, (c, j) -> {
            run(c, j, 5, 0, 0, false);
            j.violations = 0;
            j.moves = 0;
            j.judge(c, new double[]{0, 0.62, 0}, 1, false);
        });
        cheat("step a full block", 1.0, (c, j) -> {
            c.sprinting = true;
            run(c, j, 10, 1, 0, false);
            j.violations = 0;
            j.moves = 0;
            j.judge(c, new double[]{0, 1.0, 0.25}, 1, true);
        });
        cheat("blink (3 blocks in one move)", 1.0, (c, j) -> {
            run(c, j, 10, 1, 0, false);
            j.violations = 0;
            j.moves = 0;
            j.judge(c, new double[]{3, 0, 0}, 1);
        });

        cheat("slow glide beside a honey wall (-0.05)", 0.9, (c, j) -> {
            c.y = 40;
            c.onGround = false;
            c.honeyWall = true;
            j.groundPrev = false;
            j.prevDy = -0.05;
            j.carryGround = false;
            for (int i = 0; i < 60; i++) j.judge(c, new double[]{0.05, -0.05, 0}, 1, false);
        });
        cheat("slime bounce 30 % higher than the fall", 0.9, (c, j) -> {
            c.slime = true;
            c.y = 6;
            c.onGround = false;
            airborne(c, j);
            int bounces = 0, flagged = 0;
            for (int i = 0; i < 400 && bounces < 6; i++) {
                boolean wasGround = c.onGround;
                j.judge(c, c.tick(0, 0, false), 1);
                if (c.onGround && !wasGround) {
                    bounces++;
                    // the cheat throws itself up faster than the slime did
                    c.vy *= 1.3;
                    int before = j.violations;
                    j.judge(c, c.tick(0, 0, false), 1);
                    if (j.violations > before) flagged++;
                }
            }
            // only the throws count
            j.moves = bounces;
            j.violations = flagged;
        });
        cheat("step a full block beside a still boat", 1.0, (c, j) -> {
            run(c, j, 10, 1, 0, false);
            j.violations = 0;
            j.moves = 0;
            j.in.platformRise = 0.05;
            j.judge(c, new double[]{0, 1.0, 0.2}, 1, true);
        });
        cheat("high jump (0.8) off a still boat", 1.0, (c, j) -> {
            run(c, j, 5, 0, 0, false);
            j.violations = 0;
            j.moves = 0;
            j.in.platformRise = 0.05;
            j.judge(c, new double[]{0, 0.8, 0}, 1, false);
        });

        System.out.println(failures == 0 ? "all movement checks pass" : failures + " movement check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
