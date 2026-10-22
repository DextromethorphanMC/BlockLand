package com.blockland.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Collects a frame's draws of retained models and submits them together: all per-draw transforms are written
 * with one {@link DynamicUniforms#writeTransforms} call and everything is drawn in a single render pass,
 * opaque first, then blended. Draw records are pooled, so steady-state frames allocate nothing but the
 * transform array.
 */
public final class DrawList {
    private final List<Entry> pool = new ArrayList<>();
    private int size;
    /** Draw calls and triangles of the last flush (shown on the F3 screen). */
    private int lastDraws, lastTriangles;

    private static final class Entry {
        RenderPipeline pipeline;
        GpuBuffer vertices;
        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        int firstIndex;
        int indexCount;
        GpuTextureView texture;
        final Matrix4f modelView = new Matrix4f();
        final Vector4f color = new Vector4f();
        final Vector3f offset = new Vector3f();
        final Matrix4f params = new Matrix4f();
    }

    /** Surface parameters of one draw (see gun.fsh). */
    public record Surface(float specular, float shininess, float alphaCutoff, float metallic, boolean emissive) {
        public static final Surface MATTE = new Surface(0.05f, 8, 0, 0, false);
        public static final Surface EMISSIVE = new Surface(0, 1, 0, 0, true);
    }

    public int lastDraws() {
        return lastDraws;
    }

    public int lastTriangles() {
        return lastTriangles;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
    }

    public void add(RenderPipeline pipeline, GpuBuffer vertices, GpuBuffer indices, VertexFormat.IndexType indexType,
                    int firstIndex, int indexCount, Matrix4f modelView, Vector4f color, int blockLight, int skyLight,
                    Surface surface, GpuTextureView texture) {
        if (size == pool.size()) pool.add(new Entry());
        Entry e = pool.get(size++);
        e.pipeline = pipeline;
        e.vertices = vertices;
        e.indices = indices;
        e.indexType = indexType;
        e.firstIndex = firstIndex;
        e.indexCount = indexCount;
        e.texture = texture;
        e.modelView.set(modelView);
        e.color.set(color);
        e.offset.set(blockLight, skyLight, surface.emissive() ? 1 : 0);
        e.params.zero();
        e.params.m00(surface.specular()).m01(surface.shininess()).m02(surface.alphaCutoff()).m03(surface.metallic());
    }

    /**
     * Draws everything into the given targets and clears the list.
     *
     * @param lightmap Minecraft's lightmap texture (sampler 2)
     */
    public void flush(GpuTextureView color, GpuTextureView depth, GpuTextureView lightmap, String label) {
        if (size == 0) return;
        // opaque before blended; otherwise keep submission order (callers add back to front)
        Entry[] ordered = new Entry[size];
        int n = 0;
        for (int i = 0; i < size; i++) if (GunPipelines.isOpaque(pool.get(i).pipeline)) ordered[n++] = pool.get(i);
        for (int i = 0; i < size; i++) if (!GunPipelines.isOpaque(pool.get(i).pipeline)) ordered[n++] = pool.get(i);

        DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[size];
        for (int i = 0; i < size; i++) {
            Entry e = ordered[i];
            transforms[i] = new DynamicUniforms.Transform(e.modelView, e.color, e.offset, e.params, 1.0F);
        }
        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        lastDraws = size;
        lastTriangles = 0;
        for (int i = 0; i < size; i++) lastTriangles += ordered[i].indexCount / 3;

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> label, color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindSampler("Sampler2", lightmap);
            RenderPipeline pipeline = null;
            GpuBuffer vertices = null;
            GpuBuffer indices = null;
            GpuTextureView texture = null;
            for (int i = 0; i < size; i++) {
                Entry e = ordered[i];
                if (e.pipeline != pipeline) {
                    pass.setPipeline(e.pipeline);
                    pipeline = e.pipeline;
                }
                if (e.vertices != vertices) {
                    pass.setVertexBuffer(0, e.vertices);
                    vertices = e.vertices;
                }
                if (e.indices != indices) {
                    pass.setIndexBuffer(e.indices, e.indexType);
                    indices = e.indices;
                }
                if (e.texture != texture) {
                    pass.bindSampler("Sampler0", e.texture);
                    texture = e.texture;
                }
                pass.setUniform("DynamicTransforms", slices[i]);
                pass.drawIndexed(0, e.firstIndex, e.indexCount, 1);
            }
        }
        clear();
    }
}
