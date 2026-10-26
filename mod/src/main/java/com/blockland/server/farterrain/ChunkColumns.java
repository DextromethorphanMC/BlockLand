package com.blockland.server.farterrain;

import com.blockland.farterrain.FarTile;
import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The 256 columns of one chunk as far terrain sees them (rendering spec §22), taken from a loaded chunk on the server
 * thread ({@link #capture}) or from its saved data on a worker ({@link #parse}); immutable once made, so it can be handed
 * to a worker. Per column: the height of the top face (the topmost motion-blocking block + 1, leaves and fluids
 * included; {@link FarTile#EMPTY} for a void column), the top block (a snow layer or carpet lying on it instead; water
 * as plain water), the block under it (3 below on land, leaves under leaves; the floor under water), the water's depth
 * and the biome at the top.
 */
final class ChunkColumns {
    static final int WATER = Block.getId(Blocks.WATER.defaultBlockState());
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    final int cx, cz;
    final short[] height = new short[256];
    final int[] top = new int[256], under = new int[256];
    final byte[] water = new byte[256];
    final byte[] biome = new byte[256];
    final List<String> biomes = new ArrayList<>(4);

    private ChunkColumns(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    long chunk() {
        return Chunks.key(cx, cz);
    }

    /** A thin cover that changes how the top looks from afar (snow, carpets); plants never replace the top. */
    static boolean isCover(BlockState state) {
        return state.is(Blocks.SNOW) || state.is(BlockTags.WOOL_CARPETS) || state.is(Blocks.MOSS_CARPET) || state.is(Blocks.PALE_MOSS_CARPET);
    }

    private int biomeIndex(String id) {
        int i = biomes.indexOf(id);
        if (i >= 0) return i;
        if (biomes.size() >= 255) return 0;
        biomes.add(id);
        return biomes.size() - 1;
    }

    String biomeAt(int column) {
        return biomes.get(biome[column] & 0xFF);
    }

    static String biomeId(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
    }

    /** The columns of a loaded chunk; server thread only (reads the chunk's sections and heightmaps). */
    static ChunkColumns capture(LevelChunk chunk) {
        ChunkColumns c = new ChunkColumns(chunk.getPos().x, chunk.getPos().z);
        int minY = chunk.getMinY(), maxY = chunk.getMaxY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x0 = c.cx << 4, z0 = c.cz << 4;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lx + lz * 16;
                int x = x0 + lx, z = z0 + lz;
                int topY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);
                if (topY < minY) {
                    c.height[i] = FarTile.EMPTY;
                    continue;
                }
                BlockState top = chunk.getBlockState(pos.set(x, topY, z));
                if (top.getFluidState().is(FluidTags.WATER)) {
                    int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz);
                    BlockState bottom = floor >= minY ? chunk.getBlockState(pos.set(x, floor, z)) : Blocks.AIR.defaultBlockState();
                    c.set(i, topY + 1, WATER, Block.getId(bottom), Math.max(1, topY - Math.max(floor, minY - 1)));
                } else {
                    BlockState shown = top;
                    if (topY + 1 <= maxY) {
                        BlockState cover = chunk.getBlockState(pos.set(x, topY + 1, z));
                        if (isCover(cover)) shown = cover;
                    }
                    BlockState below = top.is(BlockTags.LEAVES) || topY - 3 < minY ? top : chunk.getBlockState(pos.set(x, topY - 3, z));
                    if (below.isAir()) below = top;
                    c.set(i, topY + 1, Block.getId(shown), Block.getId(below), 0);
                }
                c.biome[i] = (byte) c.biomeIndex(biomeId(chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(topY), QuartPos.fromBlock(z))));
            }
        }
        return c;
    }

    private void set(int i, int topFace, int topState, int underState, int depth) {
        height[i] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, topFace));
        top[i] = topState;
        under[i] = underState;
        water[i] = (byte) Math.max(0, Math.min(255, depth));
    }

    /** Why a saved chunk gave no columns. */
    enum Refusal { NONE, NOT_FULL }

    /**
     * The columns of a saved chunk ({@code ChunkMap.read}'s raw tag); worker thread. Null with {@code refusal[0]} set when
     * the chunk is not fully generated or is not of the current game version ({@link SavedChunks#current} brings older
     * ones up to date first; it is then estimated, and captured when it loads).
     */
    @Nullable
    static ChunkColumns parse(ServerLevel level, Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec, CompoundTag tag, Refusal[] refusal) {
        refusal[0] = Refusal.NOT_FULL;
        if (NbtUtils.getDataVersion(tag, -1) != SharedConstants.getCurrentVersion().dataVersion().version()) return null;
        if (!"minecraft:full".equals(tag.getString("Status").orElse(""))) return null;
        int cx = tag.getIntOr("xPos", 0), cz = tag.getIntOr("zPos", 0);
        Optional<CompoundTag> maps = tag.getCompound("Heightmaps");
        if (maps.isEmpty()) return null;
        long[] motion = maps.get().getLongArray(Heightmap.Types.MOTION_BLOCKING.getSerializationKey()).orElse(null);
        long[] ocean = maps.get().getLongArray(Heightmap.Types.OCEAN_FLOOR.getSerializationKey()).orElse(null);
        if (motion == null || ocean == null) return null;
        int minY = level.getMinY(), maxY = level.getMaxY();
        int bits = Mth.ceillog2(level.getHeight() + 1);
        SimpleBitStorage motionMap, oceanMap;
        try {
            motionMap = new SimpleBitStorage(bits, 256, motion);
            oceanMap = new SimpleBitStorage(bits, 256, ocean);
        } catch (RuntimeException e) {
            return null;
        }
        Map<Integer, CompoundTag> raw = new HashMap<>();
        ListTag sections = tag.getListOrEmpty("sections");
        for (int s = 0; s < sections.size(); s++) {
            sections.getCompound(s).ifPresent(section -> raw.put((int) section.getByteOr("Y", (byte) 0), section));
        }
        Sections blocks = new Sections(raw, biomeCodec);
        ChunkColumns c = new ChunkColumns(cx, cz);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lx + lz * 16;
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                int topY = motionMap.get(i) + minY - 1;
                if (topY < minY) {
                    c.height[i] = FarTile.EMPTY;
                    continue;
                }
                BlockState top = blocks.state(lx, topY, lz);
                if (top.getFluidState().is(FluidTags.WATER)) {
                    int floor = oceanMap.get(i) + minY - 1;
                    BlockState bottom = floor >= minY ? blocks.state(lx, floor, lz) : Blocks.AIR.defaultBlockState();
                    c.set(i, topY + 1, WATER, Block.getId(bottom), Math.max(1, topY - Math.max(floor, minY - 1)));
                } else {
                    BlockState shown = top;
                    if (topY + 1 <= maxY) {
                        BlockState cover = blocks.state(lx, topY + 1, lz);
                        if (isCover(cover)) shown = cover;
                    }
                    BlockState below = top.is(BlockTags.LEAVES) || topY - 3 < minY ? top : blocks.state(lx, topY - 3, lz);
                    if (below.isAir()) below = top;
                    c.set(i, topY + 1, Block.getId(shown), Block.getId(below), 0);
                }
                Holder<Biome> biome = blocks.biome(lx, topY, lz);
                c.biome[i] = (byte) c.biomeIndex(biome == null ? "minecraft:plains" : biomeId(biome));
            }
        }
        refusal[0] = Refusal.NONE;
        return c;
    }

    /** The biome codec of a level's registry (saved sections store biomes by name). */
    static Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec(ServerLevel level) {
        Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        return PalettedContainer.codecRO(registry.asHolderIdMap(), registry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES,
                registry.getOrThrow(Biomes.PLAINS));
    }

    /** A saved chunk's sections, each decoded the first time one of its blocks is needed. */
    private static final class Sections {
        private final Map<Integer, CompoundTag> raw;
        private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
        private final Map<Integer, PalettedContainer<BlockState>> states = new HashMap<>();
        private final Map<Integer, PalettedContainerRO<Holder<Biome>>> biomes = new HashMap<>();

        Sections(Map<Integer, CompoundTag> raw, Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec) {
            this.raw = raw;
            this.biomeCodec = biomeCodec;
        }

        BlockState state(int lx, int y, int lz) {
            int sy = y >> 4;
            PalettedContainer<BlockState> container = states.computeIfAbsent(sy, k -> {
                CompoundTag section = raw.get(k);
                if (section == null) return null;
                return section.getCompound("block_states").flatMap(t -> STATES.parse(NbtOps.INSTANCE, t).result()).orElse(null);
            });
            return container == null ? Blocks.AIR.defaultBlockState() : container.get(lx, y & 15, lz);
        }

        @Nullable
        Holder<Biome> biome(int lx, int y, int lz) {
            int sy = y >> 4;
            PalettedContainerRO<Holder<Biome>> container = biomes.computeIfAbsent(sy, k -> {
                CompoundTag section = raw.get(k);
                if (section == null) return null;
                return section.getCompound("biomes").flatMap(t -> biomeCodec.parse(NbtOps.INSTANCE, t).result()).orElse(null);
            });
            return container == null ? null : container.get((lx >> 2) & 3, (y >> 2) & 3, (lz >> 2) & 3);
        }
    }
}
