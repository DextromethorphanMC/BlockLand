package com.blockland.client.viewmodel;

import com.blockland.client.render.ShaderPacks;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Puts a {@link ViewmodelRig} on screen: its own narrower projection (like modern shooters, so the weapon reads
 * larger and less distorted), the main colour/depth targets and Minecraft's lightmap — or, under an OptiFine shader
 * pack, vanilla render types in the hand pass. Also converts viewmodel positions to world space for effects that leave
 * the weapon (shells, tracers, sparks).
 */
public final class ViewmodelRenderer {
    /** Vertical FOV of the viewmodel projection (asset spec §2). */
    public static final float FOV = 54f;
    /** Vertical FOV vanilla projects the hand pass with (the shader-pack path draws in it). */
    private static final float HAND_FOV = 70f;
    /** Created on the render thread at first use (it owns a GPU buffer). */
    @Nullable
    private static CachedPerspectiveProjectionMatrixBuffer projection;
    @Nullable
    private static PoseStack handPose;
    @Nullable
    private static MultiBufferSource handBuffers;

    private ViewmodelRenderer() {
    }

    /** Remembers the pose and buffers of the hand pass (before any weapon takes the event over). */
    public static void init() {
        RenderHandEvent.BUS.addListener(Priority.HIGHEST, event -> {
            handPose = event.getPoseStack();
            handBuffers = event.getMultiBufferSource();
        });
    }

    /**
     * Draws the rig's queued frame over the world. Under an OptiFine shader pack the pack owns the hand pass (its
     * buffers, its programs, its compositing), so the frame goes through vanilla render types into the hand pass's
     * buffers instead, scaled to look the same under the hand projection's wider field of view.
     */
    public static void submit(ViewmodelRig<?> rig, String label) {
        if (ShaderPacks.active()) {
            if (handPose != null && handBuffers != null && !ShaderPacks.shadowPass()) {
                float scale = (float) (Math.tan(Math.toRadians(HAND_FOV) / 2) / Math.tan(Math.toRadians(FOV) / 2));
                rig.draws().flushVanilla(handPose, handBuffers, scale);
            } else {
                rig.draws().clear();
            }
            return;
        }
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
