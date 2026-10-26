package com.blockland.client.graphics.far;

import com.blockland.farterrain.FarTile;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * Turns a far tile into block columns the way Minecraft draws terrain (rendering spec §22), on a worker thread: tops
 * merged greedily where height, look and sprite agree, walls between cells of different height (a one-block band of the top
 * block's side, the block under it below; the band left out where cells are wider than 4 blocks), merged along their edge
 * when they match, and skirts at the tile's borders reaching below the neighbour's height (or 2 cells deep where it is not
 * known) so tiles of different levels side by side never leave a gap. Vertices: {@link FarVertex}, quads in the order the
 * shared quad index buffer expects (front faces counter-clockwise, outward).
 */
final class MeshBuilder {
    /** A finished mesh in native memory (free with {@link #free}), its quads and the height range it spans. */
    static final class Result {
        ByteBuffer data;
        final int quads, minY, maxY;

        Result(ByteBuffer data, int quads, int minY, int maxY) {
            this.data = data;
            this.quads = quads;
            this.minY = minY;
            this.maxY = maxY;
        }

        void free() {
            if (data != null) MemoryUtil.memFree(data);
            data = null;
        }
    }

    /** The same-level neighbours' border heights (64 each, along the shared edge), null where unknown. */
    record Borders(@Nullable short[] north, @Nullable short[] south, @Nullable short[] west, @Nullable short[] east) {
    }

    private static final int S = FarTile.SIZE;
    private static final long NONE = Long.MIN_VALUE;

    private final FarTile tile;
    private final TileColors.Palette palette;
    private final Borders borders;
    private final int floorY;
    private final int cell;
    private final int skirt;
    private ByteBuffer buffer;
    private long address;
    private int quads, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
    /** The foot of the wall being written (its vertices there are marked), or none for tops. */
    private int wallFoot = Integer.MIN_VALUE;

    private MeshBuilder(FarTile tile, TileColors.Palette palette, Borders borders, int floorY) {
        this.tile = tile;
        this.palette = palette;
        this.borders = borders;
        this.floorY = floorY;
        this.cell = FarTile.cell(tile.level);
        this.skirt = Math.max(8, 2 * cell);
    }

    /** Builds the mesh of a tile; {@code floorY} is the dimension's lowest y (nothing reaches below it). */
    static Result build(FarTile tile, TileColors.Palette palette, Borders borders, int floorY) {
        MeshBuilder builder = new MeshBuilder(tile, palette, borders, floorY);
        try {
            builder.run();
        } catch (RuntimeException | Error e) {
            if (builder.buffer != null) MemoryUtil.memFree(builder.buffer);
            throw e;
        }
        if (builder.quads == 0) {
            if (builder.buffer != null) MemoryUtil.memFree(builder.buffer);
            return new Result(null, 0, 0, 0);
        }
        builder.buffer.limit(builder.quads * 4 * FarVertex.BYTES);
        builder.buffer.position(0);
        return new Result(builder.buffer, builder.quads, builder.minY, builder.maxY);
    }

    private void run() {
        long[] keys = new long[FarTile.CELLS];
        // a face's look: its sprite code (high word, 0 = none) and its colour (the tint with a sprite, else the average)
        long[] side = new long[FarTile.CELLS], below = new long[FarTile.CELLS];
        int[] topSprite = new int[FarTile.CELLS];
        for (int c = 0; c < FarTile.CELLS; c++) {
            if (tile.isEmpty(c)) {
                keys[c] = NONE;
                continue;
            }
            int topState = tile.top[c] & 0xFF, underState = tile.under[c] & 0xFF, biome = tile.biome[c] & 0xFF;
            biome = Math.min(biome, palette.biomes() - 1);
            int depth = tile.waterDepth(c);
            boolean water = depth > 0 || palette.water()[topState];
            int rgb, emission = palette.emission()[topState];
            if (water) {
                int surface = palette.waterColor()[biome];
                int floor = palette.top(underState, biome);
                rgb = waterOver(surface, floor, Math.max(1, depth));
                side[c] = surface;
                below[c] = surface;
                emission = 0;
            } else {
                topSprite[c] = palette.topSprite()[topState];
                rgb = topSprite[c] != 0 ? palette.topTint(topState, biome) : palette.top(topState, biome);
                side[c] = sideLook(topState, biome);
                below[c] = sideLook(underState, biome);
            }
            keys[c] = (long) (tile.height[c] & 0xFFFF) << 32 | (long) rgb << 5 | (water ? 16 : 0) | emission;
        }
        tops(keys, topSprite);
        walls(keys, side, below);
    }

    private long sideLook(int state, int biome) {
        int sprite = palette.sideSprite()[state];
        int rgb = sprite != 0 ? palette.sideTint(state, biome) : palette.side(state, biome);
        return (long) sprite << 32 | rgb;
    }

    /** What deep and shallow water look like from above: the floor shows through shallow water, dimmed by its depth. */
    private static int waterOver(int water, int floor, int depth) {
        float level = Math.max(0, 15 - depth) / 15f;
        float lit = level / (4 - 3 * level);
        float t = 0.32f * lit;
        int r = Math.round((water >> 16 & 0xFF) * (1 - t) + (floor >> 16 & 0xFF) * t);
        int g = Math.round((water >> 8 & 0xFF) * (1 - t) + (floor >> 8 & 0xFF) * t);
        int b = Math.round((water & 0xFF) * (1 - t) + (floor & 0xFF) * t);
        return r << 16 | g << 8 | b;
    }

    /** Greedy rectangles of cells with the same key (height, colour, water, emission) and sprite. */
    private void tops(long[] keys, int[] sprites) {
        boolean[] done = new boolean[FarTile.CELLS];
        for (int j = 0; j < S; j++) {
            for (int i = 0; i < S; i++) {
                int c = FarTile.index(i, j);
                long key = keys[c];
                if (done[c] || key == NONE) continue;
                int sprite = sprites[c];
                int w = 1;
                while (i + w < S && !done[c + w] && keys[c + w] == key && sprites[c + w] == sprite) w++;
                int h = 1;
                grow:
                while (j + h < S) {
                    int row = FarTile.index(i, j + h);
                    for (int k = 0; k < w; k++) {
                        if (done[row + k] || keys[row + k] != key || sprites[row + k] != sprite) break grow;
                    }
                    h++;
                }
                for (int dz = 0; dz < h; dz++) for (int dx = 0; dx < w; dx++) done[FarTile.index(i + dx, j + dz)] = true;
                int y = (short) (key >>> 32);
                int rgb = (int) (key >>> 5) & 0xFFFFFF;
                boolean water = (key & 16) != 0;
                int look = FarVertex.look(rgb, FarVertex.UP, water, (int) (key & 15));
                quad(look, sprite,
                        i, y, j,
                        i, y, j + h,
                        i + w, y, j + h,
                        i + w, y, j);
            }
        }
    }

    /**
     * Walls on the four sides of every cell where the neighbour stands lower (inside the tile), and skirts on the tile's
     * borders; runs of matching walls along an edge become one quad.
     */
    private void walls(long[] keys, long[] side, long[] below) {
        // faces toward -z (north) and +z (south): runs along x within each row
        for (int j = 0; j < S; j++) {
            for (int dir = 0; dir < 2; dir++) {
                int face = dir == 0 ? FarVertex.NORTH : FarVertex.SOUTH;
                int runStart = -1, runTop = 0, runBottom = 0;
                long runBand = 0, runBelow = 0;
                for (int i = 0; i <= S; i++) {
                    int top = 0, bottom = 0;
                    long band = 0, under = 0;
                    boolean wall = false;
                    if (i < S) {
                        int c = FarTile.index(i, j);
                        if (keys[c] != NONE) {
                            top = tile.height[c];
                            bottom = neighbourBottom(keys, i, j, 0, dir == 0 ? -1 : 1, top);
                            if (bottom < top) {
                                wall = true;
                                band = side[c];
                                under = below[c];
                            }
                        }
                    }
                    if (runStart >= 0 && (!wall || top != runTop || bottom != runBottom || band != runBand || under != runBelow)) {
                        int z = dir == 0 ? j : j + 1;
                        emitWall(face, runStart, z, i, z, runTop, runBottom, runBand, runBelow);
                        runStart = -1;
                    }
                    if (wall && runStart < 0) {
                        runStart = i;
                        runTop = top;
                        runBottom = bottom;
                        runBand = band;
                        runBelow = under;
                    }
                }
            }
        }
        // faces toward -x (west) and +x (east): runs along z within each column
        for (int i = 0; i < S; i++) {
            for (int dir = 0; dir < 2; dir++) {
                int face = dir == 0 ? FarVertex.WEST : FarVertex.EAST;
                int runStart = -1, runTop = 0, runBottom = 0;
                long runBand = 0, runBelow = 0;
                for (int j = 0; j <= S; j++) {
                    int top = 0, bottom = 0;
                    long band = 0, under = 0;
                    boolean wall = false;
                    if (j < S) {
                        int c = FarTile.index(i, j);
                        if (keys[c] != NONE) {
                            top = tile.height[c];
                            bottom = neighbourBottom(keys, i, j, dir == 0 ? -1 : 1, 0, top);
                            if (bottom < top) {
                                wall = true;
                                band = side[c];
                                under = below[c];
                            }
                        }
                    }
                    if (runStart >= 0 && (!wall || top != runTop || bottom != runBottom || band != runBand || under != runBelow)) {
                        int x = dir == 0 ? i : i + 1;
                        emitWall(face, x, runStart, x, j, runTop, runBottom, runBand, runBelow);
                        runStart = -1;
                    }
                    if (wall && runStart < 0) {
                        runStart = j;
                        runTop = top;
                        runBottom = bottom;
                        runBand = band;
                        runBelow = under;
                    }
                }
            }
        }
    }

    /**
     * How far down the wall on this side of cell (i, j) reaches: the neighbour's top inside the tile (no wall when it is
     * as high); across the tile's border the lower of the neighbour's top and a skirt; next to an empty cell a skirt.
     */
    private int neighbourBottom(long[] keys, int i, int j, int di, int dj, int top) {
        int ni = i + di, nj = j + dj;
        int bottom;
        if (ni < 0 || nj < 0 || ni >= S || nj >= S) {
            short[] border = di < 0 ? borders.west() : di > 0 ? borders.east() : dj < 0 ? borders.north() : borders.south();
            int along = di != 0 ? j : i;
            int skirtBottom = top - skirt;
            if (border != null && border[along] != FarTile.EMPTY) bottom = Math.min(border[along], skirtBottom);
            else bottom = skirtBottom;
        } else {
            int n = FarTile.index(ni, nj);
            bottom = keys[n] == NONE ? top - skirt : tile.height[n];
        }
        return Math.max(floorY, bottom);
    }

    /** A wall from (x0, z0) to (x1, z1) in cells, between y bottom and top, facing {@code face}. */
    private void emitWall(int face, int x0, int z0, int x1, int z1, int top, int bottom, long band, long under) {
        wallFoot = bottom;
        emitWallQuads(face, x0, z0, x1, z1, top, bottom, band, under);
        wallFoot = Integer.MIN_VALUE;
    }

    private void emitWallQuads(int face, int x0, int z0, int x1, int z1, int top, int bottom, long band, long under) {
        int height = top - bottom;
        if (height <= 2) {
            // a step of a block or two shows the top block's side (a grass block's green-edged dirt)
            wallQuad(face, x0, z0, x1, z1, top, bottom, band);
        } else if (cell <= 4) {
            wallQuad(face, x0, z0, x1, z1, top, top - 1, band);
            wallQuad(face, x0, z0, x1, z1, top - 1, bottom, under);
        } else {
            // a block-high band is less than a pixel on cells this wide
            wallQuad(face, x0, z0, x1, z1, top, bottom, under);
        }
    }

    private void wallQuad(int face, int x0, int z0, int x1, int z1, int hi, int lo, long faceLook) {
        if (hi <= lo) return;
        int look = FarVertex.look((int) faceLook & 0xFFFFFF, face, false, 0), sprite = (int) (faceLook >>> 32);
        switch (face) {
            case FarVertex.NORTH -> quad(look, sprite, x1, hi, z0, x1, lo, z0, x0, lo, z0, x0, hi, z0);
            case FarVertex.SOUTH -> quad(look, sprite, x0, hi, z1, x0, lo, z1, x1, lo, z1, x1, hi, z1);
            case FarVertex.WEST -> quad(look, sprite, x0, hi, z0, x0, lo, z0, x0, lo, z1, x0, hi, z1);
            default -> quad(look, sprite, x1, hi, z1, x1, lo, z1, x1, lo, z0, x1, hi, z0);
        }
    }

    private void quad(int look, int sprite, int ax, int ay, int az, int bx, int by, int bz, int cx, int cy, int cz, int dx, int dy, int dz) {
        ensure(quads + 1);
        long at = address + (long) quads * 4 * FarVertex.BYTES;
        FarVertex.put(at, position(ax, ay, az), look, sprite);
        FarVertex.put(at + FarVertex.BYTES, position(bx, by, bz), look, sprite);
        FarVertex.put(at + 2L * FarVertex.BYTES, position(cx, cy, cz), look, sprite);
        FarVertex.put(at + 3L * FarVertex.BYTES, position(dx, dy, dz), look, sprite);
        quads++;
        minY = Math.min(minY, Math.min(Math.min(ay, by), Math.min(cy, dy)));
        maxY = Math.max(maxY, Math.max(Math.max(ay, by), Math.max(cy, dy)));
    }

    private int position(int x, int y, int z) {
        int p = FarVertex.position(x, y, z);
        return y == wallFoot ? FarVertex.foot(p) : p;
    }

    private void ensure(int quadCount) {
        int bytes = quadCount * 4 * FarVertex.BYTES;
        if (buffer != null && buffer.capacity() >= bytes) return;
        int capacity = Math.max(bytes, buffer == null ? 64 * 1024 : buffer.capacity() * 2);
        buffer = buffer == null ? MemoryUtil.memAlloc(capacity) : MemoryUtil.memRealloc(buffer, capacity);
        address = MemoryUtil.memAddress(buffer);
    }
}
