package com.blockland.client.graphics;

import com.blockland.client.graphics.frame.FrameHooks;
import com.blockland.client.graphics.frame.FrameStage;
import com.blockland.client.graphics.light.WorldLighting;
import com.blockland.client.graphics.raytrace.RayTracing;
import com.blockland.client.graphics.reflect.ScreenReflections;
import com.blockland.client.graphics.shadow.Shadows;
import com.blockland.client.graphics.sky.FogSwitch;
import com.blockland.client.graphics.sky.VolumetricClouds;
import com.blockland.client.graphics.wind.Wind;
import com.blockland.client.settings.ClientSettings;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * BlockLand's render pipeline (rendering spec): render scale and upscaling, anti-aliasing, the HDR post chain, the sun
 * with shadows, dynamic lights, emissive materials and screen-space effects of the world. This class only wires its
 * independent parts together; each part talks to the others through the small contracts here ({@link FrameStage},
 * {@link ShadowInfo}, {@link WorldEffects}, {@link GraphicsStatus}) and to the rest of the mod through
 * {@code client/render} ({@code SceneLighting}, {@code LightSources}, {@code FrameJitter}).
 * <ul>
 *   <li>{@code frame}: the frame's phases, render scale, projection jitter, the post chain and the upscalers;</li>
 *   <li>{@code shadow}: the sun's shadow map and the screen-space effects of the world (ambient occlusion, volumetric
 *   light) — a stage registered before the lighting, so this frame's cascades are ready when the uniforms are written;</li>
 *   <li>{@code raytrace}: ray traced global illumination and ambient occlusion from a voxel copy of the blocks around the
 *   camera (after the level, like the screen-space effects);</li>
 *   <li>{@code light}: the scene uniforms, the lighting pack (vanilla shader overrides) and the dynamic lights;</li>
 *   <li>{@code sky}: the fog switch and the volumetric clouds (after the level, folded in by the post chain);</li>
 *   <li>{@code wind}: gusts and the entities pushing plants, written into the scene uniforms for the terrain's wind.</li>
 * </ul>
 */
public final class Graphics {
    private static final List<FrameStage> stages = new ArrayList<>();
    private static final List<Runnable> changeListeners = new ArrayList<>();
    private static final List<Runnable> commitListeners = new ArrayList<>();
    private static final GraphicsStatus status = new GraphicsStatus();
    private static Supplier<ShadowInfo> shadows = () -> null;
    private static Supplier<ReflectionInfo> reflections = () -> null;
    private static WorldEffects worldEffects = WorldEffects.NONE;
    private static WorldEffects rayTracing = WorldEffects.NONE;
    private static WorldEffects clouds = WorldEffects.NONE;
    /** The screen-space effects and the ray traced lighting as one (the decode pass asks for each kind). */
    private static final WorldEffects combined = new WorldEffects() {
        @Nullable
        @Override
        public GpuTextureView ambientOcclusion() {
            return worldEffects.ambientOcclusion();
        }

        @Nullable
        @Override
        public GpuTextureView volumetricLight() {
            return worldEffects.volumetricLight();
        }

        @Nullable
        @Override
        public GpuTextureView rayTraced() {
            return rayTracing.rayTraced();
        }

        @Nullable
        @Override
        public GpuTextureView clouds() {
            return clouds.clouds();
        }
    };

    private Graphics() {
    }

    public static void init(BusGroup modBus) {
        FrameHooks.init(modBus);
        Shadows.init(modBus);
        RayTracing.init(modBus);
        // before the light module: the cloud layer is in the frame when the scene uniforms are written
        VolumetricClouds.init();
        ScreenReflections.init();
        WorldLighting.init(modBus);
        FogSwitch.init();
        Wind.init(modBus);
    }

    public static GraphicsSettings settings() {
        return ClientSettings.get().graphics;
    }

    /** The pipeline is switched on; whether it ran: {@link GraphicsStatus#running}. */
    public static boolean wanted() {
        return settings().enabled;
    }

    public static GraphicsStatus status() {
        return status;
    }

    /**
     * The next frame starts the pipeline from scratch: its screen-sized resources are made again, the frame count and
     * every stage's history start over ({@link FrameStage#restart}). Two runs of the same frames after this render the
     * same pictures (the benchmark's frame comparison).
     */
    public static void restartTemporalState() {
        FrameHooks.requestRestart();
    }

    // ---- wiring between the parts -----------------------------------------------------------------------------------

    /** Adds a stage; stages run in the order they were added. */
    public static void addStage(FrameStage stage) {
        stages.add(stage);
    }

    public static List<FrameStage> stages() {
        return stages;
    }

    public static void setShadows(Supplier<ShadowInfo> source) {
        shadows = source;
    }

    /** This frame's shadow map (from {@code cameraReady} on), or null when there is none. */
    @Nullable
    public static ShadowInfo shadows() {
        return shadows.get();
    }

    public static void setReflections(Supplier<ReflectionInfo> source) {
        reflections = source;
    }

    /** Last frame's world for screen-space reflections, or null when this frame has none (off, first frame, resized). */
    @Nullable
    public static ReflectionInfo reflections() {
        return reflections.get();
    }

    public static void setWorldEffects(WorldEffects effects) {
        worldEffects = effects;
    }

    public static void setRayTracing(WorldEffects effects) {
        rayTracing = effects;
    }

    public static void setClouds(WorldEffects effects) {
        clouds = effects;
    }

    /**
     * The world effects the post chain folds in: screen-space ambient occlusion and volumetric light, ray traced light,
     * volumetric clouds.
     */
    public static WorldEffects worldEffects() {
        return combined;
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    /** Called on every change of a graphics setting (while a slider moves too): cheap reactions only. */
    public static void onSettingsChanged(Runnable listener) {
        changeListeners.add(listener);
    }

    /** Called when the settings are saved for good (the settings screen closes): expensive reactions (resource reload). */
    public static void onSettingsCommitted(Runnable listener) {
        commitListeners.add(listener);
    }

    public static void settingsChanged() {
        settings().sanitize();
        changeListeners.forEach(Runnable::run);
    }

    public static void settingsCommitted() {
        settings().sanitize();
        commitListeners.forEach(Runnable::run);
    }
}
