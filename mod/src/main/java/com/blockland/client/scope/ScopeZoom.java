package com.blockland.client.scope;

import com.blockland.craft.CraftData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The power a magnified scope is set to. A variable scope ({@code zoomLevels}) steps through its powers with the mouse
 * wheel while aimed, and the picture eases from one to the next like a turned power ring; the chosen step is kept per
 * scope for the session. Only changes what this client sees (and its turning speed), so nothing is sent anywhere.
 */
final class ScopeZoom {
    /** How quickly the picture follows a new power (1/s, in log space). */
    private static final float EASE = 14;

    /** Chosen step per optic id. */
    private final Map<String, Integer> chosen = new HashMap<>();
    @Nullable
    private CraftData.Attachment optic;
    private float[] levels = {1};
    private int level;
    private float shown = 1;

    /** Follows the held optic (null = none or not a scope); a different scope starts at its remembered step. */
    void track(@Nullable CraftData.Attachment sight) {
        if (sight == optic) return;
        optic = sight;
        if (sight == null || !sight.isScope()) {
            levels = new float[]{1};
            level = 0;
        } else {
            levels = sight.scope.levels();
            level = Math.max(0, Math.min(levels.length - 1, chosen.getOrDefault(sight.id, sight.scope.startLevel())));
        }
        shown = levels[level];
    }

    /** Steps the power (+1 = up); false when the scope is fixed or already at that end. */
    boolean step(int direction) {
        int next = Math.max(0, Math.min(levels.length - 1, level + Integer.signum(direction)));
        if (next == level) return false;
        level = next;
        if (optic != null) chosen.put(optic.id, level);
        return true;
    }

    void update(float dt) {
        float target = levels[level];
        float k = 1 - (float) Math.exp(-dt * EASE);
        shown = (float) Math.exp(Math.log(shown) + (Math.log(target) - Math.log(shown)) * k);
    }

    boolean variable() {
        return levels.length > 1;
    }

    /** The power right now (eased between steps). */
    float magnification() {
        return shown;
    }

    /** The power the current step is heading to. */
    float target() {
        return levels[level];
    }
}
