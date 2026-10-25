package com.blockland.server.anticheat.net;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arrival times of tick-end packets, recorded on the netty thread and taken in order by the server thread when it
 * handles the same packets (anti-cheat spec §5): the timer check measures when packets reached us, not when a busy
 * server tick got round to them. A missed pairing only shifts every later time by one packet, which the timer's
 * differences do not see.
 */
public final class Arrivals {
    private static final int CAPACITY = 256;

    private final ConcurrentHashMap<UUID, Ring> rings = new ConcurrentHashMap<>();

    private static final class Ring {
        private final long[] times = new long[CAPACITY];
        private int head;
        private int size;

        synchronized void push(long nanos) {
            if (size == CAPACITY) {
                head = (head + 1) % CAPACITY;
                size--;
            }
            times[(head + size) % CAPACITY] = nanos;
            size++;
        }

        synchronized long pop() {
            if (size == 0) return 0;
            long t = times[head];
            head = (head + 1) % CAPACITY;
            size--;
            return t;
        }

        synchronized void clear() {
            head = 0;
            size = 0;
        }
    }

    /** Netty thread. */
    public void push(UUID player, long nanos) {
        rings.computeIfAbsent(player, id -> new Ring()).push(nanos);
    }

    /** Server thread: the oldest recorded arrival, or now when none was recorded. */
    public long pop(UUID player) {
        Ring ring = rings.get(player);
        long t = ring == null ? 0 : ring.pop();
        return t == 0 ? System.nanoTime() : t;
    }

    public void reset(UUID player) {
        Ring ring = rings.get(player);
        if (ring != null) ring.clear();
    }

    public void forget(UUID player) {
        rings.remove(player);
    }

    public void clear() {
        rings.clear();
    }
}
