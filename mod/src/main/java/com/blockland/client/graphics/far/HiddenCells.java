package com.blockland.client.graphics.far;

import com.blockland.client.perf.terrain.FullFaces;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * {@code FAR_HIDDEN_GEOMETRY} (rendering spec §20, §23): the cells of a far unit nothing outside it can see. The camera is
 * never inside a far unit, so a line of sight reaches a cell only through open cells — every cell that is not a full
 * occluder on all six faces (the blocks whose faces vanilla's culling treats as hiding) — connected to the unit's sides,
 * top or bottom. A cell is hidden when neither it nor any of its six neighbours is such a reachable open cell, and it
 * does not lie on the unit's border (whatever lies beyond is unknown here): sealed caves with what is in them, and
 * blocks buried all round. Leaving their blocks out removes only geometry no pixel can show; everything drawn is meshed
 * exactly as before (the getter still answers the real blocks and light).
 * <p>
 * Cells in the unit's grid, index {@code (y * 16 + z) * 16 + x}, y from its lowest section. Worker thread.
 */
final class HiddenCells {
    private HiddenCells() {
    }

    /** One bit per cell, set where the cell is hidden. */
    static long[] of(ColumnGetter.Snapshot column) {
        LevelChunkSection[] sections = column.sections();
        int height = sections.length * 16, cells = height * 256;
        long[] open = new long[(cells + 63) >> 6];
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            int base = s * 16 * 256;
            for (int i = 0; i < 4096; i++) {
                if (section == null || FullFaces.of(section.getBlockState(i & 15, i >> 8, i >> 4 & 15)) != FullFaces.ALL) {
                    int c = base + i;
                    open[c >> 6] |= 1L << c;
                }
            }
        }
        return fromOpen(open, height);
    }

    /** The hidden cells of a grid {@code height} cells tall whose open cells are set in {@code open}. */
    static long[] fromOpen(long[] open, int height) {
        int cells = height * 256;
        // reachable open cells: a flood from the open cells on the unit's border
        long[] reach = new long[open.length];
        int[] queue = new int[cells];
        int head = 0, tail = 0;
        for (int c = 0; c < cells; c++) {
            if (!has(open, c) || !border(c, height)) continue;
            reach[c >> 6] |= 1L << c;
            queue[tail++] = c;
        }
        while (head < tail) {
            int c = queue[head++];
            int x = c & 15, z = c >> 4 & 15, y = c >> 8;
            if (x > 0) tail = visit(c - 1, open, reach, queue, tail);
            if (x < 15) tail = visit(c + 1, open, reach, queue, tail);
            if (z > 0) tail = visit(c - 16, open, reach, queue, tail);
            if (z < 15) tail = visit(c + 16, open, reach, queue, tail);
            if (y > 0) tail = visit(c - 256, open, reach, queue, tail);
            if (y < height - 1) tail = visit(c + 256, open, reach, queue, tail);
        }
        long[] hidden = new long[open.length];
        for (int c = 0; c < cells; c++) {
            if (has(reach, c) || border(c, height)) continue;
            // inside the unit (not on its border): all six neighbours exist
            if (has(reach, c - 1) || has(reach, c + 1) || has(reach, c - 16) || has(reach, c + 16) || has(reach, c - 256)
                    || has(reach, c + 256)) continue;
            hidden[c >> 6] |= 1L << c;
        }
        return hidden;
    }

    private static int visit(int c, long[] open, long[] reach, int[] queue, int tail) {
        if (has(reach, c) || !has(open, c)) return tail;
        reach[c >> 6] |= 1L << c;
        queue[tail] = c;
        return tail + 1;
    }

    private static boolean border(int c, int height) {
        int x = c & 15, z = c >> 4 & 15, y = c >> 8;
        return x == 0 || x == 15 || z == 0 || z == 15 || y == 0 || y == height - 1;
    }

    static boolean has(long[] bits, int c) {
        return (bits[c >> 6] >>> c & 1) != 0;
    }
}
