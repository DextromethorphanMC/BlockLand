package com.blockland.server.anticheat.track;

/**
 * The client's tick rate by the arrival times of its tick-end packets (anti-cheat spec §5): every tick spends 50 ms of a
 * balance that real time refills. A client faster than 20 ticks per second drains it; packets that a network stall
 * held back and then delivered at once are paid for by the time the stall banked, up to a cap.
 */
public final class TickClock {
    private long last;
    private double balance;

    /**
     * One tick-end arrived at {@code nanos}.
     *
     * @param bankMs   most time the client may have banked (a stall's worth)
     * @param floorMs  how far below zero the balance may go before ticks count as extra (positive number)
     * @return milliseconds of ticks beyond real time this packet made (0 = fine); the balance is held at the floor
     */
    public double tick(long nanos, double bankMs, double floorMs) {
        if (last == 0) {
            last = nanos;
            return 0;
        }
        double elapsed = (nanos - last) / 1e6;
        last = nanos;
        balance = Math.min(bankMs, balance + elapsed - 50.0);
        if (balance >= -floorMs) return 0;
        double over = -floorMs - balance;
        balance = -floorMs;
        return over;
    }

    public double balance() {
        return balance;
    }

    public void reset() {
        last = 0;
        balance = 0;
    }
}
