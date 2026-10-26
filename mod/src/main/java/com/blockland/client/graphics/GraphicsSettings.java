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

    /**
     * Volumetric clouds: share of the internal resolution per axis they are marched at, steps along the view ray and
     * towards the sun.
     */
    public enum CloudQuality {
        LOW(0.33f, 32, 3), MEDIUM(0.5f, 48, 4), HIGH(0.5f, 72, 6);

        public final float scale;
        public final int steps;
        public final int lightSteps;

        CloudQuality(float scale, int steps, int lightSteps) {
            this.scale = scale;
            this.steps = steps;
            this.lightSteps = lightSteps;
        }
    }

    /** Volumetric fog: steps along the view ray (half the internal resolution). */
    public enum FogQuality {
        LOW(16), MEDIUM(28), HIGH(44);

        public final int steps;

        FogQuality(int steps) {
            this.steps = steps;
        }
    }

    /** Screen-space reflections on water and puddles: the most steps a reflected ray takes (0 = off). */
    public enum ReflectionQuality {
        OFF(0), LOW(20), HIGH(40);

        public final int steps;

        ReflectionQuality(int steps) {
            this.steps = steps;
        }
    }

    /** Motion blur samples per pixel (along the motion of its neighbourhood). */
    public enum MotionBlurQuality {
        LOW(8), MEDIUM(16), HIGH(24);

        public final int taps;

        MotionBlurQuality(int taps) {
            this.taps = taps;
        }
    }

    /**
     * The picture's shape (rendering spec §21): native = the window's; otherwise the virtual framebuffer takes this ratio
     * ({@code w : h}; 21:9 is 64:27, the 2560×1080 panels).
     */
    public enum AspectRatio {
        NATIVE(0, 0), R4_3(4, 3), R5_4(5, 4), R3_2(3, 2), R16_10(16, 10), R16_9(16, 9), R21_9(64, 27), R32_9(32, 9);

        public final int w, h;

        AspectRatio(int w, int h) {
            this.w = w;
            this.h = h;
        }

        /** Width over height, or 0 for the window's own. */
        public float ratio() {
            return h == 0 ? 0 : w / (float) h;
        }
    }

    /** How a picture of another shape than the window is shown: stretched over the window, or whole with black bars. */
    public enum AspectFit { STRETCH, BARS }

    /**
     * Far terrain detail (rendering spec §22): a tile of cell size c is drawn from {@code detail × c} blocks away on, so its
     * cells cover about the same number of pixels at every distance.
     */
    public enum FarTerrainQuality {
        LOW(96), MEDIUM(128), HIGH(192), ULTRA(256);

        public final int detail;

        FarTerrainQuality(int detail) {
            this.detail = detail;
        }
    }

    /** Smallest and largest custom resolution per axis (rendering spec §21). */
    public static final int MIN_WIDTH = 640, MIN_HEIGHT = 360, MAX_WIDTH = 7680, MAX_HEIGHT = 4320;
    /** Far terrain distance range in blocks. */
    public static final int FAR_MIN = 1024, FAR_MAX = 32768;
    /** Real far chunk distance range (chunks from the camera). */
    public static final int FAR_CHUNKS_MIN = 8, FAR_CHUNKS_MAX = 4096;
    /** Real far chunks drawn block for block out to this many chunks (coarser cubes of real blocks beyond). */
    public static final int FAR_CHUNKS_FULL_MIN = 16, FAR_CHUNKS_FULL_MAX = 256;

    /** {@link #motionBlur} / {@link #motionBlurViewmodel} are shutters (current meaning, see {@link #migrateMotionBlur}). */
    private static final int MOTION_BLUR_MODEL = 2;
    /** Exposure of a fully open shutter (1 = 360° at a 30 fps reference: 1/30 s), in seconds. */
    public static final float FULL_SHUTTER_SECONDS = 1f / 30f;

    // ---- master switch ----------------------------------------------------------------------------------------------
    /** The whole BlockLand render pipeline (off = vanilla rendering, the weapons keep their studio lighting). */
    public boolean enabled = true;
    public String preset = "high";

    // ---- display: the picture's shape and size (rendering spec §21; not part of the presets) --------------------------
    /** {@link AspectRatio} id. */
    public String aspectRatio = "native";
    /** {@link AspectFit} id. */
    public String aspectFit = "stretch";
    /** {@code native}, a preset {@code WIDTHxHEIGHT}, or {@code custom} ({@link #customWidth} × {@link #customHeight}). */
    public String resolution = "native";
    public int customWidth = 1920, customHeight = 1080;

    // ---- far terrain (远景地平线, rendering spec §22) ------------------------------------------------------------------
    /** Distant terrain beyond the loaded chunks, streamed from the server. */
    public boolean farTerrain = true;
    /** How far it reaches, in blocks ({@link #FAR_MIN} … {@link #FAR_MAX}). */
    public int farTerrainDistance = 16384;
    /** {@link FarTerrainQuality} id. */
    public String farTerrainQuality = "medium";
    /** Out to this many chunks from the camera the far terrain keeps every block (1-block cells); coarser only beyond. */
    public int farTerrainFullDetail = 24;
    /**
     * Real far chunks (rendering spec §23): beyond the render distance the real chunks out to {@link #farChunkDistance}
     * chunks, drawn as Minecraft draws its own (its block models, textures, light) — block for block out to
     * {@link #farChunkFullDetail} chunks, beyond that as cubes of 2, 4, 8 … real blocks that double with the distance (each
     * still the real block there); the far terrain only beyond them, or where the server has no real chunk yet.
     */
    public boolean farChunks = true;
    public int farChunkDistance = 1024;
    public int farChunkFullDetail = 48;

    // ---- the distant atmosphere (rendering spec §24): how far things look to the eye --------------------------------
    public boolean farAtmosphere = true;
    /** Air between the eye and far land: blue-shifted, lower contrast, lighter, 0..2 (1 = natural). */
    public float aerialPerspective = 1f;
    /** Heat haze over far ground in warm, sunny air, 0..1. */
    public float heatShimmer = 0.5f;
    /** Far detail blurring out (the eye no longer resolves it), 0..1. */
    public float farBlur = 0.5f;
    /** How hazy the horizon gets, 0..2 (1 = 95 % haze at the far distance; 0 = none). */
    public float farTerrainHaze = 1f;

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
    /** Colour temperature of sunlight, −1 cool … +1 warm (0 = BlockLand's own sun colours over the day). */
    public float sunTemperature = 0f;
    /** Colour temperature of moonlight, −1 cool … +1 warm. */
    public float moonTemperature = 0f;
    /** Colour temperature of block light (torches, lava, glowstone …), −1 cool … +1 warm (0 = vanilla's). */
    public float blockLightTemperature = 0f;
    /** Brightness of block light, 0..2 (1 = vanilla's). */
    public float blockLightStrength = 1f;

    // ---- fog and sky ------------------------------------------------------------------------------------------------
    /**
     * Distance fog in the open air (off: only a thin fade where the loaded world ends; water, lava, blindness and
     * darkness keep their fog).
     */
    public boolean fog = true;
    /** Volumetric fog density (haze and ground mist), 0..2 (0 = vanilla's fog only). */
    public float fogDensity = 1f;
    public String fogQuality = "medium";
    /** How much rain wets the world, the entities and the weapons (darker, glossier, puddles on flat ground), 0..1. */
    public float rainWetness = 1f;
    /** Ripples and splashes where entities move, attack, fall and blocks are placed in water. */
    public boolean waterRipples = true;
    /**
     * Things come out of water wet: entities drip and dry over about 45 s (slower in the rain), the weapon in hand stays
     * wet and beaded with water (rendering spec §15, soaking).
     */
    public boolean soaking = true;
    /**
     * Water on the lens (rendering spec §27): out of the water, drops on the screen that refract the picture, slide down
     * and dry within a few seconds; under water the picture wavers gently.
     */
    public boolean lensWater = true;
    /**
     * Wind (rendering spec §18, needs world lighting): gusts bend and shake grass, flowers, crops, vines and leaves, and
     * creatures walking through plants push them aside.
     */
    public boolean wind = true;
    /** How far the wind moves the plants, 0..2 (1 = natural). */
    public float windStrength = 1f;
    /** BlockLand's high-resolution fluid textures (a built-in resource pack; switching reloads resources). */
    public boolean fluidTextures = true;
    /** Screen-space reflections on water and puddles ({@link ReflectionQuality} id). */
    public String reflections = "high";
    /** Ray-marched clouds in place of vanilla's (needs world lighting). */
    public boolean volumetricClouds = false;
    public String cloudQuality = "medium";
    /** Share of the sky the clouds cover in fair weather, 0..1 (rain and thunder add to it). */
    public float cloudCoverage = 0.45f;

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

    public ReflectionQuality reflectionQualityMode() {
        return parse(ReflectionQuality.class, reflections, ReflectionQuality.HIGH);
    }

    public FogQuality fogQualityMode() {
        return parse(FogQuality.class, fogQuality, FogQuality.MEDIUM);
    }

    public CloudQuality cloudQualityMode() {
        return parse(CloudQuality.class, cloudQuality, CloudQuality.MEDIUM);
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

    public AspectRatio aspectRatioMode() {
        return parse(AspectRatio.class, aspectRatio, AspectRatio.NATIVE);
    }

    public AspectFit aspectFitMode() {
        return parse(AspectFit.class, aspectFit, AspectFit.STRETCH);
    }

    /**
     * The chosen render resolution as {width, height}, or null for native (the window's size, or the largest box of the
     * chosen ratio it holds).
     */
    @javax.annotation.Nullable
    public int[] resolutionSize() {
        if (resolution == null || resolution.equals("native")) return null;
        if (resolution.equals("custom")) return new int[]{customWidth, customHeight};
        int x = resolution.indexOf('x');
        if (x <= 0) return null;
        try {
            int w = Integer.parseInt(resolution.substring(0, x).trim()), h = Integer.parseInt(resolution.substring(x + 1).trim());
            if (w < MIN_WIDTH || h < MIN_HEIGHT || w > MAX_WIDTH || h > MAX_HEIGHT) return null;
            return new int[]{w, h};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public FarTerrainQuality farTerrainQualityMode() {
        return parse(FarTerrainQuality.class, farTerrainQuality, FarTerrainQuality.MEDIUM);
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
                volumetricClouds = false;
                cloudQuality = id(CloudQuality.LOW);
                fogQuality = id(FogQuality.LOW);
                farTerrain = false;
                farAtmosphere = false;
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
                volumetricClouds = false;
                cloudQuality = id(CloudQuality.LOW);
                fogQuality = id(FogQuality.LOW);
                farTerrain = true;
                farTerrainDistance = 8192;
                farChunks = true;
                farChunkDistance = 512;
                farChunkFullDetail = 32;
                farTerrainQuality = id(FarTerrainQuality.LOW);
                farAtmosphere = true;
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
                volumetricClouds = true;
                cloudQuality = id(CloudQuality.MEDIUM);
                fogQuality = id(FogQuality.MEDIUM);
                farTerrain = true;
                farTerrainDistance = 16384;
                farChunks = true;
                farChunkDistance = 1024;
                farChunkFullDetail = 48;
                farTerrainQuality = id(FarTerrainQuality.MEDIUM);
                farAtmosphere = true;
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
                volumetricClouds = true;
                cloudQuality = id(CloudQuality.HIGH);
                fogQuality = id(FogQuality.HIGH);
                farTerrain = true;
                farTerrainDistance = 32768;
                farChunks = true;
                farChunkDistance = 2048;
                farChunkFullDetail = 64;
                farTerrainQuality = id(FarTerrainQuality.HIGH);
                farAtmosphere = true;
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
        sunTemperature = clamp(sunTemperature, -1, 1);
        moonTemperature = clamp(moonTemperature, -1, 1);
        blockLightTemperature = clamp(blockLightTemperature, -1, 1);
        blockLightStrength = clamp(blockLightStrength, 0, 2);
        fogDensity = clamp(fogDensity, 0, 2);
        cloudQuality = id(cloudQualityMode());
        fogQuality = id(fogQualityMode());
        rainWetness = clamp(rainWetness, 0, 1);
        windStrength = clamp(windStrength, 0, 2);
        reflections = id(reflectionQualityMode());
        cloudCoverage = clamp(cloudCoverage, 0, 1);
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
        aspectRatio = id(aspectRatioMode());
        aspectFit = id(aspectFitMode());
        customWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, customWidth));
        customHeight = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, customHeight));
        if (resolution == null || !resolution.equals("custom") && resolutionSize() == null) resolution = "native";
        farTerrainDistance = Math.max(FAR_MIN, Math.min(FAR_MAX, farTerrainDistance));
        farTerrainQuality = id(farTerrainQualityMode());
        farTerrainFullDetail = Math.max(0, Math.min(64, farTerrainFullDetail));
        farChunkDistance = Math.max(FAR_CHUNKS_MIN, Math.min(FAR_CHUNKS_MAX, farChunkDistance));
        farChunkFullDetail = Math.max(FAR_CHUNKS_FULL_MIN, Math.min(FAR_CHUNKS_FULL_MAX, farChunkFullDetail));
        aerialPerspective = clamp(aerialPerspective, 0, 2);
        heatShimmer = clamp(heatShimmer, 0, 1);
        farBlur = clamp(farBlur, 0, 1);
        farTerrainHaze = clamp(farTerrainHaze, 0, 2);
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
