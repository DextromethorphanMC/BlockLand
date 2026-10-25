package com.blockland.server.anticheat.evidence;

import com.blockland.server.anticheat.check.CheckCatalog;
import com.blockland.server.anticheat.rules.CheckSettings;
import com.blockland.server.anticheat.rules.RuleSet;

/**
 * How sure the anti-cheat is that a player cheats (anti-cheat spec §7). Each check's level saturates towards its
 * weight — {@code c = weight × (1 − e^(−VL / s))}, {@code s} a third of the kick level — and checks that latency can
 * fool count less while the player's round trip is above the preset's high-ping mark. Checks combine as independent
 * evidence: {@code C = 1 − Π(1 − c)}.
 */
public final class Confidence {
    /** The combined confidence and the check that contributes most. */
    public record Result(double value, int top, double topValue) {
    }

    private Confidence() {
    }

    /** One check's confidence at level {@code vl}; {@code latencyFactor} ≤ 1 for latency-sensitive checks on a slow link. */
    public static double single(CheckSettings settings, double vl, double latencyFactor) {
        if (!settings.enabled() || vl <= 0) return 0;
        double scale = settings.kickVl() > 0 ? 0.35 * settings.kickVl() : 15;
        return settings.weight() * (1 - Math.exp(-vl / Math.max(1, scale))) * latencyFactor;
    }

    /** The factor latency-sensitive checks are scaled by at round trip {@code rttMs}. */
    public static double latencyFactor(RuleSet rules, double rttMs) {
        return rttMs <= rules.highPingMs ? 1 : Math.max(0.3, rules.highPingMs / Math.max(1, rttMs));
    }

    public static Result of(ViolationLedger ledger, RuleSet rules, double rttMs, long now) {
        CheckCatalog catalog = CheckCatalog.get();
        double latency = latencyFactor(rules, rttMs);
        double keep = 1;
        int top = -1;
        double topValue = 0;
        for (int i = 0; i < ledger.size() && i < catalog.size(); i++) {
            CheckSettings settings = rules.check(i);
            if (!settings.enabled()) continue;
            double vl = ledger.get(i, settings.decay(), now);
            if (vl <= 0) continue;
            double c = single(settings, vl, catalog.at(i).latencySensitive() ? latency : 1);
            keep *= 1 - c;
            if (c > topValue) {
                topValue = c;
                top = i;
            }
        }
        return new Result(1 - keep, top, topValue);
    }
}
