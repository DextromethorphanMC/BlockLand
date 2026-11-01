package com.blockland.client.hud.damage;

import net.minecraft.world.phys.Vec3;

/**
 * One damage arrow: where its hurts came from (the attacker to follow, or the point) and how much they took lately.
 * Every hit from the same attacker lands on the same arrow: it pops again and grows with the damage, which then cools.
 */
final class DamageArrow {
    /** The attacker's entity id to follow while it can be seen, 0 to stay at {@link #at}. */
    int attacker;
    /** Where the hurts came from (the attacker's eyes as last seen, or the blast). */
    Vec3 at;
    /** {@code RenderClock} nanos of the latest hit. */
    long last;
    /** Recent damage to the health and to the plates as of the latest hit (it halves with time). */
    float health, plates;
    /** The latest hit's size against a strong hit (0.35 … 1): how hard it pops. */
    float kick;

    DamageArrow(int attacker, Vec3 at) {
        this.attacker = attacker;
        this.at = at;
    }

    void hit(long now, Vec3 from, float health, float plates, DamageIndicatorData d) {
        float keep = last == 0 ? 0 : decay(now, d);
        this.health = this.health * keep + health;
        this.plates = this.plates * keep + plates;
        this.last = now;
        this.at = from;
        kick = Math.max(0.35f, Math.min(1, (health + plates) / Math.max(0.1f, d.fullDamage * 0.5f)));
    }

    /** Seconds since the latest hit. */
    float age(long now) {
        return Math.max(0, (now - last) / 1e9f);
    }

    private float decay(long now, DamageIndicatorData d) {
        return (float) Math.pow(0.5, age(now) / Math.max(0.05f, d.halfLife));
    }

    /** How strong it is now (the recent damage against {@code fullDamage}, at least {@code minStrength}). */
    float strength(long now, DamageIndicatorData d) {
        float recent = (health + plates) * decay(now, d);
        return Math.max(d.minStrength, Math.min(1, recent / Math.max(0.1f, d.fullDamage)));
    }

    /** The plates' share of what it took (its colour, from the health's to the plates'). */
    float plateShare() {
        float sum = health + plates;
        return sum <= 0 ? 0 : plates / sum;
    }
}
