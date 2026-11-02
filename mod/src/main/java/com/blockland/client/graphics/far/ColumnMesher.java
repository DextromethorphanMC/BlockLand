package com.blockland.client.graphics.far;

import com.blockland.client.perf.Optimization;
import com.blockland.client.perf.terrain.FaceGroups;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.model.data.ModelData;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Meshes one far unit with Minecraft's own block renderer (rendering spec §23), on a worker: every block (a unit of scale
 * k: every cube of 2^k blocks, drawn as its block scaled up) of every
 * non-empty section as {@code SectionCompiler} does it — fluids through {@code renderLiquid}, models through
 * {@code renderBatched} with their Forge render types and seeds, sides culled against the neighbours — into one buffer per
 * layer, positions relative to the column's region (so a region's columns share one vertex buffer and one draw). No block
 * entities (chests and the like are drawn by their own renderers, never in section meshes either).
 */
final class ColumnMesher {
    /** The column's layers, each in native memory ({@link #free}); positions relative to its region's corner. */
    static final class Result {
        final EnumMap<ChunkSectionLayer, ByteBuffer> layers = new EnumMap<>(ChunkSectionLayer.class);
        /** {@code FACE_GROUPS}: the solid and cutout layers' quads by facing. */
        final EnumMap<ChunkSectionLayer, FaceGroups> groups = new EnumMap<>(ChunkSectionLayer.class);
        /** {@code FAR_GPU_CULLING}: each layer's quads in clusters with their boxes (made with every mesh). */
        final EnumMap<ChunkSectionLayer, Clusters> clusters = new EnumMap<>(ChunkSectionLayer.class);
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        void free() {
            for (ByteBuffer b : layers.values()) MemoryUtil.memFree(b);
            layers.clear();
            groups.clear();
            clusters.clear();
        }

        long bytes() {
            long n = 0;
            for (ByteBuffer b : layers.values()) n += b.remaining();
            return n;
        }
    }

    private ColumnMesher() {
    }

    /**
     * @param getter   the unit and its neighbours (in the unit's grid)
     * @param column   the unit's sections
     * @param scale    the unit's scale: its grid's cubes are {@code 1 << scale} blocks
     * @param originX  the region's corner (in the unit's grid); positions are written relative to it (and scaled to blocks),
     *                 y relative to {@code originY} (the level's lowest block, in the grid: minY >> scale)
     */
    static Result mesh(ColumnGetter getter, ColumnGetter.Snapshot column, int scale, int originX, int originY, int originZ) {
        float size = 1 << scale;
        Result result = new Result();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        Map<ChunkSectionLayer, ByteBufferBuilder> bytes = new EnumMap<>(ChunkSectionLayer.class);
        Map<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
        PoseStack pose = new PoseStack();
        RandomSource random = RandomSource.create();
        List<BlockModelPart> parts = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = column.cx() << 4, baseZ = column.cz() << 4;
        pose.scale(size, size, size);
        // FAR_HIDDEN_GEOMETRY: cells no line of sight from outside the unit reaches are left out
        long[] hidden = Optimization.FAR_HIDDEN_GEOMETRY.on() ? HiddenCells.of(column) : null;
        boolean grouped = Optimization.FACE_GROUPS.on();
        ModelBlockRenderer.enableCaching();
        try {
            for (int i = 0; i < column.sections().length; i++) {
                LevelChunkSection section = column.sections()[i];
                if (section == null || section.hasOnlyAir()) continue;
                int baseY = (column.minSection() + i) << 4;
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            BlockState state = section.getBlockState(lx, ly, lz);
                            if (state.isAir()) continue;
                            if (hidden != null && HiddenCells.has(hidden, (i * 16 + ly) * 256 + lz * 16 + lx)) continue;
                            pos.set(baseX + lx, baseY + ly, baseZ + lz);
                            FluidState fluid = state.getFluidState();
                            if (!fluid.isEmpty()) {
                                BufferBuilder buffer = builder(ItemBlockRenderTypes.getRenderLayer(fluid), bytes, builders);
                                // the liquid renderer writes positions within the section: shift them to the region
                                LiquidOffset.write(dispatcher, pos, getter, buffer, state, fluid, baseX - originX, baseY - originY, baseZ - originZ,
                                        size);
                            }
                            if (state.getRenderShape() != RenderShape.MODEL) continue;
                            BlockStateModel model = dispatcher.getBlockModel(state);
                            ModelData data = model.getModelData(getter, pos, state, ModelData.EMPTY);
                            random.setSeed(state.getSeed(pos));
                            for (ChunkSectionLayer layer : model.getRenderTypes(state, random, data)) {
                                BufferBuilder buffer = builder(layer, bytes, builders);
                                random.setSeed(state.getSeed(pos));
                                model.collectParts(random, parts, data, layer);
                                pose.pushPose();
                                pose.translate(pos.getX() - originX, pos.getY() - originY, pos.getZ() - originZ);
                                dispatcher.renderBatched(state, pos, getter, pose, buffer, true, parts);
                                pose.popPose();
                                parts.clear();
                            }
                            result.minY = Math.min(result.minY, pos.getY() << scale);
                            result.maxY = Math.max(result.maxY, pos.getY() + 1 << scale);
                        }
                    }
                }
            }
            for (Map.Entry<ChunkSectionLayer, BufferBuilder> e : builders.entrySet()) {
                try (MeshData mesh = e.getValue().build()) {
                    if (mesh == null) continue;
                    ByteBuffer vertices = mesh.vertexBuffer();
                    ByteBuffer copy = MemoryUtil.memAlloc(vertices.remaining());
                    MemoryUtil.memCopy(vertices, copy);
                    result.layers.put(e.getKey(), copy);
                    if (grouped && FaceGroups.grouped(e.getKey())) {
                        FaceGroups g = FaceGroups.of(copy, DefaultVertexFormat.BLOCK.getVertexSize());
                        if (g != null) result.groups.put(e.getKey(), g);
                    }
                    // FAR_GPU_CULLING reads them; made whatever the switch says (one pass over the vertices), so flipping it
                    // never meshes the far chunks again
                    Clusters c = Clusters.of(copy, DefaultVertexFormat.BLOCK.getVertexSize(), size);
                    if (c != null) result.clusters.put(e.getKey(), c);
                }
            }
        } catch (RuntimeException | Error e) {
            result.free();
            throw e;
        } finally {
            ModelBlockRenderer.clearCache();
            for (ByteBufferBuilder b : bytes.values()) b.close();
        }
        return result;
    }

    private static BufferBuilder builder(ChunkSectionLayer layer, Map<ChunkSectionLayer, ByteBufferBuilder> bytes,
                                         Map<ChunkSectionLayer, BufferBuilder> builders) {
        BufferBuilder builder = builders.get(layer);
        if (builder == null) {
            ByteBufferBuilder memory = new ByteBufferBuilder(64 * 1024);
            bytes.put(layer, memory);
            builder = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            builders.put(layer, builder);
        }
        return builder;
    }

    /** {@code renderLiquid} writes section-relative positions; a consumer in between moves them into the region. */
    private static final class LiquidOffset {
        static void write(BlockRenderDispatcher dispatcher, BlockPos pos, ColumnGetter getter, BufferBuilder buffer, BlockState state,
                          FluidState fluid, float dx, float dy, float dz, float scale) {
            dispatcher.renderLiquid(pos, getter, new Shifted(buffer, dx, dy, dz, scale), state, fluid);
        }
    }

    /** A vertex consumer adding an offset to every position, then scaling it (grid cubes to blocks). */
    private record Shifted(BufferBuilder to, float dx, float dy, float dz, float scale) implements com.mojang.blaze3d.vertex.VertexConsumer {
        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            return to.addVertex((x + dx) * scale, (y + dy) * scale, (z + dz) * scale);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return to.setColor(r, g, b, a);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            return to.setUv(u, v);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return to.setUv1(u, v);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return to.setUv2(u, v);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            return to.setNormal(x, y, z);
        }

    }
}
