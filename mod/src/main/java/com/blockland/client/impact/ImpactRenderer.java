package com.blockland.client.impact;

import com.blockland.BlockLand;
import com.blockland.client.perf.Optimization;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Draws every live impact piece in the particle pass with pipelines of its own (rendering spec §12), right away into the
 * frame's target: debris ({@code impact_debris}: opaque, the block atlas — chunks of the very block they came from),
 * molten rock and water ({@code impact_fluid}: crown sheets, columns, drops, rings and white water shaded like the water
 * surface; lava glowing, opaque), dust clouds ({@code impact_dust}: lobes integrating their cloud's density field from
 * the {@code ImpactClouds} block, back to front, soft against the world's depth) and sparks ({@code impact_spark}). Two
 * vertex buffers per frame (vanilla's particle layout, the block layout for the fluids); everything the pass binds is
 * made before it opens.
 */
final class ImpactRenderer {
    private static final BlendFunction PREMULTIPLIED = new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA,
            SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
    private static final BlendFunction ADD_EMISSION = new BlendFunction(SourceFactor.ONE, DestFactor.ONE,
            SourceFactor.ZERO, DestFactor.ONE_MINUS_SRC_ALPHA);
    /** Bytes of the cloud block: four vec4 per cloud. */
    private static final int CLOUD_BYTES = ImpactParticles.CLOUDS * 4 * 16;

    static final RenderPipeline DEBRIS = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(BlockLand.id("pipeline/impact_debris"))
            .withVertexShader(BlockLand.id("core/impact_debris"))
            .withFragmentShader(BlockLand.id("core/impact_debris"))
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withSampler("BlShadowMap")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .withoutBlend()
            .withVertexFormat(DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS)
            .build();
    static final RenderPipeline CLOUD = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(BlockLand.id("pipeline/impact_dust"))
            .withVertexShader(BlockLand.id("core/impact_dust"))
            .withFragmentShader(BlockLand.id("core/impact_dust"))
            .withSampler("Sampler2")
            .withSampler("DepthSampler")
            .withSampler("BlShadowMap")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
            .withUniform("ImpactClouds", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(PREMULTIPLIED)
            .withVertexFormat(DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS)
            .build();
    static final RenderPipeline SPARKS = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(BlockLand.id("pipeline/impact_spark"))
            .withVertexShader(BlockLand.id("core/impact_spark"))
            .withFragmentShader(BlockLand.id("core/impact_spark"))
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(ADD_EMISSION)
            .withVertexFormat(DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS)
            .build();
    private static RenderPipeline.Builder fluid(String name) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(BlockLand.id("pipeline/" + name))
                .withVertexShader(BlockLand.id("core/impact_fluid"))
                .withFragmentShader(BlockLand.id("core/impact_fluid"))
                .withSampler("Sampler2")
                .withSampler("BlShadowMap")
                .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withCull(false)
                .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS);
    }
    /** Water: translucent, premultiplied, no depth written. */
    static final RenderPipeline FLUID = fluid("impact_fluid").withDepthWrite(false).withBlend(PREMULTIPLIED).build();
    /** Molten rock: opaque, depth written, its glow in alpha. */
    static final RenderPipeline MOLTEN = fluid("impact_molten").withDepthWrite(true).withoutBlend().build();
    /**
     * Blood lying on things (rendering spec §19, {@link BloodStains}): its own vertex layout and program, translucent and
     * premultiplied like water, a depth bias keeping it on the face or blade it lies on.
     */
    static final RenderPipeline BLOOD = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(BlockLand.id("pipeline/impact_blood"))
            .withVertexShader(BlockLand.id("core/impact_blood"))
            .withFragmentShader(BlockLand.id("core/impact_blood"))
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withSampler("BlShadowMap")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withDepthBias(-1.0F, -4.0F)
            .withCull(false)
            .withBlend(PREMULTIPLIED)
            .withVertexFormat(BloodStains.FORMAT, VertexFormat.Mode.QUADS)
            .build();

    /** Fluid draw modes (impact_fluid.fsh). */
    private static final int SHEET = 0, DROP = 1, RINGS = 2, FOAM = 3, MOLTEN_DROP = 4, MOLTEN_FLAT = 5, BLOOD_DROP = 6,
            BUBBLE = 8;
    private static final int SHEET_SEGMENTS = 16, COLUMN_SEGMENTS = 8;

    private static ByteBufferBuilder bytes, fluidBytes;
    @Nullable
    private static MappableRingBuffer cloudBuffer;
    private static final List<GpuTexture> depthCopies = new ArrayList<>(2);
    private static final List<GpuTextureView> depthViews = new ArrayList<>(2);
    private static GpuTexture farDepth;
    private static GpuTextureView farDepthView;
    private static boolean depthCopyFailed;

    // scratch, the render thread only
    private static final Matrix3f turn = new Matrix3f();
    private static final Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f();
    private static final List<ImpactParticles.Cloud> sortedClouds = new ArrayList<>();
    private static final List<ImpactParticles.Splash> sortedSplashes = new ArrayList<>();
    /** The frame's live debris in pool order ({@code IMPACT_POOLS}; the pool's own pieces, which live as long as it). */
    private static final ImpactParticles.Debris[] liveDebris = new ImpactParticles.Debris[ImpactParticles.CAPACITY];
    /**
     * The cloud block's contents, written on the CPU each frame and copied into the ring buffer before the pass. Native
     * byte order: a direct buffer starts out big-endian, and the GPU would read every float byte-swapped.
     */
    private static final ByteBuffer cloudData = ByteBuffer.allocateDirect(CLOUD_BYTES).order(ByteOrder.nativeOrder());

    private ImpactRenderer() {
    }

    /** Draws the pools; {@code pose} is the particle pass's (camera-relative world). */
    static void draw(ImpactParticles pool, Camera camera, Matrix4f pose, float partialTick, float time) {
        if (pool.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        if (color == null || depth == null) return;
        Vec3 cam = camera.getPosition();
        Vector3f right = new Vector3f(camera.getLeftVector()).negate(), up = new Vector3f(camera.getUpVector()),
                back = new Vector3f(camera.getLookVector()).negate();

        // the pieces the walks below visit, in pool order: the whole pool, or (IMPACT_POOLS) only the live ones, gathered
        // in one walk over the range that can hold them — the same pieces in the same order, as nothing here changes them
        ImpactParticles.Debris[] pieces;
        int pieceCount, sparkFrom, sparkTo;
        if (Optimization.IMPACT_POOLS.on()) {
            pieces = liveDebris;
            pieceCount = pool.liveDebris(liveDebris);
            sparkFrom = pool.sparkLo;
            sparkTo = pool.sparkHi;
        } else {
            pieces = pool.debris;
            pieceCount = pieces.length;
            sparkFrom = 0;
            sparkTo = pool.sparks.length - 1;
        }

        // ---- the particle layout: debris, cloud lobes, sparks
        if (bytes == null) bytes = new ByteBufferBuilder(256 * 1024);
        BufferBuilder buffer = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        int debris = 0;
        for (int k = 0; k < pieceCount; k++) {
            ImpactParticles.Debris p = pieces[k];
            if (p.alive && p.fluid == 0 && !p.bubble) debris += debrisQuads(buffer, p, cam, right, up, partialTick);
        }
        cloudData.clear();
        int lobes = cloudLobes(buffer, pool, cam, right, up, partialTick, cloudData);
        int sparks = 0;
        for (int k = sparkFrom; k <= sparkTo; k++) {
            ImpactParticles.Spark p = pool.sparks[k];
            if (p.alive) sparks += sparkQuad(buffer, p, cam, up, partialTick);
        }

        // ---- the block layout: molten rock first (opaque), then water from the surface up
        if (fluidBytes == null) fluidBytes = new ByteBufferBuilder(128 * 1024);
        BufferBuilder fluids = new BufferBuilder(fluidBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        sortedSplashes.clear();
        for (ImpactParticles.Splash s : pool.splashes) {
            if (s.alive) sortedSplashes.add(s);
        }
        sortedSplashes.sort((p, q) -> Double.compare(distanceSqr(q.x, q.y, q.z, cam), distanceSqr(p.x, p.y, p.z, cam)));
        int moltenDrops = 0, moltenFlats = 0;
        for (int k = 0; k < pieceCount; k++) {
            ImpactParticles.Debris p = pieces[k];
            if (p.alive && p.fluid == ImpactParticles.LAVA && !p.resting) moltenDrops += moltenDrop(fluids, p, cam, right, up, partialTick);
        }
        for (int k = 0; k < pieceCount; k++) {
            ImpactParticles.Debris p = pieces[k];
            if (p.alive && p.fluid == ImpactParticles.LAVA && p.resting) moltenFlats += moltenSplat(fluids, p, cam, partialTick);
        }
        for (ImpactParticles.Splash s : sortedSplashes) {
            if (s.kind == ImpactParticles.LAVA) moltenFlats += crater(fluids, s, cam, partialTick);
        }
        // blood lying on surfaces and plants (its own layout; drawn under the splashes and drops in front of it)
        int stains = BloodStains.build(pieces, pieceCount, cam, partialTick);
        int rings = 0, foam = 0, sheets = 0, drops = 0, bubbles = 0, bloodDrops = 0;
        for (ImpactParticles.Splash s : sortedSplashes) {
            if (s.kind == ImpactParticles.WATER) rings += rings(fluids, s, cam, partialTick);
        }
        for (ImpactParticles.Splash s : sortedSplashes) {
            if (s.kind == ImpactParticles.WATER) foam += foam(fluids, s, cam, partialTick);
        }
        for (ImpactParticles.Splash s : sortedSplashes) {
            if (s.kind == ImpactParticles.WATER) sheets += crown(fluids, s, cam, partialTick) + column(fluids, s, cam, partialTick);
        }
        for (int k = 0; k < pieceCount; k++) {
            ImpactParticles.Debris p = pieces[k];
            if (p.alive && p.fluid == ImpactParticles.WATER) drops += waterDrop(fluids, p, cam, right, up, partialTick);
        }
        for (int k = 0; k < pieceCount; k++) {
            ImpactParticles.Debris p = pieces[k];
            if (p.alive && p.bubble) bubbles += bubble(fluids, p, cam, right, up, partialTick);
        }
        for (int k = 0; k < pieceCount; k++) {
            ImpactParticles.Debris p = pieces[k];
            if (p.alive && p.fluid == ImpactParticles.BLOOD && !p.resting) bloodDrops += bloodDrop(fluids, p, cam, right, up, partialTick);
        }

        int particleQuads = debris + lobes + sparks,
                fluidQuads = moltenDrops + moltenFlats + rings + foam + sheets + drops + bubbles + bloodDrops;
        if (particleQuads + fluidQuads + stains == 0) {
            buffer.build();
            fluids.build();
            return;
        }
        try (MeshData mesh = buffer.build(); MeshData fluidMesh = fluids.build()) {
            // everything the pass binds is made before it opens: creating, clearing or writing a buffer or texture inside
            // an open pass throws
            GpuBuffer vertices = mesh == null ? null : DefaultVertexFormat.PARTICLE.uploadImmediateVertexBuffer(mesh.vertexBuffer());
            GpuBuffer fluidVertices = fluidMesh == null ? null : DefaultVertexFormat.BLOCK.uploadImmediateVertexBuffer(fluidMesh.vertexBuffer());
            GpuBuffer stainVertices = stains > 0 ? BloodStains.FORMAT.uploadImmediateVertexBuffer(BloodStains.data()) : null;
            RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = sequential.getBuffer(Math.max(Math.max(particleQuads, fluidQuads), stains) * 6);
            GpuTextureView worldDepth = lobes > 0 ? copyDepth(depth) : null;
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
            GpuBufferSlice solid = transform(modelView, right, up, back, time, false, 0);
            GpuBufferSlice cloudMode = transform(modelView, right, up, back, time, worldDepth != null, 0);
            GpuBufferSlice[] fluidMode = new GpuBufferSlice[9];
            for (int i = 0; i < fluidMode.length; i++) fluidMode[i] = transform(modelView, right, up, back, time, false, i);
            GpuTextureView atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            GpuTextureView dustDepth = worldDepth != null ? worldDepth : farDepth();
            GpuBufferSlice scene = SceneLighting.uniforms();
            GpuTextureView shadowMap = SceneLighting.shadowMap();
            MappableRingBuffer clouds = cloudBuffer();
            if (lobes > 0) {
                try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(clouds.currentBuffer(), false, true)) {
                    cloudData.rewind();
                    view.data().put(cloudData);
                }
            }
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "BlockLand bullet impacts", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("BlockLandScene", scene);
                pass.setUniform("ImpactClouds", clouds.currentBuffer());
                pass.bindSampler("BlShadowMap", shadowMap);
                pass.bindSampler("Sampler0", atlas);
                pass.bindSampler("Sampler2", mc.gameRenderer.lightTexture().getTextureView());
                pass.bindSampler("DepthSampler", dustDepth);
                pass.setIndexBuffer(indices, sequential.type());
                // opaque first: debris, molten rock
                if (vertices != null && debris > 0) {
                    pass.setVertexBuffer(0, vertices);
                    pass.setPipeline(DEBRIS);
                    draw(pass, solid, 0, debris);
                }
                int quad = 0;
                if (fluidVertices != null && moltenDrops + moltenFlats > 0) {
                    pass.setVertexBuffer(0, fluidVertices);
                    pass.setPipeline(MOLTEN);
                    quad = draw(pass, fluidMode[MOLTEN_DROP], quad, moltenDrops);
                    quad = draw(pass, fluidMode[MOLTEN_FLAT], quad, moltenFlats);
                }
                if (stainVertices != null) {
                    // blood lying on things, over the molten rock and under the water in front of it
                    pass.setVertexBuffer(0, stainVertices);
                    pass.setPipeline(BLOOD);
                    draw(pass, solid, 0, stains);
                }
                if (fluidVertices != null && fluidQuads > moltenDrops + moltenFlats) {
                    pass.setVertexBuffer(0, fluidVertices);
                    pass.setPipeline(FLUID);
                    quad = draw(pass, fluidMode[RINGS], quad, rings);
                    quad = draw(pass, fluidMode[FOAM], quad, foam);
                    quad = draw(pass, fluidMode[SHEET], quad, sheets);
                    quad = draw(pass, fluidMode[DROP], quad, drops);
                    quad = draw(pass, fluidMode[BUBBLE], quad, bubbles);
                    draw(pass, fluidMode[BLOOD_DROP], quad, bloodDrops);
                }
                if (vertices != null && lobes + sparks > 0) {
                    pass.setVertexBuffer(0, vertices);
                    pass.setPipeline(CLOUD);
                    draw(pass, cloudMode, debris, lobes);
                    pass.setPipeline(SPARKS);
                    draw(pass, solid, debris + lobes, sparks);
                }
            }
            if (lobes > 0) clouds.rotate();
        }
    }

    private static int draw(RenderPass pass, GpuBufferSlice transform, int firstQuad, int quads) {
        if (quads <= 0) return firstQuad;
        pass.setUniform("DynamicTransforms", transform);
        pass.drawIndexed(0, firstQuad * 6, quads * 6, 1);
        return firstQuad + quads;
    }

    /** The draw's parameters (impact_fx.glsl): camera axes, time, soft depth, mode. */
    private static GpuBufferSlice transform(Matrix4f modelView, Vector3f right, Vector3f up, Vector3f back, float time, boolean soft, int mode) {
        Matrix4f params = new Matrix4f(
                right.x, right.y, right.z, time,
                up.x, up.y, up.z, soft ? 1 : 0,
                back.x, back.y, back.z, mode,
                0, 0, 0, 0);
        return RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), params, 1.0F);
    }

    private static MappableRingBuffer cloudBuffer() {
        if (cloudBuffer == null) {
            cloudBuffer = new MappableRingBuffer(() -> "BlockLand impact clouds", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, CLOUD_BYTES);
        }
        return cloudBuffer;
    }

    // ---- debris -------------------------------------------------------------------------------------

    private static int debrisQuads(BufferBuilder buf, ImpactParticles.Debris p, Vec3 cam, Vector3f right, Vector3f up, float pt) {
        float fade = ImpactParticles.fade(p.age, p.life, pt, 0.3f);
        if (fade <= 0.01f) return 0;
        float x = (float) (Mth.lerp(pt, p.px, p.x) - cam.x), y = (float) (Mth.lerp(pt, p.py, p.y) - cam.y),
                z = (float) (Mth.lerp(pt, p.pz, p.z) - cam.z);
        float s = p.size * (0.6f + 0.4f * fade);
        int color = (Math.round(fade * 255) << 24) | p.rgb;
        switch (p.shape) {
            case ImpactSpawner.DROPLET -> {
                if (p.resting) {
                    // landed (muddy water): a splat lying on the surface
                    float r = s * 1.7f, h = y - p.size * 0.25f + 0.003f;
                    quad(buf, x - r, h, z - r, x + r, h, z - r, x + r, h, z + r, x - r, h, z + r, p, color);
                    return 1;
                }
                // flying: a drop stretched along its motion, facing the camera
                return streak(buf, x, y, z, p.vx, p.vy, p.vz, s, right, up, p, color);
            }
            default -> {
                turn.rotationXYZ(Mth.lerp(pt, p.pa, p.a), Mth.lerp(pt, p.pb, p.b), Mth.lerp(pt, p.pc, p.c));
                return solidShape(buf, p.shape, x, y, z, s, p, color);
            }
        }
    }

    /** The chunk shapes in their own frame, turned by {@link #turn}: one or two quads each. */
    private static int solidShape(BufferBuilder buf, byte shape, float x, float y, float z, float s, ImpactParticles.Debris p, int color) {
        switch (shape) {
            case ImpactSpawner.FLAKE, ImpactSpawner.LEAF -> {
                local(buf, x, y, z, -s, 0, -0.75f * s, s, 0, -0.75f * s, s, 0, 0.75f * s, -s, 0, 0.75f * s, p, color);
                return 1;
            }
            case ImpactSpawner.SPLINTER -> {
                float l = 2.2f * s, w = 0.3f * s;
                local(buf, x, y, z, -l, 0, -w, l, 0, -w, l, 0, w, -l, 0, w, p, color);
                local(buf, x, y, z, -l, -w, 0, l, -w, 0, l, w, 0, -l, w, 0, p, color);
                return 2;
            }
            case ImpactSpawner.SHARD -> {
                // a triangle (the quad's last corner on its third): glass breaks into points
                local(buf, x, y, z, -s, 0, -0.8f * s, 1.1f * s, 0, -0.5f * s, 0.2f * s, 0, 1.2f * s, 0.2f * s, 0, 1.2f * s, p, color);
                return 1;
            }
            case ImpactSpawner.GRAIN -> {
                float g = 0.6f * s;
                local(buf, x, y, z, -g, -g, 0, g, -g, 0, g, g, 0, -g, g, 0, p, color);
                local(buf, x, y, z, 0, -g, -g, 0, -g, g, 0, g, g, 0, g, -g, p, color);
                return 2;
            }
            default -> {
                // a chip: two crossed faces, so it has a body from every side
                local(buf, x, y, z, -s, -0.7f * s, 0, s, -0.7f * s, 0, s, 0.7f * s, 0, -s, 0.7f * s, 0, p, color);
                local(buf, x, y, z, 0, -0.7f * s, -0.8f * s, 0, -0.7f * s, 0.8f * s, 0, 0.7f * s, 0.8f * s, 0, 0.7f * s, -0.8f * s, p, color);
                return 2;
            }
        }
    }

    private static void local(BufferBuilder buf, float x, float y, float z,
                              float x0, float y0, float z0, float x1, float y1, float z1,
                              float x2, float y2, float z2, float x3, float y3, float z3, ImpactParticles.Debris p, int color) {
        turn.transform(a.set(x0, y0, z0));
        turn.transform(b.set(x1, y1, z1));
        turn.transform(c.set(x2, y2, z2));
        turn.transform(d.set(x3, y3, z3));
        quad(buf, x + a.x, y + a.y, z + a.z, x + b.x, y + b.y, z + b.z, x + c.x, y + c.y, z + c.z, x + d.x, y + d.y, z + d.z, p, color);
    }

    private static void quad(BufferBuilder buf, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, ImpactParticles.Debris p, int color) {
        buf.addVertex(x0, y0, z0).setUv(p.u0, p.v0).setColor(color).setLight(p.light);
        buf.addVertex(x1, y1, z1).setUv(p.u1, p.v0).setColor(color).setLight(p.light);
        buf.addVertex(x2, y2, z2).setUv(p.u1, p.v1).setColor(color).setLight(p.light);
        buf.addVertex(x3, y3, z3).setUv(p.u0, p.v1).setColor(color).setLight(p.light);
    }

    private static void billboard(BufferBuilder buf, float x, float y, float z, float s, Vector3f r, Vector3f u,
                                  ImpactParticles.Debris p, int color) {
        quad(buf, x - (r.x + u.x) * s, y - (r.y + u.y) * s, z - (r.z + u.z) * s,
                x + (r.x - u.x) * s, y + (r.y - u.y) * s, z + (r.z - u.z) * s,
                x + (r.x + u.x) * s, y + (r.y + u.y) * s, z + (r.z + u.z) * s,
                x - (r.x - u.x) * s, y - (r.y - u.y) * s, z - (r.z - u.z) * s, p, color);
    }

    /** A camera-facing quad from the piece back along its motion (a flying drop). */
    private static int streak(BufferBuilder buf, float x, float y, float z, float vx, float vy, float vz, float s,
                              Vector3f right, Vector3f up, ImpactParticles.Debris p, int color) {
        a.set(vx, vy, vz).mul(1.6f);
        if (a.lengthSquared() < s * s) {
            billboard(buf, x, y, z, s, right, up, p, color);
            return 1;
        }
        b.set(a).cross(x, y, z);
        if (b.lengthSquared() < 1e-10f) b.set(up);
        b.normalize(s);
        quad(buf, x - a.x - b.x, y - a.y - b.y, z - a.z - b.z, x - a.x + b.x, y - a.y + b.y, z - a.z + b.z,
                x + b.x, y + b.y, z + b.z, x - b.x, y - b.y, z - b.z, p, color);
        return 1;
    }

    // ---- dust clouds --------------------------------------------------------------------------------

    /**
     * Each live cloud's entry in the cloud block and its lobes, nearest last: the first lobe sits on the centre, the
     * others round it — pushed out of the surface the cloud came off and spreading along it as it ages, turning slowly,
     * drawn out along the throw while the jet lasts and lower where the powder sinks.
     * Far clouds use fewer lobes (the field is the same; only its coverage gets coarser).
     */
    private static int cloudLobes(BufferBuilder buf, ImpactParticles pool, Vec3 cam, Vector3f right, Vector3f up, float pt,
                                  ByteBuffer data) {
        sortedClouds.clear();
        for (ImpactParticles.Cloud cl : pool.clouds) {
            if (cl.alive && cl.density(cl.age + pt) > 0.02f) sortedClouds.add(cl);
        }
        if (sortedClouds.isEmpty()) return 0;
        sortedClouds.sort((p, q) -> Double.compare(distanceSqr(q.x, q.y, q.z, cam), distanceSqr(p.x, p.y, p.z, cam)));
        Std140Builder std = Std140Builder.intoBuffer(data);
        int quads = 0;
        for (int index = 0; index < sortedClouds.size(); index++) {
            ImpactParticles.Cloud cl = sortedClouds.get(index);
            float ticks = cl.age + pt;
            float x = (float) (Mth.lerp(pt, cl.px, cl.x) - cam.x), y = (float) (Mth.lerp(pt, cl.py, cl.y) - cam.y),
                    z = (float) (Mth.lerp(pt, cl.pz, cl.z) - cam.z);
            float r = Math.max(0.02f, cl.radius(ticks)), rho = cl.density(ticks), age = Mth.clamp(ticks / Math.max(1, cl.life), 0, 1);
            float stretch = cl.stretch(ticks), sag = cl.sag(ticks);
            // the jet reaches out of the hole: the field's middle sits forward along the throw while it is drawn out
            float shift = (stretch - 1) * r * 0.45f;
            x += cl.ax * shift;
            y += cl.ay * shift;
            z += cl.az * shift;
            float distance = (float) Math.sqrt(x * x + y * y + z * z);
            int n = cl.lobes;
            if (distance > 56) n = 1;
            else if (distance > 28) n = Math.max(1, (n + 1) / 2);
            // overlapping lobes count the overlap twice: their shared density is scaled down for it
            float weight = 1 / (1 + 0.14f * (n - 1));
            std.putVec4(x, y, z, r).putVec4(rho * weight, rho, cl.seed, age)
                    .putVec4(cl.ax * stretch, cl.ay * stretch, cl.az * stretch, sag)
                    .putVec4(cl.water ? (float) (cl.surface - cam.y) : 0, cl.water ? 1 : 0, 0, 0);
            int color = (index & 0xFF) << 24 | cl.rgb;
            for (int i = 0; i < n; i++) {
                float ox = 0, oy = 0, oz = 0, lr = r * 0.74f;
                if (i > 0) {
                    // a fixed direction per lobe, out of the surface and spreading along it with age, turning slowly
                    float h1 = hash(cl.seed + i * 12.9898f), h2 = hash(cl.seed * 1.7f + i * 78.233f);
                    float theta = h1 * Mth.TWO_PI + ticks * 0.01f * ((i & 1) == 0 ? 1 : -1), cosPhi = h2 * 2 - 1;
                    float sinPhi = Mth.sqrt(Math.max(0, 1 - cosPhi * cosPhi));
                    float ux = Mth.cos(theta) * sinPhi, uy = cosPhi, uz = Mth.sin(theta) * sinPhi;
                    float along = ux * cl.nx + uy * cl.ny + uz * cl.nz;
                    float spread = 0.5f + 0.35f * age, lift = 0.22f + 0.18f * Math.abs(along);
                    ox = ((ux - cl.nx * along) * spread + cl.nx * lift) * r;
                    oy = ((uy - cl.ny * along) * spread + cl.ny * lift) * r;
                    oz = ((uz - cl.nz * along) * spread + cl.nz * lift) * r;
                    lr = r * (0.62f - 0.06f * (i % 3));
                    // drawn out along the throw and hanging lower, like the field they cover
                    float onAxis = (ox * cl.ax + oy * cl.ay + oz * cl.az) * (stretch - 1);
                    ox += cl.ax * onAxis;
                    oy += cl.ay * onAxis;
                    oz += cl.az * onAxis;
                    if (oy < 0) oy *= 1 + sag;
                    lr *= 1 + 0.25f * (stretch - 1);
                } else if (n == 1) {
                    // alone it has to hold the whole field
                    lr = r * (1.2f + 0.8f * (stretch - 1) + 0.4f * sag);
                }
                float qr = lr * 1.08f;
                float cx = x + ox, cy = y + oy, cz = z + oz;
                lobeCorner(buf, cx - (right.x + up.x) * qr, cy - (right.y + up.y) * qr, cz - (right.z + up.z) * qr, -qr, -qr, color, cl.light);
                lobeCorner(buf, cx + (right.x - up.x) * qr, cy + (right.y - up.y) * qr, cz + (right.z - up.z) * qr, qr, -qr, color, cl.light);
                lobeCorner(buf, cx + (right.x + up.x) * qr, cy + (right.y + up.y) * qr, cz + (right.z + up.z) * qr, qr, qr, color, cl.light);
                lobeCorner(buf, cx - (right.x - up.x) * qr, cy - (right.y - up.y) * qr, cz - (right.z - up.z) * qr, -qr, qr, color, cl.light);
                quads++;
            }
        }
        return quads;
    }

    private static void lobeCorner(BufferBuilder buf, float x, float y, float z, float u, float v, int argb, int light) {
        buf.addVertex(x, y, z).setUv(u, v).setColor(argb).setLight(light);
    }

    /** A stable 0..1 hash of a float. */
    private static float hash(float x) {
        float s = Mth.sin(x) * 43758.5453f;
        return s - Mth.floor(s);
    }

    private static double distanceSqr(double x, double y, double z, Vec3 cam) {
        double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- fluids -------------------------------------------------------------------------------------

    private static void fluidVertex(BufferBuilder buf, float x, float y, float z, int argb, float u, float v, int light,
                                    float nx, float ny, float nz) {
        buf.addVertex(x, y, z).setColor(argb).setUv(u, v).setLight(light).setNormal(nx, ny, nz);
    }

    /** Seconds since the splash, smooth between ticks. */
    private static float seconds(ImpactParticles.Splash s, float pt) {
        return (s.age + pt) / 20f;
    }

    /**
     * The crown: a thin sheet of water rising round the hole, flaring out, its rim breaking into the drops the spawner
     * threw; it climbs for a third of a second and falls back. Three rings of {@value #SHEET_SEGMENTS} quads.
     */
    private static int crown(BufferBuilder buf, ImpactParticles.Splash s, Vec3 cam, float pt) {
        float t = seconds(s, pt), e = s.energy;
        float rise = (s.secondary ? 0.16f : 0.3f) * (float) Math.sqrt(Math.max(e, 0.2f));
        float p = t / rise;
        if (p >= 1.3f) return 0;
        float height = s.sheet * (float) Math.pow(e, 0.8) * (s.secondary ? 0.45f : 1) * Mth.sin(Mth.PI * Math.min(p, 1) * 0.92f);
        if (p > 1) height *= Math.max(0, 1 - (p - 1) * 3.3f);
        if (height < 0.008f) return 0;
        float base = 0.03f + 0.045f * e + 0.8f * e * t * (s.secondary ? 0.6f : 1), top = base * 1.32f + 0.03f;
        int coverage = Math.round(Mth.clamp(1 - Math.max(0, p - 0.85f) * 2.2f, 0, 1) * 255);
        int argb = coverage << 24 | s.rgb;
        float cx = (float) (s.x - cam.x), cy = (float) (s.y - cam.y) + 0.005f, cz = (float) (s.z - cam.z);
        float[] heights = {0, 0.55f, 1};
        int quads = 0;
        for (int seg = 0; seg < SHEET_SEGMENTS; seg++) {
            for (int ring = 0; ring < 2; ring++) {
                for (int k = 0; k < 4; k++) {
                    int si = seg + (k == 1 || k == 2 ? 1 : 0), ri = ring + (k >= 2 ? 1 : 0);
                    float ang = si * Mth.TWO_PI / SHEET_SEGMENTS, f = heights[ri];
                    float rad = Mth.lerp((float) Math.pow(f, 1.5), base, top);
                    // the surface leans out with the flare: its normal tips down by the radius' growth with height
                    float flare = Math.min(1.2f, (top - base) / height * 1.5f * (float) Math.sqrt(Math.max(f, 0.05f)));
                    float nl = Mth.invSqrt(1 + flare * flare);
                    float ca = Mth.cos(ang), sa = Mth.sin(ang);
                    fluidVertex(buf, cx + ca * rad, cy + f * height, cz + sa * rad, argb, si / (float) SHEET_SEGMENTS, f, s.light,
                            ca * nl, -flare * nl, sa * nl);
                }
                quads++;
            }
        }
        return quads;
    }

    /** The column (Worthington jet) shooting up out of the hole a moment after the crown, then falling back into it. */
    private static int column(BufferBuilder buf, ImpactParticles.Splash s, Vec3 cam, float pt) {
        if (s.secondary || s.column <= 0 || s.energy <= 0.6f) return 0;
        float t = seconds(s, pt) - 0.09f, e = s.energy;
        float span = 0.42f * (float) Math.sqrt(e);
        if (t <= 0 || t >= span) return 0;
        float q = t / span;
        float height = s.column * (float) Math.pow(e, 1.5) * Mth.sin(Mth.PI * q);
        if (height < 0.01f) return 0;
        float radius = 0.03f * e * (1.2f - 0.4f * q);
        float[] heights = {0, 0.5f, 1};
        float[] widths = {1.3f, 1, 0.45f};
        int argb = 0xFF << 24 | s.rgb;
        float cx = (float) (s.x - cam.x), cy = (float) (s.y - cam.y) + 0.005f, cz = (float) (s.z - cam.z);
        int quads = 0;
        for (int seg = 0; seg < COLUMN_SEGMENTS; seg++) {
            for (int ring = 0; ring < 2; ring++) {
                for (int k = 0; k < 4; k++) {
                    int si = seg + (k == 1 || k == 2 ? 1 : 0), ri = ring + (k >= 2 ? 1 : 0);
                    float ang = si * Mth.TWO_PI / COLUMN_SEGMENTS;
                    float ca = Mth.cos(ang), sa = Mth.sin(ang), rad = radius * widths[ri];
                    fluidVertex(buf, cx + ca * rad, cy + heights[ri] * height, cz + sa * rad, argb, si / (float) COLUMN_SEGMENTS,
                            heights[ri] * 0.6f, s.light, ca, 0.15f, sa);
                }
                quads++;
            }
        }
        return quads;
    }

    /** Rings spreading on the surface: a flat quad over them, the crests drawn by the shader from where they are. */
    private static int rings(BufferBuilder buf, ImpactParticles.Splash s, Vec3 cam, float pt) {
        float t = seconds(s, pt), life = s.life / 20f;
        float amplitude = Math.min(1, 0.4f + 0.6f * s.energy) * (float) Math.pow(Math.max(0, 1 - t / life), 1.6);
        if (amplitude < 0.01f) return 0;
        float radius = 0.08f + s.ripple * t;
        float width = Mth.clamp(0.05f / radius, 0.03f, 0.4f);
        float half = radius * (1 + width * 3) * 1.05f;
        int argb = Math.round(amplitude * 255) << 24 | s.rgb;
        flat(buf, s, cam, 0.012f, half, half / radius, argb, width, s.seed / 97f);
        return 1;
    }

    /** White water over a heavy round's hole, breaking up as it fades. */
    private static int foam(BufferBuilder buf, ImpactParticles.Splash s, Vec3 cam, float pt) {
        if (s.foam <= 0) return 0;
        float t = seconds(s, pt), life = s.life / 20f * 1.2f;
        float coverage = s.foam * (float) Math.pow(Math.max(0, 1 - t / life), 1.2);
        if (coverage < 0.02f) return 0;
        float radius = 0.12f + 0.18f * s.energy + 0.12f * t;
        flat(buf, s, cam, 0.016f, radius, 1, Math.round(coverage * 255) << 24 | 0xFFFFFF, 0, s.seed / 97f);
        return 1;
    }

    /** A molten pool struck: a glowing crater spreading and cooling on the surface. */
    private static int crater(BufferBuilder buf, ImpactParticles.Splash s, Vec3 cam, float pt) {
        float t = seconds(s, pt), life = s.life / 20f;
        float heat = Math.max(0, 1 - t / life);
        if (heat < 0.02f) return 0;
        float radius = 0.1f + 0.22f * s.energy * (float) Math.sqrt(t);
        flat(buf, s, cam, 0.01f, radius, 1, Math.round(heat * 255) << 24 | s.rgb, 0, s.seed / 97f);
        return 1;
    }

    /** A flat quad on the surface at the splash, UV0 = position in units of {@code uvScale}/half. */
    private static void flat(BufferBuilder buf, ImpactParticles.Splash s, Vec3 cam, float lift, float half, float uvScale, int argb,
                             float nx, float ny) {
        float x = (float) (s.x - cam.x), y = (float) (s.y - cam.y) + lift, z = (float) (s.z - cam.z);
        fluidVertex(buf, x - half, y, z - half, argb, -uvScale, -uvScale, s.light, nx, ny, 0);
        fluidVertex(buf, x - half, y, z + half, argb, -uvScale, uvScale, s.light, nx, ny, 0);
        fluidVertex(buf, x + half, y, z + half, argb, uvScale, uvScale, s.light, nx, ny, 0);
        fluidVertex(buf, x + half, y, z - half, argb, uvScale, -uvScale, s.light, nx, ny, 0);
    }

    /** A bubble of air under water: a camera-facing sphere, bright at its rim. */
    private static int bubble(BufferBuilder buf, ImpactParticles.Debris p, Vec3 cam, Vector3f right, Vector3f up, float pt) {
        float fade = ImpactParticles.fade(p.age, p.life, pt, 0.15f);
        if (fade <= 0.01f) return 0;
        return sphere(buf, p, cam, right, up, pt, Math.round(fade * 255) << 24 | p.rgb, 0);
    }

    /** A flying drop of blood: a glossy sphere, drawn longer along its motion. */
    private static int bloodDrop(BufferBuilder buf, ImpactParticles.Debris p, Vec3 cam, Vector3f right, Vector3f up, float pt) {
        float fade = ImpactParticles.fade(p.age, p.life, pt, 0.2f);
        if (fade <= 0.01f) return 0;
        return sphere(buf, p, cam, right, up, pt, Math.round(fade * 255) << 24 | p.rgb, 0.9f);
    }

    /** A drop of water: a camera-facing sphere, drawn longer along its motion. */
    private static int waterDrop(BufferBuilder buf, ImpactParticles.Debris p, Vec3 cam, Vector3f right, Vector3f up, float pt) {
        float fade = ImpactParticles.fade(p.age, p.life, pt, 0.25f);
        if (fade <= 0.01f) return 0;
        return sphere(buf, p, cam, right, up, pt, Math.round(fade * 255) << 24 | p.rgb, 0.8f);
    }

    /** Molten spatter in flight, its temperature in alpha (it cools over its life). */
    private static int moltenDrop(BufferBuilder buf, ImpactParticles.Debris p, Vec3 cam, Vector3f right, Vector3f up, float pt) {
        float heat = Mth.clamp(1 - (p.age + pt) / Math.max(1, p.life), 0, 1);
        return sphere(buf, p, cam, right, up, pt, Math.round(heat * 255) << 24 | p.rgb, 0.5f);
    }

    /** Spatter that landed: a splat on the surface, cooling to a dark crust. */
    private static int moltenSplat(BufferBuilder buf, ImpactParticles.Debris p, Vec3 cam, float pt) {
        float heat = Mth.clamp(1 - (p.age + pt) / Math.max(1, p.life), 0, 1);
        if (heat <= 0.01f && p.age > p.life - 2) return 0;
        float x = (float) (p.x - cam.x), y = (float) (p.y - cam.y) - p.size * 0.2f + 0.004f, z = (float) (p.z - cam.z);
        float r = p.size * 2.2f;
        int argb = Math.round(heat * 255) << 24 | p.rgb;
        float seed = (p.size * 977) % 1;
        fluidVertex(buf, x - r, y, z - r, argb, -1, -1, p.light, 0, seed, 0);
        fluidVertex(buf, x - r, y, z + r, argb, -1, 1, p.light, 0, seed, 0);
        fluidVertex(buf, x + r, y, z + r, argb, 1, 1, p.light, 0, seed, 0);
        fluidVertex(buf, x + r, y, z - r, argb, 1, -1, p.light, 0, seed, 0);
        return 1;
    }

    /**
     * A camera-facing quad round a drop (UV0 = the corner, the shader makes it a sphere), stretched back along its motion
     * by {@code stretch} of a tick's travel — at the speed of a splash a drop is a short streak to the eye.
     */
    private static int sphere(BufferBuilder buf, ImpactParticles.Debris p, Vec3 cam, Vector3f right, Vector3f up, float pt, int argb,
                              float stretch) {
        float x = (float) (Mth.lerp(pt, p.px, p.x) - cam.x), y = (float) (Mth.lerp(pt, p.py, p.y) - cam.y),
                z = (float) (Mth.lerp(pt, p.pz, p.z) - cam.z);
        float s = p.size;
        a.set(p.vx, p.vy, p.vz).mul(stretch);
        float seed = (p.size * 977) % 1;
        if (a.lengthSquared() < s * s * 4) {
            fluidVertex(buf, x - (right.x + up.x) * s, y - (right.y + up.y) * s, z - (right.z + up.z) * s, argb, -1, -1, p.light, 0, seed, 0);
            fluidVertex(buf, x + (right.x - up.x) * s, y + (right.y - up.y) * s, z + (right.z - up.z) * s, argb, 1, -1, p.light, 0, seed, 0);
            fluidVertex(buf, x + (right.x + up.x) * s, y + (right.y + up.y) * s, z + (right.z + up.z) * s, argb, 1, 1, p.light, 0, seed, 0);
            fluidVertex(buf, x - (right.x - up.x) * s, y - (right.y - up.y) * s, z - (right.z - up.z) * s, argb, -1, 1, p.light, 0, seed, 0);
            return 1;
        }
        // along the motion (back from the drop) and across it, facing the camera
        b.set(a).cross(x, y, z);
        if (b.lengthSquared() < 1e-10f) b.set(up);
        b.normalize(s);
        c.set(a).normalize(s);
        float bx = x - a.x, by = y - a.y, bz = z - a.z;
        fluidVertex(buf, bx - c.x - b.x, by - c.y - b.y, bz - c.z - b.z, argb, -1, -1, p.light, 0, seed, 0);
        fluidVertex(buf, bx - c.x + b.x, by - c.y + b.y, bz - c.z + b.z, argb, 1, -1, p.light, 0, seed, 0);
        fluidVertex(buf, x + c.x + b.x, y + c.y + b.y, z + c.z + b.z, argb, 1, 1, p.light, 0, seed, 0);
        fluidVertex(buf, x + c.x - b.x, y + c.y - b.y, z + c.z - b.z, argb, -1, 1, p.light, 0, seed, 0);
        return 1;
    }

    // ---- sparks -------------------------------------------------------------------------------------

    private static int sparkQuad(BufferBuilder buf, ImpactParticles.Spark p, Vec3 cam, Vector3f up, float pt) {
        float fade = ImpactParticles.fade(p.age, p.life, pt, 0.6f);
        if (fade <= 0.01f) return 0;
        a.set((float) (Mth.lerp(pt, p.px, p.x) - cam.x), (float) (Mth.lerp(pt, p.py, p.y) - cam.y), (float) (Mth.lerp(pt, p.pz, p.z) - cam.z));
        // a streak as long as its travel over about a tick (motion blur of a glowing point)
        b.set(p.vx, p.vy, p.vz).mul(1.2f);
        c.set(a).sub(b);
        d.set(b).cross(a);
        if (d.lengthSquared() < 1e-10f) d.set(up);
        d.normalize(p.width);
        if (b.length() < p.width * 2) {
            b.normalize(p.width * 2);
            c.set(a).sub(b);
        }
        int color = (Math.round(Math.min(1, fade * 0.6f) * 255) << 24) | p.rgb;
        corner(buf, c.x - d.x, c.y - d.y, c.z - d.z, -1, -1, color, 0);
        corner(buf, c.x + d.x, c.y + d.y, c.z + d.z, -1, 1, color, 0);
        corner(buf, a.x + d.x, a.y + d.y, a.z + d.z, 1, 1, color, 0);
        corner(buf, a.x - d.x, a.y - d.y, a.z - d.z, 1, -1, color, 0);
        return 1;
    }

    private static void corner(BufferBuilder buf, float x, float y, float z, float u, float v, int argb, int light) {
        buf.addVertex(x, y, z).setUv(u, v).setColor(argb).setLight(light);
    }

    // ---- the world's depth (soft dust edges) --------------------------------------------------------

    private static GpuTextureView copyDepth(GpuTextureView depth) {
        GpuTexture source = depth.texture();
        if (depthCopyFailed || (source.usage() & GpuTexture.USAGE_COPY_SRC) == 0 || source.getDepthOrLayers() != 1) return null;
        int width = source.getWidth(0), height = source.getHeight(0);
        try {
            int index = -1;
            for (int i = 0; i < depthCopies.size(); i++) {
                GpuTexture copy = depthCopies.get(i);
                if (!copy.isClosed() && copy.getWidth(0) == width && copy.getHeight(0) == height && copy.getFormat() == source.getFormat()) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                if (depthCopies.size() >= 2) releaseCopy(0);
                GpuTexture copy = RenderSystem.getDevice().createTexture(() -> "BlockLand impact depth",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, source.getFormat(), width, height, 1, 1);
                copy.setTextureFilter(FilterMode.NEAREST, false);
                depthCopies.add(copy);
                depthViews.add(RenderSystem.getDevice().createTextureView(copy));
                index = depthCopies.size() - 1;
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, depthCopies.get(index), 0, 0, 0, 0, 0, width, height);
            return depthViews.get(index);
        } catch (Throwable t) {
            BlockLand.LOGGER.warn("[BlockLand] Bullet impacts: no soft dust edges ({})", t.toString());
            depthCopyFailed = true;
            return null;
        }
    }

    private static GpuTextureView farDepth() {
        if (farDepthView == null || farDepthView.isClosed()) {
            farDepth = RenderSystem.getDevice().createTexture(() -> "BlockLand impact depth (far)", GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, TextureFormat.DEPTH32, 1, 1, 1, 1);
            farDepth.setTextureFilter(FilterMode.NEAREST, false);
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(farDepth, 1.0);
            farDepthView = RenderSystem.getDevice().createTextureView(farDepth);
        }
        return farDepthView;
    }

    private static void releaseCopy(int index) {
        depthViews.remove(index).close();
        depthCopies.remove(index).close();
    }

    /** Frees the depth copies once nothing is live (they are full-screen textures). */
    static void release() {
        while (!depthCopies.isEmpty()) releaseCopy(0);
    }
}
