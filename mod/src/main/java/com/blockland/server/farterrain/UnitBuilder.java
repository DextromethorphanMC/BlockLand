package com.blockland.server.farterrain;

import com.blockland.farterrain.FarColumn;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;

/**
 * Real far chunk units of scale 1 and coarser (rendering spec §23), made from the four units one scale finer: every 2 × 2 × 2
 * cubes of the children become one cube ({@link UnitMath#pick}: a cube of scale k so stands for its 2<sup>k</sup> real
 * blocks), with the brightest sky and block light of the eight and the children's biome at the middle of each biome
 * cell; which chunks exist is the union of the children's. Also cuts a unit to a player's view limit ({@link #clip}).
 * Worker threads; only the children's bytes are read, never the level.
 */
final class UnitBuilder {
    private UnitBuilder() {
    }

    /** One child decoded: its sections (null = air) and light, and its highest block (cube y, for light it lacks). */
    private static final class Child {
        final FarColumn column;
        final LevelChunkSection[] sections;
        int top = Integer.MIN_VALUE;

        Child(FarColumn column, Registry<Biome> biomes) {
            this.column = column;
            this.sections = new LevelChunkSection[column.sections.length];
            for (int i = 0; i < sections.length; i++) {
                if (column.sections[i] == null) continue;
                sections[i] = UnitBuilder.section(column.sections[i], biomes);
                if (!sections[i].hasOnlyAir()) top = Math.max(top, highest(sections[i], (column.minSection + i) << 4));
            }
        }

        /** The section at section y {@code abs} (in this child's cubes), or null. */
        @Nullable
        LevelChunkSection section(int abs) {
            int i = abs - column.minSection;
            return i >= 0 && i < sections.length ? sections[i] : null;
        }

        @Nullable
        byte[] sky(int abs) {
            int i = abs - column.minSection;
            return i >= 0 && i < sections.length ? column.skyLight[i] : null;
        }

        @Nullable
        byte[] block(int abs) {
            int i = abs - column.minSection;
            return i >= 0 && i < sections.length ? column.blockLight[i] : null;
        }

        /** Sky light of a cube where the save has none, as the client takes it: open above the highest block, dark below. */
        int derivedSky(int cubeY) {
            return cubeY > top ? 15 : 0;
        }
    }

    static LevelChunkSection section(byte[] bytes, Registry<Biome> biomes) {
        LevelChunkSection section = new LevelChunkSection(biomes);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        section.read(buf);
        return section;
    }

    private static int highest(LevelChunkSection section, int baseY) {
        for (int y = 15; y >= 0; y--) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!section.getBlockState(x, y, z).isAir()) return baseY + y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /** What a cube holds for {@link UnitMath#pick}. */
    static byte kind(BlockState state) {
        if (state.isAir()) return UnitMath.AIR;
        if (state.getBlock() instanceof LiquidBlock) {
            FluidState fluid = state.getFluidState();
            // flowing fluid is not counted (a waterfall is no wall from afar); a source is
            if (!fluid.isSource()) return UnitMath.AIR;
            return fluid.getType().isSame(Fluids.WATER) ? UnitMath.WATER : UnitMath.BLOCK;
        }
        return state.is(Blocks.BUBBLE_COLUMN) ? UnitMath.WATER : UnitMath.BLOCK;
    }

    /**
     * The unit {@code key} (scale ≥ 1) of a level whose blocks span {@code minY} … {@code minY + height − 1}, from its
     * children's encoded bytes (index dx + 2 dz; null = none of the child exists).
     */
    static FarColumn build(Registry<Biome> biomes, long key, int minY, int height, byte[][] children) {
        int scale = FarColumn.scale(key);
        FarColumn unit = new FarColumn(scale, FarColumn.x(key), FarColumn.z(key), FarColumn.minSection(minY, scale),
                FarColumn.sectionCount(minY, height, scale));
        Child[] kids = new Child[4];
        long[][] masks = new long[4][];
        boolean[] exists = new boolean[4];
        for (int i = 0; i < 4; i++) {
            if (children[i] == null) continue;
            FarColumn c = FarColumn.decode(children[i]);
            if (c.scale != scale - 1 || c.key() != FarColumn.child(key, i)) throw new IllegalArgumentException("far chunk child " + i + " of " + key + " is " + c.key());
            kids[i] = new Child(c, biomes);
            masks[i] = c.present;
            exists[i] = c.presentCount() > 0;
        }
        unit.present = UnitMath.union(scale, masks, exists);
        if (unit.presentCount() == 0) return unit;
        Holder<Biome> plains = biomes.getOrThrow(Biomes.PLAINS);
        BlockState air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        Object[] members = new Object[8];
        byte[] kinds = new byte[8];
        for (int s = 0; s < unit.sections.length; s++) {
            int abs = unit.minSection + s;
            boolean blocks = false, sky = false, block = false;
            for (Child k : kids) {
                if (k == null) continue;
                for (int h = 0; h < 2; h++) {
                    blocks |= k.section(2 * abs + h) != null;
                    sky |= k.sky(2 * abs + h) != null;
                    block |= k.block(2 * abs + h) != null;
                }
            }
            if (blocks) {
                PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
                boolean any = false;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            Child k = kids[(x >> 3) + 2 * (z >> 3)];
                            LevelChunkSection cs = k == null ? null : k.section(2 * abs + (y >> 3));
                            if (cs == null) continue;
                            int bx = 2 * (x & 7), by = 2 * (y & 7), bz = 2 * (z & 7);
                            for (int i = 0; i < 8; i++) {
                                BlockState m = cs.getBlockState(bx + (i & 1), by + (i >> 2), bz + (i >> 1 & 1));
                                members[i] = m;
                                kinds[i] = kind(m);
                            }
                            int pick = UnitMath.pick(members, kinds);
                            if (pick == UnitMath.PICK_AIR) continue;
                            states.getAndSetUnchecked(x, y, z, pick == UnitMath.PICK_WATER ? water : (BlockState) members[pick]);
                            any = true;
                        }
                    }
                }
                if (any) {
                    PalettedContainer<Holder<Biome>> cells = new PalettedContainer<>(biomes.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES);
                    for (int j = 0; j < 4; j++) {
                        for (int k = 0; k < 4; k++) {
                            for (int i = 0; i < 4; i++) {
                                cells.getAndSetUnchecked(i, j, k, biome(kids, abs, 4 * i + 2, 4 * j + 2, 4 * k + 2, plains));
                            }
                        }
                    }
                    LevelChunkSection section = new LevelChunkSection(states, cells);
                    if (!section.hasOnlyAir()) unit.sections[s] = ColumnService.sectionBytes(section);
                }
            }
            if (sky) unit.skyLight[s] = light(kids, abs, true);
            if (block) unit.blockLight[s] = light(kids, abs, false);
        }
        return unit;
    }

    /** The children's biome at the middle of a biome cell (cube x, y, z of the section {@code abs}). */
    private static Holder<Biome> biome(Child[] kids, int abs, int x, int y, int z, Holder<Biome> fallback) {
        Child k = kids[(x >> 3) + 2 * (z >> 3)];
        int cx = 2 * (x & 7) + 1, cz = 2 * (z & 7) + 1;
        int cy = 2 * (abs * 16 + y) + 1;
        if (k != null) {
            LevelChunkSection cs = k.section(cy >> 4);
            if (cs != null) return cs.getNoiseBiome(cx >> 2, (cy & 15) >> 2, cz >> 2);
            // an air section keeps no biome worth having: the nearest section of the child that holds blocks
            for (int d = 1; d < k.sections.length; d++) {
                LevelChunkSection below = k.section((cy >> 4) - d), above = k.section((cy >> 4) + d);
                if (below != null) return below.getNoiseBiome(cx >> 2, 3, cz >> 2);
                if (above != null) return above.getNoiseBiome(cx >> 2, 0, cz >> 2);
            }
        }
        for (Child other : kids) {
            if (other == null) continue;
            for (LevelChunkSection cs : other.sections) if (cs != null) return cs.getNoiseBiome(1, 1, 1);
        }
        return fallback;
    }

    /** One light layer of the section {@code abs}: the brightest of each cube's eight; null for block light all dark. */
    @Nullable
    private static byte[] light(Child[] kids, int abs, boolean sky) {
        byte[] out = new byte[FarColumn.LIGHT_BYTES];
        boolean lit = false;
        for (int y = 0; y < 16; y++) {
            int childAbs = 2 * abs + (y >> 3), by = 2 * (y & 7);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Child k = kids[(x >> 3) + 2 * (z >> 3)];
                    int value;
                    if (k == null) {
                        // nothing of the child exists: open sky over it, as the client takes a column it has no light for
                        value = sky ? 15 : 0;
                    } else {
                        byte[] data = sky ? k.sky(childAbs) : k.block(childAbs);
                        int bx = 2 * (x & 7), bz = 2 * (z & 7);
                        value = 0;
                        for (int i = 0; i < 8 && value < 15; i++) {
                            int lx = bx + (i & 1), ly = by + (i >> 2), lz = bz + (i >> 1 & 1);
                            int v;
                            if (data == null) v = sky ? k.derivedSky((childAbs << 4) + ly) : 0;
                            else v = nibble(data, ly << 8 | lz << 4 | lx);
                            value = Math.max(value, v);
                        }
                    }
                    if (value != 0) {
                        lit = true;
                        int index = y << 8 | z << 4 | x;
                        out[index >> 1] |= (byte) (value << ((index & 1) << 2));
                    }
                }
            }
        }
        return lit || sky ? out : null;
    }

    private static int nibble(byte[] data, int index) {
        return data[index >> 1] >> ((index & 1) << 2) & 15;
    }

    /**
     * The unit cut to a view limit: its chunks that do not touch the limit are no longer there, and the cubes lying only
     * on such chunks are air (null: nothing of it is left). Its version is that of what is left.
     */
    @Nullable
    static FarColumn clip(Registry<Biome> biomes, byte[] bytes, BoundingBox limit) {
        FarColumn unit = FarColumn.decode(bytes);
        int scale = unit.scale, side = 1 << scale;
        long x0 = (long) unit.cx << scale, z0 = (long) unit.cz << scale;
        boolean[] gone = new boolean[side * side];
        boolean cut = false;
        for (int dz = 0; dz < side; dz++) {
            for (int dx = 0; dx < side; dx++) {
                if (UnitMath.chunkTouches(limit, x0 + dx, z0 + dz)) continue;
                gone[dx + dz * side] = true;
                if (unit.present(dx, dz)) {
                    unit.setPresent(dx, dz, false);
                    cut = true;
                }
            }
        }
        if (unit.presentCount() == 0) return null;
        if (!cut) return unit;
        // which cube columns lie only on chunks that are gone (a cube spans one chunk up to scale 4, several above)
        boolean[] clear = new boolean[256];
        boolean anyClear = false;
        int size = 1 << scale;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean all = true;
                for (int cz = z * size >> 4; cz <= (z * size + size - 1) >> 4 && all; cz++) {
                    for (int cx = x * size >> 4; cx <= (x * size + size - 1) >> 4 && all; cx++) all = gone[cx + cz * side];
                }
                clear[x + z * 16] = all;
                anyClear |= all;
            }
        }
        if (anyClear) {
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int s = 0; s < unit.sections.length; s++) {
                if (unit.sections[s] == null) continue;
                LevelChunkSection section = section(unit.sections[s], biomes);
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (clear[x + z * 16]) section.setBlockState(x, y, z, air, false);
                        }
                    }
                }
                unit.sections[s] = section.hasOnlyAir() ? null : ColumnService.sectionBytes(section);
            }
        }
        unit.version = 0;
        unit.computeVersion();
        return unit;
    }
}
