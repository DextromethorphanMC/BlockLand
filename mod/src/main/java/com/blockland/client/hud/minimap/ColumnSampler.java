package com.blockland.client.hud.minimap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

import javax.annotation.Nullable;

/**
 * One column's colour on the minimap (UI spec §3.4), from the client's loaded chunks only (never loads anything):
 *
 * <ul>
 *   <li>surface: the top block's map colour, lit by the slope against its north and west neighbours (hills read as
 *   relief), water by its depth (the bed showing through where it is shallow);</li>
 *   <li>layer (a roof over the player — underground, the Nether): the floor under the player's height, darker the
 *   deeper it lies, walls at head height dark, nothing within reach near black.</li>
 * </ul>
 */
final class ColumnSampler implements MinimapGrid.Sampler {
    /** A column with nothing to show (the End's void): drawn clear, and not looked for again. */
    static final int VOID = 0x00000001;
    private static final int WATER_SHALLOW = 0x3D7CC0, WATER_DEEP = 0x14305E;
    private static final int WALL = 0xFF2A2C31, DARK = 0xFF0D0E11;
    /** How far under the player's height the layer looks for a floor. */
    private static final int LAYER_DEPTH = 24;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ClientLevel level;
    private boolean layer;
    private int layerY;
    // the last chunk looked at (columns come in runs along a ring)
    private int chunkX = Integer.MIN_VALUE, chunkZ;
    @Nullable
    private LevelChunk chunk;

    /** What the next samples read: the level, and the surface or the layer at {@code y}. */
    void use(ClientLevel level, boolean layer, int y) {
        if (level != this.level) chunk = null;
        if (level != this.level) chunkX = Integer.MIN_VALUE;
        this.level = level;
        this.layer = layer;
        this.layerY = y;
    }

    @Nullable
    private LevelChunk chunk(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (cx != chunkX || cz != chunkZ || chunk == null) {
            chunkX = cx;
            chunkZ = cz;
            chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        }
        return chunk;
    }

    @Override
    public int sample(int x, int z) {
        LevelChunk c = chunk(x, z);
        if (c == null) return MinimapGrid.NO_CHUNK;
        return layer ? layer(c, x, z) : surface(c, x, z);
    }

    // ---- surface -------------------------------------------------------------------------------

    private int surface(LevelChunk c, int x, int z) {
        int minY = level.getMinY();
        int y = c.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (y < minY) return VOID;
        BlockState state = c.getBlockState(pos.set(x, y, z));
        MapColor color = state.getMapColor(level, pos);
        // glass and the like have no map colour: what is under them
        for (int i = 0; color == MapColor.NONE && i < 12 && y > minY; i++) {
            state = c.getBlockState(pos.set(x, --y, z));
            color = state.getMapColor(level, pos);
        }
        if (color == MapColor.NONE) return VOID;
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER)) return water(c, x, y, z, minY);
        // relief: brighter facing north-west, darker facing south-east
        int north = height(x, z - 1, y), west = height(x - 1, z, y);
        float slope = (y - north) + (y - west);
        return shade(color.col, 1 + Mth.clamp(slope * 0.06f, -0.28f, 0.22f));
    }

    /** Water at ({@code x}, {@code top}, {@code z}): deeper is darker; a shallow bed shows through. */
    private int water(LevelChunk c, int x, int top, int z, int minY) {
        int depth = 0, y = top;
        BlockState bed;
        do {
            y--;
            depth++;
            bed = c.getBlockState(pos.set(x, y, z));
        } while (depth < 16 && y > minY && bed.getFluidState().is(FluidTags.WATER));
        float deep = Mth.clamp(depth / 12f, 0, 1);
        int water = lerp(WATER_SHALLOW, WATER_DEEP, deep);
        if (depth <= 2) {
            MapColor under = bed.getMapColor(level, pos);
            if (under != MapColor.NONE) water = lerp(under.col, water, depth == 1 ? 0.55f : 0.72f);
        }
        return 0xFF000000 | water;
    }

    /** The surface height at (x, z), or {@code fallback} where its chunk is not loaded. */
    private int height(int x, int z, int fallback) {
        LevelChunk c = chunk(x, z);
        return c == null ? fallback : c.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    // ---- layer ---------------------------------------------------------------------------------

    private int layer(LevelChunk c, int x, int z) {
        int minY = level.getMinY();
        BlockState head = c.getBlockState(pos.set(x, layerY + 1, z));
        if (head.blocksMotion()) {
            MapColor wall = head.getMapColor(level, pos);
            return wall == MapColor.NONE ? WALL : shade(wall.col, 0.42f);
        }
        for (int y = layerY; y >= layerY - LAYER_DEPTH && y >= minY; y--) {
            BlockState state = c.getBlockState(pos.set(x, y, z));
            FluidState fluid = state.getFluidState();
            if (state.blocksMotion() || !fluid.isEmpty()) {
                MapColor color = fluid.is(FluidTags.WATER) ? MapColor.WATER : state.getMapColor(level, pos);
                if (color == MapColor.NONE) continue;
                int base = fluid.is(FluidTags.WATER) ? WATER_SHALLOW : color.col;
                return shade(base, Math.max(0.35f, 1 - (layerY - y) * 0.03f));
            }
        }
        return DARK;
    }

    // ---- colour --------------------------------------------------------------------------------

    private static int shade(int rgb, float k) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((rgb & 0xFF) * k));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int lerp(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return r << 16 | g << 8 | bl;
    }
}
