package com.blockland.combat;

/**
 * What the round that is hurting something right now carries (the gun's ammo attachment, `craft/attachments.json`):
 * set by the gun service around its hurt call, read by whatever soaks or answers the hurt (body armour, a vehicle's
 * hull, a burning hit) without knowing the gun module. Outside a round's hurt it is {@link #NONE}. Server thread only.
 */
public final class RoundTraits {
    /**
     * @param variant     the ammo attachment id ({@code normal}, {@code incendiary}, {@code armor_piercing},
     *                    {@code tracer}, {@code water}), empty for none
     * @param armorDamage multiplier on the durability armour (plates, vehicle hulls) loses to this round (1 = normal)
     * @param fireSeconds seconds the struck target burns (0 = no fire)
     */
    public record Traits(String variant, float armorDamage, float fireSeconds) {
    }

    public static final Traits NONE = new Traits("", 1, 0);

    private static Traits current = NONE;

    private RoundTraits() {
    }

    /** The traits of the round being applied right now, {@link #NONE} outside a round's hurt. */
    public static Traits current() {
        return current;
    }

    /** Runs {@code hurt} (a round's damage) with {@code traits} as {@link #current}. */
    public static void during(Traits traits, Runnable hurt) {
        Traits before = current;
        current = traits;
        try {
            hurt.run();
        } finally {
            current = before;
        }
    }
}
