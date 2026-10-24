package com.blockland.client.impact;

import java.util.Locale;

/**
 * The player's bullet impact settings (kept with the other client settings, {@code config/blockland-settings.json} key
 * {@code impacts}; field names = JSON keys): a quality level, switches for dust, bounces, the surroundings and fluids,
 * and the blood.
 */
public final class ImpactSettings {
    /**
     * How much an impact throws out and how far away it is still drawn in full.
     *
     * @param count  multiplier of the data's counts
     * @param budget most live pieces of each kind (debris, dust, sparks) at once; the oldest give way
     * @param detail distance (blocks) up to which impacts are full, fading to a few pieces at twice that
     */
    public enum Quality {
        OFF(0, 0, 0), LOW(0.35f, 500, 20), MEDIUM(0.65f, 1200, 36), HIGH(1, 2500, 56), ULTRA(1.5f, 5000, 88);

        public final float count;
        public final int budget;
        public final float detail;

        Quality(float count, int budget, float detail) {
            this.count = count;
            this.budget = budget;
            this.detail = detail;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Quality of(String id) {
            for (Quality q : values()) {
                if (q.id().equalsIgnoreCase(id)) return q;
            }
            return HIGH;
        }
    }

    public String quality = Quality.HIGH.id();
    /** Debris and sparks bounce off what they land on (off: they vanish on contact). */
    public boolean secondCollisions = true;
    /** Puffs of dust and powder (the most expensive part on screen). */
    public boolean dust = true;
    /** Weather and surroundings change the effect (rain, snow, under water, wind). */
    public boolean environment = true;
    /** How much blood a hit on a living target throws (drops, mist, plumes), 0..2 (0 = none). */
    public float blood = 1;
    /** Blood spreads in water as a drifting, colouring plume (off: no blood under water). */
    public boolean bloodInWater = true;
    /**
     * How long landed blood stays on floors, walls and ceilings (× the data's time), 0..2 (0 = drops vanish where they
     * land).
     */
    public float bloodStains = 1;
    /**
     * Fluids act on what is in them: chunks splash into water and sink or float and drift with the current, burn up in
     * lava, sparks die in water, bubbles rise and wobble, rounds leave a trail of bubbles under water (off: pieces only
     * fall more slowly in water).
     */
    public boolean fluidPhysics = true;

    public Quality qualityLevel() {
        return Quality.of(quality);
    }

    public void sanitize() {
        quality = Quality.of(quality).id();
        blood = Float.isNaN(blood) ? 1 : Math.max(0, Math.min(2, blood));
        bloodStains = Float.isNaN(bloodStains) ? 1 : Math.max(0, Math.min(2, bloodStains));
    }
}
