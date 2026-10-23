package com.blockland.client.craft;

import com.blockland.BlockLand;
import com.blockland.client.gltf.ClipSampler;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.spray.SprayDecals;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
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
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a crafted weapon (gun or knife, with its attachment models and skin) into a GUI texture with the same GPU
 * pipeline as the viewmodel (perspective camera, studio lighting). Re-renders only when the state changes (build,
 * rotation, zoom, size): while the player just looks at the 工匠 screen the GPU only blits the cached image.
 */
public final class WeaponPreviewRenderer extends PictureInPictureRenderer<WeaponPreviewRenderer.State> {
    private static final float FOV = 28f;

    private final DrawList draws = new DrawList();
    private final CachedPerspectiveProjectionMatrixBuffer projection = new CachedPerspectiveProjectionMatrixBuffer("BlockLand weapon preview", 0.01f, 40f);
    @Nullable
    private State rendered;
    /** The rendered skin has shader effects (the cached image would freeze them). */
    private boolean animated;

    /**
     * @param orientation the view rotation to use instead of {@code pitch} / {@code yaw} (e.g. a weapon side seen square
     *                    on while placing sprays); null = from the angles
     */
    public record State(String weaponId, Build build, float yaw, float pitch, float zoom, @Nullable Quaternionfc orientation,
                        int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea,
                        @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
        public static State of(String weaponId, Build build, float yaw, float pitch, float zoom, int x0, int y0, int x1, int y1,
                               @Nullable ScreenRectangle scissor) {
            return of(weaponId, build, yaw, pitch, zoom, null, x0, y0, x1, y1, scissor);
        }

        public static State of(String weaponId, Build build, float yaw, float pitch, float zoom, @Nullable Quaternionfc orientation,
                               int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissor) {
            return new State(weaponId, build, yaw, pitch, zoom, orientation == null ? null : new Quaternionf(orientation),
                    x0, y0, x1, y1, 1f, scissor, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor));
        }
    }

    /**
     * One model's meshes to draw: the weapon's own, or an attachment's at its mount (with its slot's skin region, and the
     * mount's rest position as its effect origin so skin effects run on across the joint, spec §30).
     */
    private record Part(GpuModel model, Pose pose, boolean[] hidden, int root, String region, Vector3fc effectOrigin) {
    }

    /**
     * How the last preview was seen: the weapon's rest-pose space to view space, the vertical field of view (degrees) and
     * the aspect, so the 工匠 screen can turn a click on the preview into a ray (placing sprays).
     */
    public record Camera(String weaponId, Matrix4f view, float fov, float aspect) {
    }

    @Nullable
    private static volatile Camera lastCamera;

    public WeaponPreviewRenderer(MultiBufferSource.BufferSource buffers) {
        super(buffers);
    }

    /** The camera of the last rendered preview, or null before the first one. */
    @Nullable
    public static Camera lastCamera() {
        return lastCamera;
    }

    @Override
    public Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    protected String getTextureLabel() {
        return "blockland_weapon_preview";
    }

    @Override
    protected boolean textureIsReadyToBlit(State state) {
        // shader skins move (iridescence, flowing light, stars): those re-render every frame
        return state.equals(rendered) && !animated;
    }

    @Override
    protected void renderToTexture(State state, PoseStack poseStack) {
        Craftable weapon = CraftCatalog.get(state.weaponId()).orElse(null);
        Build build = weapon == null ? null : CraftCatalog.complete(weapon, state.build());
        // a special skin's own model (same node contract, spec §30), else the weapon's
        GpuModel model = weapon == null ? null : ModelCache.get(WeaponLook.modelOf(weapon, build));
        if (model == null && weapon != null) model = ModelCache.get(BlockLand.id(weapon.model()));
        GpuTextureView color = RenderSystem.outputColorTextureOverride;
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride;
        if (model == null || color == null) return;
        int width = color.texture().getWidth(0), height = color.texture().getHeight(0);

        GltfModel source = model.source;
        int weaponRoot = source.node(weapon.modelRoot());
        if (weaponRoot < 0) return;
        Pose pose = new Pose(source);
        GltfModel.Animation idle = source.animation("idle");
        if (idle != null) new ClipSampler(idle).sample(0, pose);
        pose.computeWorld(new Matrix4f());
        boolean[] hidden = WeaponLook.hiddenNodes(source, weapon, build, -1);

        List<Part> parts = new ArrayList<>();
        parts.add(new Part(model, pose, hidden, weaponRoot, "", new Vector3f()));
        for (ViewmodelRig.Attachment attachment : WeaponLook.attachments(weapon, build)) {
            int mount = source.node(attachment.mount());
            GpuModel att = mount < 0 || hidden[mount] ? null : ModelCache.get(attachment.model());
            if (att == null) continue;
            Pose attPose = new Pose(att.source);
            attPose.computeWorld(pose.world[mount]);
            parts.add(new Part(att, attPose, new boolean[att.source.nodes.size()], att.source.node("attachment"), attachment.region(),
                    model.restOrigin(mount)));
        }

        // frame the visible weapon: centre and radius of its meshes in the rest pose
        Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), p = new Vector3f();
        for (Part part : parts) {
            GltfModel src = part.model.source;
            for (int index : src.order) {
                if (!drawn(part, index)) continue;
                for (GltfModel.Primitive primitive : src.meshes.get(src.nodes.get(index).mesh).primitives) {
                    float[] pos = primitive.positions;
                    for (int v = 0; v < pos.length; v += 3) {
                        part.pose.world[index].transformPosition(pos[v], pos[v + 1], pos[v + 2], p);
                        min.min(p);
                        max.max(p);
                    }
                }
            }
        }
        if (min.x > max.x) return;
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        float radius = new Vector3f(max).sub(min).length() * 0.5f;
        float distance = radius / (float) Math.tan(Math.toRadians(FOV) / 2) * 1.08f / Math.max(0.5f, state.zoom());

        Matrix4f view = new Matrix4f()
                .translate(0, 0, -distance)
                .rotate(state.orientation() != null ? state.orientation() : new Quaternionf()
                        .rotateX((float) Math.toRadians(state.pitch())).rotateY((float) Math.toRadians(state.yaw())))
                .translate(-center.x, -center.y, -center.z);
        CraftData.Skin skin = WeaponLook.skinOf(weapon, build);
        animated = skin != null && skin.hasEffects();
        GpuModel.Style style = WeaponLook.style(skin);
        Matrix4f modelView = new Matrix4f();
        for (Part part : parts) {
            GltfModel src = part.model.source;
            for (int index : src.order) {
                if (!drawn(part, index)) continue;
                modelView.set(view).mul(part.pose.world[index]);
                part.model.addNode(draws, index, modelView, style, part.region, part.effectOrigin, 15, 15, 1);
            }
        }
        // the build's sprays, projected at this same rest pose (spec §29)
        SprayDecals sprays = SprayDecals.of(weapon, build);
        if (sprays.madeFor(model)) sprays.drawRest(draws, view, 15, 15);
        lastCamera = new Camera(weapon.id(), new Matrix4f(view), FOV, width / (float) Math.max(1, height));
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection.getBuffer(width, height, FOV), ProjectionType.PERSPECTIVE);
        draws.flush(color, depth, Minecraft.getInstance().gameRenderer.lightTexture().getTextureView(), "BlockLand weapon preview");
        RenderSystem.restoreProjectionMatrix();
        rendered = state;
    }

    /** A mesh node of the part's root subtree that the build does not hide. */
    private static boolean drawn(Part part, int index) {
        GltfModel src = part.model.source;
        GltfModel.Node node = src.nodes.get(index);
        if (node.mesh < 0 || part.hidden[index]) return false;
        return part.root < 0 || index == part.root || src.isUnder(index, part.root);
    }
}
