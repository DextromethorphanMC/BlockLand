package com.blockland.client.graphics;

import java.util.Locale;

/**
 * The 画质 (graphics) settings, saved with the other client preferences ({@code ClientSettings.Data.graphics}). Choices are
 * stored as lower-case ids so the file stays readable; the enums below parse them. A preset fills in the individual
 * values; changing any value afterwards makes the preset {@code custom}.
 */
public final class GraphicsSettings {
    public enum Preset { LOW, MEDIUM, HIGH, ULTRA, CUSTOM }

    /**
     * Who renders below the output resolution and scales up: nobody, the built-in temporal upscaler, NVIDIA DLSS Super
     * Resolution, or DLAA (DLSS's model at native resolution: anti-aliasing only).
     */
    public enum Upscaler { NONE, TAAU, DLSS, DLAA }

    /**
     * The DLSS model (Streamline presets): DEFAULT = NVIDIA's pick per mode (K for DLAA / Quality / Balanced, M for
     * Performance, L for Ultra Performance in the current runtime) and whatever the driver's override says; J, K, L, M force
     * one model for every mode.
     */
    public enum DlssPreset {
        DEFAULT(0), J(10), K(11), L(12), M(13);

        /** Streamline's DLSSPreset value. */
        public final int sl;

        DlssPreset(int sl) {
            this.sl = sl;
        }
    }

    /** Render scale of an upscaler (share of the output resolution per axis). */
    public enum UpscaleQuality {
        NATIVE(1f), QUALITY(0.667f), BALANCED(0.58f), PERFORMANCE(0.5f), ULTRA_PERFORMANCE(0.333f);

        public final float scale;

        UpscaleQuality(float scale) {
            this.scale = scale;
        }
    }

    /** Anti-aliasing at native resolution (without an upscaler). */
    public enum AntiAliasing { OFF, FXAA, TAA }

    public enum ShadowQuality {
        OFF(0, 0), LOW(1024, 2), MEDIUM(2048, 3), HIGH(2048, 4), ULTRA(4096, 4);

        /** Side of one cascade's tile in texels. */
        public final int tile;
        public final int cascades;

        ShadowQuality(int tile, int cascades) {
            this.tile = tile;
            this.cascades = cascades;
        }
    }

    /** VANILLA keeps Minecraft's tones and only rolls off highlights; FILMIC and ACES are cinematic curves. */
    public enum Tonemap { VANILLA, FILMIC, ACES }

    /**
     * Ray tracing quality: resolution of the traced light (half or the internal resolution), rays per pixel, and how many
     * cells a ray may cross per block of tracing distance.
     */
    public enum RayQuality {
        LOW(0.5f, 1, 1.0f), MEDIUM(0.5f, 2, 1.25f), HIGH(1f, 2, 1.5f), ULTRA(1f, 4, 2f);

        public final float scale;
        public final int rays;
        public final float cellsPerBlock;

        RayQuality(float scale, int rays, float cellsPerBlock) {
            this.scale = scale;
            this.rays = rays;
            this.cellsPerBlock = cellsPerBlock;
        }
    }

    /** What denoises the traced light: BlockLand's own temporal + spatial filter, or DLSS Ray Reconstruction. */
    public enum RayDenoiser { BLOCKLAND, DLSS_RR }

    /** Motion blur samples per pixel (along the motion of its neighbourhood). */
    public enum MotionBlurQuality {
        LOW(8), MEDIUM(16), HIGH(24);

        public final int taps;

        MotionBlurQuality(int taps) {
            this.taps = taps;
        }
    }

    /** {@link #motionBlur} / {@link #motionBlurViewmodel} are shutters (current meaning, see {@link #migrateMotionBlur}). */
    private static final int MOTION_BLUR_MODEL = 2;
    /** Exposure of a fully open shutter (1 = 360° at a 30 fps reference: 1/30 s), in seconds. */
    public static final float FULL_SHUTTER_SECONDS = 1f / 30f;

    // ---- master switch ----------------------------------------------------------------------------------------------
    /** The whole BlockLand render pipeline (off = vanilla rendering, the weapons keep their studio lighting). */
    public boolean enabled = true;
    public String preset = "high";

    // ---- resolution and anti-aliasing -------------------------------------------------------------------------------
    public String upscaler = "none";
    public String upscaleQuality = "quality";
    public String antialiasing = "taa";
    /** Contrast-adaptive sharpening after anti-aliasing / upscaling, 0..1. */
    public float sharpness = 0.35f;
    /** DLSS model preset id (see {@link DlssPreset}). */
    public String dlssPreset = "default";
    /**
     * Sharpening after DLSS / DLAA, 0..1 (DLSS's own sharpening is retired in the current runtime; this drives the same
     * contrast-adaptive pass as {@link #sharpness}, separately tuned because DLSS output is softer).
     */
    public float dlssSharpness = 0.25f;
    /**
     * DLSS Frame Generation: frames on screen per rendered frame, 1 = off, 2 = Frame Generation, 3..6 = Multi Frame Generation
     * (capped at what the GPU allows; presented through a Vulkan swapchain).
     */
    public int frameGeneration = 1;
    /** NVIDIA Reflex while frames are generated (Frame Generation requires it): "on" or "boost". */
    public String reflex = "on";

    // ---- ray tracing (voxel ray tracing of the blocks around the camera) ---------------------------------------------
    /** Ray traced global illumination and ambient occlusion. */
    public boolean rayTracing = false;
    public String rayQuality = "high";
    /** Longest ray in blocks (also sizes the voxel box). */
    public float rayDistance = 32f;
    /** Strength of the light bounced off blocks, 0..2. */
    public float rayGlobalIllumination = 1f;
    /** Strength of the ray traced ambient occlusion (replaces the screen-space one), 0..1. */
    public float rayAmbientOcclusion = 0.8f;
    /** How much glowing blocks light their surroundings through the traced light, 0..2. */
    public float rayEmission = 1f;
    /** Sunlit faces bounce sunlight (one shadow ray per hit). */
    public boolean raySunBounce = true;
    public String rayDenoiser = "dlss_rr";

    // ---- DLSS 5 neural rendering (RTX Remix's DLSS-NR options and defaults) ------------------------------------------
    /** DLSS 5 3D-guided neural rendering relights the finished picture (RTX 50, a driver with DLSS-NR). */
    public boolean neuralRendering = false;
    /** Overall effect, 0..1. */
    public float neuralIntensity = 1f;
    /** Adjustments to details, shadows and materials, 0..1. */
    public float neuralStructure = 0.7f;
    /** Adjustments to lightness, darkness and colour, 0..1. */
    public float neuralTone = 0.3f;
    /** Appearance model: 0 A, 1 B, 2 C. */
    public int neuralModel = 0;
    /** Combine NVIDIA's automatic character mask with the control mask. */
    public boolean neuralAutoMask = true;
    /** Structure adjustments on characters (with the automatic mask), 0..1. */
    public float neuralCharacters = 0.5f;
    /** Bright highlights keep their original HDR instead of the model's SDR-compressed version. */
    public boolean neuralHighlightRecovery = true;

    // ---- world lighting (the lighting pack) -------------------------------------------------------------------------
    /** Sunlight with shadows, dynamic lights and emissive blocks on the world (needs a resource reload to switch). */
    public boolean worldLighting = true;
    public String shadows = "high";
    /** Blocks from the camera the last cascade reaches. */
    public float shadowDistance = 96;
    /** Shadow filter size multiplier (0.5 sharp … 2 soft). */
    public float shadowSoftness = 1f;
    /** Direct sunlight strength (1 = keeps vanilla brightness on average). */
    public float sunStrength = 1f;
    public boolean dynamicLights = true;
    /** Held light sources (torches, lanterns, glowstone …) light the world around their holder. */
    public boolean heldItemLights = true;
    /** Glow of emissive blocks, items and weapon parts (bloom boost), 0..2. */
    public float emission = 1f;
    public boolean waterReflections = true;
    /** Water refraction and depth absorption (needs 极佳 / Fabulous graphics: translucent layers are separate there). */
    public boolean waterRefraction = true;
    /** Weapons (first and third person) lit by the world's sun, shadows and lights instead of only studio lights. */
    public boolean weaponWorldLighting = true;

    // ---- screen-space effects ---------------------------------------------------------------------------------------
    /** Screen-space ambient occlusion strength, 0 = off. */
    public float ambientOcclusion = 0.6f;
    /** Volumetric light (sun shafts, light in fog and rain), 0 = off. */
    public float volumetricLight = 0.5f;

    // ---- camera and colour ------------------------------------------------------------------------------------------
    public float bloom = 0.5f;
    public boolean autoExposure = true;
    /** Exposure compensation in EV (−2 … +2). */
    public float exposure = 0f;
    public String tonemap = "vanilla";
    /**
     * 运动模糊: the shutter of the world's motion blur, 0..1 = 0..360° against a 30 fps reference (0.5 = 180° = 1/60 s
     * exposure, whatever the frame rate). Everything that moves on screen smears by it: the camera turning and moving and
     * the entities moving themselves. 0 = off.
     */
    public float motionBlur = 0.5f;
    /** 手臂与武器运动模糊: the shutter for the first-person weapon and arms (their own motion: sway, recoil, reloads), 0 = off. */
    public float motionBlurViewmodel = 0.6f;
    /** Motion blur samples ({@link MotionBlurQuality} id). */
    public String motionBlurQuality = "high";
    /** Which meaning {@link #motionBlur} has (files written before it existed hold the old per-frame strengths). */
    public int motionBlurModel;
    public float vignette = 0.2f;
    public float chromaticAberration = 0f;
    public float filmGrain = 0f;
    public float saturation = 1f;
    public float contrast = 1f;
    /** White balance: −1 cool … +1 warm. */
    public float temperature = 0f;
    /** Timing and resolution lines on the F3 screen. */
    public boolean showStats = false;

    // ---- parsed choices ---------------------------------------------------------------------------------------------

    public Preset presetMode() {
        return parse(Preset.class, preset, Preset.HIGH);
    }

    public Upscaler upscalerMode() {
        return parse(Upscaler.class, upscaler, Upscaler.NONE);
    }

    /** Reflex low latency with boost (the GPU keeps its clocks up). */
    public boolean reflexBoost() {
        return "boost".equals(reflex);
    }

    public DlssPreset dlssPresetMode() {
        return parse(DlssPreset.class, dlssPreset, DlssPreset.DEFAULT);
    }

    /** DLSS Super Resolution or DLAA is chosen. */
    public boolean dlssChosen() {
        return upscalerMode() == Upscaler.DLSS || upscalerMode() == Upscaler.DLAA;
    }

    public UpscaleQuality upscaleQualityMode() {
        return parse(UpscaleQuality.class, upscaleQuality, UpscaleQuality.QUALITY);
    }

    public AntiAliasing antialiasingMode() {
        return parse(AntiAliasing.class, antialiasing, AntiAliasing.TAA);
    }

    public ShadowQuality shadowQuality() {
        return parse(ShadowQuality.class, shadows, ShadowQuality.HIGH);
    }

    public RayQuality rayQualityMode() {
        return parse(RayQuality.class, rayQuality, RayQuality.HIGH);
    }

    public RayDenoiser rayDenoiserMode() {
        return parse(RayDenoiser.class, rayDenoiser, RayDenoiser.DLSS_RR);
    }

    /** DLSS Ray Reconstruction is asked for: ray tracing on, DLSS Super Resolution or DLAA chosen, RR as the denoiser. */
    public boolean rayReconstruction() {
        return rayTracing && dlssChosen() && rayDenoiserMode() == RayDenoiser.DLSS_RR;
    }

    public MotionBlurQuality motionBlurQualityMode() {
        return parse(MotionBlurQuality.class, motionBlurQuality, MotionBlurQuality.HIGH);
    }

    /** Seconds the world's shutter is open (0 = no motion blur). */
    public float motionBlurExposure() {
        return motionBlur * FULL_SHUTTER_SECONDS;
    }

    /** Seconds the weapon's shutter is open. */
    public float viewmodelBlurExposure() {
        return motionBlurViewmodel * FULL_SHUTTER_SECONDS;
    }

    public Tonemap tonemapMode() {
        return parse(Tonemap.class, tonemap, Tonemap.VANILLA);
    }

    /** Lower-case id of a choice, as stored. */
    public static String id(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String id, E fallback) {
        if (id == null) return fallback;
        try {
            return Enum.valueOf(type, id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ---- presets and sanity -----------------------------------------------------------------------------------------

    /** Fills in the values of a preset (CUSTOM changes nothing). */
    public void apply(Preset value) {
        preset = id(value);
        switch (value) {
            case LOW -> {
                upscaler = id(Upscaler.TAAU);
                upscaleQuality = id(UpscaleQuality.PERFORMANCE);
                antialiasing = id(AntiAliasing.FXAA);
                shadows = id(ShadowQuality.LOW);
                shadowDistance = 48;
                dynamicLights = true;
                heldItemLights = false;
                ambientOcclusion = 0;
                volumetricLight = 0;
                bloom = 0.3f;
                motionBlur = 0;
                motionBlurViewmodel = 0;
                motionBlurQuality = id(MotionBlurQuality.LOW);
            }
            case MEDIUM -> {
                upscaler = id(Upscaler.TAAU);
                upscaleQuality = id(UpscaleQuality.QUALITY);
                antialiasing = id(AntiAliasing.TAA);
                shadows = id(ShadowQuality.MEDIUM);
                shadowDistance = 64;
                dynamicLights = true;
                heldItemLights = true;
                ambientOcclusion = 0.5f;
                volumetricLight = 0.3f;
                bloom = 0.45f;
                motionBlur = 0.35f;
                motionBlurViewmodel = 0.5f;
                motionBlurQuality = id(MotionBlurQuality.MEDIUM);
            }
            case HIGH -> {
                upscaler = id(Upscaler.NONE);
                upscaleQuality = id(UpscaleQuality.QUALITY);
                antialiasing = id(AntiAliasing.TAA);
                shadows = id(ShadowQuality.HIGH);
                shadowDistance = 96;
                dynamicLights = true;
                heldItemLights = true;
                ambientOcclusion = 0.6f;
                volumetricLight = 0.5f;
                bloom = 0.5f;
                motionBlur = 0.5f;
                motionBlurViewmodel = 0.6f;
                motionBlurQuality = id(MotionBlurQuality.HIGH);
            }
            case ULTRA -> {
                upscaler = id(Upscaler.NONE);
                upscaleQuality = id(UpscaleQuality.NATIVE);
                antialiasing = id(AntiAliasing.TAA);
                shadows = id(ShadowQuality.ULTRA);
                shadowDistance = 160;
                dynamicLights = true;
                heldItemLights = true;
                ambientOcclusion = 0.7f;
                volumetricLight = 0.65f;
                bloom = 0.55f;
                motionBlur = 0.5f;
                motionBlurViewmodel = 0.6f;
                motionBlurQuality = id(MotionBlurQuality.HIGH);
            }
            case CUSTOM -> {
            }
        }
    }

    /** Clamps every value into its range and normalises unknown ids (after loading the file). */
    public void sanitize() {
        preset = id(presetMode());
        upscaler = id(upscalerMode());
        upscaleQuality = id(upscaleQualityMode());
        dlssPreset = id(dlssPresetMode());
        dlssSharpness = clamp(dlssSharpness, 0, 1);
        frameGeneration = Math.max(1, Math.min(6, frameGeneration));
        rayQuality = id(rayQualityMode());
        rayDenoiser = id(rayDenoiserMode());
        rayDistance = clamp(rayDistance, 16, 64);
        rayGlobalIllumination = clamp(rayGlobalIllumination, 0, 2);
        rayAmbientOcclusion = clamp(rayAmbientOcclusion, 0, 1);
        rayEmission = clamp(rayEmission, 0, 2);
        neuralIntensity = clamp(neuralIntensity, 0, 1);
        neuralStructure = clamp(neuralStructure, 0, 1);
        neuralTone = clamp(neuralTone, 0, 1);
        neuralModel = Math.max(0, Math.min(2, neuralModel));
        neuralCharacters = clamp(neuralCharacters, 0, 1);
        reflex = reflexBoost() ? "boost" : "on";
        antialiasing = id(antialiasingMode());
        shadows = id(shadowQuality());
        tonemap = id(tonemapMode());
        sharpness = clamp(sharpness, 0, 1);
        shadowDistance = clamp(shadowDistance, 16, 256);
        shadowSoftness = clamp(shadowSoftness, 0.25f, 3);
        sunStrength = clamp(sunStrength, 0, 2);
        emission = clamp(emission, 0, 2);
        ambientOcclusion = clamp(ambientOcclusion, 0, 1);
        volumetricLight = clamp(volumetricLight, 0, 1);
        bloom = clamp(bloom, 0, 1);
        exposure = clamp(exposure, -2, 2);
        migrateMotionBlur();
        motionBlur = clamp(motionBlur, 0, 1);
        motionBlurViewmodel = clamp(motionBlurViewmodel, 0, 1);
        motionBlurQuality = id(motionBlurQualityMode());
        vignette = clamp(vignette, 0, 1);
        chromaticAberration = clamp(chromaticAberration, 0, 1);
        filmGrain = clamp(filmGrain, 0, 1);
        saturation = clamp(saturation, 0, 2);
        contrast = clamp(contrast, 0.5f, 1.5f);
        temperature = clamp(temperature, -1, 1);
    }

    /**
     * Older files stored per-frame strengths (1 = one 1/60 s frame of motion, which looked weak); they become shutters:
     * off stays off, anything else at least 180° (the new default), a stronger choice keeps its number.
     */
    private void migrateMotionBlur() {
        if (motionBlurModel >= MOTION_BLUR_MODEL) return;
        motionBlur = motionBlur > 0.001f ? Math.max(0.5f, motionBlur) : 0;
        motionBlurViewmodel = motionBlurViewmodel > 0.001f ? Math.max(0.6f, motionBlurViewmodel) : 0;
        motionBlurModel = MOTION_BLUR_MODEL;
    }

    private static float clamp(float value, float min, float max) {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
