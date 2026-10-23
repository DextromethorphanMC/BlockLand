package com.blockland.client.scope;

import com.blockland.BlockLand;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.GunPipelines;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.ShaderPacks;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.craft.CraftData;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

/**
 * Reflex sights (red dots, holographic sights; asset spec §22). Their reticle is light focused at infinity: it sits on
 * the sight line wherever the eye is behind the window, and is only seen through the glass. So it is not a quad in the
 * model (that would drift with the glass, double up and show from the side) but drawn by the {@code reflex_glass}
 * shader on the optic's own {@code glass}: per pixel the view ray in the sight's frame gives the reticle — a dot, or a
 * holographic ring around a dot — plus the glass: nearly clear looking straight through, reflecting the player's
 * surroundings towards the frame (the coating's colour only in that grazing reflection). The viewmodel leaves the model's
 * glass and reticle out meanwhile ({@link #takesOver}). One small draw per window; under an OptiFine shader pack the
 * model draws its own glass and reticle instead.
 */
public final class ReflexSights {
    static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(BlockLand.id("pipeline/reflex_glass"))
            .withVertexShader(BlockLand.id("core/reflex_glass"))
            .withFragmentShader(BlockLand.id("core/reflex_glass"))
            .withUniform("ReflexInfo", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withVertexFormat(GunPipelines.FORMAT, VertexFormat.Mode.TRIANGLES)
            .build();
    /** Materials of a reflex sight's model this pass draws instead of the viewmodel. */
    private static final Set<String> DRAWN = Set.of("glass", "reticle");
    private static final String GLASS = "glass";
    private static final float MOA = (float) (Math.PI / 10800);
    private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putVec4().putVec4().putVec4().putVec4().get();

    @Nullable
    private static MappableRingBuffer ubo;
    @Nullable
    private static CachedPerspectiveProjectionMatrixBuffer projection;

    private ReflexSights() {
    }

    /** The materials of the held optic's model the viewmodel must leave out this frame (empty = none). */
    public static Set<String> takesOver(@Nullable CraftData.Attachment optic) {
        return optic != null && optic.isReflex() && !ShaderPacks.active() ? DRAWN : Set.of();
    }

    /**
     * Draws the held reflex sight's glass with its reticle over the viewmodel, which has just been submitted.
     *
     * @param nodeView view-space matrix of a node of the optic's model this frame, or null when it is not on screen
     */
    public static void render(CraftData.Attachment optic, Function<String, Matrix4f> nodeView, int blockLight, int skyLight) {
        if (takesOver(optic).isEmpty() || ShaderPacks.shadowPass()) return;
        GpuModel model = ModelCache.get(BlockLand.id(optic.model));
        Matrix4f aim = nodeView.apply(optic.aimNode);
        if (model == null || aim == null) return;
        Minecraft mc = Minecraft.getInstance();
        var main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        if (color == null || depth == null) return;
        ensureBuffers();

        CraftData.Reflex data = optic.reflex;
        // reticle sizes are angles in the world; the glass is drawn with the viewmodel's narrower projection, where the
        // same screen size is a smaller tangent
        float tanWorld = (float) Math.tan(Math.toRadians(Optics.worldFov()) / 2);
        float tanViewmodel = (float) Math.tan(Math.toRadians(ViewmodelRenderer.FOV) / 2);
        float toViewmodel = tanViewmodel / Math.max(1e-4f, tanWorld);
        float dot = (float) Math.tan(data.dot * MOA / 2) * toViewmodel;
        float ring = (float) Math.tan(data.ring * MOA / 2) * toViewmodel;
        float ringWidth = (float) Math.tan(data.ringWidth * MOA) * toViewmodel;
        Quaternionf viewToSight = aim.getNormalizedRotation(new Quaternionf()).conjugate();
        int rgb = data.colorRgb(), coating = data.coatingRgb();
        Vector3f environment = new Vector3f();
        float ambient = OpticLight.environment(mc.gameRenderer.getMainCamera(), blockLight, skyLight, environment);
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(ubo.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(new Matrix4f().rotation(viewToSight))
                    .putVec4(dot, ring, ringWidth, data.holographic() ? 1 : 0)
                    .putVec4((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, data.brightness)
                    .putVec4((coating >> 16 & 0xFF) / 255f, (coating >> 8 & 0xFF) / 255f, (coating & 0xFF) / 255f, data.tint)
                    .putVec4(environment.x, environment.y, environment.z, ambient);
        }

        // the glass parts and their transforms (written before the pass: buffers cannot be mapped inside one)
        GltfModel source = model.source;
        List<GpuModel.Part> parts = new ArrayList<>();
        List<GpuBufferSlice> transforms = new ArrayList<>();
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            if (node.mesh < 0) continue;
            Matrix4f modelView = null;
            for (GpuModel.Part part : model.parts(node.mesh)) {
                if (part.material() < 0 || !GLASS.equals(source.materials.get(part.material()).name)) continue;
                if (modelView == null) modelView = nodeView.apply(node.name);
                if (modelView == null) break;
                parts.add(part);
                transforms.add(RenderSystem.getDynamicUniforms()
                        .writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f(), 0));
            }
        }
        if (parts.isEmpty()) return;

        GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projection.getBuffer(mc.getWindow().getWidth(), mc.getWindow().getHeight(), ViewmodelRenderer.FOV),
                ProjectionType.PERSPECTIVE);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "BlockLand reflex sight", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("ReflexInfo", ubo.currentBuffer());
            pass.setVertexBuffer(0, model.vertexBuffer());
            pass.setIndexBuffer(model.indexBuffer(), model.indexType());
            for (int i = 0; i < parts.size(); i++) {
                pass.setUniform("DynamicTransforms", transforms.get(i));
                pass.drawIndexed(0, parts.get(i).firstIndex(), parts.get(i).indexCount(), 1);
            }
        } finally {
            RenderSystem.setProjectionMatrix(savedProjection, savedType);
        }
        ubo.rotate();
    }

    private static void ensureBuffers() {
        if (ubo == null) ubo = new MappableRingBuffer(() -> "BlockLand reflex UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
        if (projection == null) projection = new CachedPerspectiveProjectionMatrixBuffer("BlockLand reflex sight", 0.02f, 128f);
    }
}
