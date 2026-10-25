package com.blockland.server.parachute;

import com.blockland.BlockLand;
import com.blockland.parachute.ParachuteRules;
import com.blockland.server.anticheat.api.AntiCheatSignals;
import com.blockland.server.anticheat.api.Suspicion;
import com.blockland.server.util.FloatingCheck;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Judges the air time of players who wear a parachute from the positions the server accepted, tick by tick: under an
 * open canopy they must sink at least about the canopy's descent speed and move no faster sideways than it steers
 * (plus the momentum they came in with, decaying as it would); in free fall they must actually fall; nobody climbs in
 * mid-air. Only when a full window of ticks passed the test is vanilla's floating kick {@link FloatingCheck#excuse
 * excused} — so a sky-dive is never kicked, while claiming a canopy (or wearing one) gives no way to hover or fly: a
 * failed tick closes the canopy and puts the player back where they last moved legally, and repeated failures kick.
 */
final class ParachuteFlightCheck {
    /** Ticks of positions a vertical test looks back over. */
    private static final int WINDOW = 10;
    /** A single step longer than this is a teleport by the server (a game moving the player): history starts over. */
    private static final double TELEPORT_STEP = 8;
    /** Free fall: blocks the window must drop once the fall is under way (gravity gives several times as much). */
    private static final double FREE_FALL_DROP = 1.0;
    /** Sideways speed possible without a canopy (air control on top of a sprint jump), blocks per tick. */
    private static final double FREE_FALL_SIDEWAYS = 0.36;
    /** Rising more than this over a window in mid-air (after a jump's own climb) is impossible. */
    private static final double MAX_RISE = 0.6;
    /** Strikes within {@link #STRIKE_WINDOW} ticks that end in a kick. */
    private static final int KICK_STRIKES = 4;
    private static final int STRIKE_WINDOW = 200;

    private final Map<UUID, Flight> flights = new HashMap<>();

    private static final class Flight {
        final double[] x = new double[WINDOW + 1], y = new double[WINDOW + 1], z = new double[WINDOW + 1];
        int samples;
        int head;
        int airTicks;
        boolean open;
        /** Ticks since the canopy last opened or closed. */
        int stateTicks;
        /** Sideways blocks per tick allowed beyond the steady limit: momentum carried in, decaying. */
        double carried;
        boolean hasSafe;
        double safeX, safeY, safeZ;
        int strikes;
        int firstStrikeTick;

        void restart() {
            samples = 0;
            head = 0;
        }

        void push(double px, double py, double pz) {
            head = (head + 1) % (WINDOW + 1);
            x[head] = px;
            y[head] = py;
            z[head] = pz;
            samples = Math.min(samples + 1, WINDOW + 1);
        }

        /** The sample {@code back} ticks ago (0 = the newest). */
        int at(int back) {
            return (head - back + WINDOW + 1) % (WINDOW + 1);
        }
    }

    /** What the check does to a player it caught (implemented by the parachute service). */
    interface Enforcer {
        void closeCanopy(ServerPlayer player);
    }

    /** Called every server tick for every player, after their movement for the tick was accepted. */
    void tick(ServerPlayer player, boolean open, int now, Enforcer enforcer) {
        if (!airborne(player)) {
            Flight flight = flights.get(player.getUUID());
            if (flight != null) {
                flight.restart();
                flight.airTicks = 0;
            }
            return;
        }
        Flight flight = flights.computeIfAbsent(player.getUUID(), id -> new Flight());
        ParachuteRules rules = ParachuteRules.get();
        flight.airTicks++;
        if (open != flight.open) {
            flight.open = open;
            flight.stateTicks = 0;
        } else {
            flight.stateTicks++;
        }
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        if (flight.samples == 0) {
            // whatever momentum the fall began with (a plane, a run-up) is allowed to wear off
            flight.carried = Math.max(flight.carried, player.getDeltaMovement().horizontalDistance() + 0.3);
            flight.push(px, py, pz);
            return;
        }
        int last = flight.at(0);
        double dx = px - flight.x[last], dy = py - flight.y[last], dz = pz - flight.z[last];
        if (dx * dx + dy * dy + dz * dz > TELEPORT_STEP * TELEPORT_STEP) {
            flight.restart();
            flight.carried = player.getDeltaMovement().horizontalDistance() + 0.3;
            flight.push(px, py, pz);
            return;
        }
        // a hit (knock back, a blast) may throw the player: its push is the server's, so it is allowed
        if (player.hurtTime > 0) flight.carried = Math.max(flight.carried, 3.0);
        double limit = open ? (rules.steerSpeed + rules.forwardGlide) * 1.35 + 0.05 : FREE_FALL_SIDEWAYS;
        double sideways = Math.sqrt(dx * dx + dz * dz);
        String failure = null;
        if (sideways > (limit + flight.carried) * 1.15 + 0.05) failure = "moved sideways too fast";
        flight.carried *= open ? 1 - rules.steerResponse : 0.92;
        flight.push(px, py, pz);

        if (failure == null && flight.samples > WINDOW) {
            double drop = py - flight.y[flight.at(WINDOW)];
            if (open && flight.stateTicks > rules.deployTicks + 4 && drop > -rules.descentSpeed * WINDOW * 0.55) {
                failure = "did not sink under the canopy";
            } else if (!open && flight.stateTicks > 2 * WINDOW && flight.airTicks > 2 * WINDOW && drop > -FREE_FALL_DROP) {
                failure = "did not fall";
            } else if (flight.airTicks > 15 && player.hurtTime == 0 && drop > MAX_RISE) {
                failure = "climbed in mid-air";
            }
        }
        if (failure != null) {
            strike(player, flight, failure, now, enforcer);
            return;
        }
        if (flight.samples > WINDOW) {
            FloatingCheck.excuse(player);
            flight.hasSafe = true;
            flight.safeX = px;
            flight.safeY = py;
            flight.safeZ = pz;
        }
    }

    private void strike(ServerPlayer player, Flight flight, String failure, int now, Enforcer enforcer) {
        if (now - flight.firstStrikeTick > STRIKE_WINDOW) {
            flight.firstStrikeTick = now;
            flight.strikes = 0;
        }
        flight.strikes++;
        BlockLand.LOGGER.warn("[BlockLand] {} {} while wearing a parachute (strike {}/{})", player.getName().getString(), failure,
                flight.strikes, KICK_STRIKES);
        Suspicion.report(player, AntiCheatSignals.PARACHUTE, 1, failure);
        enforcer.closeCanopy(player);
        if (flight.strikes >= KICK_STRIKES) {
            flights.remove(player.getUUID());
            player.connection.disconnect(Component.translatable("blockland.parachute.kick"));
            return;
        }
        // back to where it last moved legally; the fall starts over from there
        if (flight.hasSafe) player.connection.teleport(flight.safeX, flight.safeY, flight.safeZ, player.getYRot(), player.getXRot());
        flight.restart();
        flight.carried = 0;
    }

    /** In the open air, wearing a parachute, and subject to normal physics (not riding, swimming, climbing or flying). */
    private static boolean airborne(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative() && !player.getAbilities().mayfly
                && ParachuteRules.wears(player) && !player.onGround() && !player.isInWater() && !player.isInLava()
                && !player.isPassenger() && !player.isFallFlying() && !player.onClimbable() && !player.isSleeping()
                && !player.isNoGravity();
    }

    void forget(UUID player) {
        flights.remove(player);
    }

    void clear() {
        flights.clear();
    }
}
