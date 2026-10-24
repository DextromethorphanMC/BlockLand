package com.blockland.client.bomb.fx;

import com.blockland.BlockLand;
import com.blockland.client.render.SceneLighting;
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
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Draws the live bomb explosions in the particle pass with BlockLand's own pipelines (rendering spec §10), right away
 * into the frame's target like the third-person weapons. One vertex buffer per frame in vanilla's particle layout, three
 * pipelines: the puffs ({@code bomb_smoke}, premultiplied alpha: glowing gas adds light, smoke covers), their glow
 * ({@code bomb_smoke_glow}: the hot gas again, writing only the target's alpha as encoded emission so the post chain makes
 * it bloom) and the sparks ({@code bomb_spark}, additive, lowering the target's alpha for their glow). All depth tested, none writing depth. The world's
 * depth is copied once per frame while an explosion is live so the puffs fade where they meet geometry.
 */
final class BombFxRenderer {
    private static final BlendFunction PREMULTIPLIED = new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA,
            SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
    private static final BlendFunction ADD_EMISSION = new BlendFunction(SourceFactor.ONE, DestFactor.ONE,
            SourceFactor.ZERO, DestFactor.ONE_MINUS_SRC_ALPHA);

    static final RenderPipeline SMOKE = smoke("bomb_smoke").withBlend(PREMULTIPLIED).build();
    /** Writes only the target's alpha (the encoded emission of the hot gas), no blending: see bomb_smoke.fsh. */
    static final RenderPipeline GLOW = smoke("bomb_smoke_glow").withShaderDefine("GLOW_PASS").withoutBlend()
            .withColorWrite(false, true).build();
    static final RenderPipeline SPARKS = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(BlockLand.id("pipeline/bomb_spark"))
            .withVertexShader(BlockLand.id("core/bomb_spark"))
            .withFragmentShader(BlockLand.id("core/bomb_spark"))
            .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withBlend(ADD_EMISSION)
            .withVertexFormat(DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS)
            .build();

    private static ByteBufferBuilder bytes;
    /** The world's depth of this frame (by size: the main view and a scope's picture differ). */
    private static final List<GpuTexture> depthCopies = new ArrayList<>(2);
    private static final List<GpuTextureView> depthViews = new ArrayList<>(2);
    private static GpuTexture farDepth;
    private static GpuTextureView farDepthView;
    private static boolean depthCopyFailed;

    private BombFxRenderer() {
    }

    private static RenderPipeline.Builder smoke(String name) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(BlockLand.id("pipeline/" + name))
                .withVertexShader(BlockLand.id("core/bomb_smoke"))
                .withFragmentShader(BlockLand.id("core/bomb_smoke"))
                .withSampler("Sampler2")
                .withSampler("DepthSampler")
                .withSampler("BlShadowMap")
                .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .withVertexFormat(DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS);
    }

    /** What one frame of the explosions consists of, in camera-relative world space. */
    static final class Frame {
        final Camera camera;
        final Vector3f right, up, back;
        final float partialTick;
        final List<Puff> puffs = new ArrayList<>();
        final List<Puff> rings = new ArrayList<>();
        final List<Spark> sparks = new ArrayList<>();
        final List<Spark> discs = new ArrayList<>();
        /** The fireball's light on its own smoke: camera-relative position, linear colour × intensity, reach. */
        final Vector3f fireAt = new Vector3f();
        final Vector3f fireColor = new Vector3f();
        float fireReach = 1;
        float time;

        Frame(Camera camera, float partialTick) {
            this.camera = camera;
            this.partialTick = partialTick;
            this.right = new Vector3f(camera.getLeftVector()).negate();
            this.up = new Vector3f(camera.getUpVector());
            this.back = new Vector3f(camera.getLookVector()).negate();
        }

        /**
         * A puff of gas centred at (x, y, z) camera-relative, radius {@code r}; heat, density, seed and soot 0..1, light
         * packed as vanilla's lightmap coordinates.
         */
        void puff(float x, float y, float z, float r, float heat, float density, float seed, float soot, int light) {
            if (r <= 0.01f || density <= 0.004f) return;
            puffs.add(new Puff(x, y, z, r, heat, density, seed, soot, light));
        }

        /** The dust ring lying on the floor: {@code opacity} 0..1, {@code inner} where its band starts (0..1 of r). */
        void ring(float x, float y, float z, float r, float opacity, float inner, float seed, float soot, int light) {
            if (r <= 0.01f || opacity <= 0.004f) return;
            rings.add(new Puff(x, y, z, r, opacity, inner, seed, soot, light));
        }

        /** A streak from {@code tail} to {@code head} (camera-relative), {@code width} blocks across, display colour. */
        void spark(Vector3f tail, Vector3f head, float width, int rgb, float intensity) {
            if (intensity <= 0.004f) return;
            sparks.add(new Spark(new Vector3f(tail), new Vector3f(head), width, rgb, intensity));
        }

        /** A round glow of half-size {@code size} facing the camera. */
        void disc(float x, float y, float z, float size, int rgb, float intensity) {
            if (intensity <= 0.004f || size <= 0.01f) return;
            Vector3f c = new Vector3f(x, y, z);
            discs.add(new Spark(c, c, size, rgb, intensity));
        }

        boolean isEmpty() {
            return puffs.isEmpty() && rings.isEmpty() && sparks.isEmpty() && discs.isEmpty();
        }
    }

    record Puff(float x, float y, float z, float r, float heat, float density, float seed, float soot, int light) {
        float distanceSqr() {
            return x * x + y * y + z * z;
        }
    }

    record Spark(Vector3f tail, Vector3f head, float width, int rgb, float intensity) {
    }

    /** Draws a frame of explosions; {@code pose} is the particle pass's (camera-relative world). */
    static void draw(Frame frame, Matrix4f pose) {
        if (frame.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        if (color == null || depth == null) return;

        // back to front: the rings lie under everything, the puffs sorted, the hot ones again for their glow
        frame.puffs.sort(Comparator.comparingDouble(Puff::distanceSqr).reversed());
        List<Puff> hot = frame.puffs.stream().filter(p -> p.heat() > 0.03f).toList();
        if (bytes == null) bytes = new ByteBufferBuilder(64 * 1024);
        BufferBuilder buffer = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        for (Puff ring : frame.rings) ringQuad(buffer, ring);
        for (Puff puff : frame.puffs) puffQuad(buffer, frame, puff);
        for (Puff puff : hot) puffQuad(buffer, frame, puff);
        for (Spark spark : frame.sparks) sparkQuad(buffer, frame, spark);
        for (Spark disc : frame.discs) discQuad(buffer, frame, disc);
        int rings = frame.rings.size(), puffs = frame.puffs.size(), glows = hot.size(), sparks = frame.sparks.size(),
                discs = frame.discs.size();

        try (MeshData mesh = buffer.build()) {
            if (mesh == null) return;
            GpuBuffer vertices = DefaultVertexFormat.PARTICLE.uploadImmediateVertexBuffer(mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = sequential.getBuffer(mesh.drawState().indexCount());
            GpuTextureView worldDepth = copyDepth(depth);

            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
            GpuBufferSlice puffMode = transform(frame, modelView, 0, worldDepth != null);
            GpuBufferSlice ringMode = transform(frame, modelView, 1, worldDepth != null);
            GpuBufferSlice sparkMode = transform(frame, modelView, 0, false);
            GpuBufferSlice discMode = transform(frame, modelView, 1, false);
            // everything the pass binds is made before it opens: creating or clearing a texture inside an open pass throws
            GpuTextureView smokeDepth = worldDepth != null ? worldDepth : farDepth();
            GpuBufferSlice scene = SceneLighting.uniforms();
            GpuTextureView shadowMap = SceneLighting.shadowMap();

            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "BlockLand bomb explosion", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("BlockLandScene", scene);
                pass.bindSampler("BlShadowMap", shadowMap);
                pass.bindSampler("Sampler2", mc.gameRenderer.lightTexture().getTextureView());
                pass.bindSampler("DepthSampler", smokeDepth);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indices, sequential.type());
                int quad = 0;
                pass.setPipeline(SMOKE);
                quad = draw(pass, ringMode, quad, rings);
                quad = draw(pass, puffMode, quad, puffs);
                pass.setPipeline(GLOW);
                quad = draw(pass, puffMode, quad, glows);
                pass.setPipeline(SPARKS);
                quad = draw(pass, sparkMode, quad, sparks);
                draw(pass, discMode, quad, discs);
            }
        }
    }

    private static int draw(RenderPass pass, GpuBufferSlice transform, int firstQuad, int quads) {
        if (quads <= 0) return firstQuad;
        pass.setUniform("DynamicTransforms", transform);
        pass.drawIndexed(0, firstQuad * 6, quads * 6, 1);
        return firstQuad + quads;
    }

    /** The draw's parameters (bomb_fx.glsl): camera axes, time, soft depth, mode, the fireball's light. */
    private static GpuBufferSlice transform(Frame frame, Matrix4f modelView, int mode, boolean softDepth) {
        Matrix4f params = new Matrix4f(
                frame.right.x, frame.right.y, frame.right.z, frame.time,
                frame.up.x, frame.up.y, frame.up.z, softDepth ? 1 : 0,
                frame.back.x, frame.back.y, frame.back.z, mode,
                0, 0, 0, 0);
        return RenderSystem.getDynamicUniforms().writeTransform(modelView,
                new Vector4f(frame.fireColor, frame.fireReach), frame.fireAt, params, 1.0F);
    }

    // ---- quads ----------------------------------------------------------------------------------------

    private static void puffQuad(BufferBuilder buffer, Frame frame, Puff p) {
        Vector3f r = frame.right, u = frame.up;
        float s = p.r();
        int color = packed(p.heat(), p.density(), p.seed(), p.soot());
        corner(buffer, p.x() - (r.x + u.x) * s, p.y() - (r.y + u.y) * s, p.z() - (r.z + u.z) * s, -s, -s, color, p.light());
        corner(buffer, p.x() + (r.x - u.x) * s, p.y() + (r.y - u.y) * s, p.z() + (r.z - u.z) * s, s, -s, color, p.light());
        corner(buffer, p.x() + (r.x + u.x) * s, p.y() + (r.y + u.y) * s, p.z() + (r.z + u.z) * s, s, s, color, p.light());
        corner(buffer, p.x() - (r.x - u.x) * s, p.y() - (r.y - u.y) * s, p.z() - (r.z - u.z) * s, -s, s, color, p.light());
    }

    private static void ringQuad(BufferBuilder buffer, Puff p) {
        float s = p.r();
        int color = packed(p.heat(), p.density(), p.seed(), p.soot());
        corner(buffer, p.x() - s, p.y(), p.z() - s, -s, -s, color, p.light());
        corner(buffer, p.x() + s, p.y(), p.z() - s, s, -s, color, p.light());
        corner(buffer, p.x() + s, p.y(), p.z() + s, s, s, color, p.light());
        corner(buffer, p.x() - s, p.y(), p.z() + s, -s, s, color, p.light());
    }

    private static void sparkQuad(BufferBuilder buffer, Frame frame, Spark s) {
        Vector3f axis = new Vector3f(s.head()).sub(s.tail());
        // seen end on, or barely moving: a round dot the size of its width
        Vector3f mid = new Vector3f(s.head()).add(s.tail()).mul(0.5f);
        Vector3f side = new Vector3f(axis).cross(mid);
        if (side.lengthSquared() < 1e-8f) side.set(frame.up);
        side.normalize(s.width());
        float length = axis.length();
        if (length < s.width() * 2) {
            Vector3f dir = length < 1e-5f ? new Vector3f(frame.right) : new Vector3f(axis).normalize();
            axis.set(dir).mul(s.width() * 2);
            s.tail().set(mid).sub(axis.x * 0.5f, axis.y * 0.5f, axis.z * 0.5f);
            s.head().set(mid).add(axis.x * 0.5f, axis.y * 0.5f, axis.z * 0.5f);
        }
        int color = (Math.round(Math.min(1, s.intensity() * 0.5f) * 255) << 24) | (s.rgb() & 0xFFFFFF);
        Vector3f t = s.tail(), h = s.head();
        corner(buffer, t.x - side.x, t.y - side.y, t.z - side.z, -1, -1, color, 0);
        corner(buffer, t.x + side.x, t.y + side.y, t.z + side.z, -1, 1, color, 0);
        corner(buffer, h.x + side.x, h.y + side.y, h.z + side.z, 1, 1, color, 0);
        corner(buffer, h.x - side.x, h.y - side.y, h.z - side.z, 1, -1, color, 0);
    }

    private static void discQuad(BufferBuilder buffer, Frame frame, Spark d) {
        Vector3f r = frame.right, u = frame.up, c = d.head();
        float s = d.width();
        int color = (Math.round(Math.min(1, d.intensity() * 0.5f) * 255) << 24) | (d.rgb() & 0xFFFFFF);
        corner(buffer, c.x - (r.x + u.x) * s, c.y - (r.y + u.y) * s, c.z - (r.z + u.z) * s, -1, -1, color, 0);
        corner(buffer, c.x + (r.x - u.x) * s, c.y + (r.y - u.y) * s, c.z + (r.z - u.z) * s, 1, -1, color, 0);
        corner(buffer, c.x + (r.x + u.x) * s, c.y + (r.y + u.y) * s, c.z + (r.z + u.z) * s, 1, 1, color, 0);
        corner(buffer, c.x - (r.x - u.x) * s, c.y - (r.y - u.y) * s, c.z - (r.z - u.z) * s, -1, 1, color, 0);
    }

    private static void corner(BufferBuilder buffer, float x, float y, float z, float u, float v, int argb, int light) {
        buffer.addVertex(x, y, z).setUv(u, v).setColor(argb).setLight(light);
    }

    /** Four 0..1 values as the vertex colour (r, g, b, a). */
    private static int packed(float r, float g, float b, float a) {
        return byteOf(a) << 24 | byteOf(r) << 16 | byteOf(g) << 8 | byteOf(b);
    }

    private static int byteOf(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    // ---- the world's depth ------------------------------------------------------------------------------

    /** A copy of the target's depth as it is now (terrain and entities), or null when it cannot be made. */
    private static GpuTextureView copyDepth(GpuTextureView depth) {
        GpuTexture source = depth.texture();
        // a target that cannot be copied from (not ours to change): this frame without soft edges
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
                GpuTexture copy = RenderSystem.getDevice().createTexture(() -> "BlockLand bomb depth",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, source.getFormat(), width, height, 1, 1);
                copy.setTextureFilter(FilterMode.NEAREST, false);
                depthCopies.add(copy);
                depthViews.add(RenderSystem.getDevice().createTextureView(copy));
                index = depthCopies.size() - 1;
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, depthCopies.get(index), 0, 0, 0, 0, 0, width, height);
            return depthViews.get(index);
        } catch (Throwable t) {
            // soft edges are a nicety: without them the puffs are still drawn, depth tested
            BlockLand.LOGGER.warn("[BlockLand] Bomb explosion: no soft smoke edges ({})", t.toString());
            depthCopyFailed = true;
            return null;
        }
    }

    /** A 1×1 depth at the far plane: nothing is in front of any puff. */
    private static GpuTextureView farDepth() {
        if (farDepthView == null || farDepthView.isClosed()) {
            farDepth = RenderSystem.getDevice().createTexture(() -> "BlockLand bomb depth (far)", GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, com.mojang.blaze3d.textures.TextureFormat.DEPTH32, 1, 1, 1, 1);
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

    /** Frees the depth copies once no explosion is left (they are full-screen textures). */
    static void release() {
        while (!depthCopies.isEmpty()) releaseCopy(0);
    }
}
