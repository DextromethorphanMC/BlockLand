package com.blockland.client.render;

import com.blockland.client.gltf.GltfModel;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A glTF model resident on the GPU: every mesh of the file is packed into ONE vertex buffer and ONE index
 * buffer at load time (no per-frame vertex building or uploads), plus one texture per embedded image.
 * Drawing a mesh is then just "set transform, draw range" — see {@link #addMesh}.
 */
public final class GpuModel implements AutoCloseable {
    public final GltfModel source;
    private final GpuBuffer vertices;
    private final GpuBuffer indices;
    private final VertexFormat.IndexType indexType;
    private final List<List<Part>> meshParts = new ArrayList<>();
    private final GpuTexture[] textures;
    private final GpuTextureView[] views;
    private final Vector4f color = new Vector4f();

    /** An index range of the shared buffers drawn with one material. */
    public record Part(int firstIndex, int indexCount, int material) {
    }

    /** Per-material look overrides (skins, the player's skin on arms). */
    public interface Style {
        Style NONE = new Style() { };

        /** Colour multiplier for a material, or null. */
        @Nullable
        default float[] tint(String material) {
            return null;
        }

        /** Texture replacing the material's own, or null. */
        @Nullable
        default GpuTextureView texture(String material) {
            return null;
        }
    }

    private GpuModel(String label, GltfModel source) {
        this.source = source;
        int vertexCount = 0, indexCount = 0;
        for (GltfModel.Mesh mesh : source.meshes) {
            for (GltfModel.Primitive p : mesh.primitives) {
                vertexCount += p.vertexCount();
                indexCount += p.indices.length;
            }
        }
        boolean shortIndices = vertexCount < 65536;
        indexType = shortIndices ? VertexFormat.IndexType.SHORT : VertexFormat.IndexType.INT;
        ByteBuffer vb = ByteBuffer.allocateDirect(Math.max(1, vertexCount) * GunPipelines.FORMAT.getVertexSize()).order(ByteOrder.nativeOrder());
        ByteBuffer ib = ByteBuffer.allocateDirect(Math.max(1, indexCount) * (shortIndices ? 2 : 4)).order(ByteOrder.nativeOrder());
        int baseVertex = 0, firstIndex = 0;
        for (GltfModel.Mesh mesh : source.meshes) {
            List<Part> parts = new ArrayList<>();
            for (GltfModel.Primitive p : mesh.primitives) {
                for (int v = 0; v < p.vertexCount(); v++) {
                    vb.putFloat(p.positions[v * 3]).putFloat(p.positions[v * 3 + 1]).putFloat(p.positions[v * 3 + 2]);
                    vb.putFloat(p.uvs[v * 2]).putFloat(p.uvs[v * 2 + 1]);
                    vb.put(normalByte(p.normals[v * 3])).put(normalByte(p.normals[v * 3 + 1])).put(normalByte(p.normals[v * 3 + 2])).put((byte) 0);
                }
                // indices are rebased on upload so every draw uses base vertex 0
                for (int index : p.indices) {
                    if (shortIndices) ib.putShort((short) (index + baseVertex));
                    else ib.putInt(index + baseVertex);
                }
                parts.add(new Part(firstIndex, p.indices.length, p.material));
                baseVertex += p.vertexCount();
                firstIndex += p.indices.length;
            }
            meshParts.add(parts);
        }
        vb.flip();
        ib.flip();
        GpuDevice device = RenderSystem.getDevice();
        vertices = device.createBuffer(() -> label + " vertices", GpuBuffer.USAGE_VERTEX, vb);
        indices = device.createBuffer(() -> label + " indices", GpuBuffer.USAGE_INDEX, ib);

        textures = new GpuTexture[source.images.size()];
        views = new GpuTextureView[source.images.size()];
        for (int i = 0; i < textures.length; i++) {
            try (NativeImage image = NativeImage.read(source.images.get(i))) {
                textures[i] = texture(label + " image " + i, image, true);
                views[i] = device.createTextureView(textures[i]);
            } catch (Exception e) {
                textures[i] = null;
                views[i] = null;
            }
        }
    }

    public static GpuModel upload(String label, GltfModel source) {
        return new GpuModel(label, source);
    }

    /** Uploads an image as a sampled texture ({@code repeat} for tiling patterns). */
    public static GpuTexture texture(String label, NativeImage image, boolean repeat) {
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture texture = device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8, image.getWidth(), image.getHeight(), 1, 1);
        device.createCommandEncoder().writeToTexture(texture, image);
        texture.setAddressMode(repeat ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE);
        texture.setTextureFilter(FilterMode.LINEAR, false);
        return texture;
    }

    private static float srgb(float linear) {
        return (float) Math.pow(Math.max(0, Math.min(1, linear)), 1 / 2.2);
    }

    private static byte normalByte(float v) {
        return (byte) Math.round(Math.max(-1, Math.min(1, v)) * 127);
    }

    public List<Part> parts(int mesh) {
        return meshParts.get(mesh);
    }

    /**
     * Queues every primitive of {@code mesh} with the given model-view matrix.
     *
     * @param alpha overall opacity (fades), multiplied into each material's colour
     */
    public void addMesh(DrawList list, int mesh, Matrix4f modelView, Style style, int blockLight, int skyLight, float alpha) {
        for (Part part : meshParts.get(mesh)) {
            GltfModel.Material material = part.material >= 0 && part.material < source.materials.size()
                    ? source.materials.get(part.material) : null;
            String name = material == null ? "" : material.name;
            boolean emissive = material != null && material.isEmissive();
            float[] base = material == null ? new float[]{1, 1, 1, 1} : material.baseColor;
            float[] tint = style.tint(name);
            // glTF colour factors are linear; Minecraft shades in display (sRGB) space
            if (emissive) {
                // emissive parts glow with their emissive colour, unaffected by light
                float[] e = material.emissive;
                color.set(srgb(Math.max(base[0], e[0])), srgb(Math.max(base[1], e[1])), srgb(Math.max(base[2], e[2])), base[3] * alpha);
            } else {
                color.set(srgb(base[0]), srgb(base[1]), srgb(base[2]), base[3] * alpha);
            }
            // a skin gives the material's display colour directly (dark base colours would swallow a multiplier)
            if (tint != null) color.set(tint[0], tint[1], tint[2], color.w);
            GpuTextureView texture = style.texture(name);
            if (texture == null) texture = material != null && material.image >= 0 && material.image < views.length
                    && views[material.image] != null ? views[material.image] : Textures.white();
            RenderPipeline pipeline;
            if (material != null && "ADD".equals(material.alphaMode)) pipeline = GunPipelines.ADDITIVE; // effects only
            else if (material != null && material.isBlend() || alpha < 0.999f) pipeline = GunPipelines.TRANSLUCENT;
            else if (material != null && material.doubleSided) pipeline = GunPipelines.OPAQUE_NO_CULL;
            else pipeline = GunPipelines.OPAQUE;
            DrawList.Surface surface = surface(material, emissive);
            list.add(pipeline, vertices, indices, indexType, part.firstIndex, part.indexCount, modelView, color,
                    blockLight, skyLight, surface, texture);
        }
    }

    /** Maps glTF metallic/roughness to the shader's simple specular model. */
    private static DrawList.Surface surface(@Nullable GltfModel.Material material, boolean emissive) {
        if (material == null) return DrawList.Surface.MATTE;
        float cutoff = "MASK".equals(material.alphaMode) ? material.alphaCutoff : 0;
        if (emissive) return new DrawList.Surface(0, 1, cutoff, 0, true);
        float smooth = 1 - Math.max(0, Math.min(1, material.roughness));
        float metallic = Math.max(0, Math.min(1, material.metallic));
        float specular = (0.06f + 0.9f * metallic) * (0.2f + 0.8f * smooth * smooth);
        float shininess = 6 + 140 * smooth * smooth * smooth;
        return new DrawList.Surface(specular, shininess, cutoff, metallic, false);
    }

    @Override
    public void close() {
        vertices.close();
        indices.close();
        for (int i = 0; i < textures.length; i++) {
            if (views[i] != null) views[i].close();
            if (textures[i] != null) textures[i].close();
        }
    }
}
