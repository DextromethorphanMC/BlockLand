package com.blockland.client.perf.terrain;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Which faces of a block state hide the neighbour's face against them outright: the first test of
 * {@code Block.shouldRenderFace} ({@code neighbour.getFaceOcclusionShape(side.getOpposite()) == Shapes.block()} culls the
 * face, whatever the block). One bit per {@code Direction}, remembered per state id (states never change; a race only
 * computes the same bits twice). Any thread.
 */
public final class FullFaces {
    /** All six faces full. */
    public static final int ALL = 0x3F;
    private static final int KNOWN = 0x80;
    private static volatile byte[] bits = new byte[0];

    private FullFaces() {
    }

    public static int of(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        byte[] table = bits;
        if (id >= 0 && id < table.length) {
            int b = table[id];
            if ((b & KNOWN) != 0) return b & ALL;
        }
        int b = 0;
        for (Direction d : Direction.values()) {
            if (state.getFaceOcclusionShape(d) == Shapes.block()) b |= 1 << d.ordinal();
        }
        if (id >= 0) {
            if (id >= table.length) {
                synchronized (FullFaces.class) {
                    table = bits;
                    if (id >= table.length) bits = table = java.util.Arrays.copyOf(table, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
                }
            }
            table[id] = (byte) (b | KNOWN);
        }
        return b;
    }
}
