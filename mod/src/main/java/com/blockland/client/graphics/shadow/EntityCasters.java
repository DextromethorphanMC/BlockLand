package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.blockland.client.perf.Optimization;
import com.blockland.client.render.ShadowPass;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entities near the camera as shadow casters. Each is drawn through its own renderer — models, armour, held items
 * (third-person weapons included: they take their vanilla-render-type path while {@link ShadowPass} is set) — into a
 * capturing buffer source that keeps only the opaque and alpha-tested entity geometry; the captured meshes then go into
 * one vertex buffer and are drawn depth-only, alpha-tested against their own textures, into the near cascades. The local
 * player casts a whole shadow in first person too, head included; the first-person weapon does not take that shadow
 * (it looks past what is right behind it towards the sun: gun.fsh), so the head around the eye does not darken it.
 * <p>
 * {@link Optimization#SHADOW_GPU_CASTERS}: a BlockLand model's draws whose triangles already sit in its own GPU
 * buffers are not rebuilt as vertices — the capture keeps each one's buffers, index range and placement and draws them
 * with {@link ShadowPipelines#ENTITY_GPU}, which places every vertex by the same float operations as the CPU would. The
 * shadow map is depth-only (the nearest depth wins whatever the order), so the same triangles give the same map.
 */
final class EntityCasters implements ShadowPass.Capture {
    static final float RANGE = 32f;
    private static final int MAX_ENTITIES = 24;
    private static final int MAX_MESHES = 64;
    /** Render types that are light, glow or overlays, not surfaces: they cast nothing. */
    private static final String[] SKIPPED = {"emissive", "eyes", "energy", "glint", "shadow", "outline", "lightning", "beam",
            "water_mask", "text", "lines", "leash", "crumbling", "portal", "gateway", "breeze", "dragon", "debug", "decal"};

    private record Mesh(GpuTextureView texture, int baseVertex, int indexCount) {
    }

    /** A model draw taken from its own buffers ({@link ShadowPass.Capture#gpuCaster}). */
    private record GpuCaster(RenderType type, Matrix4f matrix, GpuBuffer vertices, GpuBuffer indices, VertexFormat.IndexType indexType,
                             int firstIndex, int indexCount, float uvScale) {
    }

    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
    private static final Matrix4f IDENTITY = new Matrix4f();
    /** Whether the driver has {@code precise} (ARB_gpu_shader5, which shadow_entity_gpu.vsh requires) for the exact placing. */
    @Nullable
    private static Boolean gpuSupported;

    private final Map<RenderType, BufferBuilder> building = new IdentityHashMap<>();
    private final Map<RenderType, ByteBufferBuilder> memory = new IdentityHashMap<>();
    private final ArrayDeque<ByteBufferBuilder> free = new ArrayDeque<>();
    private final List<Mesh> meshes = new ArrayList<>();
    private final List<GpuCaster> gpuCasters = new ArrayList<>();
    private final List<GpuTextureView> gpuTextures = new ArrayList<>();
    private GpuBufferSlice[] gpuTransforms = new GpuBufferSlice[0];
    private final List<Entity> nearby = new ArrayList<>();
    private final PoseStack pose = new PoseStack();
    @Nullable
    private ByteBuffer staging;
    @Nullable
    private GpuBuffer vertices;
    /** The vertex buffers of the frames before (SHADOW_ENTITY_CAPTURE): written again only once the GPU is done. */
    private final GpuBuffer[] spare = new GpuBuffer[2];
    private int spareIndex;
    private int vertexBytes;
    private int maxIndices;
    private int entities;
    private boolean warned;
    /** The box round every caster vertex of this frame (camera-relative), while {@link #boxKnown}. */
    private final Vector3f boxMin = new Vector3f(), boxMax = new Vector3f();
    private boolean boxKnown;
    private final Vector3f corner = new Vector3f();

    int entityCount() {
        return entities;
    }

    int maxSequentialIndices() {
        return maxIndices;
    }

    boolean isEmpty() {
        return meshes.isEmpty() && gpuCasters.isEmpty();
    }

    /**
     * The box round everything this frame's casters draw (camera-relative; every captured vertex, every GPU caster's
     * box placed by its matrix): what the shadow map's tiles can get from them. False while there is nothing.
     */
    boolean box(Vector3f min, Vector3f max) {
        if (!boxKnown) return false;
        min.set(boxMin);
        max.set(boxMax);
        return true;
    }

    private void extend(float x, float y, float z) {
        if (!boxKnown) {
            boxMin.set(x, y, z);
            boxMax.set(x, y, z);
            boxKnown = true;
            return;
        }
        boxMin.min(corner.set(x, y, z));
        boxMax.max(corner);
    }

    /** Draws the entities near the camera into the capture and packs the result into the vertex buffer. */
    void capture(ClientLevel level, double camX, double camY, double camZ, float partialTick) {
        meshes.clear();
        gpuCasters.clear();
        gpuTextures.clear();
        maxIndices = 0;
        vertexBytes = 0;
        entities = 0;
        boxKnown = false;
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        if (dispatcher.camera == null) return;
        collect(level, camX, camY, camZ);
        if (nearby.isEmpty()) return;
        ShadowPass.set(true);
        try {
            for (Entity entity : nearby) {
                double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - camX;
                double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - camY;
                double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - camZ;
                if (render(dispatcher, entity, x, y, z, partialTick)) entities++;
            }
        } finally {
            ShadowPass.set(false);
        }
        pack();
        packGpu();
    }

    /** The nearest few entities in range that have a surface. */
    private void collect(ClientLevel level, double camX, double camY, double camZ) {
        nearby.clear();
        float range2 = RANGE * RANGE;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isInvisible() || entity.isSpectator() || entity instanceof ExperienceOrb || entity instanceof LightningBolt
                    || entity instanceof AreaEffectCloud || entity instanceof Marker || entity instanceof Display) continue;
            if (entity.distanceToSqr(camX, camY, camZ) > range2) continue;
            nearby.add(entity);
        }
        if (nearby.size() > MAX_ENTITIES) {
            nearby.sort((a, b) -> Double.compare(a.distanceToSqr(camX, camY, camZ), b.distanceToSqr(camX, camY, camZ)));
            nearby.subList(MAX_ENTITIES, nearby.size()).clear();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean render(EntityRenderDispatcher dispatcher, Entity entity, double x, double y, double z, float partialTick) {
        EntityRenderer renderer = dispatcher.getRenderer(entity);
        try {
            EntityRenderState state = renderer.createRenderState(entity, partialTick);
            Vec3 offset = renderer.getRenderOffset(state);
            pose.pushPose();
            pose.translate(x + offset.x, y + offset.y, z + offset.z);
            renderer.render(state, pose, this, LightTexture.FULL_BRIGHT);
            return true;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                BlockLand.LOGGER.warn("[BlockLand] An entity could not be drawn into the shadow map ({}); it casts no shadow", entity.getType(), t);
            }
            return false;
        } finally {
            while (!pose.isEmpty()) pose.popPose();
        }
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        BufferBuilder builder = building.get(type);
        if (builder != null) return builder;
        if (!casts(type) || building.size() >= MAX_MESHES) return ShadowPass.DISCARD;
        ByteBufferBuilder bytes = free.isEmpty() ? new ByteBufferBuilder(64 * 1024) : free.poll();
        builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        building.put(type, builder);
        memory.put(type, bytes);
        return builder;
    }

    @Override
    public boolean gpuCaster(RenderType type, Matrix4f matrix, GpuBuffer vertices, GpuBuffer indices, VertexFormat.IndexType indexType,
                             int firstIndex, int indexCount, float uvScale, float[] bounds) {
        if (gpuSupported == null) gpuSupported = GL.getCapabilities().GL_ARB_gpu_shader5;
        if (!gpuSupported || vertices.isClosed() || indices.isClosed()) return false;
        gpuCasters.add(new GpuCaster(type, new Matrix4f(matrix), vertices, indices, indexType, firstIndex, indexCount, uvScale));
        // the placed box's corners bound the placed vertices (the shader places them by this very matrix)
        for (int i = 0; i < 8; i++) {
            matrix.transformPosition((i & 1) == 0 ? bounds[0] : bounds[3], (i & 2) == 0 ? bounds[1] : bounds[4], (i & 4) == 0 ? bounds[2] : bounds[5], corner);
            extend(corner.x, corner.y, corner.z);
        }
        return true;
    }

    /** The GPU casters' textures and their placings as per-draw transforms (one write for all of them). */
    private void packGpu() {
        if (gpuCasters.isEmpty()) return;
        Map<RenderType, GpuTextureView> textures = new IdentityHashMap<>();
        DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[gpuCasters.size()];
        for (int i = 0; i < gpuCasters.size(); i++) {
            GpuCaster c = gpuCasters.get(i);
            gpuTextures.add(textures.computeIfAbsent(c.type, EntityCasters::textureOf));
            // ModelViewMat places the vertices, ModelOffset.x scales the texture coordinates (shadow_entity_gpu.vsh)
            transforms[i] = new DynamicUniforms.Transform(c.matrix, WHITE, new Vector3f(c.uvScale, 0, 0), IDENTITY, 1.0F);
        }
        gpuTransforms = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
    }

    private static boolean casts(RenderType type) {
        if (type.format() != DefaultVertexFormat.NEW_ENTITY || type.mode() != VertexFormat.Mode.QUADS) return false;
        String name = type.getName();
        for (String skipped : SKIPPED) if (name.contains(skipped)) return false;
        return true;
    }

    /** Builds the captured meshes, copies them into one staging buffer and finds each one's texture. */
    private void pack() {
        int vertexSize = DefaultVertexFormat.NEW_ENTITY.getVertexSize();
        for (Map.Entry<RenderType, BufferBuilder> entry : building.entrySet()) {
            RenderType type = entry.getKey();
            MeshData mesh = entry.getValue().build();
            if (mesh == null) continue;
            try {
                ByteBuffer data = mesh.vertexBuffer();
                int bytes = data.remaining();
                GpuTextureView texture = textureOf(type);
                if (texture == null || bytes == 0) continue;
                staging = ensure(staging, vertexBytes + bytes);
                staging.position(vertexBytes);
                staging.put(data);
                // the position is the format's first element
                for (int at = data.position() - bytes; at + 12 <= data.position(); at += vertexSize) {
                    extend(data.getFloat(at), data.getFloat(at + 4), data.getFloat(at + 8));
                }
                int indices = mesh.drawState().indexCount();
                meshes.add(new Mesh(texture, vertexBytes / vertexSize, indices));
                maxIndices = Math.max(maxIndices, indices);
                vertexBytes += bytes;
            } finally {
                mesh.close();
            }
        }
        building.clear();
        free.addAll(memory.values());
        memory.clear();
        if (vertexBytes == 0) {
            meshes.clear();
            return;
        }
        if (Optimization.SHADOW_ENTITY_CAPTURE.on()) {
            // the buffer the last frames' shadow draws read is left alone: this frame's go into the oldest of three
            GpuBuffer oldest = spare[spareIndex];
            spare[spareIndex] = vertices;
            vertices = oldest;
            spareIndex = (spareIndex + 1) % spare.length;
        }
        if (vertices == null || vertices.isClosed() || vertices.size() < vertexBytes) {
            if (vertices != null) vertices.close();
            int size = Integer.highestOneBit(Math.max(vertexBytes, 64 * 1024) - 1) << 1;
            vertices = RenderSystem.getDevice().createBuffer(() -> "BlockLand shadow casters", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
        }
        staging.position(0).limit(vertexBytes);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(vertices.slice(0, vertexBytes), staging);
        staging.limit(staging.capacity());
    }

    /** The texture a render type binds (its state set up and cleared again, as its own draw would). */
    @Nullable
    private static GpuTextureView textureOf(RenderType type) {
        GpuTextureView before = RenderSystem.getShaderTexture(0);
        type.setupRenderState();
        GpuTextureView texture = RenderSystem.getShaderTexture(0);
        type.clearRenderState();
        RenderSystem.setShaderTexture(0, before);
        return texture;
    }

    private static ByteBuffer ensure(@Nullable ByteBuffer buffer, int size) {
        if (buffer != null && buffer.capacity() >= size) {
            buffer.limit(buffer.capacity());
            return buffer;
        }
        int capacity = Integer.highestOneBit(Math.max(size, 256 * 1024) - 1) << 1;
        ByteBuffer grown = MemoryUtil.memAlloc(capacity);
        if (buffer != null) {
            buffer.position(0).limit(buffer.capacity());
            grown.put(buffer);
            MemoryUtil.memFree(buffer);
        }
        grown.clear();
        return grown;
    }

    /** Draws the captured meshes with the entity pipeline already set on the pass. */
    void draw(RenderPass pass, @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType) {
        drawCaptured(pass, sequential, sequentialType);
        drawGpu(pass);
    }

    /** The GPU casters (SHADOW_GPU_CASTERS) from their own buffers, with the shadow map's cascade uniform already set. */
    private void drawGpu(RenderPass pass) {
        if (gpuCasters.isEmpty()) return;
        pass.setPipeline(ShadowPipelines.ENTITY_GPU);
        GpuBuffer boundVertices = null, boundIndices = null;
        GpuTextureView bound = null;
        for (int i = 0; i < gpuCasters.size(); i++) {
            GpuCaster c = gpuCasters.get(i);
            GpuTextureView texture = gpuTextures.get(i);
            if (texture == null || c.vertices.isClosed() || c.indices.isClosed()) continue;
            if (c.vertices != boundVertices) {
                pass.setVertexBuffer(0, c.vertices);
                boundVertices = c.vertices;
            }
            if (c.indices != boundIndices) {
                pass.setIndexBuffer(c.indices, c.indexType);
                boundIndices = c.indices;
            }
            if (texture != bound) {
                pass.bindSampler("Sampler0", texture);
                bound = texture;
            }
            pass.setUniform("DynamicTransforms", gpuTransforms[i]);
            pass.drawIndexed(0, c.firstIndex, c.indexCount, 1);
        }
    }

    private void drawCaptured(RenderPass pass, @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType) {
        if (vertices == null || sequential == null || sequentialType == null) return;
        pass.setVertexBuffer(0, vertices);
        pass.setIndexBuffer(sequential, sequentialType);
        GpuTextureView bound = null;
        for (Mesh mesh : meshes) {
            if (mesh.texture != bound) {
                pass.bindSampler("Sampler0", mesh.texture);
                bound = mesh.texture;
            }
            pass.drawIndexed(mesh.baseVertex, 0, mesh.indexCount, 1);
        }
    }

    void close() {
        if (vertices != null) vertices.close();
        vertices = null;
        for (int i = 0; i < spare.length; i++) {
            if (spare[i] != null) spare[i].close();
            spare[i] = null;
        }
        if (staging != null) MemoryUtil.memFree(staging);
        staging = null;
        free.forEach(ByteBufferBuilder::close);
        free.clear();
    }
}
