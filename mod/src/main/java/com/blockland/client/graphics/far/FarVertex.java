package com.blockland.client.graphics.far;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryUtil;

/**
 * The far terrain's vertex (rendering spec §22): 8 bytes, one integer element {@code Packed} (uvec2 in far_terrain.vsh).
 * Word 0: x (bits 0–6) and z (7–13) in cells of the tile, 0 … 64, y + 32768 (14–29) in blocks, and (30) the foot of a
 * wall (shaded darker, the way light gathers less where a wall meets the ground below it). Word 1: the colour,
 * RGB (0–23, red lowest, display colours), the face (24–26: 0 up, 1 north −z, 2 south +z, 3 west −x, 4 east +x), water
 * (27) and the block light it gives off (28–31) — the tint for a face drawn from the atlas, else its average colour.
 * Word 2: the face's sprite ({@link TileColors#spriteCode}, 0 = none).
 */
final class FarVertex {
    static final int BYTES = 12;
    static final int UP = 0, NORTH = 1, SOUTH = 2, WEST = 3, EAST = 4;
    static final VertexFormatElement PACKED = element();
    static final VertexFormat FORMAT = VertexFormat.builder().add("Packed", PACKED).build();

    private FarVertex() {
    }

    /** A vertex element of our own: the first free id from the top (Minecraft's take the lowest ones). */
    private static VertexFormatElement element() {
        for (int id = VertexFormatElement.MAX_COUNT - 1; id > 5; id--) {
            if (VertexFormatElement.byId(id) == null) {
                return VertexFormatElement.register(id, 0, VertexFormatElement.Type.UINT, VertexFormatElement.Usage.GENERIC, 3);
            }
        }
        throw new IllegalStateException("No free vertex format element id for the far terrain");
    }

    static int position(int x, int y, int z) {
        return x | z << 7 | (Math.max(-32768, Math.min(32767, y)) + 32768) << 14;
    }

    static int foot(int position) {
        return position | 1 << 30;
    }

    /** @param rgb 0xRRGGBB */
    static int look(int rgb, int face, boolean water, int emission) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return r | g << 8 | b << 16 | face << 24 | (water ? 1 << 27 : 0) | Math.min(15, emission) << 28;
    }

    static void put(long address, int position, int look, int sprite) {
        MemoryUtil.memPutInt(address, position);
        MemoryUtil.memPutInt(address + 4, look);
        MemoryUtil.memPutInt(address + 8, sprite);
    }
}
