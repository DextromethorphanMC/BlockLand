package com.blockland.client.viewmodel;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Puts a {@link ViewmodelRig} on screen: its own narrower projection (like modern shooters, so the weapon reads
 * larger and less distorted), the main colour/depth targets and Minecraft's lightmap. Also converts viewmodel
 * positions to world space for effects that leave the weapon (shells, tracers, sparks).
 */
public final class ViewmodelRenderer {
    /** Vertical FOV of the viewmodel projection (asset spec §2). */
    public static final float FOV = 54f;
    /** Created on the render thread at first use (it owns a GPU buffer). */
    @Nullable
    private static CachedPerspectiveProjectionMatrixBuffer projection;

    private ViewmodelRenderer() {
    }

    /** Draws the rig's queued frame over the world. */
    public static void submit(ViewmodelRig<?> rig, String label) {
        Minecraft mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        RenderSystem.backupProjectionMatrix();
        if (projection == null) projection = new CachedPerspectiveProjectionMatrixBuffer("BlockLand viewmodel", 0.02f, 128f);
        RenderSystem.setProjectionMatrix(projection.getBuffer(mc.getWindow().getWidth(), mc.getWindow().getHeight(), FOV), ProjectionType.PERSPECTIVE);
        rig.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), label);
        RenderSystem.restoreProjectionMatrix();
    }

    /** The viewmodel brings its own walk motion: Minecraft's view bobbing would shake weapon and camera violently. */
    public static void suppressViewBobbing(LocalPlayer player) {
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
