package com.blockland.client.graphics.far;

import com.blockland.BlockLand;
import com.blockland.client.graphics.frame.LevelPasses;
import com.blockland.client.graphics.gpu.Fullscreen;
import com.blockland.client.graphics.gpu.RenderTexture;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.render.FrameJitter;
import com.blockland.client.render.SceneLighting;
import com.blockland.farterrain.FarTile;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
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
 * Draws the far terrain (rendering spec §22) in the level's pass before vanilla's terrain: into the frame's main target's
 * colour with a depth texture of its own, with the level's projection (bobbing and nausea included) whose near and far
 * planes are the far terrain's, plus the frame's jitter in the main view. Tiles frustum-culled and front to back; tiles
 * reaching into vanilla's area through the masked program, then its depth carried into the target's own where the level
 * projection reaches. A scope's picture draws it with its own depth texture.
 */
final class FarRenderer {
    private static final int VIEW_BYTES = 128;
    static final RenderPipeline PLAIN = pipeline("far_terrain", false);
    static final RenderPipeline MASKED = pipeline("far_terrain_masked", true);

    private static RenderPipeline pipeline(String name, boolean masked) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(BlockLand.id("pipeline/" + name))
                .withVertexShader(BlockLand.id("core/far_terrain"))
                .withFragmentShader(BlockLand.id("core/far_terrain"))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                .withUniform("BlockLandScene", UniformType.UNIFORM_BUFFER)
                .withUniform("FarView", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withSampler("Sampler2")
                .withSampler("BlShadowMap")
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withDepthWrite(true)
                .withCull(true)
                .withoutBlend()
                .withVertexFormat(FarVertex.FORMAT, VertexFormat.Mode.QUADS);
        if (masked) builder.withShaderDefine("MASKED").withSampler("FarMaskSampler").withSampler("FarMaskCoarseSampler");
        return builder.build();
    }

    /**
     * The far terrain's depth carried into the target's own (far_depth.fsh): within the level projection's far plane the
     * level's depth there becomes the far terrain's, so vanilla's clouds, rain and particles behind it stay hidden.
     */
    static final RenderPipeline DEPTH = Fullscreen.pipeline("far_depth")
            .withSampler("FarDepthSampler")
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true)
            .withColorWrite(false)
            .build();

    final CoverageMask mask = new CoverageMask();
    /** Made on the render thread at the first draw (the renderer is built during mod construction). */
    @Nullable
    private Ubo mainDepthInfo, extraDepthInfo;
    private final RenderTexture mainDepth = new RenderTexture("BlockLand far terrain depth", RenderTexture.Format.DEPTH, FilterMode.NEAREST);
    private final RenderTexture extraDepth = new RenderTexture("BlockLand far terrain depth (scope)", RenderTexture.Format.DEPTH,
            FilterMode.NEAREST);
    private final ByteBuffer scratch = ByteBuffer.allocateDirect(VIEW_BYTES).order(ByteOrder.nativeOrder());
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final List<Selection.Draw> sorted = new ArrayList<>();
    private final List<GpuBufferSlice> transforms = new ArrayList<>();
    @Nullable
    private GpuBuffer mainView, extraView;
    @Nullable
    private GpuBuffer mainProjection, extraProjection;
    /** Tiles, cells and real far chunk columns drawn in the last main view. */
    int drawn, chunksDrawn;
    long cells;

    /**
     * Draws the real far chunks ({@code ring}, may be null) and the tiles into the view's target: the chunks' solid layers
     * first (nearest first), the tiles, the chunks' translucent layers last, all with the same far projection and depth.
     * Returns the depth texture drawn (the main view's is what the post chain reads), or null when nothing was drawn.
     */
    @Nullable
    GpuTextureView draw(LevelPasses.View view, List<Selection.Draw> draws, @Nullable RegionRenderer ring, float near, float far) {
        if (draws.isEmpty() && (ring == null || ring.columns() == 0)) return null;
        GpuTextureView color = view.target().getColorTextureView();
        if (color == null) return null;
        int width = view.target().width, height = view.target().height;
        RenderTexture depth = view.mainView() ? mainDepth : extraDepth;
        depth.ensure(width, height);

        Matrix4f projection = farProjection(view.projection(), near, far);
        if (view.mainView()) FrameJitter.apply(projection);
        frustum.set(new Matrix4f(projection).mul(view.viewRotation()), false);
        Vec3 cam = view.camera().getPosition();

        // front to back (early depth test), the masked ring first
        sorted.clear();
        for (Selection.Draw d : draws) {
            FarMesh mesh = d.entry().mesh;
            if (mesh == null || mesh.buffer == null || mesh.quads == 0 || mesh.buffer.isClosed()) continue;
            int span = FarTile.span(d.entry().level);
            double x0 = (double) d.entry().tx * span - cam.x, z0 = (double) d.entry().tz * span - cam.z;
            if (!frustum.testAab((float) x0, (float) (mesh.minY - cam.y), (float) z0, (float) (x0 + span), (float) (mesh.maxY - cam.y),
                    (float) (z0 + span))) continue;
            sorted.add(d);
        }
        List<RegionRenderer.Draw> ringDraws = ring != null
                ? ring.prepare(frustum, view.viewRotation(), cam.x, cam.y, cam.z) : List.of();
        // nothing in view: no depth this frame (the texture still holds an older one)
        if (sorted.isEmpty() && ringDraws.isEmpty()) return null;
        sorted.sort((a, b) -> {
            if (a.masked() != b.masked()) return a.masked() ? -1 : 1;
            return Double.compare(distance(a.entry(), cam), distance(b.entry(), cam));
        });

        // everything the pass binds is made before it opens (creating or writing a buffer inside an open pass throws)
        GpuTextureView maskView = mask.fine(), coarseView = maskView != null ? mask.coarse() : null;
        if (coarseView == null) maskView = null;
        GpuBuffer viewBuffer = viewBuffer(view.mainView());
        scratch.clear();
        Std140Builder out = Std140Builder.intoBuffer(scratch);
        out.putMat4f(projection); // FarProjection
        out.putVec4(Math.floorMod(mask.centreX, CoverageMask.FINE), Math.floorMod(mask.centreZ, CoverageMask.FINE),
                maskView != null ? 1 : 0, CoverageMask.FINE); // FarMask
        double chunkX = mask.centreX * 16.0, chunkZ = mask.centreZ * 16.0;
        out.putVec4((float) (cam.x - chunkX), (float) cam.y, (float) (cam.z - chunkZ), far); // FarCamera
        GpuTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture();
        out.putVec4(atlas.getWidth(0), atlas.getHeight(0), atlas.getMipLevels() - 1, 0); // FarAtlas
        out.putVec4(Math.floorMod(mask.centreX, CoverageMask.CELL * CoverageMask.COARSE), Math.floorMod(mask.centreZ,
                CoverageMask.CELL * CoverageMask.COARSE), CoverageMask.CELL, CoverageMask.COARSE); // FarMaskCoarse
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(viewBuffer.slice(), out.get());
        GpuBuffer projectionBuffer = null;
        if (!ringDraws.isEmpty()) {
            // vanilla's terrain programs read the projection from their own block
            projectionBuffer = projectionBuffer(view.mainView());
            scratch.clear();
            Std140Builder proj = Std140Builder.intoBuffer(scratch);
            proj.putMat4f(projection);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(projectionBuffer.slice(), proj.get());
        }
        transforms.clear();
        int maxQuads = RegionRenderer.maxQuads(ringDraws);
        Vector4f white = new Vector4f(1, 1, 1, 1);
        Matrix4f textureMatrix = new Matrix4f();
        for (Selection.Draw d : sorted) {
            TileEntry e = d.entry();
            int span = FarTile.span(e.level);
            Vector3f offset = new Vector3f((float) ((double) e.tx * span - cam.x), (float) -cam.y, (float) ((double) e.tz * span - cam.z));
            textureMatrix.identity().m00(FarTile.cell(e.level));
            transforms.add(RenderSystem.getDynamicUniforms().writeTransform(view.viewRotation(), white, offset, textureMatrix, 1));
            maxQuads = Math.max(maxQuads, e.mesh.quads);
        }
        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indices = sequential.getBuffer(maxQuads * 6);
        GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightTexture().getTextureView();
        GpuTextureView atlasView = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        GpuBufferSlice scene = SceneLighting.uniforms();
        GpuTextureView shadowMap = SceneLighting.shadowMap();

        long cellCount = 0;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "BlockLand far terrain", color, OptionalInt.empty(), depth.view(), OptionalDouble.of(1.0))) {
            if (projectionBuffer != null) {
                // the real far chunks: vanilla's own pipelines, their defaults with the far projection and the level's fog
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("Projection", projectionBuffer);
                pass.bindSampler("Sampler2", lightmap);
                pass.setUniform("Fog", view.fog());
                pass.setIndexBuffer(indices, sequential.type());
                RegionRenderer.draw(pass, ringDraws, false, indices, sequential.type());
            }
            pass.setUniform("Fog", view.fog());
            pass.setUniform("BlockLandScene", scene);
            pass.setUniform("FarView", viewBuffer);
            pass.bindSampler("Sampler0", atlasView);
            pass.bindSampler("Sampler2", lightmap);
            pass.bindSampler("BlShadowMap", shadowMap);
            if (maskView != null) {
                pass.bindSampler("FarMaskSampler", maskView);
                pass.bindSampler("FarMaskCoarseSampler", coarseView);
            }
            pass.setIndexBuffer(indices, sequential.type());
            RenderPipeline current = null;
            for (int i = 0; i < sorted.size(); i++) {
                Selection.Draw d = sorted.get(i);
                RenderPipeline pipeline = d.masked() && maskView != null ? MASKED : PLAIN;
                if (pipeline != current) {
                    pass.setPipeline(pipeline);
                    current = pipeline;
                }
                FarMesh mesh = d.entry().mesh;
                pass.setVertexBuffer(0, mesh.buffer);
                pass.setUniform("DynamicTransforms", transforms.get(i));
                pass.drawIndexed(0, 0, mesh.quads * 6, 1);
                cellCount += FarTile.CELLS;
            }
            if (projectionBuffer != null) {
                pass.bindSampler("Sampler2", lightmap);
                RegionRenderer.draw(pass, ringDraws, true, indices, sequential.type());
            }
        }
        if (view.mainView()) {
            drawn = sorted.size();
            cells = cellCount;
            chunksDrawn = ring != null ? ring.drawnColumns : 0;
        }
        resolveDepth(view, depth.view(), projection);
        return depth.view();
    }

    /** far_depth.fsh: the far terrain's depth into the target's depth, where it lies within the level's far plane. */
    private void resolveDepth(LevelPasses.View view, GpuTextureView farDepth, Matrix4f farProjection) {
        GpuTextureView targetDepth = view.target().getDepthTextureView();
        GpuTextureView color = view.target().getColorTextureView();
        if (targetDepth == null || color == null) return;
        Matrix4f level = new Matrix4f(view.projection());
        if (view.mainView()) FrameJitter.apply(level);
        if (mainDepthInfo == null) {
            mainDepthInfo = Ubo.pass("far terrain depth");
            extraDepthInfo = Ubo.pass("far terrain depth (scope)");
        }
        Ubo info = view.mainView() ? mainDepthInfo : extraDepthInfo;
        info.pass(level.mul(new Matrix4f(farProjection).invert()));
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(6);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "BlockLand far terrain depth", color, OptionalInt.empty(), targetDepth, OptionalDouble.empty())) {
            pass.setPipeline(DEPTH);
            pass.setUniform(Fullscreen.PASS_BLOCK, info.buffer().slice());
            pass.bindSampler("FarDepthSampler", farDepth);
            pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(0, 0, 6, 1);
        }
    }

    /**
     * The level's projection with other near and far planes: its depth row rebuilt from its w row (the level's matrix is
     * a perspective times affine view-space transforms — bobbing, nausea — whose last row stays (0, 0, 0, 1)).
     */
    static Matrix4f farProjection(Matrix4f level, float near, float far) {
        Matrix4f p = new Matrix4f(level);
        float a = (far + near) / (near - far), b = 2 * far * near / (near - far);
        p.m02(-a * p.m03());
        p.m12(-a * p.m13());
        p.m22(-a * p.m23());
        p.m32(-a * p.m33() + b);
        return p;
    }

    private static double distance(TileEntry e, Vec3 cam) {
        int span = FarTile.span(e.level);
        double x0 = (double) e.tx * span, z0 = (double) e.tz * span;
        double dx = Math.max(0, Math.max(x0 - cam.x, cam.x - (x0 + span)));
        double dz = Math.max(0, Math.max(z0 - cam.z, cam.z - (z0 + span)));
        return dx * dx + dz * dz;
    }

    private GpuBuffer viewBuffer(boolean main) {
        GpuBuffer buffer = main ? mainView : extraView;
        if (buffer == null || buffer.isClosed()) {
            buffer = RenderSystem.getDevice().createBuffer(() -> main ? "BlockLand far view" : "BlockLand far view (scope)",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, VIEW_BYTES);
            if (main) mainView = buffer;
            else extraView = buffer;
        }
        return buffer;
    }

    private GpuBuffer projectionBuffer(boolean main) {
        GpuBuffer buffer = main ? mainProjection : extraProjection;
        if (buffer == null || buffer.isClosed()) {
            buffer = RenderSystem.getDevice().createBuffer(() -> main ? "BlockLand far chunk projection" : "BlockLand far chunk projection (scope)",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
            if (main) mainProjection = buffer;
            else extraProjection = buffer;
        }
        return buffer;
    }

    /** The main view's depth texture, when one was drawn into. */
    @Nullable
    GpuTextureView mainDepth() {
        return mainDepth.exists() ? mainDepth.view() : null;
    }

    void close() {
        mainDepth.close();
        extraDepth.close();
        mask.close();
        if (mainView != null) mainView.close();
        if (extraView != null) extraView.close();
        if (mainProjection != null) mainProjection.close();
        if (extraProjection != null) extraProjection.close();
        mainView = extraView = mainProjection = extraProjection = null;
        drawn = chunksDrawn = 0;
        cells = 0;
    }
}
