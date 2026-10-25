package com.blockland.server.anticheat.evidence;

/**
 * A player's violation level per check (anti-cheat spec §7): rises with every violation, wears off by the check's decay
 * per second of clean play. Decay is applied lazily from timestamps when a value is read or changed, so nothing runs
 * per tick. Also remembers what was last reported per check, so evidence goes out on threshold crossings, not on every
 * violation.
 */
public final class ViolationLedger {
    private final double[] vl;
    private final long[] at;
    private final double[] reported;
    private final long[] reportedAt;

    public ViolationLedger(int checks) {
        vl = new double[checks];
        at = new long[checks];
        reported = new double[checks];
        reportedAt = new long[checks];
    }

    /** The level after decay up to {@code now}. */
    public double get(int check, double decayPerSecond, long now) {
        if (check < 0 || check >= vl.length) return 0;
        decay(check, decayPerSecond, now);
        return vl[check];
    }

    /** Adds a violation; returns the new level. */
    public double add(int check, double amount, double decayPerSecond, long now) {
        if (check < 0 || check >= vl.length) return 0;
        decay(check, decayPerSecond, now);
        vl[check] = Math.min(100_000, vl[check] + Math.max(0, amount));
        return vl[check];
    }

    private void decay(int check, double decayPerSecond, long now) {
        if (at[check] != 0 && vl[check] > 0) {
            double seconds = (now - at[check]) / 1e9;
            vl[check] = Math.max(0, vl[check] - seconds * Math.max(0, decayPerSecond));
        }
        at[check] = now;
        // a level that wore off far below the last report may be reported again once it climbs back
        if (vl[check] < reported[check] * 0.5) reported[check] = vl[check];
    }

    /**
     * Whether a level is worth a new piece of evidence: it crossed {@code step} (the check's setback level, at least 1)
     * or rose another {@code step} since the last report, and the check's report cooldown is over.
     */
    public boolean shouldReport(int check, double level, double step, long now, long cooldownNanos) {
        if (check < 0 || check >= vl.length) return false;
        double s = Math.max(1, step);
        if (level < s || level - reported[check] < s) return false;
        if (reportedAt[check] != 0 && now - reportedAt[check] < cooldownNanos) return false;
        reported[check] = level;
        reportedAt[check] = now;
        return true;
    }

    public void reset() {
        java.util.Arrays.fill(vl, 0);
        java.util.Arrays.fill(at, 0);
        java.util.Arrays.fill(reported, 0);
        java.util.Arrays.fill(reportedAt, 0);
    }

    public int size() {
        return vl.length;
    }
}
