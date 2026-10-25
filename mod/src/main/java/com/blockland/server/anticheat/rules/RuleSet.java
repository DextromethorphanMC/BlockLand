package com.blockland.server.anticheat.rules;

/**
 * Everything the anti-cheat does for the players of one mode (anti-cheat spec §4), resolved from the global config, the
 * mode's preset and the mode's own overrides: the preset-level numbers and every check's settings by catalog index.
 * Immutable; cached per mode and configuration version by {@link RuleResolver}.
 */
public final class RuleSet {
    /** Nothing is checked (global switch, the mode's switch). State is still tracked. */
    public final boolean enabled;
    public final String preset;
    public final double toleranceScale;
    // ping compensation (spec §5)
    public final double maxCompensationMs;
    public final double jitterMultiplier;
    public final int extraTicks;
    public final double highPingMs;
    // confidence thresholds (spec §7)
    public final double alert;
    public final double review;
    public final double autoBan;
    // master switches
    public final boolean setback;
    public final boolean kick;
    public final boolean autoBanAllowed;
    private final CheckSettings[] checks;

    RuleSet(boolean enabled, String preset, double toleranceScale, double maxCompensationMs, double jitterMultiplier, int extraTicks,
            double highPingMs, double alert, double review, double autoBan, boolean setback, boolean kick, boolean autoBanAllowed,
            CheckSettings[] checks) {
        this.enabled = enabled;
        this.preset = preset;
        this.toleranceScale = toleranceScale;
        this.maxCompensationMs = maxCompensationMs;
        this.jitterMultiplier = jitterMultiplier;
        this.extraTicks = extraTicks;
        this.highPingMs = highPingMs;
        this.alert = alert;
        this.review = review;
        this.autoBan = autoBan;
        this.setback = setback;
        this.kick = kick;
        this.autoBanAllowed = autoBanAllowed;
        this.checks = checks;
    }

    /** The check's settings; off when the whole rule set is. */
    public CheckSettings check(int index) {
        if (!enabled || index < 0 || index >= checks.length) return CheckSettings.OFF;
        return checks[index];
    }

    public boolean on(int index) {
        return check(index).enabled();
    }

    /** The check's effective tolerance: its own times the preset's (or the mode's) scale. */
    public double tolerance(int index) {
        return Math.max(0.1, check(index).tolerance() * toleranceScale);
    }
}
