package com.blockland.client.render;

import com.blockland.client.gltf.GltfModel;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Collects a frame's draws of retained models and submits them together: all per-draw transforms are written
 * with one {@link DynamicUniforms#writeTransforms} call and everything is drawn in a single render pass,
 * opaque first, then blended. Draw records are pooled, so steady-state frames allocate nothing but the
 * transform array. Each record also keeps its source triangles and a texture id, so the same frame can be handed to
 * vanilla render types instead ({@link VanillaDraws}) when the sun's shadow map captures entities. Every pass binds the
 * scene lighting ({@link SceneLighting}); a list of the world's frame ({@link #scene}) lets its draws be lit by the world
 * and store their emission in the target's alpha (rendering spec §3, §8).
 */
public final class DrawList {
    private static final long START = System.nanoTime();

    private final List<Entry> pool = new ArrayList<>();
    private int size;
    /** Draws of the world's frame (viewmodel, third person), not of a GUI picture. */
    private boolean scene;
    /** How soaked the weapon is (0..1, {@link Wetness}): packed into the flags' fraction (gun.fsh). */
    private float wetness;
    /** Unjittered projection of a list that records its motion ({@link #motion}), or null. */
    @Nullable
    private Matrix4f motionProjection;
    /** Last flush's model-view per draw key (see {@link #motionKey}), and this flush's (swapped every flush). */
    private Map<Long, Matrix4f> previous = new HashMap<>(), current = new HashMap<>();
    private final Map<Long, Integer> occurrences = new HashMap<>();
    @Nullable
    private GpuBuffer motionInfo;
    /** Draw calls and triangles of the last flush (shown on the F3 screen). */
    private int lastDraws, lastTriangles;

    /** How the vanilla path draws an entry (see {@link VanillaDraws}). */
    enum Blend { OPAQUE, TRANSLUCENT, ADDITIVE, EMISSIVE }

    static final class Entry {
        RenderPipeline pipeline;
        GpuBuffer vertices;
        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        int firstIndex;
        int indexCount;
        GpuTextureView albedo;
        GpuTextureView normal;
        GpuTextureView specular;
        final Matrix4f modelView = new Matrix4f();
        final Vector4f color = new Vector4f();
        final Vector3f offset = new Vector3f();
        final Matrix4f params = new Matrix4f();
        // vanilla path
        @Nullable
        GltfModel.Primitive source;
        @Nullable
        ResourceLocation texture;
        boolean managedTexture;
        Blend blend;
        int blockLight, skyLight;
        float uvScale;
    }

    /**
     * Surface parameters of one draw (see gun.fsh).
     *
     * @param metallic    0 = dielectric, 1 = metal (without a {@code _s} map)
     * @param roughness   perceptual roughness (without a {@code _s} map)
     * @param alphaCutoff fragments below this alpha are discarded (0 = none)
     * @param uvScale     multiplier on the mesh UVs (texture set tiling)
     * @param fullbright  drawn with its colour only, no lighting (glTF emissive parts, effects)
     * @param effect      {@link MaterialLayer} effect id
     * @param params      the effect's four numbers
     * @param effectColor RGB of the effect's light (display space)
     * @param surge       0..1 how far the effect surges this frame (asset spec §38; 0 = at rest)
     */
    public record Material(float metallic, float roughness, float alphaCutoff, float uvScale, boolean fullbright,
                           int effect, float[] params, float[] effectColor, float surge) {
        public static final Material MATTE = new Material(0, 0.75f, 0, 1, false, MaterialLayer.NONE, new float[4], new float[]{1, 1, 1});
        public static final Material EMISSIVE = new Material(0, 1, 0, 1, true, MaterialLayer.NONE, new float[4], new float[]{1, 1, 1});

        /** A material whose effect does not surge. */
        public Material(float metallic, float roughness, float alphaCutoff, float uvScale, boolean fullbright,
                        int effect, float[] params, float[] effectColor) {
            this(metallic, roughness, alphaCutoff, uvScale, fullbright, effect, params, effectColor, 0);
        }
    }

    /** Old surface description (Blinn specular / shininess); converted to a {@link Material}. */
    public record Surface(float specular, float shininess, float alphaCutoff, float metallic, boolean emissive) {
        public static final Surface MATTE = new Surface(0.05f, 8, 0, 0, false);
        public static final Surface EMISSIVE = new Surface(0, 1, 0, 0, true);

        public Material material() {
            float smooth = (float) Math.cbrt(Math.max(0, Math.min(1, (shininess - 6) / 140f)));
            return new Material(metallic, 1 - smooth, alphaCutoff, 1, emissive, MaterialLayer.NONE, new float[4], new float[]{1, 1, 1});
        }
    }

    // flags in ModelOffset.z (see gun.fsh)
    static final int FULLBRIGHT = 1, NORMAL_MAP = 2, SPECULAR_MAP = 4, PREMULTIPLY = 8, SCENE = 16, OPAQUE = 32, WORLD_LIT = 64;
    /** The wetness rides in the flags' fraction: 0 … this (gun.fsh divides it back out). */
    private static final float WETNESS_SCALE = 0.45f;

    /**
     * Marks this list's draws as part of the world's frame (the viewmodel, third-person weapons): they store emission in
     * the target's alpha while the frame encodes it, and are lit by the world's sun, shadows and lights while weapons are
     * (rendering spec §8). GUI pictures (the 工匠 preview) leave it off and keep their studio lighting.
     */
    public DrawList scene(boolean value) {
        scene = value;
        return this;
    }

    /**
     * How soaked the weapons this list draws are, 0..1 (rendering spec §15, soaking: out of the water they stay wet and
     * dry slowly; rain wetness comes on top in the shader). Rides in ModelOffset.z's fraction, under one half so the flags
     * still round to themselves.
     */
    public DrawList wetness(float value) {
        wetness = Math.max(0, Math.min(1, value));
        return this;
    }

    /**
     * Makes this list record its draws' screen motion into the frame's {@link HandMotion} texture (the viewmodel): each
     * draw's model-view is kept for the next flush, which draws the opaque geometry once more with both matrices.
     *
     * @param unjittered the projection the list is drawn with, without this frame's jitter (null = stop recording)
     */
    public DrawList motion(@Nullable Matrix4f unjittered) {
        if (unjittered == null) {
            motionProjection = null;
            previous.clear();
        } else {
            if (motionProjection == null) motionProjection = new Matrix4f();
            motionProjection.set(unjittered);
        }
        return this;
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

    /** Seconds for animated effects (wraps every ~68 minutes to keep float precision). */
    public static float time() {
        return (float) (((System.nanoTime() - START) / 1e9) % 4096.0);
    }

    /** Queues a draw described the old way (no texture maps, no effect, no source triangles). */
    public void add(RenderPipeline pipeline, GpuBuffer vertices, GpuBuffer indices, VertexFormat.IndexType indexType,
                    int firstIndex, int indexCount, Matrix4f modelView, Vector4f color, int blockLight, int skyLight,
                    Surface surface, GpuTextureView texture) {
        add(pipeline, vertices, indices, indexType, firstIndex, indexCount, modelView, color, blockLight, skyLight,
                surface.material(), ZERO, texture, null, null, null, null, false);
    }

    private static final Vector3fc ZERO = new Vector3f();
    /** The surge rides in the effect id's fraction (gun.fsh: effect = floor, surge = fraction / SURGE_SCALE). */
    private static final float SURGE_SCALE = 0.98f;

    /**
     * Queues a draw of a pipeline with its own per-draw parameters (GPU particles, {@link GpuParticles}): its uniform
     * block is the model-view, the colour, {@code offset} in ModelOffset and {@code params} in TextureMat, as the
     * pipeline's shader reads them. It is fullbright and blended (no texture maps, nothing for the vanilla path).
     */
    public void addRaw(RenderPipeline pipeline, GpuBuffer vertices, GpuBuffer indices, VertexFormat.IndexType indexType,
                       int firstIndex, int indexCount, Matrix4f modelView, Vector4f color, Vector3fc offset, Matrix4f params,
                       GpuTextureView texture) {
        if (size == pool.size()) pool.add(new Entry());
        Entry e = pool.get(size++);
        e.pipeline = pipeline;
        e.vertices = vertices;
        e.indices = indices;
        e.indexType = indexType;
        e.firstIndex = firstIndex;
        e.indexCount = indexCount;
        e.albedo = texture;
        e.normal = null;
        e.specular = null;
        e.modelView.set(modelView);
        e.color.set(color);
        e.offset.set(offset);
        e.params.set(params);
        e.source = null;
        e.texture = null;
        e.managedTexture = false;
        e.blend = Blend.ADDITIVE;
        e.blockLight = e.skyLight = 15;
        e.uvScale = 1;
    }

    /**
     * Queues one draw.
     *
     * @param origin         where the mesh's own origin sits in its model's rest pose (effects run in model space)
     * @param normal         LabPBR {@code _n} map, or null
     * @param specular       LabPBR {@code _s} map, or null
     * @param source         the triangles behind this index range (the vanilla path draws from them), or null
     * @param texture        the albedo's id for the vanilla path (null = plain white)
     * @param managedTexture the id belongs to the TextureManager already (player skins)
     */
    public void add(RenderPipeline pipeline, GpuBuffer vertices, GpuBuffer indices, VertexFormat.IndexType indexType,
                    int firstIndex, int indexCount, Matrix4f modelView, Vector4f color, int blockLight, int skyLight,
                    Material material, Vector3fc origin, GpuTextureView albedo, @Nullable GpuTextureView normal,
                    @Nullable GpuTextureView specular, @Nullable GltfModel.Primitive source, @Nullable ResourceLocation texture,
                    boolean managedTexture) {
        if (size == pool.size()) pool.add(new Entry());
        Entry e = pool.get(size++);
        e.pipeline = pipeline;
        e.vertices = vertices;
        e.indices = indices;
        e.indexType = indexType;
        e.firstIndex = firstIndex;
        e.indexCount = indexCount;
        e.albedo = albedo;
        e.normal = normal;
        e.specular = specular;
        e.modelView.set(modelView);
        e.color.set(color);
        boolean additive = pipeline == GunPipelines.ADDITIVE;
        boolean opaque = GunPipelines.isOpaque(pipeline);
        int flags = (material.fullbright() ? FULLBRIGHT : 0) | (normal != null ? NORMAL_MAP : 0)
                | (specular != null ? SPECULAR_MAP : 0) | (additive ? PREMULTIPLY : 0) | (opaque ? OPAQUE : 0)
                | (scene ? SCENE : 0) | (scene && SceneLighting.weaponsLit() ? WORLD_LIT : 0);
        e.offset.set(blockLight, skyLight, flags + (scene ? WETNESS_SCALE * wetness : 0));
        // TextureMat columns: (metallic, roughness, cutoff, uv scale) (origin, time) (effect params) (effect colour,
        // effect + its surge: the id's whole part, the surge × SURGE_SCALE its fraction)
        float[] p = material.params();
        float[] c = material.effectColor();
        e.params.set(
                material.metallic(), material.roughness(), material.alphaCutoff(), material.uvScale(),
                origin.x(), origin.y(), origin.z(), time(),
                p[0], p[1], p[2], p[3],
                c[0], c[1], c[2], material.effect() + SURGE_SCALE * Math.max(0, Math.min(1, material.surge())));
        e.source = source;
        e.texture = texture;
        e.managedTexture = managedTexture;
        e.blend = additive ? Blend.ADDITIVE : material.fullbright() ? Blend.EMISSIVE
                : opaque ? Blend.OPAQUE : Blend.TRANSLUCENT;
        e.blockLight = blockLight;
        e.skyLight = skyLight;
        e.uvScale = material.uvScale();
    }

    /** Entries in drawing order: opaque before blended, otherwise in submission order (callers add back to front). */
    Entry[] ordered() {
        Entry[] ordered = new Entry[size];
        int n = 0;
        for (int i = 0; i < size; i++) if (GunPipelines.isOpaque(pool.get(i).pipeline)) ordered[n++] = pool.get(i);
        for (int i = 0; i < size; i++) if (!GunPipelines.isOpaque(pool.get(i).pipeline)) ordered[n++] = pool.get(i);
        return ordered;
    }

    /**
     * Draws everything into the given targets and clears the list.
     *
     * @param lightmap Minecraft's lightmap texture (sampler 2)
     */
    public void flush(GpuTextureView color, GpuTextureView depth, GpuTextureView lightmap, String label) {
        if (size == 0) return;
        Entry[] ordered = ordered();
        DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[size];
        for (int i = 0; i < size; i++) {
            Entry e = ordered[i];
            transforms[i] = new DynamicUniforms.Transform(e.modelView, e.color, e.offset, e.params, 1.0F);
        }
        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        lastDraws = size;
        lastTriangles = 0;
        for (int i = 0; i < size; i++) lastTriangles += ordered[i].indexCount / 3;
        // the frame's mip bias (a temporally upscaled frame samples textures at the output's detail)
        Textures.syncSampling();
        GpuTextureView flatNormal = Textures.flatNormal();
        GpuTextureView noSpecular = Textures.noSpecular();

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> label, color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("BlockLandScene", SceneLighting.uniforms());
            pass.bindSampler("BlShadowMap", SceneLighting.shadowMap());
            pass.bindSampler("Sampler2", lightmap);
            RenderPipeline pipeline = null;
            GpuBuffer vertices = null;
            GpuBuffer indices = null;
            GpuTextureView albedo = null, normal = null, specular = null;
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
                if (e.albedo != albedo) {
                    pass.bindSampler("Sampler0", e.albedo);
                    albedo = e.albedo;
                }
                GpuTextureView n = e.normal != null ? e.normal : flatNormal;
                if (n != normal) {
                    pass.bindSampler("NormalSampler", n);
                    normal = n;
                }
                GpuTextureView s = e.specular != null ? e.specular : noSpecular;
                if (s != specular) {
                    pass.bindSampler("SpecularSampler", s);
                    specular = s;
                }
                pass.setUniform("DynamicTransforms", slices[i]);
                pass.drawIndexed(0, e.firstIndex, e.indexCount, 1);
            }
        }
        if (motionProjection != null) drawMotion(ordered, depth);
        clear();
    }

    /**
     * The opaque draws again into the frame's motion texture: model-view now and at the last flush (a draw seen for the
     * first time has not moved). Draws are matched across flushes by their mesh range and the order they come in.
     */
    private void drawMotion(Entry[] ordered, GpuTextureView depth) {
        GpuTextureView target = HandMotion.target();
        Map<Long, Matrix4f> swap = previous;
        previous = current;
        current = swap;
        current.clear();
        occurrences.clear();
        List<Entry> opaque = new ArrayList<>(size);
        List<DynamicUniforms.Transform> transforms = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Entry e = ordered[i];
            long key = motionKey(e);
            Matrix4f before = previous.get(key);
            current.put(key, new Matrix4f(e.modelView));
            if (target == null || !GunPipelines.isOpaque(e.pipeline)) continue;
            opaque.add(e);
            transforms.add(new DynamicUniforms.Transform(e.modelView, e.color, e.offset, before != null ? before : e.modelView, 1.0F));
        }
        if (target == null || opaque.isEmpty()) return;
        if (motionInfo == null || motionInfo.isClosed()) {
            motionInfo = RenderSystem.getDevice().createBuffer(() -> "BlockLand hand motion", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        }
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, 64).putMat4f(motionProjection).get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(motionInfo.slice(), data);
        }
        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms.toArray(new DynamicUniforms.Transform[0]));
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "BlockLand hand motion", target, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("HandMotionInfo", motionInfo.slice());
            pass.setPipeline(GunPipelines.VELOCITY);
            GpuBuffer vertices = null, indices = null;
            for (int i = 0; i < opaque.size(); i++) {
                Entry e = opaque.get(i);
                if (e.vertices != vertices) {
                    pass.setVertexBuffer(0, e.vertices);
                    vertices = e.vertices;
                }
                if (e.indices != indices) {
                    pass.setIndexBuffer(e.indices, e.indexType);
                    indices = e.indices;
                }
                pass.setUniform("DynamicTransforms", slices[i]);
                pass.drawIndexed(0, e.firstIndex, e.indexCount, 1);
            }
        }
    }

    /** A draw's identity across flushes: its buffer and index range, and how often that range came before this flush. */
    private long motionKey(Entry e) {
        long range = ((long) System.identityHashCode(e.vertices) << 32) ^ ((long) e.firstIndex << 8) ^ e.indexCount;
        int n = occurrences.merge(range, 1, Integer::sum);
        return range * 31 + n;
    }

    /**
     * Hands everything to vanilla render types (the shadow map's entity capture, {@link ShadowPass}) and clears the list.
     *
     * @param pose the pose the draws' matrices are relative to (an item's pose in third person)
     */
    public void flushVanilla(PoseStack pose, MultiBufferSource buffers) {
        if (size == 0) return;
        Entry[] ordered = ordered();
        lastDraws = size;
        lastTriangles = 0;
        for (Entry e : ordered) lastTriangles += e.indexCount / 3;
        VanillaDraws.emit(ordered, pose, buffers);
        clear();
    }
}
