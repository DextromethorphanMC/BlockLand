package com.blockland.client.ctm;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * The tile arithmetic of the connected textures (rendering spec §25), shared by every rule and mirrored by
 * {@code tools/ctm/check_ctm.py} and {@code tools/assets/gen_containers.py}.
 * <p>
 * Directions in a face's plane are numbered 0..7: up, right, down, left, up-right, down-right, down-left, up-left; a
 * mask has bit {@code i} set when the neighbour that way connects. Each face of a block has a fixed frame
 * ({@link #right}, {@link #up}); a quad maps it onto its texture's frame (image right = +u, image up = −v), so the same
 * world neighbours give the right tile however a model rotates or mirrors its uvs.
 * <p>
 * {@code ctm} (47 tiles): the mask is reduced — a corner counts only when both of its edges connect (else the edges
 * already draw the frame there) — and the tile is the rank of the reduced mask among all 47 reduced masks in ascending
 * order: tile 0 is a block alone (frame all round), tile 46 is fully surrounded (no frame). A tile draws a frame along
 * every edge whose bit is clear, and an inner-corner notch at a corner whose two edges are set but whose own bit is
 * clear.
 */
public final class CtmTiles {
    public static final int UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3, UP_RIGHT = 4, DOWN_RIGHT = 5, DOWN_LEFT = 6, UP_LEFT = 7;
    /** The eight in-plane directions as (x along right, y along up). */
    static final int[][] DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {1, -1}, {-1, -1}, {-1, 1}};
    public static final int CTM_TILES = 47;

    /** Raw 8-bit mask → ctm tile (0..46). */
    private static final byte[] CTM_INDEX = new byte[256];

    static {
        int next = 0;
        int[] rank = new int[256];
        for (int m = 0; m < 256; m++) rank[m] = reduce(m) == m ? next++ : -1;
        if (next != CTM_TILES) throw new IllegalStateException("ctm layout has " + next + " tiles");
        for (int m = 0; m < 256; m++) CTM_INDEX[m] = (byte) rank[reduce(m)];
    }

    private CtmTiles() {
    }

    /** Clears the corner bits whose two edges are not both connected. */
    public static int reduce(int mask) {
        int m = mask & 0xF;
        if ((mask & 1 << UP_RIGHT) != 0 && (mask & (1 << UP | 1 << RIGHT)) == (1 << UP | 1 << RIGHT)) m |= 1 << UP_RIGHT;
        if ((mask & 1 << DOWN_RIGHT) != 0 && (mask & (1 << DOWN | 1 << RIGHT)) == (1 << DOWN | 1 << RIGHT)) m |= 1 << DOWN_RIGHT;
        if ((mask & 1 << DOWN_LEFT) != 0 && (mask & (1 << DOWN | 1 << LEFT)) == (1 << DOWN | 1 << LEFT)) m |= 1 << DOWN_LEFT;
        if ((mask & 1 << UP_LEFT) != 0 && (mask & (1 << UP | 1 << LEFT)) == (1 << UP | 1 << LEFT)) m |= 1 << UP_LEFT;
        return m;
    }

    /** The {@code ctm} tile for a mask in the texture's frame. */
    public static int ctm(int mask) {
        return CTM_INDEX[mask & 0xFF];
    }

    /** The {@code horizontal} tile (4): 0 alone, 1 joined on the left, 2 on the right, 3 both. */
    public static int horizontal(int mask) {
        return (mask >> LEFT & 1) | (mask >> RIGHT & 1) << 1;
    }

    /** The {@code vertical} tile (4): 0 alone, 1 joined above, 2 below, 3 both. */
    public static int vertical(int mask) {
        return (mask >> UP & 1) | (mask >> DOWN & 1) << 1;
    }

    /** A face's fixed in-plane "right" (seen from outside the block). */
    public static Vec3i right(Direction face) {
        return switch (face) {
            case UP, DOWN, SOUTH -> new Vec3i(1, 0, 0);
            case NORTH -> new Vec3i(-1, 0, 0);
            case WEST -> new Vec3i(0, 0, 1);
            case EAST -> new Vec3i(0, 0, -1);
        };
    }

    /** A face's fixed in-plane "up" (seen from outside the block). */
    public static Vec3i up(Direction face) {
        return switch (face) {
            case UP -> new Vec3i(0, 0, -1);
            case DOWN -> new Vec3i(0, 0, 1);
            default -> new Vec3i(0, 1, 0);
        };
    }

    /**
     * The permutation from a texture's frame to its face's frame: {@code perm[t]} is the face direction the texture
     * direction {@code t} points to, given where +u and +v run in the face's frame (each a unit step along right or up:
     * {@code (±1, 0)} or {@code (0, ±1)}).
     */
    static int[] permutation(int uRight, int uUp, int vRight, int vUp) {
        int[] perm = new int[8];
        for (int t = 0; t < 8; t++) {
            int tx = DIRS[t][0], ty = DIRS[t][1];
            // texture right = +u, texture up = −v
            int fx = tx * uRight - ty * vRight;
            int fy = tx * uUp - ty * vUp;
            perm[t] = direction(fx, fy);
        }
        return perm;
    }

    /** A face-frame mask carried into a texture's frame. */
    static int toTexture(int faceMask, int[] perm) {
        int m = 0;
        for (int t = 0; t < 8; t++) m |= (faceMask >> perm[t] & 1) << t;
        return m;
    }

    static int direction(int x, int y) {
        for (int i = 0; i < 8; i++) if (DIRS[i][0] == x && DIRS[i][1] == y) return i;
        throw new IllegalArgumentException("no direction " + x + "," + y);
    }
}
