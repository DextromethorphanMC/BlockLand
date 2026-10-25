package com.blockland.server.anticheat.track;

import com.blockland.server.anticheat.evidence.ViolationLedger;
import com.blockland.server.anticheat.predict.MoveEnvelope;
import com.blockland.server.anticheat.predict.MoveInput;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything the anti-cheat keeps about one player in the world (the state tracking layer, anti-cheat spec §2): the
 * last accepted position and step, ground and surroundings, the per-tick state window, latency, pings in flight, the
 * server's velocities, the client tick clock, violation levels and combat bookkeeping. Plain fields, server thread only
 * (the netty-side arrival times live in {@code net.Arrivals}).
 */
public final class PlayerTrack {
    public final UUID id;
    /** The entity the state belongs to (a respawn makes a new one: everything positional starts over). */
    public int entityId = -1;

    // ---- the last accepted move ----------------------------------------------------------------------------
    public boolean anchored;
    public double x, y, z;
    public float yaw, pitch;
    public double prevDx, prevDy, prevDz;
    public boolean groundPrev;
    /** The last move started on the ground, on this slipperiness: the friction that carried its step into the next. */
    public boolean carryGround;
    public double carrySlip = 0.6;
    /** Upwards speed a landing on slime / a bed gives the next move, 0 = none. */
    public double bounce;
    /** Moves since the player was last in a liquid (exit grace). */
    public int liquidAge = 1000;
    /** At the accepted position something holds the player up without ground (liquid, ladder, cobweb, bubble column). */
    public boolean heldUp;
    /** The last judged move was exempt (flying, riding…): the first judged one after it starts afresh. */
    public boolean wasExempt;
    /** Vanilla is waiting for the player to confirm a teleport: moves are not judged until it did. */
    public boolean teleportPending;
    /** When and at which height the player last stood on a block the server sees (a floor removed under them). */
    public int restTick = Integer.MIN_VALUE / 2;
    public double restY = Double.NaN;
    /** Moves to skip after a teleport, respawn or exemption ending (the client settles). */
    public int settleMoves;
    /** Server tick until which this player is exempt ({@code /bl ac exempt}). */
    public int exemptUntil;

    // ---- client ticks ------------------------------------------------------------------------------------
    /** Client tick-end packets since the last movement packet. */
    public int tickEndsSinceMove;
    /** Consecutive client ticks without a movement packet while airborne. */
    public int silentAirTicks;
    public final TickClock clock = new TickClock();

    // ---- ground verification of the movement packet being handled -----------------------------------------------
    public Object groundPacket;
    public boolean groundVerified;

    // ---- state, latency, pings, velocities ------------------------------------------------------------
    public final StateWindow window = new StateWindow();
    public final LatencyStats latency = new LatencyStats();
    public final Pings pings = new Pings();
    public final List<ServerVelocity> velocities = new ArrayList<>(4);
    /** A ping should follow what was sent to this player this tick (a velocity). */
    public boolean wantsPing;

    // ---- judging ------------------------------------------------------------------------------------------
    public final ViolationLedger ledger;
    public final Surroundings around = new Surroundings();
    public final MoveInput input = new MoveInput();
    public final MoveEnvelope envelope = new MoveEnvelope();

    // ---- combat --------------------------------------------------------------------------------------------
    /** Client tick count (tick-ends seen) at the last attack, and the entity attacked then. */
    public long clientTicks;
    public long attackTick = -1;
    public int attackTarget = -1;
    /** Attacks within the current second, and when that second started (nanos). */
    public int attacksThisSecond;
    public long attackSecondStart;

    // ---- enforcement ------------------------------------------------------------------------------------
    public boolean banRequested;
    public long lastAlertNanos;
    public long lastTimeoutReportNanos;

    public PlayerTrack(UUID id, int checks) {
        this.id = id;
        this.ledger = new ViolationLedger(checks);
    }

    /** Starts the positional state over at the player's current position (teleport, respawn, first sight). */
    public void anchor(double px, double py, double pz, boolean onGround) {
        anchored = true;
        x = px;
        y = py;
        z = pz;
        prevDx = prevDy = prevDz = 0;
        groundPrev = onGround;
        carryGround = onGround;
        carrySlip = 0.6;
        bounce = 0;
        silentAirTicks = 0;
        tickEndsSinceMove = 1;
        settleMoves = Math.max(settleMoves, 1);
    }

    /** Pings in flight: ids (negative, so they never meet vanilla's) and when each was sent, oldest first. */
    public static final class Pings {
        public static final int CAPACITY = 128;
        private final int[] ids = new int[CAPACITY];
        private final long[] sent = new long[CAPACITY];
        private int head;
        private int size;
        private int next = -1;
        /** Ids above this were sent before the last {@link #clear} (a pong for one is late, not forged). */
        private int clearedAt;

        public int nextId() {
            int id = next;
            next = next <= Integer.MIN_VALUE + 1 ? -1 : next - 1;
            return id;
        }

        public boolean full() {
            return size >= CAPACITY;
        }

        public void push(int id, long nanos) {
            if (full()) pop();
            int at = (head + size) % CAPACITY;
            ids[at] = id;
            sent[at] = nanos;
            size++;
        }

        public int size() {
            return size;
        }

        public int oldestId() {
            return ids[head];
        }

        public long oldestSent() {
            return sent[head];
        }

        public void pop() {
            if (size == 0) return;
            head = (head + 1) % CAPACITY;
            size--;
        }

        /** How many entries are ahead of {@code id}, -1 when it is not in flight. */
        public int position(int id) {
            for (int i = 0; i < size; i++) if (ids[(head + i) % CAPACITY] == id) return i;
            return -1;
        }

        public boolean stale(int id) {
            return id > clearedAt;
        }

        public void clear() {
            head = 0;
            size = 0;
            clearedAt = next;
        }
    }
}
