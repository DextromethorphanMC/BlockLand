package com.blockland.client.craft;

import com.blockland.client.render.DrawList;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
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

import javax.annotation.Nullable;

/**
 * Renders a crafted weapon (gun or knife, with its attachment models and skin) into a GUI texture with the same GPU
 * pipeline as the viewmodel (perspective camera, studio lighting). Re-renders only when the state changes (build,
 * rotation, zoom, size): while the player just looks at the 工匠 screen the GPU only blits the cached image.
 */
public final class WeaponPreviewRenderer extends PictureInPictureRenderer<WeaponPreviewRenderer.State> {
    /** Vertical field of view of the preview camera (degrees). */
    public static final float FOV = 28f;

    private final DrawList draws = new DrawList();
    private final CachedPerspectiveProjectionMatrixBuffer projection = new CachedPerspectiveProjectionMatrixBuffer("BlockLand weapon preview", 0.01f, 40f);
    @Nullable
    private State rendered;
    /** The rendered skin has shader effects (the cached image would freeze them). */
    private boolean animated;
    /** Texture sets finished when it was rendered: one finishing later (loaded in the background) shows on a redraw. */
    private int renderedLoads = -1;

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

    /**
     * The preview camera for an assembly: the visible weapon framed by the centre and radius of its meshes in the rest
     * pose, turned by {@code yaw} / {@code pitch} (or {@code orientation}), {@code zoom} closer. Maps the assembly's pose
     * space to view space; with {@link #FOV} and the picture's aspect it tells where a node lands on the picture (the
     * in-game 工匠 draws its callout lines to the real mounts with it). Null when nothing of the weapon is drawn.
     */
    @Nullable
    public static Matrix4f viewFor(WeaponAssembly assembly, float yaw, float pitch, float zoom, @Nullable Quaternionfc orientation) {
        float[] frame = frameOf(assembly);
        return frame == null ? null : viewFor(frame, yaw, pitch, zoom, orientation);
    }

    /** What the camera frames: the centre (x, y, z) and radius of the assembly's drawn meshes; null when nothing is drawn. */
    @Nullable
    public static float[] frameOf(WeaponAssembly assembly) {
        Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
        assembly.bounds(new Matrix4f(), true, min, max);
        if (min.x > max.x) return null;
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        return new float[]{center.x, center.y, center.z, new Vector3f(max).sub(min).length() * 0.5f};
    }

    /** {@link #viewFor(WeaponAssembly, float, float, float, Quaternionfc)} for a {@link #frameOf frame} computed once. */
    public static Matrix4f viewFor(float[] frame, float yaw, float pitch, float zoom, @Nullable Quaternionfc orientation) {
        float distance = frame[3] / (float) Math.tan(Math.toRadians(FOV) / 2) * 1.08f / Math.max(0.5f, zoom);
        return new Matrix4f()
                .translate(0, 0, -distance)
                .rotate(orientation != null ? orientation : new Quaternionf()
                        .rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw)))
                .translate(-frame[0], -frame[1], -frame[2]);
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
        return state.equals(rendered) && !animated && renderedLoads == com.blockland.client.render.Textures.loads();
    }

    @Override
    protected void renderToTexture(State state, PoseStack poseStack) {
        Craftable weapon = CraftCatalog.get(state.weaponId()).orElse(null);
        Build build = weapon == null ? null : CraftCatalog.complete(weapon, state.build());
        // a special skin's own model (same node contract, spec §30), else the weapon's
        WeaponAssembly assembly = weapon == null ? null : WeaponAssembly.of(weapon, build, null, false);
        GpuTextureView color = RenderSystem.outputColorTextureOverride;
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride;
        if (assembly == null || color == null) return;
        int width = color.texture().getWidth(0), height = color.texture().getHeight(0);

        Matrix4f view = viewFor(assembly, state.yaw(), state.pitch(), state.zoom(), state.orientation());
        if (view == null) return;
        animated = assembly.animated();
        renderedLoads = com.blockland.client.render.Textures.loads();
        assembly.draw(draws, view, 15, 15, true);
        // the build's sprays, projected at this same rest pose (spec §29)
        assembly.drawSprays(draws, view, 15, 15);
        lastCamera = new Camera(weapon.id(), new Matrix4f(view), FOV, width / (float) Math.max(1, height));
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection.getBuffer(width, height, FOV), ProjectionType.PERSPECTIVE);
        draws.flush(color, depth, Minecraft.getInstance().gameRenderer.lightTexture().getTextureView(), "BlockLand weapon preview");
        RenderSystem.restoreProjectionMatrix();
        rendered = state;
    }
}
