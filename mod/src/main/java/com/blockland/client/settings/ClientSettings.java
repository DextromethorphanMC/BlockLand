package com.blockland.client.settings;

import com.blockland.BlockLand;
import com.blockland.client.camera.CameraRig;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.hud.Crosshair;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.impact.ImpactSettings;
import com.blockland.client.perf.Perf;
import com.blockland.client.perf.PerfSettings;
import com.blockland.client.scope.ScopeQuality;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The player's own preferences, kept on this computer ({@code config/blockland-settings.json}): aim-down-sights
 * sensitivity and the look of the crosshair and the hit marker. They only change how this client looks and feels,
 * so the server never sees them; language and look sensitivity are vanilla options, privacy lives on the server.
 */
public final class ClientSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** JSON layout of the file (field names = keys). */
    public static final class Data {
        /** Multiplier of the look sensitivity while aiming down sights: the 1× class (iron, red dot, holographic sights). */
        public float adsSensitivity = 1.0f;
        /**
         * Multiplier per magnified class ({@link ScopeSensitivity}: "2x" … "10x"), on top of the physical slow-down by the
         * magnification; null in files from before the classes (then filled from {@link #scopeSensitivity}).
         */
        public Map<String, Float> scopeSensitivities;
        /** Picture in picture for magnified scopes: {@code off} (the whole view zooms), {@code low}, {@code medium}, {@code high}. */
        public String scopeQuality = "medium";
        /** Blur the view around a magnified scope while looking through it (the eye is focused through the scope). */
        public boolean scopeBlurOutside = true;
        /** Darken the view around a scope while looking through it. */
        public boolean scopeDimOutside = false;
        /** Legacy: the one multiplier every magnified scope shared (read once into {@link #scopeSensitivities}, then dropped). */
        public Float scopeSensitivity;
        /** Camera feedback (asset spec §32), 0..1.5: impacts (shots, slams, landings, hits) and locomotion sway. */
        public float cameraImpact = 1.0f;
        public float cameraMotion = 1.0f;
        public Crosshair.Style crosshair = new Crosshair.Style();
        public HitMarker.Style hitmarker = new HitMarker.Style();
        /** The 画质 tab: render pipeline, lighting and post-processing. */
        public GraphicsSettings graphics = new GraphicsSettings();
        /** Bullet impacts (弹着效果): quality and switches. */
        public ImpactSettings impacts = new ImpactSettings();
        /** The 性能 tab: which optimizations run, timings on the F3 screen. */
        public PerfSettings performance = new PerfSettings();
    }

    private static Data data;

    private ClientSettings() {
    }

    public static Data get() {
        if (data == null) load();
        return data;
    }

    /** Reads the file and hands the styles to the HUD. */
    public static void load() {
        data = new Data();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (read != null) data = read;
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] Could not read {}", file, e);
            }
        }
        if (data.crosshair == null) data.crosshair = new Crosshair.Style();
        if (data.hitmarker == null) data.hitmarker = new HitMarker.Style();
        if (data.graphics == null) data.graphics = new GraphicsSettings();
        if (data.impacts == null) data.impacts = new ImpactSettings();
        if (data.performance == null) data.performance = new PerfSettings();
        data.performance.sanitize();
        data.impacts.sanitize();
        data.graphics.sanitize();
        data.adsSensitivity = clamp(data.adsSensitivity, 0.1f, 2f);
        data.scopeQuality = ScopeQuality.of(data.scopeQuality).id();
        if (data.scopeSensitivities == null) {
            // the old multiplier applied on top of the ADS one: the classes keep that product, so nothing turns differently
            float old = data.scopeSensitivity == null ? 1f : clamp(data.scopeSensitivity, 0.2f, 2f);
            data.scopeSensitivities = ScopeSensitivity.filled(data.adsSensitivity * old);
        }
        data.scopeSensitivities = ScopeSensitivity.sanitize(data.scopeSensitivities);
        data.scopeSensitivity = null;
        data.cameraImpact = clamp(data.cameraImpact, 0f, 1.5f);
        data.cameraMotion = clamp(data.cameraMotion, 0f, 1.5f);
        data.crosshair.sanitize();
        data.hitmarker.sanitize();
        apply();
    }

    /** Applies the current values to the HUD and writes the file. */
    public static void save() {
        apply();
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not save {}", file, e);
        }
    }

    private static void apply() {
        Crosshair.setStyle(data.crosshair);
        HitMarker.setStyle(data.hitmarker);
        CameraRig.setIntensity(data.cameraImpact, data.cameraMotion);
        Perf.applySettings();
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("blockland-settings.json");
    }

    static float clamp(float value, float min, float max) {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
