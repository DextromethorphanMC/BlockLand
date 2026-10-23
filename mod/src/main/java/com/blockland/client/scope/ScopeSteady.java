package com.blockland.client.scope;

import com.blockland.craft.CraftData;
import org.joml.Vector2f;

import javax.annotation.Nullable;

/**
 * The aimed rifle's breathing sway through a magnified scope, and holding the breath to steady it (the sprint key while
 * aimed, like COD's). The sway turns the rifle about the support hand, so the reticle wanders over the target and —
 * since shots follow the sight line — so does the point of impact. Holding the breath stills it for the scope's
 * {@code holdBreath} seconds; running out makes the rifle tremble until the breath is back. Client-side feel only: the
 * server checks shots against the look direction, not against the sway.
 */
final class ScopeSteady {
    /** Breath refills this many seconds after running out completely. */
    private static final float REFILL = 3.2f;
    /** After running out the breath cannot be held again for this long. */
    private static final float WINDED = 1.6f;

    private final Vector2f sway = new Vector2f();
    private final Vector2f scratch = new Vector2f();
    private float clock;
    private float breath = 1;
    private boolean holding;
    /** 0 = breathing, 1 = held still (eased). */
    private float steady;
    /** 1 just after running out, fading. */
    private float tremble;
    private float winded;

    /**
     * @param scope    the raised scope's optics, null when no magnified scope is up
     * @param aim      eased aim amount (sway grows in as the sights come up)
     * @param wantHold the player holds the steady key
     */
    void update(float dt, @Nullable CraftData.Scope scope, float aim, boolean wantHold) {
        float hold = scope == null ? 0 : scope.holdBreath;
        boolean canHold = hold > 0 && aim > 0.9f && winded <= 0 && breath > 0;
        boolean nowHolding = wantHold && canHold && (holding || breath > 0.15f);
        if (nowHolding) {
            breath -= dt / hold;
            if (breath <= 0) {
                breath = 0;
                nowHolding = false;
                winded = WINDED;
                tremble = 1;
            }
        } else {
            breath = Math.min(1, breath + dt / REFILL);
            winded = Math.max(0, winded - dt);
        }
        holding = nowHolding;
        steady += ((holding ? 1 : 0) - steady) * (1 - (float) Math.exp(-dt * (holding ? 9 : 4)));
        tremble = Math.max(0, tremble - dt / 2.4f);
        clock += dt * (1 + 0.6f * tremble);

        float amplitude = scope == null ? 0 : scope.sway * aim * (1 - 0.92f * steady) * (1 + 1.3f * tremble);
        ScopeOptics.sway(clock, scratch);
        // a fine tremor when winded
        float shake = scope == null ? 0 : scope.sway * aim * 0.35f * tremble;
        sway.set(scratch.x * amplitude + (float) Math.sin(clock * 23.0) * shake,
                scratch.y * amplitude + (float) Math.sin(clock * 19.0 + 1.1) * shake);
    }

    /** Yaw of the sway this frame (degrees). */
    float yaw() {
        return sway.x;
    }

    /** Pitch of the sway this frame (degrees, positive = muzzle down). */
    float pitch() {
        return sway.y;
    }

    boolean holding() {
        return holding;
    }

    /** Breath left, 0..1. */
    float breath() {
        return breath;
    }

    /** The breath ran out a moment ago and cannot be held yet. */
    boolean winded() {
        return winded > 0;
    }
}
