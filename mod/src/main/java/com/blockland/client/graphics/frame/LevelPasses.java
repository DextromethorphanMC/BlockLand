package com.blockland.client.graphics.frame;

import com.blockland.BlockLand;
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
 * and before vanilla's main pass, for whatever must lie behind all of vanilla's world (the far terrain). Fed by
 * {@code LevelRendererMixin} ({@code com.blockland.mixin.level}): every {@code LevelRenderer.renderLevel} hands over its
 * camera, view rotation and projection (bobbing and nausea included, without jitter), and the frame graph gets the pass.
 * The main view and a scope's picture in picture both go through here; {@link View#mainView} tells them apart.
 */
public final class LevelPasses {
    /**
     * One level render's view: the camera, camera-relative world → view, the level's projection, its fog (the {@code Fog}
     * block vanilla draws the world with), the target drawn into.
     */
    public record View(Camera camera, Matrix4f viewRotation, Matrix4f projection, GpuBufferSlice fog, RenderTarget target,
                       boolean mainView) {
    }

    public interface BeforeTerrain {
        /** Draws into {@code view.target()} (the frame's main target: the scene, or a scope's picture). */
        void draw(View view);
    }

    private static final List<BeforeTerrain> beforeTerrain = new ArrayList<>();
    private static final Matrix4f viewRotation = new Matrix4f(), projection = new Matrix4f();
    private static Camera camera;
    private static GpuBufferSlice fog;

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
        if (beforeTerrain.isEmpty() || camera == null || targets.main == null) return;
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
            View view = new View(passCamera, passView, passProjection, passFog, target, main);
            for (BeforeTerrain drawer : beforeTerrain) {
                try {
                    drawer.draw(view);
                } catch (RuntimeException e) {
                    BlockLand.LOGGER.error("[BlockLand] A pass before the terrain failed", e);
                }
            }
        });
    }
}
