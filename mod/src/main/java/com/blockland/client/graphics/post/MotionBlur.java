package com.blockland.client.graphics.post;

import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.perf.FrameTimings;
import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Motion blur (rendering spec §11) with a real shutter: every pixel is smeared by how far it moves on screen while the
 * shutter is open ({@link GraphicsSettings#motionBlurExposure()} seconds, the same at any frame rate) — the camera turning
 * and moving, entities moving themselves, the first-person weapon and arms by their own animation. Four passes at the
 * output resolution, the reconstruction filter of McGuire et al. 2012 (with Jimenez's 2014 refinements):
 * <ol>
 *   <li>velocity — each pixel's blur (half the smear, in pixels) and its distance from the camera; world pixels are
 *   reprojected through the camera's motion and, when they belong to one of the nearest moving entities (a box per
 *   entity in world space), through the entity's own motion too; weapon pixels read the hand motion texture;</li>
 *   <li>tile max — the longest blur in each tile of {@code tile × tile} pixels (tile = the longest blur allowed);</li>
 *   <li>neighbour max — the longest blur of each tile and its eight neighbours: how far any pixel may gather;</li>
 *   <li>gather — taps along the neighbourhood's blur, weighted by depth (whatever is in front smears over what is
 *   behind, never the other way) and by how far each tap's own blur reaches, so silhouettes and the weapon get no halo.</li>
 * </ol>
 */
final class MotionBlur implements AutoCloseable {
    private static final RenderPipeline VELOCITY = Fullscreen.pipeline("gfx_mb_velocity")
            .withSampler("ColorSampler").withSampler("WorldDepthSampler").withSampler("HandVelocitySampler")
            .withUniform("MotionBlurData", UniformType.UNIFORM_BUFFER)
            .build();
    private static final RenderPipeline TILE = Fullscreen.pipeline("gfx_mb_tile").withSampler("VelocitySampler").build();
    /** The same tile maximum in two separable passes ({@link Optimization#MOTION_BLUR_TILES}): rows, then columns. */
    private static final RenderPipeline TILE_ROWS = Fullscreen.pipeline("gfx_mb_tile_rows").withSampler("VelocitySampler").build();
    private static final RenderPipeline TILE_COLUMNS = Fullscreen.pipeline("gfx_mb_tile_columns").withSampler("RowSampler").build();
    private static final RenderPipeline NEIGHBOR = Fullscreen.pipeline("gfx_mb_neighbor").withSampler("TileSampler").build();
    private static final RenderPipeline GATHER = Fullscreen.pipeline("gfx_motion_blur")
            .withSampler("ColorSampler").withSampler("VelocitySampler").withSampler("NeighborSampler")
            .build();
    /** Entities with their own motion per frame (the nearest moving ones). */
    private static final int MAX_ENTITIES = 32;
    /** Entities further than this keep only the camera's motion (blocks). */
    private static final double ENTITY_RANGE = 96;
    /** Longest blur radius as a share of the output height (half the smear: 5 % of 1080 p = 54 px either side). */
    private static final float MAX_RADIUS_SHARE = 0.05f;
    private static final int DATA_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putVec4().get() + MAX_ENTITIES * 3 * 16;

    private final RenderTexture velocity = new RenderTexture("BlockLand motion blur velocity", RenderTexture.Format.RGBA16F, FilterMode.NEAREST);
    private final RenderTexture tiles = new RenderTexture("BlockLand motion blur tiles", RenderTexture.Format.RGBA16F, FilterMode.NEAREST);
    /** Each tile row's longest blur (tiles wide, pixels high), the first of the two tile passes. */
    private final RenderTexture tileRows = new RenderTexture("BlockLand motion blur tile rows", RenderTexture.Format.RG16F, FilterMode.NEAREST);
    private final RenderTexture neighbors = new RenderTexture("BlockLand motion blur neighbours", RenderTexture.Format.RGBA16F, FilterMode.NEAREST);
    private final RenderTexture blurred = new RenderTexture("BlockLand motion blur", RenderTexture.Format.RGBA16F, FilterMode.LINEAR);
    private final Ubo velocityUbo = Ubo.pass("motion blur velocity").skipUnchanged();
    private final Ubo data = new Ubo("motion blur data", DATA_SIZE).skipUnchanged();
    private final Ubo tileUbo = Ubo.pass("motion blur tiles").skipUnchanged();
    private final Ubo neighborUbo = Ubo.pass("motion blur neighbours").skipUnchanged();
    private final Ubo gatherUbo = Ubo.pass("motion blur gather").skipUnchanged();
    /** Where each entity was drawn last frame (camera-independent world positions), by entity id. */
    private Int2ObjectOpenHashMap<Vec3> drawnAt = new Int2ObjectOpenHashMap<>();
    private Int2ObjectOpenHashMap<Vec3> drawnNow = new Int2ObjectOpenHashMap<>();
    /**
     * The same without a vector per entity ({@link Optimization#FRAME_GARBAGE}); {@code kept}: last frame's positions are
     * in these (a switch hands them over, so no frame loses its motion).
     */
    private DrawnPositions keptAt = new DrawnPositions(), keptNow = new DrawnPositions();
    private boolean kept;

    /** A moving entity this frame: its box (camera-relative) and how far it moved since the last frame (world). */
    private record Mover(AABB box, Vec3 delta, double distance) {
    }

    /**
     * Smears {@code color} (output size, alpha 1 on weapon pixels); returns the blurred picture, or {@code color} when
     * nothing is to be done.
     */
    GpuTextureView apply(FrameContext ctx, GraphicsSettings s, GpuTextureView color, GpuTextureView worldDepth, float seconds,
                         GpuTextureView none) {
        List<Mover> movers;
        try (FrameTimings.Span ignored = FrameTimings.span("bl/post/motion_blur/movers")) {
            movers = collectMovers(ctx);
        }
        // generated frames sit between the rendered ones: NVIDIA asks for half the smear with Frame Generation
        float generated = ctx.frameGenerating ? 0.5f : 1f;
        float worldScale = s.motionBlurExposure() * generated / seconds;
        float handScale = ctx.handVelocity != null ? s.viewmodelBlurExposure() * generated / seconds : 0;
        if (worldScale <= 0 && handScale <= 0) return color;

        int w = ctx.outputWidth, h = ctx.outputHeight;
        int tile = Math.max(8, Math.min(96, Math.round(h * MAX_RADIUS_SHARE)));
        int tw = (w + tile - 1) / tile, th = (h + tile - 1) / tile;
        velocity.ensure(w, h);
        tiles.ensure(tw, th);
        neighbors.ensure(tw, th);
        blurred.ensure(w, h);

        // 1. velocity
        Matrix4f invCurrent = new Matrix4f(ctx.projection).mul(ctx.viewRotation).invert();
        Matrix4f prevFromCurrent = new Matrix4f(ctx.prevProjection).mul(ctx.prevViewRotation)
                .translate((float) (ctx.camX - ctx.prevCamX), (float) (ctx.camY - ctx.prevCamY), (float) (ctx.camZ - ctx.prevCamZ));
        data.write(b -> {
            b.putMat4f(invCurrent);
            b.putMat4f(prevFromCurrent);
            b.putVec4(worldScale > 0 ? movers.size() : 0, worldScale, handScale, tile);
            for (int i = 0; i < MAX_ENTITIES; i++) {
                if (i < movers.size()) {
                    Mover m = movers.get(i);
                    b.putVec4((float) m.box.minX, (float) m.box.minY, (float) m.box.minZ, 0);
                    b.putVec4((float) m.box.maxX, (float) m.box.maxY, (float) m.box.maxZ, 0);
                    b.putVec4((float) m.delta.x, (float) m.delta.y, (float) m.delta.z, 0);
                } else {
                    b.putVec4(0, 0, 0, 0);
                    b.putVec4(0, 0, 0, 0);
                    b.putVec4(0, 0, 0, 0);
                }
            }
        });
        velocityUbo.pass(new Matrix4f(), w, h, 1f / w, 1f / h, ctx.jitterX * 0.5f, ctx.jitterY * 0.5f);
        Fullscreen.pass("BlockLand post: motion blur velocity", velocity.view(), VELOCITY)
                .sampler("ColorSampler", color)
                .sampler("WorldDepthSampler", worldDepth)
                .sampler("HandVelocitySampler", ctx.handVelocity != null ? ctx.handVelocity : none)
                .uniform(velocityUbo)
                .uniform("MotionBlurData", data.buffer().slice())
                .draw();

        // 2. tile max, 3. neighbour max
        try (FrameTimings.Span ignored = FrameTimings.span("bl/post/motion_blur/tiles")) {
            tileUbo.pass(new Matrix4f(), tile, w, h);
            if (Optimization.MOTION_BLUR_TILES.on()) {
                // one fragment per tile scanning tile² texels keeps a few hundred threads busy; the same first-longest
                // blur comes out of a row pass (tiles wide × pixels high) and a column pass over its results
                tileRows.ensure(tw, h);
                Fullscreen.pass("BlockLand post: motion blur tile rows", tileRows.view(), TILE_ROWS)
                        .sampler("VelocitySampler", velocity.view()).uniform(tileUbo).draw();
                Fullscreen.pass("BlockLand post: motion blur tiles", tiles.view(), TILE_COLUMNS)
                        .sampler("RowSampler", tileRows.view()).uniform(tileUbo).draw();
            } else {
                Fullscreen.pass("BlockLand post: motion blur tiles", tiles.view(), TILE)
                        .sampler("VelocitySampler", velocity.view()).uniform(tileUbo).draw();
            }
            neighborUbo.pass(new Matrix4f(), tw, th);
            Fullscreen.pass("BlockLand post: motion blur neighbours", neighbors.view(), NEIGHBOR)
                    .sampler("TileSampler", tiles.view()).uniform(neighborUbo).draw();
        }

        // 4. gather
        gatherUbo.pass(new Matrix4f(), w, h, 1f / w, 1f / h, s.motionBlurQualityMode().taps, tile, (float) (ctx.frame & 1023));
        Fullscreen.pass("BlockLand post: motion blur", blurred.view(), GATHER)
                .sampler("ColorSampler", color)
                .sampler("VelocitySampler", velocity.view())
                .sampler("NeighborSampler", neighbors.view())
                .uniform(gatherUbo)
                .draw();
        return blurred.view();
    }

    /**
     * The nearest entities that moved since the last frame, with their boxes relative to the camera. Positions are where
     * each was drawn (interpolated with the frame's partial tick), so the motion is per rendered frame at any frame rate.
     */
    private List<Mover> collectMovers(FrameContext ctx) {
        boolean garbageFree = Optimization.FRAME_GARBAGE.on();
        if (garbageFree != kept) {
            if (garbageFree) keptNow.copyFrom(drawnNow);
            else keptNow.copyTo(drawnNow);
            kept = garbageFree;
        }
        if (garbageFree) {
            DrawnPositions swap = keptAt;
            keptAt = keptNow;
            keptNow = swap;
            keptNow.clear();
        } else {
            Int2ObjectOpenHashMap<Vec3> swap = drawnAt;
            drawnAt = drawnNow;
            drawnNow = swap;
            drawnNow.clear();
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        List<Mover> movers = new ArrayList<>();
        if (level == null) return movers;
        Entity self = mc.options.getCameraType() == CameraType.FIRST_PERSON ? mc.getCameraEntity() : null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == self || entity.isInvisible()) continue;
            double x = entity.xo + (entity.getX() - entity.xo) * ctx.partialTick;
            double y = entity.yo + (entity.getY() - entity.yo) * ctx.partialTick;
            double z = entity.zo + (entity.getZ() - entity.zo) * ctx.partialTick;
            double dx = x - ctx.camX, dy = y - ctx.camY, dz = z - ctx.camZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > ENTITY_RANGE * ENTITY_RANGE) continue;
            Vec3 delta;
            if (garbageFree) {
                keptNow.put(entity.getId(), x, y, z);
                int before = keptAt.slot(entity.getId());
                if (before < 0) continue;
                // now.subtract(before) and its lengthSqr() as Vec3 works them out (x + -x0, ...), vectors only for movers
                double mx = x + -keptAt.x(before), my = y + -keptAt.y(before), mz = z + -keptAt.z(before);
                double moved = mx * mx + my * my + mz * mz;
                if (moved < 1e-6 || moved > 64) continue;
                delta = new Vec3(mx, my, mz);
            } else {
                Vec3 now = new Vec3(x, y, z);
                drawnNow.put(entity.getId(), now);
                Vec3 before = drawnAt.get(entity.getId());
                if (before == null) continue;
                delta = now.subtract(before);
                // a teleport is a cut, not motion
                if (delta.lengthSqr() < 1e-6 || delta.lengthSqr() > 64) continue;
            }
            AABB box = entity.getBoundingBox().move(x - entity.getX() - ctx.camX, y - entity.getY() - ctx.camY, z - entity.getZ() - ctx.camZ)
                    .inflate(0.12, 0.06, 0.12);
            movers.add(new Mover(box, delta, distance));
        }
        if (movers.size() > MAX_ENTITIES) {
            movers.sort(Comparator.comparingDouble(Mover::distance));
            return movers.subList(0, MAX_ENTITIES);
        }
        return movers;
    }

    /** Forgets where entities were (a cut: the next frame starts without object motion). */
    void invalidate() {
        drawnAt.clear();
        drawnNow.clear();
        keptAt.clear();
        keptNow.clear();
    }

    @Override
    public void close() {
        velocity.close();
        tiles.close();
        tileRows.close();
        neighbors.close();
        blurred.close();
        velocityUbo.close();
        data.close();
        tileUbo.close();
        neighborUbo.close();
        gatherUbo.close();
    }
}
