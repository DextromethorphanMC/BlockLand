package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.ShadowInfo;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
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
 * The sun's cascaded shadow map (rendering spec §6): up to four orthographic boxes centred on the camera, each in its
 * tile of one depth atlas (2×2 tiles). Cascade reaches grow geometrically up to the shadow distance, so the nearest
 * shadows get a texel per few centimetres and the farthest still cover the shadow distance. The two nearest cascades are
 * redrawn every frame, the far ones on alternate frames (each keeps its last drawing, re-aimed at the moving camera).
 * Every box reaches far past the camera towards the light, so roofs, tree tops and the ground above a cave cast into it.
 */
final class ShadowMap {
    /** How far every box reaches towards the light beyond its own size (blocks). */
    private static final float UP = 192f;
    /** Alpha below which casters' texels let light through (leaves, glass frames, skins' outer layer). */
    private static final float CUTOFF = 0.4f;
    /** Light direction change (cosine) that redraws every cascade at once (the sun hands over to the moon). */
    private static final float TURN = 0.9995f;

    private final Cascade[] cascades = {new Cascade(0), new Cascade(1), new Cascade(2), new Cascade(3)};
    private final boolean[] due = new boolean[4];
    private final float[] splits = new float[4];
    private final MappableRingBuffer[] ubos = new MappableRingBuffer[4];
    private final TerrainCasters terrain = new TerrainCasters();
    private final EntityCasters entities = new EntityCasters();
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
        return String.format("%d sections, %d entities, %.2f ms CPU", drawnSections, entities.entityCount(), millis);
    }

    /** Draws this frame's cascades (those due) for a camera at the given position. */
    void render(ClientLevel level, GraphicsSettings settings, double camX, double camY, double camZ, float partialTick) {
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
        float strength = SunPath.shadowStrength(level, partialTick);
        if (strength < 0.01f) {
            reason = null;
            return;
        }
        reason = null;
        long start = System.nanoTime();
        try {
            draw(level, settings, quality, strength, camX, camY, camZ, partialTick);
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

    private void draw(ClientLevel level, GraphicsSettings settings, GraphicsSettings.ShadowQuality quality, float strength,
                      double camX, double camY, double camZ, float partialTick) {
        frame++;
        int count = Math.min(4, quality.cascades);
        ensureAtlas(quality.tile);
        if (level != this.level) {
            this.level = level;
            for (Cascade cascade : cascades) cascade.valid = false;
        }
        SunPath.light(level, partialTick, light);
        splits(count, settings.shadowDistance, splits);

        // which cascades are redrawn this frame, placed around the camera as it is now
        int outer = -1;
        boolean all = true;
        for (int c = 0; c < count; c++) {
            Cascade cascade = cascades[c];
            boolean stale = !cascade.valid || cascade.tile != tile || cascade.split != splits[c]
                    || cascade.light.dot(light) < TURN || cascade.drift(camX, camY, camZ) > cascade.half * 0.15;
            due[c] = stale || scheduled(c, count);
            if (due[c]) {
                cascade.place(light, camX, camY, camZ, splits[c], tile, UP);
                outer = c;
            } else {
                all = false;
            }
            cascade.update(camX, camY, camZ);
        }
        for (int c = count; c < 4; c++) due[c] = false;

        if (!terrain.gather(level, cascades[outer], camX, camY, camZ)) {
            throw new IllegalStateException("view area unreadable");
        }
        entities.capture(level, camX, camY, camZ, partialTick);

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (int c = 0; c < count; c++) {
            if (!due[c]) continue;
            if (ubos[c] == null) {
                int index = c;
                ubos[c] = new MappableRingBuffer(() -> "BlockLand shadow cascade " + index, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                        ShadowPipelines.CASCADE_UBO_SIZE);
            }
            Cascade cascade = cascades[c];
            try (GpuBuffer.MappedView view = encoder.mapBuffer(ubos[c].currentBuffer(), false, true)) {
                Std140Builder.intoBuffer(view.data()).putMat4f(cascade.render).putVec4(cascade.tileX(), cascade.tileY(), CUTOFF, 0);
            }
        }
        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indices = sequential.getBuffer(Math.max(6, Math.max(terrain.maxSequentialIndices(), entities.maxSequentialIndices())));
        VertexFormat.IndexType indexType = sequential.type();
        GpuTextureView blocks = ChunkSectionLayer.CUTOUT_MIPPED.textureView();

        drawnSections = 0;
        try (RenderPass pass = encoder.createRenderPass(() -> "BlockLand sun shadows", colorView, OptionalInt.empty(), atlasView,
                all ? OptionalDouble.of(1.0) : OptionalDouble.empty())) {
            for (int c = 0; c < count; c++) {
                if (!due[c]) continue;
                Cascade cascade = cascades[c];
                GpuBuffer ubo = ubos[c].currentBuffer();
                pass.enableScissor((c & 1) * tile, (c >> 1) * tile, tile, tile);
                if (!all) {
                    pass.setPipeline(ShadowPipelines.CLEAR);
                    pass.setUniform("ShadowCascade", ubo);
                    pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
                    pass.setIndexBuffer(indices, indexType);
                    pass.drawIndexed(0, 0, 6, 1);
                }
                pass.setPipeline(ShadowPipelines.TERRAIN_SOLID);
                pass.setUniform("ShadowCascade", ubo);
                drawnSections += terrain.draw(pass, cascade, false, indices, indexType);
                pass.setPipeline(ShadowPipelines.TERRAIN_CUTOUT);
                pass.setUniform("ShadowCascade", ubo);
                pass.bindSampler("Sampler0", blocks);
                terrain.draw(pass, cascade, true, indices, indexType);
                if (!entities.isEmpty() && cascade.split + cascade.half > EntityCasters.RANGE * 0.5f) {
                    pass.setPipeline(ShadowPipelines.ENTITY);
                    pass.setUniform("ShadowCascade", ubo);
                    entities.draw(pass, indices, indexType);
                }
            }
            pass.disableScissor();
        }
        for (int c = 0; c < count; c++) if (due[c]) ubos[c].rotate();

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
        atlas = device.createTexture(() -> "BlockLand sun shadows", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
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
    }

    void close() {
        release();
        entities.close();
        for (int i = 0; i < ubos.length; i++) {
            if (ubos[i] != null) ubos[i].close();
            ubos[i] = null;
        }
    }
}
