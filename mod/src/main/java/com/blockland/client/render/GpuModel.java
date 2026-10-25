package com.blockland.client.render;

import com.blockland.BlockLand;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A glTF model resident on the GPU: every mesh of the file is packed into ONE vertex buffer and ONE index
 * buffer at load time (no per-frame vertex building or uploads), plus one mipmapped texture per embedded image.
 * Drawing a mesh is then just "set transform, draw range" — see {@link #addNode}. Each draw resolves its material:
 * the glTF factors and image, a LabPBR texture set (asset spec §20) and what a {@link Style} changes — a skin's
 * layer for the material's region (§21), legacy per-material tints and textures.
 */
public final class GpuModel implements AutoCloseable {
    private static final float[] WHITE = {1, 1, 1, 1};
    private static final float[] NO_EFFECT_COLOR = {1, 1, 1};
    private static final Vector3fc ZERO = new Vector3f();

    public final GltfModel source;
    private final GpuBuffer vertices;
    private final GpuBuffer indices;
    private final VertexFormat.IndexType indexType;
    private final int vertexCount;
    private final List<List<Part>> meshParts = new ArrayList<>();
    private final GpuTexture[] textures;
    private final GpuTextureView[] views;
    /** Rest-pose position of every node's origin in model space (effects run in model space). */
    @Nullable
    private Vector3f[] restOrigins;
    private final Vector4f color = new Vector4f();
    private final Vector3f shiftedOrigin = new Vector3f();

    /**
     * An index range of the shared buffers drawn with one material, the triangles it came from and where its vertices
     * start in the shared vertex buffer (its indices are rebased by that much).
     */
    public record Part(int firstIndex, int indexCount, int material, GltfModel.Primitive primitive, int baseVertex) {
    }

    /**
     * Per-material look overrides: a skin's layer for a region, legacy per-material tints and textures, the player's
     * skin on arms, effect textures.
     */
    public interface Style {
        Style NONE = new Style() { };

        /** Display colour replacing the material's, or null. */
        @Nullable
        default float[] tint(String material) {
            return null;
        }

        /** Texture replacing the material's albedo, for both render paths, or null. */
        @Nullable
        default TextureRef textureRef(String material) {
            return null;
        }

        /** Texture replacing the material's albedo (our pipeline only; prefer {@link #textureRef}), or null. */
        @Nullable
        default GpuTextureView texture(String material) {
            return null;
        }

        /** The skin layer for a material of {@code region} (never called for region "none" or ""), or null. */
        @Nullable
        default MaterialLayer layer(String material, String region) {
            return null;
        }

        /** Whether a material is not drawn at all (another pass draws it in its own way, e.g. a reflex sight's glass). */
        default boolean hidden(String material) {
            return false;
        }

        /**
         * How far the skin's shader effects are surging this frame, 0..1 (asset spec §38: a special skin's inspect or
         * kill sets them ablaze); 0 = the effects at rest. Only layers with an effect read it.
         */
        default float surge() {
            return 0;
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
                    putVertex(vb, p.positions[v * 3], p.positions[v * 3 + 1], p.positions[v * 3 + 2], p.uvs[v * 2], p.uvs[v * 2 + 1],
                            p.normals[v * 3], p.normals[v * 3 + 1], p.normals[v * 3 + 2], p.ao == null ? 1 : p.ao[v]);
                }
                // indices are rebased on upload so every draw uses base vertex 0
                for (int index : p.indices) {
                    if (shortIndices) ib.putShort((short) (index + baseVertex));
                    else ib.putInt(index + baseVertex);
                }
                parts.add(new Part(firstIndex, p.indices.length, p.material, p, baseVertex));
                baseVertex += p.vertexCount();
                firstIndex += p.indices.length;
            }
            meshParts.add(parts);
        }
        this.vertexCount = vertexCount;
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
                BlockLand.LOGGER.warn("[BlockLand] Cannot upload image {} of {}", i, label, e);
                textures[i] = null;
                views[i] = null;
            }
        }
    }

    public static GpuModel upload(String label, GltfModel source) {
        return new GpuModel(label, source);
    }

    /** Uploads a colour image as a sampled, mipmapped texture ({@code repeat} for tiling patterns). */
    public static GpuTexture texture(String label, NativeImage image, boolean repeat) {
        return Textures.upload(label, image, repeat, Textures.Kind.COLOR);
    }

    private static float srgb(float linear) {
        return (float) Math.pow(Math.max(0, Math.min(1, linear)), 1 / 2.2);
    }

    /** Multiplier that moves an albedo channel with this mean to {@code target} (capped, near-black stays usable). */
    static float recolour(float target, float mean) {
        return Math.min(6, target / Math.max(0.05f, mean));
    }

    private static byte unit(float v) {
        return (byte) Math.round((Math.max(-1, Math.min(1, v)) * 0.5f + 0.5f) * 255);
    }

    /**
     * One vertex in {@link GunPipelines#FORMAT}: position, uv, the normal as unsigned bytes (0..255 = −1..1) and the baked
     * AO in the fourth byte. Buffers written for {@link #addMesh(DrawList, int, GpuBuffer, List, Matrix4f, Style, int, int, float)}
     * use it too.
     */
    public static void putVertex(ByteBuffer to, float x, float y, float z, float u, float v, float nx, float ny, float nz, float ao) {
        to.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        to.put(unit(nx)).put(unit(ny)).put(unit(nz)).put((byte) Math.round(Math.max(0, Math.min(1, ao)) * 255));
    }

    /** Vertices in the shared vertex buffer (a buffer standing in for it, see {@link #addMesh(DrawList, int, GpuBuffer, List, Matrix4f, Style, int, int, float)}, has as many). */
    public int vertexCount() {
        return vertexCount;
    }

    /** The shared vertex buffer (for passes that draw some parts with a pipeline of their own; see {@link #parts}). */
    public GpuBuffer vertexBuffer() {
        return vertices;
    }

    public GpuBuffer indexBuffer() {
        return indices;
    }

    public VertexFormat.IndexType indexType() {
        return indexType;
    }

    public List<Part> parts(int mesh) {
        return meshParts.get(mesh);
    }

    /** Where a node's origin sits in the model's rest pose. */
    public Vector3fc restOrigin(int node) {
        if (restOrigins == null) {
            Pose rest = new Pose(source);
            rest.computeWorld(new Matrix4f());
            restOrigins = new Vector3f[source.nodes.size()];
            for (int i = 0; i < restOrigins.length; i++) restOrigins[i] = rest.world[i].getTranslation(new Vector3f());
        }
        return node >= 0 && node < restOrigins.length ? restOrigins[node] : ZERO;
    }

    /**
     * Queues the mesh of {@code node} (nothing when it has none) with the given model-view matrix.
     *
     * @param defaultRegion skin region of materials that do not name one (an attachment's slot), or ""
     * @param alpha         overall opacity (fades), multiplied into each material's colour
     */
    public void addNode(DrawList list, int node, Matrix4f modelView, Style style, String defaultRegion,
                        int blockLight, int skyLight, float alpha) {
        if (node < 0 || node >= source.nodes.size()) return;
        int mesh = source.nodes.get(node).mesh;
        if (mesh < 0) return;
        addMesh(list, mesh, modelView, style, defaultRegion, restOrigin(node), blockLight, skyLight, alpha);
    }

    /**
     * Like {@link #addNode(DrawList, int, Matrix4f, Style, String, int, int, float)}, with the node's effect origin moved
     * by {@code originOffset}: an attachment passes its mount's rest position, so skin effects run in the weapon's space
     * and continue across the joint without a seam (asset spec §30).
     */
    public void addNode(DrawList list, int node, Matrix4f modelView, Style style, String defaultRegion, Vector3fc originOffset,
                        int blockLight, int skyLight, float alpha) {
        if (node < 0 || node >= source.nodes.size()) return;
        int mesh = source.nodes.get(node).mesh;
        if (mesh < 0) return;
        // DrawList copies the origin right away, so one scratch vector serves every call
        addMesh(list, mesh, modelView, style, defaultRegion, shiftedOrigin.set(restOrigin(node)).add(originOffset),
                blockLight, skyLight, alpha);
    }

    /**
     * Queues every primitive of {@code mesh} with the given model-view matrix (no region default, model origin).
     *
     * @param alpha overall opacity (fades), multiplied into each material's colour
     */
    public void addMesh(DrawList list, int mesh, Matrix4f modelView, Style style, int blockLight, int skyLight, float alpha) {
        addMesh(list, mesh, null, null, modelView, style, "", ZERO, blockLight, skyLight, alpha);
    }

    /**
     * Queues {@code mesh} drawn from {@code vertices} instead of the shared vertex buffer: a buffer laid out like it
     * ({@link #putVertex}, every part at its {@link Part#baseVertex}) whose vertices change every frame (skinned arms,
     * asset spec §7), with the static index ranges. {@code sources} are those vertices for the vanilla path, one per
     * part (null = the file's).
     */
    public void addMesh(DrawList list, int mesh, GpuBuffer vertices, @Nullable List<GltfModel.Primitive> sources, Matrix4f modelView,
                        Style style, int blockLight, int skyLight, float alpha) {
        addMesh(list, mesh, vertices, sources, modelView, style, "", ZERO, blockLight, skyLight, alpha);
    }

    private void addMesh(DrawList list, int mesh, Matrix4f modelView, Style style, String defaultRegion, Vector3fc origin,
                         int blockLight, int skyLight, float alpha) {
        addMesh(list, mesh, null, null, modelView, style, defaultRegion, origin, blockLight, skyLight, alpha);
    }

    private void addMesh(DrawList list, int mesh, @Nullable GpuBuffer vertexOverride, @Nullable List<GltfModel.Primitive> sources,
                         Matrix4f modelView, Style style, String defaultRegion, Vector3fc origin, int blockLight, int skyLight, float alpha) {
        List<Part> parts = meshParts.get(mesh);
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            GltfModel.Material material = part.material >= 0 && part.material < source.materials.size()
                    ? source.materials.get(part.material) : null;
            String name = material == null ? "" : material.name;
            if (style.hidden(name)) continue;
            String region = material != null && !material.region.isEmpty() ? material.region : defaultRegion;
            MaterialLayer layer = region.isEmpty() || region.equals("none") ? null : style.layer(name, region);
            boolean emissive = material != null && material.isEmissive();
            float[] base = material == null ? WHITE : material.baseColor;
            // glTF colour factors are linear; Minecraft shades in display (sRGB) space
            if (emissive) {
                // emissive parts glow with their emissive colour, unaffected by light
                float[] e = material.emissive;
                color.set(srgb(Math.max(base[0], e[0])), srgb(Math.max(base[1], e[1])), srgb(Math.max(base[2], e[2])), base[3] * alpha);
            } else {
                color.set(srgb(base[0]), srgb(base[1]), srgb(base[2]), base[3] * alpha);
            }

            // texture set: the skin layer's, else the material's own (a skin's set brings its own colours)
            Textures.TextureSet set = null;
            if (layer != null && layer.pbr() != null) {
                set = Textures.set(layer.pbr());
                if (set != null) color.set(1, 1, 1, color.w);
            }
            if (set == null && material != null) set = Textures.set(material.pbr);
            float uvScale = layer != null && layer.uvScale() > 0 ? layer.uvScale() : material == null ? 1 : material.uvScale;

            GpuTextureView albedo, normal = null, specular = null;
            ResourceLocation id = null;
            boolean managed = false;
            TextureRef ref = style.textureRef(name);
            GpuTextureView override = ref != null ? Textures.view(ref) : style.texture(name);
            if (override != null) {
                // legacy texture skins and the player's skin: tiled like before, without the texture set's maps
                albedo = override;
                id = ref != null ? ref.location() : Textures.locationOf(override);
                managed = ref != null && ref.managed();
                uvScale = 1;
                set = null;
            } else if (set != null) {
                albedo = set.albedo();
                normal = set.normal();
                specular = set.specular();
                id = set.albedoLocation();
            } else if (material != null && material.image >= 0 && material.image < views.length && views[material.image] != null) {
                albedo = views[material.image];
            } else {
                albedo = Textures.white();
            }

            if (layer != null && layer.tint() != null) color.mul(layer.tint()[0], layer.tint()[1], layer.tint()[2], 1);
            // a legacy per-material skin gives the display colour directly (dark base colours would swallow a multiplier);
            // on a texture set it recolours the albedo around that colour and keeps the texture's detail
            float[] tint = style.tint(name);
            if (tint != null) {
                if (set != null) {
                    float[] mean = set.mean();
                    color.set(recolour(tint[0], mean[0]), recolour(tint[1], mean[1]), recolour(tint[2], mean[2]), color.w);
                } else {
                    color.set(tint[0], tint[1], tint[2], color.w);
                }
            }

            RenderPipeline pipeline;
            if (material != null && "ADD".equals(material.alphaMode)) pipeline = GunPipelines.ADDITIVE; // effects only
            else if (material != null && material.isBlend() || alpha < 0.999f) pipeline = GunPipelines.TRANSLUCENT;
            else if (material != null && material.doubleSided) pipeline = GunPipelines.OPAQUE_NO_CULL;
            else pipeline = GunPipelines.OPAQUE;
            list.add(pipeline, vertexOverride != null ? vertexOverride : vertices, indices, indexType, part.firstIndex, part.indexCount,
                    modelView, color, blockLight, skyLight, material(material, emissive, uvScale, layer, style.surge()), origin, albedo,
                    normal, specular, sources != null && index < sources.size() ? sources.get(index) : part.primitive, id, managed);
        }
    }

    /** The shader parameters of a material, with a skin layer's effect (and how far it surges this frame). */
    private static DrawList.Material material(@Nullable GltfModel.Material material, boolean emissive, float uvScale,
                                              @Nullable MaterialLayer layer, float surge) {
        float cutoff = material != null && "MASK".equals(material.alphaMode) ? material.alphaCutoff : 0;
        if (material == null) return DrawList.Material.MATTE;
        int effect = layer == null ? MaterialLayer.NONE : layer.effect();
        float[] params = layer == null ? new float[4] : layer.params();
        float[] effectColor = layer != null && layer.effectColor() != null ? layer.effectColor() : NO_EFFECT_COLOR;
        return new DrawList.Material(Math.max(0, Math.min(1, material.metallic)), Math.max(0, Math.min(1, material.roughness)),
                cutoff, uvScale, emissive, effect, params, effectColor, effect == MaterialLayer.NONE ? 0 : surge);
    }

    /** Starts loading the texture sets of the model's own materials in the background ({@link Textures#prefetch}). */
    public void prefetchTextures() {
        for (GltfModel.Material material : source.materials) Textures.prefetch(material.pbr);
    }

    /** Freed by {@link #close} (a resource reload clears {@link ModelCache}); holders must not draw it any more. */
    public boolean isClosed() {
        return vertices.isClosed();
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
