package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.ShadowInfo;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.perf.FrameTimings;
import com.blockland.client.perf.Optimization;
import com.blockland.client.perf.gl.VertexPages;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The sun's cascaded shadow map (rendering spec §6): up to four orthographic boxes around the camera, each in its tile
 * of one depth atlas (2×2 tiles). Cascade reaches grow geometrically up to the shadow distance, so the nearest shadows
 * get a texel per few centimetres and the farthest still cover the shadow distance. The two nearest cascades are drawn
 * every frame, the far ones on alternate frames (each keeps its last drawing, re-aimed at the moving camera). A box is
 * placed around the camera and kept while the camera stays within its {@link Cascade#slack} and the light holds (what
 * every frame's placing would show anyway, see the cascade); its terrain can then be kept too ({@code SHADOW_REUSE}).
 * Every box reaches far past the camera towards the light, so roofs, tree tops and the ground above a cave cast into it.
 * The terrain is drawn relative to each box's centre (fixed with its placement; {@link Cascade#terrainRender}), the
 * entities relative to the camera ({@link Cascade#entityRender}), each with its own cascade uniforms.
 * <p>
 * A redrawn cascade's tile is emptied first: by the GPU's clear of just that tile ({@code SHADOW_TILE_CLEAR}), or by a
 * full-tile quad that writes the far plane; either leaves depth 1 everywhere in it.
 */
final class ShadowMap {
    /** How far every box reaches towards the light beyond its own size (blocks). */
    private static final float UP = 192f;
    /** Alpha below which casters' texels let light through (leaves, glass frames, skins' outer layer). */
    private static final float CUTOFF = 0.4f;

    private final Cascade[] cascades = {new Cascade(0), new Cascade(1), new Cascade(2), new Cascade(3)};
    private final boolean[] due = new boolean[4];
    private final float[] splits = new float[4];
    /** Per cascade: the terrain casters' uniforms (box-relative) and the entity casters' (camera-relative). */
    private final MappableRingBuffer[] ubos = new MappableRingBuffer[4], entityUbos = new MappableRingBuffer[4];
    private final TerrainCasters terrain = new TerrainCasters();
    private final EntityCasters entities = new EntityCasters();
    private final CasterReach casterReach = new CasterReach();
    private final TerrainCache terrainCache = new TerrainCache();
    /** Reusing: the cascades whose terrain is drawn (into the cache) this frame. */
    private final boolean[] fresh = new boolean[4];
    /** Reusing, per cascade: what of the atlas tile is copied back from the cache this frame, and what is drawn again. */
    private final int[][] restore = new int[4][4];
    private final int[] scratchRect = new int[4], entityRect = new int[4];
    private final Vector3f casterMin = new Vector3f(), casterMax = new Vector3f(), scratchVector = new Vector3f();
    private final TerrainCasters.Draws[] redraw = new TerrainCasters.Draws[4];
    /** Reusing, per cascade: the rectangle is copied from the static texture (else cleared). */
    private final boolean[] copied = new boolean[4];
    /** Reusing, per cascade: the static texture is patched (cleared and drawn again) within this rectangle, else drawn whole when fresh. */
    private final int[][] patch = new int[4][4];
    private final boolean[] patched = new boolean[4];
    /** Per cascade: placed anew this frame; drawn straight into its atlas tile this frame (SHADOW_REUSE). */
    private final boolean[] placed = new boolean[4], direct = new boolean[4];
    /** The batched casters drawn as paged multi-draws (TERRAIN_PAGES) this frame. */
    private boolean pagedCasters;
    /** SHADOW_VIEW_CASTERS this frame: only the casters that can shadow something in view are drawn ({@link ViewVolume}). */
    private boolean viewCasters;
    private final ViewVolume volume = new ViewVolume();
    /**
     * The client tick a cascade was last placed anew for the light's turn. While the light holds (an arena's fixed time,
     * the day cycle off) a kept drawing serves for a long time, and growing it as the camera turns (a copy and a redraw
     * of the animated sections and entities each time) costs more than drawing every reaching section once: the view
     * culling only runs while the light keeps moving ({@link #LIGHT_MOVING_TICKS}).
     */
    private long lightMovedTick = Long.MIN_VALUE;
    private static final long LIGHT_MOVING_TICKS = 6;
    /** Reusing, per cascade: sections newly in view were drawn into the kept drawing this frame (SHADOW_VIEW_CASTERS). */
    private final boolean[] grown = new boolean[4];
    private final int[] growRect = new int[4];
    /**
     * SHADOW_VIEW_CASTERS, per cascade: the texels of the static texture's tile and of the atlas tile that may hold
     * anything but the far plane (x, y, w, h; w 0: none, w −1: unknown, the whole tile), so a drawing started anew clears
     * and copies only those (in a frame looking at the sky the sections in view cover a corner of the tile).
     */
    private final int[][] cacheDirty = new int[4][4], atlasDirty = new int[4][4];
    /** Reusing, per cascade: the atlas texels the sections in view can touch (the restore of a fresh drawing). */
    private final int[][] needRect = new int[4][4];
    /** Reusing, per cascade: the atlas tile's old content is cleared before the fresh drawing is copied in. */
    private final boolean[] atlasClear = new boolean[4];
    @Nullable
    private GpuTextureView cacheViewSeen;
    private final Vector3f light = new Vector3f();
    @Nullable
    private GpuTexture atlas, color;
    @Nullable
    private GpuTextureView atlasView, colorView;
    private int tile;
    @Nullable
    private ClientLevel level;
    private long frame;
    private boolean broken;
    @Nullable
    private ShadowInfo info;
    @Nullable
    private String reason;
    private float millis;
    private int drawnSections;

    @Nullable
    ShadowInfo info() {
        return info;
    }

    /** Why there are no shadows (status key), or null. */
    @Nullable
    String reason() {
        return reason;
    }

    String stats() {
        return String.format("%d sections, %d entities, %.2f ms CPU; kept terrain: %s", drawnSections, entities.entityCount(), millis,
                terrainCache.stats());
    }

    /** How far from the camera the map is read this frame ({@link #render}); the lit world reads it anywhere in view. */
    private float readRange = Float.MAX_VALUE;

    /**
     * Draws this frame's cascades (those due) for the frame's camera.
     *
     * @param readRange how far from the camera anything reads the map this frame (blocks): unbounded while the world is
     *                  lit (terrain, entities, water, the hand, particles), the volumetric light's march range while it is
     *                  the only reader (the world unlit), 0 when nothing reads it — then (SHADOW_VIEW_CASTERS) nothing is
     *                  drawn
     */
    void render(ClientLevel level, GraphicsSettings settings, FrameContext frame, float readRange) {
        info = null;
        GraphicsSettings.ShadowQuality quality = settings.shadowQuality();
        if (quality == GraphicsSettings.ShadowQuality.OFF) {
            reason = null;
            release();
            return;
        }
        if (broken || !TerrainCasters.supported()) {
            reason = broken ? "blockland.graphics.status.shadows_failed" : "blockland.graphics.status.shadows_unsupported";
            return;
        }
        if (!level.dimensionType().hasSkyLight()) {
            reason = "blockland.graphics.status.shadows_no_sky";
            return;
        }
        float strength = SunPath.shadowStrength(level, frame.partialTick);
        if (strength < 0.01f) {
            reason = null;
            return;
        }
        if (readRange <= 0 && Optimization.SHADOW_VIEW_CASTERS.on()) {
            // nothing samples the map this frame (the world unlit, the volumetric light off): a map no one reads can be
            // left as it is — the cascades are placed and drawn again when something does
            reason = "blockland.graphics.status.shadows_unread";
            return;
        }
        this.readRange = readRange;
        reason = null;
        long start = System.nanoTime();
        try {
            draw(level, settings, quality, strength, frame);
        } catch (Throwable t) {
            broken = true;
            info = null;
            reason = "blockland.graphics.status.shadows_failed";
            BlockLand.LOGGER.error("[BlockLand] Sun shadows failed and are off for this session", t);
        } finally {
            terrain.clear();
        }
        millis = millis * 0.9f + (System.nanoTime() - start) / 1e6f * 0.1f;
    }

    /** Every cascade is placed and drawn anew next frame, and the far ones take turns from there (pipeline restart). */
    void restart() {
        frame = 0;
        for (Cascade cascade : cascades) cascade.valid = false;
        terrainCache.invalidate();
        unknownDirty();
    }

    /** Whatever the tiles hold is not tracked: the next fresh drawing clears them whole. */
    private void unknownDirty() {
        for (int c = 0; c < 4; c++) cacheDirty[c][2] = atlasDirty[c][2] = -1;
    }

    /** Clears a tracked rectangle of a tile (the whole tile when unknown), nothing when it holds nothing. */
    private void clearDirty(CommandEncoder encoder, GpuTexture target, int[] dirty, int c) {
        if (dirty[2] < 0) encoder.clearColorAndDepthTextures(color, 0, target, 1.0, (c & 1) * tile, (c >> 1) * tile, tile, tile);
        else if (dirty[2] > 0 && dirty[3] > 0) encoder.clearColorAndDepthTextures(color, 0, target, 1.0, dirty[0], dirty[1], dirty[2], dirty[3]);
        dirty[2] = dirty[3] = 0;
    }

    private void draw(ClientLevel level, GraphicsSettings settings, GraphicsSettings.ShadowQuality quality, float strength,
                      FrameContext context) {
        double camX = context.camX, camY = context.camY, camZ = context.camZ;
        float partialTick = context.partialTick;
        frame++;
        int count = Math.min(4, quality.cascades);
        ensureAtlas(quality.tile);
        if (level != this.level) {
            this.level = level;
            for (Cascade cascade : cascades) cascade.valid = false;
        }
        SunPath.light(level, partialTick, light);
        splits(count, settings.shadowDistance, splits);

        // which cascades are drawn this frame; one whose placement no longer serves (the camera left its slack, the
        // light turned further than the shadow lag allows, its size changed) is placed around the camera as it is now,
        // the others keep their boxes. The lag lets a placement serve while the sun crawls (a tick or so; one cascade
        // placed anew per frame for the light, so the whole draws spread over a tick's frames); with no lag the light
        // may not turn at all.
        float lag = settings.shadowLag;
        boolean relitOne = false;
        int outer = -1;
        boolean all = true;
        for (int c = 0; c < count; c++) {
            Cascade cascade = cascades[c];
            boolean stale = !cascade.valid || cascade.tile != tile || cascade.split != splits[c]
                    || cascade.drift(camX, camY, camZ) > cascade.slack();
            if (!stale && !cascade.light.equals(light)) {
                if (lag <= 0) stale = true;
                else if (!relitOne && cascade.lightDrift(light) > lag) stale = relitOne = true;
                if (stale) lightMovedTick = TerrainCache.ticks();
            }
            if (stale) cascade.place(light, camX, camY, camZ, splits[c], tile, UP);
            placed[c] = stale;
            due[c] = stale || scheduled(c, count);
            if (due[c]) outer = c;
            else all = false;
            cascade.update(camX, camY, camZ);
        }
        for (int c = count; c < 4; c++) due[c] = false;

        // each switch is read once per frame, so a frame never mixes the old and the new way
        boolean batched = Optimization.SHADOW_BATCHING.on();
        boolean tileClear = Optimization.SHADOW_TILE_CLEAR.on();
        boolean reuse = Optimization.SHADOW_REUSE.on();
        if (!reuse) terrainCache.invalidate();
        pagedCasters = batched && (VertexPages.on() || VertexPages.anyPaged());
        terrain.paged(pagedCasters);
        // SHADOW_VIEW_CASTERS: only the casters whose shadow can land on something in view — not in a frame that draws
        // a second view of the world (a scope's picture looks another way than the camera's frustum), and with the
        // kept drawings only while the light keeps turning (see lightMovedTick)
        boolean lightMoving = TerrainCache.ticks() - lightMovedTick <= LIGHT_MOVING_TICKS;
        viewCasters = Optimization.SHADOW_VIEW_CASTERS.on() && !SceneLighting.extraWorldViewExpected() && (!reuse || lightMoving);
        if (viewCasters) volume.set(context.projection, context.viewRotation, readRange == Float.MAX_VALUE ? ViewVolume.LIT_MARGIN : ViewVolume.SAMPLED_MARGIN);
        else volume.invalidate();
        // the reach of every section is worked out once when the batches, the kept terrain or the view culling need it
        boolean marked = batched || reuse || viewCasters;
        // culling by box: each section's measured mesh box, the padded block box only for a mesh without one (the
        // padding is unknown wherever a block model of a kind CasterReach cannot read exists)
        boolean culling = Optimization.SHADOW_CULLING.on();
        terrain.culling(culling);
        float pad = culling ? casterReach.padding() : -1;
        if (pad >= 0) {
            for (int c = 0; c < count; c++) if (due[c]) cascades[c].prepareBox(pad);
        }
        // the casters: walked for the outermost cascade drawn this frame, or (SHADOW_GATHER_CACHE) for every cascade and
        // kept from frame to frame; sorted along the light (every cascade shares it; the first is always drawn)
        boolean gatherCache = Optimization.SHADOW_GATHER_CACHE.on();
        try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/gather")) {
            if (!terrain.gather(level, cascades, count, gatherCache ? null : cascades[outer])) {
                throw new IllegalStateException("view area unreadable");
            }
            if (marked) terrain.mark(cascades, due, count, pad, gatherCache);
            if (Optimization.SHADOW_SORTING.on()) terrain.sort(gatherCache ? cascades[0] : cascades[outer]);
            terrain.view(cascades, due, count, viewCasters ? volume : null, camX, camY, camZ, readRange);
        }
        try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/entities")) {
            entities.capture(level, camX, camY, camZ, partialTick);
        }
        // how far the casters drawn into each placement reach from its centre (for the shadow lag): the sections
        // reaching it and the entities' box, the box's own corners when the sections were not marked
        boolean casterBox = !entities.isEmpty() && entities.box(casterMin, casterMax);
        for (int c = 0; c < count; c++) {
            if (!due[c]) continue;
            Cascade cascade = cascades[c];
            float reach = marked ? terrain.extent(c, cascade) : cascade.boxReach();
            if (casterBox) reach = Math.max(reach, cascade.farthest(casterMin.x, casterMin.y, casterMin.z, casterMax.x, casterMax.y, casterMax.z, camX, camY, camZ));
            cascade.reach = Math.max(cascade.reach, reach);
        }

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (int c = 0; c < count; c++) {
            if (!due[c]) continue;
            if (ubos[c] == null) {
                int index = c;
                ubos[c] = new MappableRingBuffer(() -> "BlockLand shadow cascade " + index, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                        ShadowPipelines.CASCADE_UBO_SIZE);
                entityUbos[c] = new MappableRingBuffer(() -> "BlockLand shadow cascade " + index + " (entities)",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, ShadowPipelines.CASCADE_UBO_SIZE);
            }
            Cascade cascade = cascades[c];
            try (GpuBuffer.MappedView view = encoder.mapBuffer(ubos[c].currentBuffer(), false, true)) {
                Std140Builder.intoBuffer(view.data()).putMat4f(cascade.terrainRender).putVec4(cascade.tileX(), cascade.tileY(), CUTOFF, 0);
            }
            try (GpuBuffer.MappedView view = encoder.mapBuffer(entityUbos[c].currentBuffer(), false, true)) {
                Std140Builder.intoBuffer(view.data()).putMat4f(cascade.entityRender).putVec4(cascade.tileX(), cascade.tileY(), CUTOFF, 0);
            }
        }
        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indices = sequential.getBuffer(Math.max(6, Math.max(terrain.maxSequentialIndices(), entities.maxSequentialIndices())));
        VertexFormat.IndexType indexType = sequential.type();
        GpuTextureView blocks = ChunkSectionLayer.CUTOUT_MIPPED.textureView();

        drawnSections = 0;
        if (reuse) {
            drawReusing(encoder, count, batched, marked, pad, blocks, indices, indexType);
        } else {
            drawAll(encoder, count, all, tileClear, batched, marked, pad, blocks, indices, indexType);
            unknownDirty();
        }
        for (int c = 0; c < count; c++) {
            if (!due[c]) continue;
            ubos[c].rotate();
            entityUbos[c].rotate();
        }

        Matrix4f[] matrices = new Matrix4f[4];
        float[] reach = new float[4];
        for (int c = 0; c < 4; c++) {
            matrices[c] = c < count ? new Matrix4f(cascades[c].local) : new Matrix4f();
            reach[c] = c < count ? cascades[c].split : 0;
        }
        float texel = 2 * cascades[0].half / tile;
        float filter = 1.5f * settings.shadowSoftness;
        info = new ShadowInfo(atlasView, matrices, reach, count, tile * 2, filter, texel * (1 + filter), strength);
    }

    /** Every due cascade cleared and drawn: terrain, then entities. */
    private void drawAll(CommandEncoder encoder, int count, boolean all, boolean tileClear, boolean batched, boolean marked, float pad,
                         GpuTextureView blocks, GpuBuffer indices, VertexFormat.IndexType indexType) {
        if (!all && tileClear) {
            // the tiles are disjoint and every draw below is scissored to its own: clearing them all first is the same
            // as clearing each just before its cascade; the colour attachment is never read (render attachment only)
            try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/clear")) {
                for (int c = 0; c < count; c++) {
                    if (due[c]) encoder.clearColorAndDepthTextures(color, 0, atlas, 1.0, (c & 1) * tile, (c >> 1) * tile, tile, tile);
                }
            }
        }
        int mode = viewCasters ? TerrainCasters.NEEDED : TerrainCasters.REACHING;
        for (int c = 0; c < count; c++) if (due[c]) terrain.prepare(c, cascades[c], marked, pad, batched, mode);
        try (RenderPass pass = encoder.createRenderPass(() -> "BlockLand sun shadows", colorView, OptionalInt.empty(), atlasView,
                all ? OptionalDouble.of(1.0) : OptionalDouble.empty())) {
            for (int c = 0; c < count; c++) {
                if (!due[c]) continue;
                Cascade cascade = cascades[c];
                GpuBuffer ubo = ubos[c].currentBuffer();
                pass.enableScissor((c & 1) * tile, (c >> 1) * tile, tile, tile);
                if (!all && !tileClear) {
                    try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/clear")) {
                        pass.setPipeline(ShadowPipelines.CLEAR);
                        pass.setUniform("ShadowCascade", ubo);
                        pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
                        pass.setIndexBuffer(indices, indexType);
                        pass.drawIndexed(0, 0, 6, 1);
                    }
                }
                drawTerrain(pass, c, batched, TerrainCasters.Draws.ALL, null, mode, blocks, indices, indexType);
                if (!entities.isEmpty() && cascade.split + cascade.half > EntityCasters.RANGE * 0.5f) {
                    try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/entity_draws")) {
                        pass.setPipeline(ShadowPipelines.ENTITY);
                        pass.setUniform("ShadowCascade", entityUbos[c].currentBuffer());
                        entities.draw(pass, indices, indexType);
                    }
                }
            }
            pass.disableScissor();
        }
        terrainCache.invalidateAtlas();
    }

    /**
     * {@link Optimization#SHADOW_REUSE}: each due cascade's atlas tile is kept and only brought up to date (see
     * {@link TerrainCache}): a cascade placed anew this frame is drawn whole straight into its tile (its static
     * terrain reaches the static texture only once something asks for it) — or, with {@code SHADOW_VIEW_CASTERS}, its
     * static terrain is drawn once into the static texture and copied over (the kept drawing has to be there to be
     * grown as the camera turns, and something asks for it the next frame anyway wherever an entity casts); a tile
     * whose static terrain no longer matches, or that holds something else, takes the static terrain whole (drawn into
     * the static texture, copied over) with the animated sections on top; after a tick the texels the animated
     * sections can touch (and last frame's entities') are copied back from the static texture and the animated
     * sections drawn again; sections that came into view (the kept drawing does not hold them yet) are drawn into the
     * static texture and their texels copied over too; otherwise only the texels last frame's entities could have
     * touched are cleared and the sections whose geometry reaches them drawn into them again; then this frame's
     * entities. Each tile ends as {@link #drawAll} leaves it for everything in view.
     */
    private void drawReusing(CommandEncoder encoder, int count, boolean batched, boolean marked, float pad, GpuTextureView blocks,
                             GpuBuffer indices, VertexFormat.IndexType indexType) {
        GpuTextureView cacheView = terrainCache.ensure(tile * 2);
        GpuTexture cache = terrainCache.texture();
        if (cacheView != cacheViewSeen) {
            cacheViewSeen = cacheView;
            unknownDirty();
        }
        long tick = TerrainCache.ticks();
        boolean casters = !entities.isEmpty() && entities.box(casterMin, casterMax);
        boolean anyFresh = false;
        // every reaching section, or (SHADOW_VIEW_CASTERS) those that can shadow something in view
        int all = viewCasters ? TerrainCasters.NEEDED : TerrainCasters.REACHING;
        // what fills a cleared patch of the atlas tile again: the kept drawing (every reaching section without the view
        // culling); the animated sections drawn and kept current in the tile: every reaching one, or those in view now
        // or kept (a kept one that comes back into view is current at once)
        int filling = viewCasters ? TerrainCasters.CACHED : TerrainCasters.REACHING;
        int animatedMode = viewCasters ? TerrainCasters.PREPARE : TerrainCasters.REACHING;
        int prepare = viewCasters ? TerrainCasters.PREPARE : TerrainCasters.REACHING;
        if (!viewCasters) unknownDirty();
        for (int c = 0; c < count; c++) {
            fresh[c] = false;
            patched[c] = false;
            direct[c] = false;
            copied[c] = false;
            grown[c] = false;
            atlasClear[c] = false;
            redraw[c] = null;
            if (!due[c]) continue;
            if (placed[c] && !viewCasters) {
                // placed anew this frame (the camera left its slack, the light turned, its size changed): drawn straight
                // into its tile as without this; its static terrain goes into the static texture only once something
                // asks for it — under a moving sun a placement is gone before anything does
                direct[c] = true;
                continue;
            }
            Cascade cascade = cascades[c];
            int[] rect = restore[c];
            int kept = terrainCache.check(c, cascade, blocks, terrain, patch[c]);
            boolean cached = terrainCache.cached(c);
            boolean tickDue = terrainCache.atlasTick(c) != tick && terrain.hasAnimated(c, animatedMode) && terrain.animatedRect(c, animatedMode, cascade, scratchRect);
            if (kept == TerrainCache.WHOLE || !terrainCache.atlasValid(c)) {
                // the whole tile from the static texture (drawn whole first, unless it holds this very terrain), then
                // every animated section — with the view culling only the texels the sections in view can touch, the
                // tile's old content cleared first
                fresh[c] = kept != TerrainCache.KEPT || !cached;
                if (viewCasters) {
                    terrain.rectOf(c, TerrainCasters.NEEDED, cascade, needRect[c]);
                    System.arraycopy(needRect[c], 0, rect, 0, 4);
                    atlasClear[c] = true;
                } else {
                    cascade.tileRect(rect);
                }
                redraw[c] = TerrainCasters.Draws.ANIMATED;
                copied[c] = true;
                anyFresh |= fresh[c];
                continue;
            }
            System.arraycopy(terrainCache.entityRect(c), 0, rect, 0, 4);
            if (kept == TerrainCache.PATCH) {
                // the patch (and the old entities' texels) from the static texture once it is patched — or, when it
                // does not hold the rest, drawn whole from the sections as they are now
                if (cached) patched[c] = true;
                else fresh[c] = true;
                Cascade.union(rect, patch[c]);
            }
            if (tickDue) {
                // the animated sections' texels too, from the static texture (drawn whole first when it does not hold
                // the cascade), then the animated sections
                if (!cached) fresh[c] = true;
                Cascade.union(rect, scratchRect);
            }
            if (!fresh[c] && viewCasters && terrain.rectOf(c, TerrainCasters.NEW, cascade, growRect)) {
                // sections that can shadow the view now but are not in the kept drawing (the camera turned towards
                // them): drawn into the static texture, their texels copied over
                if (cached) {
                    grown[c] = true;
                    Cascade.union(rect, growRect);
                } else {
                    fresh[c] = true;
                }
            }
            if (fresh[c] || patched[c] || tickDue || grown[c]) {
                redraw[c] = TerrainCasters.Draws.ANIMATED;
                copied[c] = true;
                anyFresh |= fresh[c] || patched[c] || grown[c];
            } else {
                // the old entities' texels cleared, then the sections whose geometry reaches them
                redraw[c] = rect[2] > 0 && rect[3] > 0 ? TerrainCasters.Draws.ALL : null;
            }
        }
        if (anyFresh) {
            for (int c = 0; c < count; c++) if (fresh[c] || patched[c] || grown[c]) terrain.prepare(c, cascades[c], marked, pad, batched, prepare);
            try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/clear")) {
                for (int c = 0; c < count; c++) {
                    if (fresh[c]) {
                        if (viewCasters) clearDirty(encoder, cache, cacheDirty[c], c);
                        else encoder.clearColorAndDepthTextures(color, 0, cache, 1.0, (c & 1) * tile, (c >> 1) * tile, tile, tile);
                    } else if (patched[c]) {
                        int[] rect = patch[c];
                        if (rect[2] > 0 && rect[3] > 0) encoder.clearColorAndDepthTextures(color, 0, cache, 1.0, rect[0], rect[1], rect[2], rect[3]);
                    }
                }
            }
            try (RenderPass pass = encoder.createRenderPass(() -> "BlockLand sun shadows (terrain kept)", colorView, OptionalInt.empty(), cacheView,
                    OptionalDouble.empty())) {
                for (int c = 0; c < count; c++) {
                    if (fresh[c]) {
                        pass.enableScissor((c & 1) * tile, (c >> 1) * tile, tile, tile);
                        try (FrameTimings.Span whole = FrameTimings.span("bl/shadow/whole")) {
                            drawTerrain(pass, c, batched, TerrainCasters.Draws.STATIC, null, all, blocks, indices, indexType);
                        }
                        terrainCache.drawn(c, cascades[c], blocks, terrain, all, true);
                        if (viewCasters) System.arraycopy(needRect[c], 0, cacheDirty[c], 0, 4);
                        continue;
                    }
                    if (patched[c]) {
                        // only into the cleared texels, only the kept sections whose geometry reaches them (the changed
                        // ones left the kept set; those in view among them come back below as new)
                        int[] rect = patch[c];
                        if (rect[2] > 0 && rect[3] > 0) {
                            pass.enableScissor(rect[0], rect[1], rect[2], rect[3]);
                            try (FrameTimings.Span patchSpan = FrameTimings.span("bl/shadow/patched")) {
                                drawTerrain(pass, c, batched, TerrainCasters.Draws.STATIC, rect, viewCasters ? TerrainCasters.CACHED : TerrainCasters.REACHING,
                                        blocks, indices, indexType);
                            }
                        }
                        if (!viewCasters) {
                            // every reaching section is in the drawing again
                            terrainCache.added(c, terrain, TerrainCasters.REACHING);
                        } else if (terrain.rectOf(c, TerrainCasters.NEW, cascades[c], growRect)) {
                            grown[c] = true;
                            Cascade.union(restore[c], growRect);
                        }
                    }
                    if (grown[c]) {
                        // the sections newly in view, whole (they only touch their own texels, copied over below)
                        pass.enableScissor((c & 1) * tile, (c >> 1) * tile, tile, tile);
                        try (FrameTimings.Span grownSpan = FrameTimings.span("bl/shadow/grown")) {
                            drawTerrain(pass, c, batched, TerrainCasters.Draws.STATIC, null, TerrainCasters.NEW, blocks, indices, indexType);
                        }
                        terrainCache.added(c, terrain, TerrainCasters.NEW);
                        if (cacheDirty[c][2] >= 0) Cascade.union(cacheDirty[c], growRect);
                    }
                }
                pass.disableScissor();
            }
        }
        try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/clear")) {
            // the old content of a tile that takes a fresh drawing (only the texels in view are copied in)
            for (int c = 0; c < count; c++) if (due[c] && atlasClear[c]) clearDirty(encoder, atlas, atlasDirty[c], c);
        }
        try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/copy")) {
            for (int c = 0; c < count; c++) {
                if (!due[c] || !copied[c]) continue;
                int[] rect = restore[c];
                if (rect[2] > 0 && rect[3] > 0) {
                    DepthBlit.copy(color, cache, atlas, rect[0], rect[1], rect[2], rect[3]);
                    if (atlasDirty[c][2] >= 0) Cascade.union(atlasDirty[c], rect);
                }
            }
        }
        try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/clear")) {
            for (int c = 0; c < count; c++) {
                if (!due[c] || copied[c]) continue;
                if (direct[c]) {
                    encoder.clearColorAndDepthTextures(color, 0, atlas, 1.0, (c & 1) * tile, (c >> 1) * tile, tile, tile);
                    atlasDirty[c][2] = -1;
                    continue;
                }
                int[] rect = restore[c];
                if (rect[2] > 0 && rect[3] > 0) encoder.clearColorAndDepthTextures(color, 0, atlas, 1.0, rect[0], rect[1], rect[2], rect[3]);
            }
        }
        for (int c = 0; c < count; c++) if (due[c] && (direct[c] || redraw[c] != null)) terrain.prepare(c, cascades[c], marked, pad, batched, prepare);
        try (RenderPass pass = encoder.createRenderPass(() -> "BlockLand sun shadows (kept)", colorView, OptionalInt.empty(), atlasView,
                OptionalDouble.empty())) {
            for (int c = 0; c < count; c++) {
                if (!due[c]) continue;
                Cascade cascade = cascades[c];
                int[] rect = restore[c];
                if (direct[c]) {
                    pass.enableScissor((c & 1) * tile, (c >> 1) * tile, tile, tile);
                    try (FrameTimings.Span placedSpan = FrameTimings.span("bl/shadow/placed")) {
                        drawTerrain(pass, c, batched, TerrainCasters.Draws.ALL, null, TerrainCasters.REACHING, blocks, indices, indexType);
                    }
                } else if (redraw[c] != null) {
                    // only into the copied or cleared texels (a draw repeated elsewhere would change nothing, so nothing
                    // is lost by not making it); the animated sections all of them, the others only those reaching
                    boolean animated = redraw[c] == TerrainCasters.Draws.ANIMATED;
                    pass.enableScissor(rect[0], rect[1], rect[2], rect[3]);
                    try (FrameTimings.Span redrawn = FrameTimings.span(animated ? "bl/shadow/animated" : "bl/shadow/touched")) {
                        drawTerrain(pass, c, batched, redraw[c], animated ? null : rect, animated ? animatedMode : filling, blocks, indices, indexType);
                    }
                    if (atlasDirty[c][2] >= 0) Cascade.union(atlasDirty[c], rect);
                }
                pass.enableScissor((c & 1) * tile, (c >> 1) * tile, tile, tile);
                entityRect[2] = entityRect[3] = 0;
                if (!entities.isEmpty() && cascade.split + cascade.half > EntityCasters.RANGE * 0.5f) {
                    try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/entity_draws")) {
                        pass.setPipeline(ShadowPipelines.ENTITY);
                        pass.setUniform("ShadowCascade", entityUbos[c].currentBuffer());
                        entities.draw(pass, indices, indexType);
                    }
                    if (!casters || !cascade.tileRect(cascade.entityRender, casterMin.x, casterMin.y, casterMin.z, casterMax.x, casterMax.y, casterMax.z,
                            entityRect, scratchVector)) {
                        // nothing of them lands in the tile — or their box is unknown: then the whole tile may hold them
                        if (!casters) cascade.tileRect(entityRect);
                    }
                }
                if (direct[c]) terrainCache.drawn(c, cascade, blocks, terrain, TerrainCasters.REACHING, false);
                if (atlasDirty[c][2] >= 0) Cascade.union(atlasDirty[c], entityRect);
                terrainCache.atlasDrawn(c, tick, entityRect);
            }
            pass.disableScissor();
        }
    }

    /**
     * A cascade's terrain casters ({@code which} of them, see {@link TerrainCasters.Draws}): the solid layer, then the
     * alpha-tested ones against the block atlas.
     *
     * @param touching just the sections whose casters can touch these atlas texels (null: all of them)
     * @param mode     which of the reaching sections ({@link TerrainCasters#picks}: every one, those in view, the kept ones, the new ones)
     */
    private void drawTerrain(RenderPass pass, int c, boolean batched, TerrainCasters.Draws which, @Nullable int[] touching, int mode,
                             GpuTextureView blocks, GpuBuffer indices, VertexFormat.IndexType indexType) {
        Cascade cascade = cascades[c];
        GpuBuffer ubo = ubos[c].currentBuffer();
        try (FrameTimings.Span ignored = FrameTimings.span("bl/shadow/terrain");
             FrameTimings.Span perCascade = FrameTimings.span(TERRAIN_SPANS[c])) {
            if (which != TerrainCasters.Draws.ANIMATED) {
                pass.setPipeline(ShadowPipelines.terrain(false, pagedCasters));
                pass.setUniform("ShadowCascade", ubo);
                drawnSections += batched ? terrain.drawBatched(pass, c, false, which, indices, indexType, touching, cascade, mode)
                        : terrain.draw(pass, c, false, which, indices, indexType, touching, cascade, mode);
            }
            drawCutout(pass, ShadowPipelines.terrain(true, pagedCasters), ubo, c, batched, which, touching, mode, blocks, indices, indexType, cascade);
        }
    }

    /** The cutout layers of the sections {@code which} takes, with the given (alpha-tested) pipeline. */
    private void drawCutout(RenderPass pass, RenderPipeline pipeline, GpuBuffer ubo, int c, boolean batched, TerrainCasters.Draws which,
                            @Nullable int[] touching, int mode, GpuTextureView blocks, GpuBuffer indices, VertexFormat.IndexType indexType, Cascade cascade) {
        pass.setPipeline(pipeline);
        pass.setUniform("ShadowCascade", ubo);
        pass.bindSampler("Sampler0", blocks);
        if (batched) terrain.drawBatched(pass, c, true, which, indices, indexType, touching, cascade, mode);
        else terrain.draw(pass, c, true, which, indices, indexType, touching, cascade, mode);
    }

    /** Each cascade's terrain on its own ({@link FrameTimings}). */
    private static final String[] TERRAIN_SPANS = {"bl/shadow/terrain/c0", "bl/shadow/terrain/c1", "bl/shadow/terrain/c2", "bl/shadow/terrain/c3"};

    /** The far cascades take turns: with four, the third on even frames and the fourth on odd ones. */
    private boolean scheduled(int cascade, int count) {
        if (count <= 2 || cascade < 2) return true;
        if (count == 3) return (frame & 1) == 0;
        return cascade == 2 ? (frame & 1) == 0 : (frame & 1) == 1;
    }

    /** Reaches growing geometrically from a near cascade of a few blocks up to the shadow distance. */
    static void splits(int count, float distance, float[] out) {
        if (count == 1) {
            out[0] = distance;
            return;
        }
        float first = count == 2 ? 0.25f : count == 3 ? 0.12f : 0.085f;
        first = Math.min(distance, Math.max(6f, Math.min(24f, distance * first)));
        for (int i = 0; i < count; i++) {
            out[i] = (float) (distance * Math.pow(first / distance, (count - 1 - i) / (double) (count - 1)));
        }
    }

    private void ensureAtlas(int tileSize) {
        if (atlas != null && tile == tileSize && !atlas.isClosed()) return;
        release();
        tile = tileSize;
        int size = tileSize * 2;
        var device = RenderSystem.getDevice();
        // (a copy destination too: the kept terrain of SHADOW_REUSE is copied into it)
        atlas = device.createTexture(() -> "BlockLand sun shadows",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                TextureFormat.DEPTH32, size, size, 1, 1);
        atlas.setTextureFilter(FilterMode.LINEAR, false);
        atlas.setAddressMode(AddressMode.CLAMP_TO_EDGE);
        SceneLighting.compareMode(atlas);
        atlasView = device.createTextureView(atlas);
        // Blaze3D render passes need a colour attachment of the target's size; nothing is ever written to it
        color = device.createTexture(() -> "BlockLand sun shadows (no colour)", GpuTexture.USAGE_RENDER_ATTACHMENT,
                TextureFormat.RED8, size, size, 1, 1);
        colorView = device.createTextureView(color);
        for (Cascade cascade : cascades) cascade.valid = false;
        terrainCache.invalidate();
        unknownDirty();
    }

    /** Frees the atlas (shadows switched off). */
    void release() {
        if (atlasView != null) atlasView.close();
        if (atlas != null) atlas.close();
        if (colorView != null) colorView.close();
        if (color != null) color.close();
        atlas = null;
        atlasView = null;
        color = null;
        colorView = null;
        info = null;
        for (Cascade cascade : cascades) cascade.valid = false;
        terrainCache.release();
    }

    void close() {
        release();
        entities.close();
        for (int i = 0; i < ubos.length; i++) {
            if (ubos[i] != null) ubos[i].close();
            if (entityUbos[i] != null) entityUbos[i].close();
            ubos[i] = null;
            entityUbos[i] = null;
        }
    }
}
