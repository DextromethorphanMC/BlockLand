package com.blockland.client.graphics.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * One frame of the render pipeline as every stage sees it (rendering spec §1). Filled by {@code FrameHooks}; stages read it
 * and never keep references to its matrices across frames. Spaces: "camera-relative world" is world space translated so
 * the camera sits at the origin (what vanilla terrain and entities are drawn in), "view" is that rotated by
 * {@link #viewRotation}.
 */
public final class FrameContext {
    /** Counts frames the pipeline ran (wraps). */
    public long frame;
    public float partialTick;
    /** Camera world position this frame and last frame. */
    public double camX, camY, camZ, prevCamX, prevCamY, prevCamZ;
    /** Camera-relative world → view (rotation only), this frame and last frame. */
    public final Matrix4f viewRotation = new Matrix4f();
    public final Matrix4f prevViewRotation = new Matrix4f();
    /** The level's projection without jitter, this frame and last frame. */
    public final Matrix4f projection = new Matrix4f();
    public final Matrix4f prevProjection = new Matrix4f();
    /** This frame's jitter in normalised device coordinates (also in {@code FrameJitter}). */
    public float jitterX, jitterY;
    public float near = 0.05f, far = 512f;
    /** Resolution the world is drawn at, and of the window it is shown in. */
    public int internalWidth, internalHeight, outputWidth, outputHeight;
    /** The internal-resolution target the world, hand and viewmodel are drawn into (Minecraft's main target meanwhile). */
    @Nullable
    public RenderTarget scene;
    /** The world's depth as it was after the level (before the hand pass clears it); from {@code afterLevel} on. */
    @Nullable
    public GpuTextureView worldDepth;
    /**
     * The first-person weapon's own motion this frame (RG16F, uv per frame, current − previous; zero elsewhere), drawn in
     * the hand phase ({@code client/render/HandMotion}); valid from {@code beforeComposite} on.
     */
    @Nullable
    public GpuTextureView handVelocity;
    /** The camera is under water / lava / powder snow (fog of its own). */
    public boolean submerged;
    /**
     * DLSS Ray Reconstruction's guides from the ray tracer this frame (internal resolution: diffuse albedo, linear; world
     * normal with linear depth in alpha), or null when it does not denoise this frame.
     */
    @Nullable
    public GpuTextureView rayAlbedo, rayNormals;
    /**
     * The cloud layer this frame (scene.glsl {@code BlClouds}, {@code BlCloudShape}): the camera's cloud noise position x
     * and z, the layer's bottom and top relative to the camera, coverage, density, shadow strength (0 = no clouds) and
     * drift time. Filled at {@code cameraReady} by the volumetric clouds (zero while they do not run).
     */
    public final float[] clouds = new float[8];
    /** DLSS Frame Generation generates frames around this one (motion blur is halved for it). */
    public boolean frameGenerating;

    /** Scale from internal to output resolution per axis (1 = native). */
    public float renderScale() {
        return outputWidth == 0 ? 1 : internalWidth / (float) outputWidth;
    }
}
