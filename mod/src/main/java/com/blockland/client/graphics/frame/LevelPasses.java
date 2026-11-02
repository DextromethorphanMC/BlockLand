package com.blockland.client.graphics.frame;

import com.blockland.BlockLand;
import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Work of the pipeline that belongs inside one of vanilla's level renders (rendering spec §22): a frame pass after the sky
 * and before vanilla's main pass, for whatever must lie behind all of vanilla's world (the far terrain) — or, with
 * {@code FAR_GPU_CULLING} (§23), right after vanilla's main pass has drawn its opaque terrain, with the level's depth there
 * to cull and test against. Fed by {@code LevelRendererMixin} ({@code com.blockland.mixin.level}): every
 * {@code LevelRenderer.renderLevel} hands over its camera, view rotation and projection (bobbing and nausea included,
 * without jitter), and the frame graph gets the pass; {@code ChunkSectionsToRenderMixin} ({@code mixin.terrain}) tells
 * when the opaque group is drawn. The main view and a scope's picture in picture both go through here;
 * {@link View#mainView} tells them apart.
 */
public final class LevelPasses {
    /**
     * One level render's view: the camera, camera-relative world → view, the level's projection, its fog (the {@code Fog}
     * block vanilla draws the world with), the target drawn into; {@code occluded} when the drawers run after vanilla's
     * opaque terrain (its depth is in the target to test against) instead of before it.
     */
    public record View(Camera camera, Matrix4f viewRotation, Matrix4f projection, GpuBufferSlice fog, RenderTarget target,
                       boolean mainView, boolean occluded) {
    }

    public interface BeforeTerrain {
        /** Draws into {@code view.target()} (the frame's main target: the scene, or a scope's picture). */
        void draw(View view);
    }

    private static final List<BeforeTerrain> beforeTerrain = new ArrayList<>();
    private static final Matrix4f viewRotation = new Matrix4f(), projection = new Matrix4f();
    private static Camera camera;
    private static GpuBufferSlice fog;
    /**
     * FAR_GPU_CULLING for this level render: the drawers run after vanilla's opaque terrain ({@link #afterOpaque}), with
     * the level's depth to test against, instead of in the pass before it. Decided once per level render.
     */
    private static boolean occludedLevel;

    private LevelPasses() {
    }

    public static void addBeforeTerrain(BeforeTerrain drawer) {
        beforeTerrain.add(drawer);
    }

    /** Mixin only: a level render begins with these matrices. */
    public static void beginLevel(Camera levelCamera, Matrix4f levelViewRotation, Matrix4f levelProjection, GpuBufferSlice levelFog) {
        camera = levelCamera;
        fog = levelFog;
        viewRotation.set(levelViewRotation);
        projection.set(levelProjection);
    }

    /** Mixin only: the level's frame graph, right before vanilla adds its main pass. */
    public static void addBeforeMain(FrameGraphBuilder builder, LevelTargetBundle targets) {
        occludedLevel = false;
        if (beforeTerrain.isEmpty() || camera == null || targets.main == null) return;
        if (Optimization.FAR_GPU_CULLING.on()) {
            // drawn inside vanilla's main pass instead, once its opaque terrain is down
            occludedLevel = true;
            return;
        }
        FramePass pass = builder.addPass("blockland_before_terrain");
        targets.main = pass.readsAndWrites(targets.main);
        // copies: a scope's level render inside this frame hands over its own matrices before this pass runs
        Camera passCamera = camera;
        GpuBufferSlice passFog = fog;
        Matrix4f passView = new Matrix4f(viewRotation), passProjection = new Matrix4f(projection);
        pass.executes(() -> {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget target = mc.getMainRenderTarget();
            boolean main = !mc.gameRenderer.isPanoramicMode() && FrameHooks.isSceneTarget(target);
            View view = new View(passCamera, passView, passProjection, passFog, target, main, false);
            for (BeforeTerrain drawer : beforeTerrain) {
                try {
                    drawer.draw(view);
                } catch (RuntimeException e) {
                    BlockLand.LOGGER.error("[BlockLand] A pass before the terrain failed", e);
                }
            }
        });
    }

    /**
     * Mixin only: vanilla's main pass has just drawn its opaque terrain group (no render pass is open between the
     * groups). With FAR_GPU_CULLING the drawers run here, told the level's depth is there to test against.
     */
    public static void afterOpaque() {
        if (!occludedLevel || beforeTerrain.isEmpty() || camera == null) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        boolean main = !mc.gameRenderer.isPanoramicMode() && FrameHooks.isSceneTarget(target);
        View view = new View(camera, new Matrix4f(viewRotation), new Matrix4f(projection), fog, target, main, true);
        for (BeforeTerrain drawer : beforeTerrain) {
            try {
                drawer.draw(view);
            } catch (RuntimeException e) {
                BlockLand.LOGGER.error("[BlockLand] A pass after the opaque terrain failed", e);
            }
        }
    }
}
