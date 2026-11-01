package com.blockland.client.graphics;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * What the render pipeline is doing right now, for the 画质 tab and the F3 screen. Each subsystem writes its own part;
 * readers only read. Reasons are translation keys ({@code blockland.graphics.status.*}) so the settings screen can show
 * them in the player's language; {@code detail} strings are shown as they are (driver names, versions).
 */
public final class GraphicsStatus {
    /** The pipeline (post chain, render scale) drew the last world frame. */
    public volatile boolean running;
    /** Why the pipeline is not running (key), or null. */
    @Nullable
    public volatile String blockedReason;

    public volatile int internalWidth, internalHeight, outputWidth, outputHeight;
    /** The upscaler / anti-aliasing actually in use (lower-case id: none, fxaa, taa, taau, dlss). */
    public volatile String activeAa = "none";

    public volatile boolean dlssAvailable;
    /** Why DLSS cannot run (key), or null when it can. */
    @Nullable
    public volatile String dlssReason;
    /** Driver / runtime detail for the DLSS line (e.g. the GPU name or the runtime version found). */
    public volatile String dlssDetail = "";

    /** DLSS Frame Generation can run (or has not been tried yet and its runtime files are there). */
    public volatile boolean frameGenAvailable;
    /** Why Frame Generation cannot run (key), or null. */
    @Nullable
    public volatile String frameGenReason;
    public volatile String frameGenDetail = "";
    /** The Vulkan swapchain presents the frames (Frame Generation is set up, generating or paused). */
    public volatile boolean frameGenPresenting;
    /** Frames were generated around the last frame. */
    public volatile boolean frameGenActive;
    /** Most frames on screen per rendered frame this system allows (0 = not known yet). */
    public volatile int frameGenMax;
    /** Frames reaching the screen per rendered frame, averaged. */
    public volatile float frameGenPresented = 1;

    /** Ray tracing traced the last frame. */
    public volatile boolean rayTracingActive;
    /** Why ray tracing does not run although it is on (key), or null. */
    @Nullable
    public volatile String rayTracingReason;
    /** Share of the voxel box that holds its blocks (0..1) and the box's size in blocks. */
    public volatile float rayVoxelsReady;
    public volatile String rayVolume = "";
    /** BlockLand's volumetric clouds replace vanilla's this frame (decided at frame start, read by the scene uniforms). */
    public volatile boolean cloudsActive;
    /** Why the volumetric clouds do not run although they are on (key), or null. */
    @Nullable
    public volatile String cloudsReason;
    public volatile float cloudMillis;
    /** The volumetric fog pass is the haze this frame (decided at frame start; the lit programs then draw only its edge). */
    public volatile boolean volumetricFogActive;
    /** The traced light is denoised by DLSS Ray Reconstruction this frame. */
    public volatile boolean rayReconstructionActive;
    /** DLSS Ray Reconstruction can run (its runtime files are there and it did not fail). */
    public volatile boolean rayReconstructionAvailable;
    /** Why Ray Reconstruction cannot run (key), or null. */
    @Nullable
    public volatile String rayReconstructionReason;
    /** GPU-side milliseconds are not measured; CPU milliseconds of the voxel updates and passes (smoothed). */
    public volatile float rayMillis;

    /** DLSS 5 neural rendering can run (its model is installed and nothing ruled it out yet). */
    public volatile boolean neuralAvailable;
    /** Why neural rendering cannot run (key), or null. */
    @Nullable
    public volatile String neuralReason;
    public volatile String neuralDetail = "";
    /** Neural rendering relit the last frame. */
    public volatile boolean neuralActive;

    /** The lighting pack is loaded and its programs are patched: shadows, sun and dynamic lights reach the world. */
    public volatile boolean worldLightingActive;
    /** Why world lighting is off (key), or null. */
    @Nullable
    public volatile String worldLightingReason;
    /** The world lighting setting differs from what is loaded: a resource reload applies it. */
    public volatile boolean reloadPending;

    public volatile int shadowCascades;
    /** Why there are no sun shadows although they are switched on (key), or null. */
    @Nullable
    public volatile String shadowReason;
    public volatile int dynamicLightCount;
    /** How many of them are spots (flashlights). */
    public volatile int dynamicSpotCount;
    /** CPU milliseconds of the pipeline's own work last frame (smoothed). */
    public volatile float cpuMillis;

    /**
     * Far terrain (rendering spec §22): how far it reaches this frame in blocks, 0 while it is not drawn. The fog and the
     * haze (the light module) follow it; written by the far terrain at frame start.
     */
    public volatile float farTerrainReach;
    /** Why far terrain is not drawn although it is on (key), or null. */
    @Nullable
    public volatile String farTerrainReason;
    /** Tiles drawn / wanted / held in memory, cells drawn, GPU bytes of the meshes, what the server offers (blocks, 0 = none). */
    public volatile int farTilesDrawn, farTilesWanted, farTilesHeld, farServerDistance;
    public volatile long farCells, farGpuBytes;
    /** Real far chunks (rendering spec §23): columns drawn / held, GPU bytes of their meshes, columns waiting for the server. */
    public volatile int farChunksDrawn, farChunksPlaced, farChunksHeld, farChunksPending;
    public volatile long farChunkGpuBytes;
    public volatile float farMillis;

    /** Display (rendering spec §21): the virtual framebuffer and the window's real one. */
    public volatile int displayWidth, displayHeight, windowWidth, windowHeight;

    /** The GPU / driver line ("NVIDIA GeForce RTX … / 4.6.0 NVIDIA …"). */
    public volatile String renderer = "";

    /** Lines for the F3 screen (each subsystem may add its own through {@link #debugLines}). */
    public List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("[BlockLand] render %s: %dx%d -> %dx%d, aa %s, %.2f ms CPU", running ? "on" : "off",
                internalWidth, internalHeight, outputWidth, outputHeight, activeAa, cpuMillis));
        lines.add(String.format("[BlockLand] world lighting %s, shadows %d cascades, %d lights (%d spots)",
                worldLightingActive ? "on" : "off", shadowCascades, dynamicLightCount, dynamicSpotCount));
        if (rayTracingActive) {
            lines.add(String.format("[BlockLand] ray tracing %s, voxels %s %.0f%%, %.2f ms CPU", rayReconstructionActive ? "DLSS-RR" : "on",
                    rayVolume, rayVoxelsReady * 100, rayMillis));
        }
        if (neuralActive) lines.add("[BlockLand] DLSS 5 neural rendering on");
        if (farTerrainReach > 0) {
            lines.add(String.format("[BlockLand] far terrain %d blocks: %d/%d tiles, %d held, %d cells, %.1f MB GPU, %.2f ms CPU",
                    Math.round(farTerrainReach), farTilesDrawn, farTilesWanted, farTilesHeld, farCells, farGpuBytes / 1048576.0, farMillis));
        }
        if (displayWidth > 0 && (displayWidth != windowWidth || displayHeight != windowHeight)) {
            lines.add(String.format("[BlockLand] display %dx%d in a %dx%d window", displayWidth, displayHeight, windowWidth, windowHeight));
        }
        if (frameGenPresenting) {
            lines.add(String.format("[BlockLand] DLSS frame generation %s, %.2f frames shown per frame (max %d×)",
                    frameGenActive ? "on" : "paused", frameGenPresented, frameGenMax));
        }
        return lines;
    }
}
