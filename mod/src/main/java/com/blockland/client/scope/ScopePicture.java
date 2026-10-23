package com.blockland.client.scope;

import com.blockland.BlockLand;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.common.util.Result;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A scope's own picture of the world (picture in picture): the level rendered a second time from the eye, along the
 * scope's optical axis and with its narrow field of view, into a small square target.
 * <p>
 * It runs from the hand phase of the same frame, after the main view is finished, so the picture never lags a frame
 * behind the world around the scope. For the duration of the call Minecraft's main render target points at the
 * scope's target (everything the level renderer draws asks Minecraft for it), the level renderer culls with the
 * scope's frustum handed in as its "captured" frustum (so the main view's section visibility graph is neither updated
 * nor disturbed) and draws only the sections inside it, and panoramic mode switches off the Fabulous transparency
 * targets and entity outlines (both would otherwise write screen-sized buffers the main view still uses).
 * <p>
 * Isolation rules learnt the hard way:
 * <ul>
 *   <li>vanilla's chunk-culling debug renderer draws a captured frustum as six translucent coloured planes (the near one
 *   cyan, the far one red) — on the near clip plane itself, so they flickered over the picture as a teal veil and red
 *   flashes against the sky. The frustum handed in is a {@link CullFrustum}: it culls exactly like a frustum but has no
 *   corners to draw;</li>
 *   <li>GPU buffers are persistently mapped and written without synchronisation, so the picture never rewrites a uniform
 *   slot the main view's queued draws still read: its fog comes from a {@link FogRenderer} of its own;</li>
 *   <li>name tags are left out (magnified six times they would hide the target they name).</li>
 * </ul>
 * Projection, model-view stack, fog, light layer, visible sections and the swapped fields are all put back, also when
 * something throws — after which picture in picture stays off for the session and scopes zoom instead.
 * <p>
 * Cost: one more level render at about the eyepiece's pixels, with a small frustum (a 6× scope sees about 1/30 of the
 * main view's sections), only while a scope is raised.
 */
final class ScopePicture {
    @Nullable
    private static final Field MAIN_TARGET = field(Minecraft.class, "mainRenderTarget");
    @Nullable
    private static final Field CAPTURED_FRUSTUM = field(LevelRenderer.class, "capturedFrustum");
    @Nullable
    private static final Field FOG_RENDERER = field(GameRenderer.class, "fogRenderer");

    /** True while the picture's level render runs (read by the name tag listener). */
    private static boolean rendering;

    @Nullable
    private TextureTarget target;
    @Nullable
    private PerspectiveProjectionMatrixBuffer projection;
    /** The picture's own fog uniforms (rotated once per picture, like the main view's). */
    @Nullable
    private FogRenderer fog;
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> saved = new ObjectArrayList<>();
    private final List<SectionRenderDispatcher.RenderSection> nearby = new ArrayList<>();
    private boolean broken;

    // F3 numbers
    private double millis;
    private int size, sections, allSections;

    static {
        RenderNameTagEvent.BUS.addListener(event -> {
            if (rendering) event.setResult(Result.DENY);
        });
    }

    /** Whether this Minecraft has everything the second render needs (an incompatible patch disables it). */
    static boolean supported() {
        return MAIN_TARGET != null && CAPTURED_FRUSTUM != null && FOG_RENDERER != null;
    }

    boolean usable() {
        return supported() && !broken;
    }

    /** "size², ms CPU, sections" of the last picture, for the F3 screen. */
    String stats() {
        return String.format("%d², %.2f ms CPU, %d/%d sections", size, millis, sections, allSections);
    }

    /**
     * Renders the picture for this frame.
     *
     * @param rotation      world rotation of the scope camera (the optical axis is its −Z)
     * @param fov           vertical (= horizontal) field of view in degrees
     * @param side          side of the square target in pixels
     * @param magnification how much the picture magnifies: entities are drawn that much further out (within the
     *                      option's range), so a rifleman through a scope sees players the bare eye would not
     * @return the picture, or null when it could not be drawn
     */
    @Nullable
    GpuTextureView render(Quaternionf rotation, float fov, int side, float magnification) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!usable() || level == null || mc.player == null) return null;
        GameRenderer gameRenderer = mc.gameRenderer;
        LevelRenderer levelRenderer = mc.levelRenderer;
        Camera camera = gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return null;
        long start = System.nanoTime();
        TextureTarget picture = ensureTarget(side);

        Matrix4f view = new Matrix4f().rotation(new Quaternionf(rotation).conjugate());
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fov), 1f, 0.05f, gameRenderer.getDepthFar());
        Vec3 eye = camera.getPosition();
        Frustum frustum = new CullFrustum(view, proj);
        frustum.prepare(eye.x, eye.y, eye.z);

        RenderTarget main = mc.getMainRenderTarget();
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = levelRenderer.getVisibleSections();
        GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        GpuBufferSlice savedFog = RenderSystem.getShaderFog();
        GpuTextureView savedLight = RenderSystem.getShaderTexture(2);
        boolean panoramic = gameRenderer.isPanoramicMode();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        OptionInstance<Double> entityDistance = mc.options.entityDistanceScaling();
        double savedDistance = entityDistance.get();
        // no clouds through the scope: their renderer streams through a per-frame ring buffer the main view's clouds
        // still use this frame (a second write flickers them), and they are rarely in a rifle's line of sight
        OptionInstance<CloudStatus> clouds = mc.options.cloudStatus();
        CloudStatus savedClouds = clouds.get();
        Object savedFrustum = null;
        boolean ok = false;
        // every swap is undone in the finally block, in reverse order
        modelView.pushMatrix();
        modelView.identity();
        saved.clear();
        saved.addAll(visible);
        allSections = visible.size();
        try {
            if (fog == null) fog = new FogRenderer();
            savedFrustum = CAPTURED_FRUSTUM.get(levelRenderer);
            visible.clear();
            nearby.clear();
            levelRenderer.getSectionOcclusionGraph().addSectionsInFrustum(LevelRenderer.offsetFrustum(frustum), visible, nearby);
            sections = visible.size();
            MAIN_TARGET.set(mc, picture);
            CAPTURED_FRUSTUM.set(levelRenderer, frustum);
            gameRenderer.setPanoramicMode(true);
            entityDistance.set(Mth.clamp(Math.round(savedDistance * Math.max(1, magnification) * 4) / 4.0, 0.5, 5.0));
            clouds.set(CloudStatus.OFF);
            RenderSystem.setProjectionMatrix(projection.getBuffer(proj), ProjectionType.PERSPECTIVE);

            DeltaTracker delta = mc.getDeltaTracker();
            float partial = delta.getGameTimeDeltaPartialTick(true);
            boolean foggy = level.effects().isFoggyAt(camera.getBlockPosition().getX(), camera.getBlockPosition().getZ())
                    || mc.gui.getBossOverlay().shouldCreateWorldFog();
            // the same fog as the main view (same camera, same frame), in buffers of our own; also gives the clear colour
            Vector4f fogColor = fog.setupFog(camera, mc.options.getEffectiveRenderDistance(), foggy, delta,
                    gameRenderer.getDarkenWorldAmount(partial), level);
            rendering = true;
            levelRenderer.renderLevel(GraphicsResourceAllocator.UNPOOLED, delta, false, camera, view, proj,
                    fog.getBuffer(FogRenderer.FogMode.WORLD), fogColor, !foggy);
            ok = true;
        } catch (Throwable t) {
            broken = true;
            BlockLand.LOGGER.error("[BlockLand] Scope picture-in-picture failed; scopes zoom the view instead for this session", t);
        } finally {
            rendering = false;
            if (fog != null) fog.endFrame();
            gameRenderer.setPanoramicMode(panoramic);
            entityDistance.set(savedDistance);
            clouds.set(savedClouds);
            try {
                CAPTURED_FRUSTUM.set(levelRenderer, savedFrustum);
                MAIN_TARGET.set(mc, main);
            } catch (ReflectiveOperationException | RuntimeException e) {
                broken = true;
                BlockLand.LOGGER.error("[BlockLand] Could not restore the main render target after the scope picture", e);
            }
            visible.clear();
            visible.addAll(saved);
            saved.clear();
            RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType);
            RenderSystem.setShaderFog(savedFog);
            RenderSystem.setShaderTexture(2, savedLight);
            modelView.popMatrix();
        }
        millis = millis * 0.9 + (System.nanoTime() - start) / 1e6 * 0.1;
        return ok ? picture.getColorTextureView() : null;
    }

    private TextureTarget ensureTarget(int side) {
        if (projection == null) projection = new PerspectiveProjectionMatrixBuffer("BlockLand scope picture");
        if (target == null) {
            target = new TextureTarget("BlockLand scope picture", side, side, true);
            target.setFilterMode(FilterMode.LINEAR);
        } else if (target.width != side || target.height != side) {
            target.resize(side, side);
            target.setFilterMode(FilterMode.LINEAR);
        }
        size = side;
        return target;
    }

    /** The picture's target (dev tools read it back). */
    @Nullable
    TextureTarget target() {
        return target;
    }

    /** Frees the target (no scope raised for a while, or the resources reload). */
    void release() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    /**
     * Culls like a frustum but has no corners: vanilla draws a captured frustum's corners as coloured debug planes
     * ({@code ChunkCullingDebugRenderer}), which must never end up in the picture. The corners are used for nothing else.
     */
    private static final class CullFrustum extends Frustum {
        private static final Vector4f[] NO_CORNERS = new Vector4f[8];

        static {
            for (int i = 0; i < NO_CORNERS.length; i++) NO_CORNERS[i] = new Vector4f(0, 0, 0, 1);
        }

        CullFrustum(Matrix4f view, Matrix4f projection) {
            super(view, projection);
        }

        @Override
        public Vector4f[] getFrustumPoints() {
            Vector4f[] corners = new Vector4f[NO_CORNERS.length];
            for (int i = 0; i < corners.length; i++) corners[i] = new Vector4f(NO_CORNERS[i]);
            return corners;
        }
    }

    @Nullable
    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Scope picture-in-picture unavailable: {}.{} not found ({})", owner.getSimpleName(), name, e.toString());
            return null;
        }
    }
}
