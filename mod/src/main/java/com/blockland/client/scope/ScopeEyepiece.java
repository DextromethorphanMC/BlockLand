package com.blockland.client.scope;

import com.blockland.BlockLand;
import com.blockland.client.render.Textures;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Draws a scope's eyepiece glass on top of the viewmodel: a disc where the model's {@code lens_ocular} glass is, shaded
 * by {@code scope_eyepiece} with the scope's picture, the reticle, the eye box, parallax, sun glare and the glass's
 * reflections ({@link ScopeOptics} has the model). Uses the viewmodel's projection and depth so the scope body in front
 * of the rim still covers it; one draw with its own small uniform block.
 */
final class ScopeEyepiece {
    static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(BlockLand.id("pipeline/scope_eyepiece"))
            .withVertexShader(BlockLand.id("core/scope_eyepiece"))
            .withFragmentShader(BlockLand.id("core/scope_eyepiece"))
            .withSampler("Sampler0")
            .withSampler("Sampler1")
            .withUniform("ScopeInfo", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLES)
            .build();
    private static final int SEGMENTS = 64;
    private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f()
            .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();
    /** The disc is lifted this far (node units × radius) towards the eye so it lands on the model's own glass. */
    private static final float LIFT = 0.002f;

    @Nullable
    private GpuBuffer vertices;
    @Nullable
    private GpuBuffer indices;
    @Nullable
    private MappableRingBuffer ubo;
    @Nullable
    private CachedPerspectiveProjectionMatrixBuffer projection;

    /** Everything the shader needs for one frame. */
    static final class Frame {
        final Matrix4f modelView = new Matrix4f();
        final Quaternionf cameraToScope = new Quaternionf();
        float tanWorldX, tanWorldY, fieldTan;
        final Vector3f eye = new Vector3f();
        /** How much of the picture is shown (0 = none yet: dark glass). */
        float picture;
        float reticleScale, parallax, cantCos = 1, cantSin;
        int reticleRgb;
        float illumination;
        /** What the glass reflects (display RGB) and the light around the player (0..1). */
        final Vector3f environment = new Vector3f();
        float ambient;
        float vignette, fringe, distortion;
        /** The sun's direction in the scope's frame and how much of its light reaches the glass. */
        final Vector3f sun = new Vector3f();
        float sunlight;
        float dirt, flare, texel, sharpen;
        int coatingRgb;
        @Nullable
        GpuTextureView image;
        @Nullable
        GpuTextureView reticle;
    }

    void draw(Frame frame) {
        ensureBuffers();
        Minecraft mc = Minecraft.getInstance();
        var main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        if (color == null || depth == null) return;

        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(ubo.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(new Matrix4f().rotation(frame.cameraToScope))
                    .putVec4(frame.tanWorldX, frame.tanWorldY, frame.fieldTan, 1 / ScopeOptics.PICTURE_MARGIN)
                    .putVec4(frame.eye.x, frame.eye.y, frame.eye.z, frame.image != null ? frame.picture : 0)
                    .putVec4(frame.reticleScale, frame.parallax, frame.cantCos, frame.cantSin)
                    .putVec4((frame.reticleRgb >> 16 & 0xFF) / 255f, (frame.reticleRgb >> 8 & 0xFF) / 255f, (frame.reticleRgb & 0xFF) / 255f,
                            frame.illumination)
                    .putVec4(frame.environment.x, frame.environment.y, frame.environment.z, frame.ambient)
                    .putVec4(frame.vignette, frame.fringe, frame.distortion, frame.reticle != null ? 1 : 0)
                    .putVec4(frame.sun.x, frame.sun.y, frame.sun.z, frame.sunlight)
                    .putVec4(frame.dirt, frame.flare, frame.texel, frame.sharpen)
                    .putVec4((frame.coatingRgb >> 16 & 0xFF) / 255f, (frame.coatingRgb >> 8 & 0xFF) / 255f, (frame.coatingRgb & 0xFF) / 255f, 0);
        }
        GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
                .writeTransform(frame.modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f(), 0);

        GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projection.getBuffer(mc.getWindow().getWidth(), mc.getWindow().getHeight(), ViewmodelRenderer.FOV),
                ProjectionType.PERSPECTIVE);
        GpuTextureView white = Textures.white();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "BlockLand scope eyepiece", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.setUniform("ScopeInfo", ubo.currentBuffer());
            pass.bindSampler("Sampler0", frame.image != null ? frame.image : white);
            pass.bindSampler("Sampler1", frame.reticle != null ? frame.reticle : white);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indices, VertexFormat.IndexType.SHORT);
            pass.drawIndexed(0, 0, SEGMENTS * 3, 1);
        } finally {
            RenderSystem.setProjectionMatrix(savedProjection, savedType);
        }
        ubo.rotate();
    }

    /** The disc's model-view: the eyepiece node's view-space matrix, centred on the glass and scaled to its radius. */
    static Matrix4f discMatrix(Matrix4f lensView, ScopeLens lens, Matrix4f into) {
        return into.set(lensView)
                .translate(lens.centre().x, lens.centre().y, lens.centre().z + lens.radius() * LIFT)
                .scale(lens.radius(), lens.radius(), 1);
    }

    /** A unit disc (centre + rim fan) in the XY plane facing +Z, UV (0.5, 0.5) at the centre. Uploaded once. */
    private void ensureBuffers() {
        if (ubo == null) ubo = new MappableRingBuffer(() -> "BlockLand scope UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
        if (projection == null) projection = new CachedPerspectiveProjectionMatrixBuffer("BlockLand scope eyepiece", 0.02f, 128f);
        if (vertices != null) return;
        int stride = DefaultVertexFormat.POSITION_TEX.getVertexSize();
        ByteBuffer vb = ByteBuffer.allocateDirect((SEGMENTS + 1) * stride).order(ByteOrder.nativeOrder());
        vb.putFloat(0).putFloat(0).putFloat(0).putFloat(0.5f).putFloat(0.5f);
        for (int i = 0; i < SEGMENTS; i++) {
            double a = 2 * Math.PI * i / SEGMENTS;
            float x = (float) Math.cos(a), y = (float) Math.sin(a);
            vb.putFloat(x).putFloat(y).putFloat(0).putFloat(0.5f + 0.5f * x).putFloat(0.5f + 0.5f * y);
        }
        vb.flip();
        ByteBuffer ib = ByteBuffer.allocateDirect(SEGMENTS * 3 * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < SEGMENTS; i++) {
            ib.putShort((short) 0).putShort((short) (1 + i)).putShort((short) (1 + (i + 1) % SEGMENTS));
        }
        ib.flip();
        vertices = RenderSystem.getDevice().createBuffer(() -> "BlockLand scope eyepiece vertices", GpuBuffer.USAGE_VERTEX, vb);
        indices = RenderSystem.getDevice().createBuffer(() -> "BlockLand scope eyepiece indices", GpuBuffer.USAGE_INDEX, ib);
    }
}
