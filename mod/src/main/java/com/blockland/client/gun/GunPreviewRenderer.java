package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.client.gltf.ClipSampler;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.gun.GunData;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Renders a gun build into a GUI texture with the same GPU pipeline as the viewmodel (perspective camera,
 * studio lighting). Re-renders only when the state changes (build, rotation, zoom, size): while the player just
 * looks at the gunsmith the GPU does nothing but blit the cached image.
 */
public final class GunPreviewRenderer extends PictureInPictureRenderer<GunPreviewRenderer.State> {
    private static final float FOV = 28f;

    private final DrawList draws = new DrawList();
    private final CachedPerspectiveProjectionMatrixBuffer projection = new CachedPerspectiveProjectionMatrixBuffer("BlockLand gun preview", 0.01f, 40f);
    @Nullable
    private State rendered;
    private int renderedWidth, renderedHeight;

    public record State(String gunId, GunState look, float yaw, float pitch, float zoom,
                        int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea,
                        @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
        public static State of(String gunId, GunState look, float yaw, float pitch, float zoom, int x0, int y0, int x1, int y1,
                               @Nullable ScreenRectangle scissor) {
            return new State(gunId, look, yaw, pitch, zoom, x0, y0, x1, y1, 1f, scissor,
                    PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor));
        }
    }

    public GunPreviewRenderer(MultiBufferSource.BufferSource buffers) {
        super(buffers);
    }

    @Override
    public Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    protected String getTextureLabel() {
        return "blockland_gun_preview";
    }

    @Override
    protected boolean textureIsReadyToBlit(State state) {
        return state.equals(rendered);
    }

    @Override
    protected void renderToTexture(State state, PoseStack poseStack) {
        GunStats stats = GunStats.of(state.gunId(), state.look()).orElse(null);
        GpuModel model = stats == null ? null : ModelCache.get(BlockLand.id(stats.gun().model));
        GpuTextureView color = RenderSystem.outputColorTextureOverride;
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride;
        if (model == null || color == null) return;
        int width = color.texture().getWidth(0), height = color.texture().getHeight(0);

        GltfModel source = model.source;
        int gunRoot = source.node("gun");
        if (gunRoot < 0) return;
        Pose pose = new Pose(source);
        GltfModel.Animation idle = source.animation(stats.gun().anims.idle);
        if (idle != null) new ClipSampler(idle).sample(0, pose);
        pose.computeWorld(new Matrix4f());
        boolean[] hidden = GunLook.hiddenNodes(source, stats.gun(), state.look(), -1);

        // frame the visible gun: centre and radius of its meshes in the rest pose
        Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), p = new Vector3f();
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            if (node.mesh < 0 || hidden[index] || !source.isUnder(index, gunRoot)) continue;
            for (GltfModel.Primitive primitive : source.meshes.get(node.mesh).primitives) {
                float[] pos = primitive.positions;
                for (int v = 0; v < pos.length; v += 3) {
                    pose.world[index].transformPosition(pos[v], pos[v + 1], pos[v + 2], p);
                    min.min(p);
                    max.max(p);
                }
            }
        }
        if (min.x > max.x) return;
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        float radius = new Vector3f(max).sub(min).length() * 0.5f;
        float distance = radius / (float) Math.tan(Math.toRadians(FOV) / 2) * 1.08f / Math.max(0.5f, state.zoom());

        Matrix4f view = new Matrix4f()
                .translate(0, 0, -distance)
                .rotateX((float) Math.toRadians(state.pitch()))
                .rotateY((float) Math.toRadians(state.yaw()))
                .translate(-center.x, -center.y, -center.z);
        GpuModel.Style style = GunLook.skin(stats.skin());
        Matrix4f modelView = new Matrix4f();
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            if (node.mesh < 0 || hidden[index] || !source.isUnder(index, gunRoot)) continue;
            modelView.set(view).mul(pose.world[index]);
            model.addMesh(draws, node.mesh, modelView, style, 15, 15, 1);
        }
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection.getBuffer(width, height, FOV), ProjectionType.PERSPECTIVE);
        draws.flush(color, depth, Minecraft.getInstance().gameRenderer.lightTexture().getTextureView(), "BlockLand gun preview");
        RenderSystem.restoreProjectionMatrix();
        rendered = state;
        renderedWidth = width;
        renderedHeight = height;
    }

    /** Which gun parts are shown for a build (for the gunsmith's "what changes" cues). */
    static boolean showsNode(GltfModel source, GunData.Gun gun, GunState look, String node) {
        int index = source.node(node);
        return index >= 0 && !GunLook.hiddenNodes(source, gun, look, -1)[index];
    }
}
