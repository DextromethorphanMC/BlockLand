package com.blockland.client.viewmodel;

import com.blockland.client.render.FrameJitter;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Puts a {@link ViewmodelRig} on screen: its own narrower projection (like modern shooters, so the weapon reads
 * larger and less distorted), the main colour/depth targets and Minecraft's lightmap. Also converts viewmodel positions to world space for effects that leave
 * the weapon (shells, tracers, sparks).
 */
public final class ViewmodelRenderer {
    /** Vertical FOV of the viewmodel projection (asset spec §2). */
    public static final float FOV = 54f;
    /** Created on the render thread at first use (it owns a GPU buffer); rewritten every frame (the jitter moves). */
    @Nullable
    private static PerspectiveProjectionMatrixBuffer projection;
    private static final Matrix4f projectionMatrix = new Matrix4f();
    /** The same projection without the frame's jitter (what motion is measured in). */
    private static final Matrix4f unjittered = new Matrix4f();

    private ViewmodelRenderer() {
    }

    /** Draws the rig's queued frame over the world. */
    public static void submit(ViewmodelRig<?> rig, String label) {
        Minecraft mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projectionBuffer(), ProjectionType.PERSPECTIVE);
        // its parts' own motion (sway, recoil, reloads) for motion blur and the temporal upscalers
        rig.draws().motion(unjittered);
        rig.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), label);
        RenderSystem.restoreProjectionMatrix();
    }

    /**
     * The viewmodel projection for this frame, shifted by the frame's sub-pixel jitter ({@link FrameJitter}) like the
     * world and the hand, so temporal anti-aliasing sees the weapon where it sees everything else. Passes drawn in the
     * viewmodel's space (a scope's eyepiece, a reflex reticle) should use it too.
     */
    public static GpuBufferSlice projectionBuffer() {
        Minecraft mc = Minecraft.getInstance();
        if (projection == null) projection = new PerspectiveProjectionMatrixBuffer("BlockLand viewmodel");
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        unjittered.setPerspective((float) Math.toRadians(FOV), aspect, 0.02f, 128f);
        return projection.getBuffer(FrameJitter.apply(projectionMatrix.set(unjittered)));
    }

    /**
     * The viewmodel brings its own walk motion: Minecraft's view bobbing would shake weapon and camera violently. Vanilla
     * bobs by the camera's entity — the local player, or the player a spectator sees through.
     */
    public static void suppressViewBobbing(Player player) {
        player.bob = 0;
        player.oBob = 0;
    }

    /** View-space node position → world, corrected for the viewmodel FOV differing from the world FOV. */
    public static Vec3 viewToWorld(Camera camera, @Nullable Matrix4f node, Vec3 fallback) {
        if (node == null) return fallback;
        Vector3f p = node.getTranslation(new Vector3f());
        float worldFov = Minecraft.getInstance().options.fov().get();
        float k = (float) (Math.tan(Math.toRadians(FOV) / 2) / Math.tan(Math.toRadians(worldFov) / 2));
        p.x *= k;
        p.y *= k;
        Vector3f world = camera.rotation().transform(p, new Vector3f());
        return camera.getPosition().add(world.x, world.y, world.z);
    }
}
