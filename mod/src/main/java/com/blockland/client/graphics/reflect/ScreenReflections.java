package com.blockland.client.graphics.reflect;

import com.blockland.BlockLand;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.ReflectionInfo;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.frame.FrameStage;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.FilterMode;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Screen-space reflections (rendering spec §17): after the level of every frame, the world's colour and linear depth are
 * kept ({@code gfx_ssr_capture}); the next frame the lighting pack's water and puddles march their reflected rays through
 * that picture, reprojected by the camera's motion, and fall back to the sky's estimate where a ray finds nothing. Taken
 * before the hand phase, so the viewmodel is never reflected. Provides {@link Graphics#reflections()}; any failure
 * switches them off for the session.
 */
public final class ScreenReflections implements FrameStage {
    private static final RenderPipeline CAPTURE = Fullscreen.pipeline("gfx_ssr_capture").withSampler("SceneSampler")
            .withSampler("DepthSampler").build();

    private final RenderTexture scene = new RenderTexture("BlockLand reflections (last frame)", RenderTexture.Format.RGBA16F,
            FilterMode.LINEAR);
    @Nullable
    private Ubo capture;
    /** The frame the picture was taken in (-1: none). */
    private long captured = -1;
    private long current;
    private boolean broken;

    private ScreenReflections() {
    }

    public static void init() {
        ScreenReflections reflections = new ScreenReflections();
        Graphics.addStage(reflections);
        Graphics.setReflections(reflections::info);
    }

    /** Last frame's picture while it is this frame's predecessor at this frame's size; null otherwise (rays off). */
    @Nullable
    private ReflectionInfo info() {
        GraphicsSettings.ReflectionQuality quality = Graphics.settings().reflectionQualityMode();
        if (broken || quality.steps == 0 || captured != current - 1 || !scene.exists()) return null;
        return new ReflectionInfo(scene.texture(), quality.steps);
    }

    @Override
    public void beginFrame(FrameContext frame) {
        current = frame.frame;
        if (Graphics.settings().reflectionQualityMode().steps == 0) {
            scene.close();
            captured = -1;
        }
    }

    @Override
    public void afterLevel(FrameContext frame) {
        if (broken || Graphics.settings().reflectionQualityMode().steps == 0 || frame.worldDepth == null || frame.scene == null) return;
        try {
            if (capture == null) capture = Ubo.pass("reflections capture");
            if (scene.ensure(frame.internalWidth, frame.internalHeight)) captured = -1;
            Matrix4f p = frame.projection;
            capture.pass(new Matrix4f(), p.m22(), p.m32());
            Fullscreen.pass("BlockLand reflections: keep the world", scene.view(), CAPTURE)
                    .sampler("SceneSampler", frame.scene.getColorTextureView()).sampler("DepthSampler", frame.worldDepth)
                    .uniform(capture).draw();
            captured = frame.frame;
        } catch (Throwable t) {
            broken = true;
            scene.close();
            BlockLand.LOGGER.error("[BlockLand] Screen-space reflections failed and are off for this session", t);
        }
    }
}
