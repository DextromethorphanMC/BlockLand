package com.blockland.client.settings;

import com.blockland.client.scope.ScopeOptics;
import com.blockland.craft.AttachmentCatalog;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aim sensitivity by magnification (设置 · 枪械 · 灵敏度). Turning through a scope already slows down with its power
 * ({@link ScopeOptics#sensitivity}: 1 / magnification, so the picture moves as fast as the bare view); on top of that
 * every magnification class has a multiplier of its own: 1× is the aim-down-sights multiplier (iron sights, red dots,
 * holographic sights), then 2×, 3×, 4×, 6×, 8× and 10×+ (everything above 10× uses the 10× value). A power between two
 * classes — a variable scope's middle step, or the picture easing from one step to the next — takes a value
 * interpolated between its neighbours in log-magnification space, so a 9× step sits close to the 10×+ value and a power
 * change never jumps. Multipliers of 1 everywhere = turning matches the magnified picture exactly.
 */
public final class ScopeSensitivity {
    /** The magnified classes with a setting of their own, low to high; the last also covers every higher power. */
    public static final float[] CLASSES = {2, 3, 4, 6, 8, 10};
    public static final float MIN = 0.1f, MAX = 2f;

    private ScopeSensitivity() {
    }

    /** Settings id of a class ("2x" …). */
    public static String id(float magnification) {
        return Math.round(magnification) + "x";
    }

    /** The default multipliers (1 for every magnified class). */
    public static Map<String, Float> defaults() {
        return filled(1);
    }

    /** Every magnified class set to {@code value}. */
    public static Map<String, Float> filled(float value) {
        Map<String, Float> map = new LinkedHashMap<>();
        for (float c : CLASSES) map.put(id(c), ClientSettings.clamp(value, MIN, MAX));
        return map;
    }

    /** Fills missing classes (1) and clamps every value; unknown keys are dropped. */
    static Map<String, Float> sanitize(Map<String, Float> values) {
        Map<String, Float> map = new LinkedHashMap<>();
        for (float c : CLASSES) {
            Float v = values.get(id(c));
            map.put(id(c), v == null ? 1f : ClientSettings.clamp(v, MIN, MAX));
        }
        return map;
    }

    public static float get(float magnificationClass) {
        Float v = ClientSettings.get().scopeSensitivities.get(id(magnificationClass));
        return v == null ? 1f : v;
    }

    public static void set(float magnificationClass, float value) {
        ClientSettings.get().scopeSensitivities.put(id(magnificationClass), ClientSettings.clamp(value, MIN, MAX));
    }

    /**
     * The player's multiplier at a magnification (before the physical 1 / magnification): the 1× (aim-down-sights)
     * value at 1× and below, the 10×+ value from 10× up, log-interpolated between neighbouring classes.
     */
    public static float multiplier(float magnification) {
        float lowPower = 1, low = ClientSettings.get().adsSensitivity;
        if (magnification <= lowPower) return low;
        for (float c : CLASSES) {
            float value = get(c);
            if (magnification <= c) {
                float t = (float) (Math.log(magnification / lowPower) / Math.log(c / lowPower));
                return low + (value - low) * t;
            }
            lowPower = c;
            low = value;
        }
        return low;
    }

    /**
     * Multiplier on top of the aim-down-sights one for a raised scope at this power (what {@code Optics.sensitivityScale}
     * reports): the total — this class's multiplier over the magnification — divided by the ADS multiplier the aim
     * sensitivity applies anyway.
     */
    public static float scopeScale(float magnification) {
        float ads = Math.max(MIN, ClientSettings.get().adsSensitivity);
        return ScopeOptics.sensitivity(magnification, multiplier(magnification) / ads);
    }

    /** "2倍镜 2× · 狙击镜 4× / 6× / 9×": the game's scopes and their powers, for the settings note. */
    public static String scopesInGame() {
        List<String> parts = new ArrayList<>();
        for (CraftData.Attachment attachment : AttachmentCatalog.get().all()) {
            if (!attachment.isScope()) continue;
            StringBuilder powers = new StringBuilder();
            for (float level : attachment.scope.levels()) {
                if (!powers.isEmpty()) powers.append(" / ");
                powers.append(level == Math.round(level) ? String.valueOf(Math.round(level)) : String.format(Locale.ROOT, "%.1f", level)).append('×');
            }
            parts.add(CraftCatalog.text("blockland.attachment." + attachment.id, attachment.name).getString() + " " + powers);
        }
        return String.join(" · ", parts);
    }
}
