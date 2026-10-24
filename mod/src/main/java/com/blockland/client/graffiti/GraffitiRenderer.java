package com.blockland.client.graffiti;

import com.blockland.BlockLand;
import com.blockland.client.render.SceneLighting;
import com.blockland.graffiti.GraffitiCatalog;
import com.blockland.graffiti.GraffitiData;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Draws the graffiti in the particle pass with two pipelines of its own (rendering spec §13), right away into the
 * frame's target, one draw per piece and layer with its own parameters:
 * <ul>
 *   <li>{@code graffiti_paint} — paint on block faces (normal pieces, every layer of a layered one at its own height off
 *   the wall, the current frame of an animated one, the glass of a neon tube): lit like the wall, premultiplied alpha,
 *   depth-biased onto the surface, the target's alpha left alone;</li>
 *   <li>{@code graffiti_glow} — additive light that also marks emission so it blooms: a neon tube's glow, a hologram
 *   standing in the air (turned towards the viewer about the vertical) with its projector ring on the floor, and the
 *   points of a particle piece.</li>
 * </ul>
 * Everything a pass binds (textures, the transforms) is made before the pass opens.
 */
final class GraffitiRenderer {
    private static final BlendFunction PAINT_BLEND = new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA,
            SourceFactor.ZERO, DestFactor.ONE);
    private static final BlendFunction ADD_EMISSION = new BlendFunction(SourceFactor.ONE, DestFactor.ONE,
            SourceFactor.ZERO, DestFactor.ONE_MINUS_SRC_ALPHA);
    /** A hair off the face, below the depth bias's reach of the next layer. */
    private static final float LIFT = 0.0025f;

    static final RenderPipeline PAINT = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(BlockLand.id("pipeline/graffiti_paint"))
            .withVertexShader(BlockLand.id("core/graffiti_paint"))
            .withFragmentShader(BlockLand.id("core/graffiti_paint"))
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withSampler("BlShadowMap")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withDepthBias(-1.0F, -10.0F)
            .withBlend(PAINT_BLEND)
            .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
            .build();
    static final RenderPipeline GLOW = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(BlockLand.id("pipeline/graffiti_glow"))
            .withVertexShader(BlockLand.id("core/graffiti_glow"))
            .withFragmentShader(BlockLand.id("core/graffiti_glow"))
            .withSampler("Sampler0")
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withDepthBias(-1.0F, -12.0F)
            .withBlend(ADD_EMISSION)
            .withVertexFormat(DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS)
            .build();

    private static final int PAINT_MODE = 0, GLASS_MODE = 1;
    private static final int NEON_MODE = 0, HOLOGRAM_MODE = 1, POINTS_MODE = 2, RING_MODE = 3;

    /** One draw: which pipeline, its texture and parameters, its quads. */
    private record Draw(boolean glow, GpuTextureView texture, GpuBufferSlice params, int firstQuad, int quads) {
    }

    private static ByteBufferBuilder paintBytes;
    private static ByteBufferBuilder glowBytes;
    private static final Set<ResourceLocation> filtered = new HashSet<>();
    private static final List<Draw> draws = new ArrayList<>();
    private static final Vector3f v = new Vector3f();

    private GraffitiRenderer() {
    }

    static void draw(Collection<GraffitiWorld.Piece> pieces, Camera camera, Matrix4f pose, float partialTick, ClientLevel level) {
        if (pieces.isEmpty() || level == null) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        if (color == null || depth == null) return;
        Vec3 cam = camera.getPosition();
        double ticks = level.getGameTime() + partialTick;
        float time = (float) ((level.getGameTime() % 24000L) + partialTick) / 20f;
        float maxDistance = GraffitiCatalog.get().rules().renderDistance;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);

        if (paintBytes == null) paintBytes = new ByteBufferBuilder(64 * 1024);
        if (glowBytes == null) glowBytes = new ByteBufferBuilder(128 * 1024);
        BufferBuilder paint = new BufferBuilder(paintBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        BufferBuilder glow = new BufferBuilder(glowBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        draws.clear();
        int[] paintQuads = {0}, glowQuads = {0};
        // camera axes for things that face the viewer
        Vector3f camLeft = new Vector3f(camera.getLeftVector()), camUp = new Vector3f(camera.getUpVector());

        for (GraffitiWorld.Piece piece : pieces) {
            Vec3 c = piece.centre();
            if (c.distanceToSqr(cam) > maxDistance * maxDistance) continue;
            float reveal = piece.reveal(ticks), fade = piece.fade(ticks);
            if (fade <= 0.01f) continue;
            GraffitiData.Graffiti g = piece.graffiti;
            float seed = (piece.placement.id() * 0.6180339f) % 1f;
            float px = (float) (c.x - cam.x), py = (float) (c.y - cam.y), pz = (float) (c.z - cam.z);
            switch (g.effect) {
                case GraffitiData.LAYERED -> {
                    for (int i = 0; i < g.layers.size(); i++) {
                        float offset = LIFT + g.depth * i / Math.max(1, g.layers.size() - 1);
                        // the outer layers come on a little later: the sticker builds up from the wall
                        float layerReveal = Mth.clamp(reveal * 1.4f - i * 0.2f, 0, 1);
                        int first = paintQuads[0];
                        paintQuads[0] += emitPaint(paint, piece, px, py, pz, offset, 0, 1);
                        add(false, g.layers.get(i), params(modelView, time, layerReveal, fade, PAINT_MODE, g, seed, 0.5f, 0.5f, 1),
                                first, paintQuads[0] - first);
                    }
                }
                case GraffitiData.NEON -> {
                    int first = paintQuads[0];
                    paintQuads[0] += emitPaint(paint, piece, px, py, pz, LIFT, 0, 1);
                    add(false, g.texture, params(modelView, time, reveal, fade, GLASS_MODE, g, seed, 0.5f, 0.5f, 1), first, paintQuads[0] - first);
                    int firstGlow = glowQuads[0];
                    glowQuads[0] += emitGlowPolygons(glow, piece, px, py, pz, LIFT * 2, 0xFFFFFFFF);
                    add(true, g.texture, params(modelView, time, reveal, fade, NEON_MODE, g, seed, 0.5f, 0.5f, 1), firstGlow, glowQuads[0] - firstGlow);
                }
                case GraffitiData.HOLOGRAM -> {
                    int first = glowQuads[0];
                    glowQuads[0] += emitHologram(glow, piece, cam, time, reveal);
                    add(true, g.texture, params(modelView, time, 1, fade, HOLOGRAM_MODE, g, seed, 0.5f, 0.5f, 1), first, glowQuads[0] - first);
                    if (!Double.isNaN(piece.floorY)) {
                        int ring = glowQuads[0];
                        float half = piece.placement.size() * 0.45f;
                        float fy = (float) (piece.floorY + 0.03 - cam.y);
                        int argb = Math.round(255 * Math.min(1, reveal * 2)) << 24 | 0xFFFFFF;
                        particle(glow, px - half, fy, pz - half, -1, -1, argb);
                        particle(glow, px + half, fy, pz - half, 1, -1, argb);
                        particle(glow, px + half, fy, pz + half, 1, 1, argb);
                        particle(glow, px - half, fy, pz + half, -1, 1, argb);
                        glowQuads[0]++;
                        add(true, g.texture, params(modelView, time, 1, fade, RING_MODE, g, seed, 0.5f, 0.5f, 1), ring, 1);
                    }
                }
                case GraffitiData.PARTICLES -> {
                    int first = glowQuads[0];
                    glowQuads[0] += emitPoints(glow, piece, cam, camLeft, camUp, ticks, time);
                    add(true, g.texture, params(modelView, time, 1, fade, POINTS_MODE, g, seed, 0.5f, 0.5f, 1), first, glowQuads[0] - first);
                }
                default -> {
                    // plain paint, or the current frame of an animated strip
                    int frames = Math.max(1, g.frames);
                    int frame = GraffitiData.ANIMATED.equals(g.effect) ? (int) Math.floor(time * g.fps + seed * frames) % frames : 0;
                    float v0 = frame / (float) frames, v1 = (frame + 1) / (float) frames;
                    int first = paintQuads[0];
                    paintQuads[0] += emitPaint(paint, piece, px, py, pz, LIFT, v0, v1);
                    add(false, g.texture, params(modelView, time, reveal, fade, PAINT_MODE, g, seed, 0.5f, (v0 + v1) / 2, frames),
                            first, paintQuads[0] - first);
                }
            }
        }
        if (paintQuads[0] + glowQuads[0] == 0) {
            closeEmpty(paint);
            closeEmpty(glow);
            return;
        }

        try (MeshData paintMesh = paint.build(); MeshData glowMesh = glow.build()) {
            GpuBuffer paintVertices = paintMesh == null ? null : DefaultVertexFormat.BLOCK.uploadImmediateVertexBuffer(paintMesh.vertexBuffer());
            GpuBuffer glowVertices = glowMesh == null ? null : DefaultVertexFormat.PARTICLE.uploadImmediateVertexBuffer(glowMesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = sequential.getBuffer(Math.max(paintQuads[0], glowQuads[0]) * 6);
            // everything the pass binds is made before it opens: creating a texture inside an open pass throws
            GpuBufferSlice scene = SceneLighting.uniforms();
            GpuTextureView shadowMap = SceneLighting.shadowMap();
            GpuTextureView lightmap = mc.gameRenderer.lightTexture().getTextureView();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "BlockLand graffiti", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("BlockLandScene", scene);
                pass.bindSampler("BlShadowMap", shadowMap);
                pass.bindSampler("Sampler2", lightmap);
                pass.setIndexBuffer(indices, sequential.type());
                // paint first (onto the walls), then the light over it
                if (paintVertices != null) {
                    pass.setPipeline(PAINT);
                    pass.setVertexBuffer(0, paintVertices);
                    for (Draw d : draws) if (!d.glow) drawOne(pass, d);
                }
                if (glowVertices != null) {
                    pass.setPipeline(GLOW);
                    pass.setVertexBuffer(0, glowVertices);
                    for (Draw d : draws) if (d.glow) drawOne(pass, d);
                }
            }
        }
        draws.clear();
    }

    private static void drawOne(RenderPass pass, Draw d) {
        if (d.quads <= 0) return;
        pass.bindSampler("Sampler0", d.texture);
        pass.setUniform("DynamicTransforms", d.params);
        pass.drawIndexed(0, d.firstQuad * 6, d.quads * 6, 1);
    }

    private static void closeEmpty(BufferBuilder builder) {
        MeshData mesh = builder.build();
        if (mesh != null) mesh.close();
    }

    /** Queues a draw; its texture is loaded (and set to smooth filtering) now, outside any pass. */
    private static void add(boolean glow, String texture, GpuBufferSlice params, int firstQuad, int quads) {
        if (quads <= 0) return;
        draws.add(new Draw(glow, smooth(BlockLand.id(texture)).getTextureView(), params, firstQuad, quads));
    }

    /** Textures are made anew on a resource reload: their filtering is set again. */
    static void forgetTextures() {
        filtered.clear();
    }

    /** The texture, loaded, with smooth filtering (graffiti are painted, not pixel art; also for the wheel's previews). */
    static AbstractTexture smooth(ResourceLocation location) {
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(location);
        if (filtered.add(location)) tex.setFilter(true, false);
        return tex;
    }

    /** The draw's parameters (graffiti_fx.glsl): time, reveal, fade, mode; colour, flicker; frame centre, seed, frames. */
    private static GpuBufferSlice params(Matrix4f modelView, float time, float reveal, float fade, int mode, GraffitiData.Graffiti g,
                                         float seed, float frameU, float frameV, int frames) {
        int rgb = g.rgb();
        Matrix4f m = new Matrix4f(
                time, reveal, fade, mode,
                (rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, g.flicker,
                frameU, frameV, seed, frames,
                0, 0, 0, 0);
        return RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), m, 1.0F);
    }

    // ---- geometry -----------------------------------------------------------------------------------

    /** The piece's cut polygons on their faces, lifted by {@code offset}, texture v mapped into [v0, v1]; returns quads. */
    private static int emitPaint(BufferBuilder buf, GraffitiWorld.Piece piece, float px, float py, float pz, float offset, float v0, float v1) {
        float nx = piece.placement.nx(), ny = piece.placement.ny(), nz = piece.placement.nz();
        int quads = 0;
        for (GraffitiGeometry.Polygon poly : piece.polygons) {
            int shade = Mth.clamp(Math.round(poly.shade() * 255), 0, 255);
            int argb = 0xFF000000 | shade << 16 | shade << 8 | shade;
            int n = poly.count();
            for (int i = 1; i < n - 1; i += 2) {
                int[] corners = {0, i, i + 1, Math.min(i + 2, n - 1)};
                for (int k : corners) {
                    float[] xyz = poly.xyz(), uv = poly.uv();
                    buf.addVertex(px + xyz[k * 3] + nx * offset, py + xyz[k * 3 + 1] + ny * offset, pz + xyz[k * 3 + 2] + nz * offset)
                            .setColor(argb).setUv(uv[k * 2], v0 + uv[k * 2 + 1] * (v1 - v0)).setLight(poly.light()).setNormal(nx, ny, nz);
                }
                quads++;
            }
        }
        return quads;
    }

    /** The same polygons for the glow pipeline (a neon tube's light lies on the wall too). */
    private static int emitGlowPolygons(BufferBuilder buf, GraffitiWorld.Piece piece, float px, float py, float pz, float offset, int argb) {
        float nx = piece.placement.nx(), ny = piece.placement.ny(), nz = piece.placement.nz();
        int quads = 0;
        for (GraffitiGeometry.Polygon poly : piece.polygons) {
            int n = poly.count();
            for (int i = 1; i < n - 1; i += 2) {
                int[] corners = {0, i, i + 1, Math.min(i + 2, n - 1)};
                for (int k : corners) {
                    float[] xyz = poly.xyz(), uv = poly.uv();
                    particle(buf, px + xyz[k * 3] + nx * offset, py + xyz[k * 3 + 1] + ny * offset, pz + xyz[k * 3 + 2] + nz * offset,
                            uv[k * 2], uv[k * 2 + 1], argb);
                }
                quads++;
            }
        }
        return quads;
    }

    /**
     * A hologram: an upright square turned towards the viewer about the vertical, bobbing a little; while it starts it
     * grows up out of the projector.
     */
    private static int emitHologram(BufferBuilder buf, GraffitiWorld.Piece piece, Vec3 cam, float time, float reveal) {
        Vec3 c = piece.centre();
        float size = piece.placement.size(), half = size / 2;
        double fx = cam.x - c.x, fz = cam.z - c.z;
        double len = Math.sqrt(fx * fx + fz * fz);
        float dx = len < 1e-4 ? 1 : (float) (fx / len), dz = len < 1e-4 ? 0 : (float) (fz / len);
        // right as the viewer sees it
        float rx = -dz, rz = dx;
        float bob = 0.04f * Mth.sin(time * 1.3f + piece.placement.id());
        float grow = 1 - (1 - reveal) * (1 - reveal);
        float bottom = (float) (c.y - cam.y) - half + bob;
        float top = bottom + size * grow;
        float x = (float) (c.x - cam.x), z = (float) (c.z - cam.z);
        float vTop = 1 - grow;
        int argb = 0xFFFFFFFF;
        particle(buf, x - rx * half, top, z - rz * half, 0, vTop, argb);
        particle(buf, x + rx * half, top, z + rz * half, 1, vTop, argb);
        particle(buf, x + rx * half, bottom, z + rz * half, 1, 1, argb);
        particle(buf, x - rx * half, bottom, z - rz * half, 0, 1, argb);
        return 1;
    }

    /**
     * Particle art: each point flies from a burst at the centre to its place in the picture (staggered), then twinkles
     * and drifts; the picture stands upright facing the viewer, its points spread a little in depth so it has a body.
     */
    private static int emitPoints(BufferBuilder buf, GraffitiWorld.Piece piece, Vec3 cam, Vector3f camLeft, Vector3f camUp,
                                  double ticks, float time) {
        Vec3 c = piece.centre();
        GraffitiData.Graffiti g = piece.graffiti;
        float size = piece.placement.size();
        double fx = cam.x - c.x, fz = cam.z - c.z;
        double len = Math.sqrt(fx * fx + fz * fz);
        float dx = len < 1e-4 ? 1 : (float) (fx / len), dz = len < 1e-4 ? 0 : (float) (fz / len);
        float rx = -dz, rz = dx;
        float age = (float) ((ticks - piece.placement.placedAt()) / 20.0);
        int tint = g.rgb();
        int quads = 0;
        for (GraffitiArt.Point p : piece.points) {
            float phase = p.phase();
            float k = Mth.clamp((age - phase * 0.7f) / 0.9f, 0, 1);
            float ease = 1 - (1 - k) * (1 - k) * (1 - k);
            // where it lands: in the picture, spread a little towards and away from the viewer
            float depth = (phase - 0.5f) * 0.25f;
            float tx = rx * p.x() * size + dx * depth, ty = p.y() * size, tz = rz * p.x() * size + dz * depth;
            // where it starts: a burst around the centre
            float a = phase * 40f, b = phase * 17f;
            float sx = Mth.cos(a) * Mth.sin(b) * size * 0.7f, sy = Mth.cos(b) * size * 0.5f, sz = Mth.sin(a) * Mth.sin(b) * size * 0.7f;
            float wob = 0.012f * Mth.sin(time * (1.1f + phase) + phase * 30f);
            float x = (float) (c.x - cam.x) + Mth.lerp(ease, sx, tx) + rx * wob;
            float y = (float) (c.y - cam.y) + Mth.lerp(ease, sy, ty) + 0.015f * Mth.sin(time * 0.9f + phase * 6f);
            float z = (float) (c.z - cam.z) + Mth.lerp(ease, sz, tz) + rz * wob;
            float twinkle = 0.55f + 0.45f * Mth.sin(time * (2f + phase * 3f) + phase * 20f);
            float intensity = Mth.clamp(twinkle * Math.min(1, k * 1.5f) + (1 - ease) * 0.5f, 0, 1);
            float s = g.pointSize * (0.8f + 0.4f * phase);
            int rgb = mix(p.rgb(), tint, 0.25f);
            int argb = Math.round(intensity * 255) << 24 | rgb;
            // a round point: a square facing the camera
            v.set(camLeft).mul(s);
            float lx = v.x, ly = v.y, lz = v.z;
            v.set(camUp).mul(s);
            float ux = v.x, uy = v.y, uz = v.z;
            particle(buf, x + lx - ux, y + ly - uy, z + lz - uz, -1, -1, argb);
            particle(buf, x + lx + ux, y + ly + uy, z + lz + uz, -1, 1, argb);
            particle(buf, x - lx + ux, y - ly + uy, z - lz + uz, 1, 1, argb);
            particle(buf, x - lx - ux, y - ly - uy, z - lz - uz, 1, -1, argb);
            quads++;
        }
        return quads;
    }

    private static void particle(BufferBuilder buf, float x, float y, float z, float u, float v, int argb) {
        buf.addVertex(x, y, z).setUv(u, v).setColor(argb).setLight(LightTexture.FULL_BRIGHT);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(Mth.lerp(t, a >> 16 & 0xFF, b >> 16 & 0xFF));
        int g = Math.round(Mth.lerp(t, a >> 8 & 0xFF, b >> 8 & 0xFF));
        int bl = Math.round(Mth.lerp(t, a & 0xFF, b & 0xFF));
        return r << 16 | g << 8 | bl;
    }
}
