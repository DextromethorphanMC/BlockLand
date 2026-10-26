package com.blockland.client.graphics.far;

import com.blockland.farterrain.FarTile;

import java.util.ArrayList;
import java.util.List;

/**
 * Which far tiles are wanted and which are drawn this frame (rendering spec §22): a quadtree from level-9 roots round the
 * camera. A node splits while its cell is coarser than {@code distance / detail}, out to the far distance; nodes wholly
 * inside the area vanilla draws are left out. Every node of the desired tree is wanted, nearest first. While some of a
 * node's children are not complete the node stands in for all four — but only when it is at most {@link #COARSEST} times
 * coarser than its place asks for; otherwise the children that are ready are drawn and the rest stays open until it
 * arrives (a far tile's giant cells never stand in right beyond the loaded chunks).
 */
final class Selection {
    /** A tile to draw; {@code masked} when it reaches into the area vanilla may have drawn (fragments there are dropped). */
    record Draw(TileEntry entry, boolean masked) {
    }

    /** How much coarser than wanted a tile may be to stand in for children still loading. */
    static final int COARSEST = 4;

    final List<Draw> draws = new ArrayList<>();
    int wanted;

    private TileStore store;
    private double camX, camZ;
    private double reach, inner, outer, full;
    private int detail;
    private long now;

    /**
     * @param reach  how far the far terrain reaches (blocks)
     * @param inner  radius round the camera vanilla surely covers (tiles wholly inside are skipped)
     * @param outer  radius beyond which vanilla surely draws nothing (tiles reaching inside are masked)
     * @param full   out to this distance every block is kept (1-block cells); the cells only grow beyond it
     */
    void run(TileStore store, double camX, double camZ, double reach, int detail, double inner, double outer, double full, long now) {
        this.full = full;
        this.store = store;
        this.camX = camX;
        this.camZ = camZ;
        this.reach = reach;
        this.detail = detail;
        this.inner = inner;
        this.outer = outer;
        this.now = now;
        draws.clear();
        wanted = 0;
        int top = FarTile.MAX_LEVEL, span = FarTile.span(top);
        int x0 = Math.floorDiv((int) Math.floor(camX - reach), span), x1 = Math.floorDiv((int) Math.ceil(camX + reach), span);
        int z0 = Math.floorDiv((int) Math.floor(camZ - reach), span), z1 = Math.floorDiv((int) Math.ceil(camZ + reach), span);
        for (int tz = z0; tz <= z1; tz++) {
            for (int tx = x0; tx <= x1; tx++) visit(top, tx, tz, true);
        }
    }

    private void visit(int level, int tx, int tz, boolean drawAllowed) {
        int span = FarTile.span(level);
        double x0 = (double) tx * span, z0 = (double) tz * span;
        double near = nearest(x0, z0, span), far = farthest(x0, z0, span);
        if (near > reach || far < inner) return;
        // nearest first; at the same distance (the camera inside them) the coarser
        TileEntry e = store.want(level, tx, tz, near - level * 1e-3, now);
        wanted++;
        // how far beyond the full-detail ring this tile begins decides its cells
        double beyond = Math.max(near - full, 1);
        boolean split = level > 0 && (double) FarTile.cell(level) * detail > beyond;
        if (!split) {
            if (drawAllowed && e.drawable()) draws.add(new Draw(e, near < outer));
            return;
        }
        boolean standIn = e.drawable() && !childrenComplete(level, tx, tz)
                && FarTile.cell(level) * detail <= COARSEST * beyond;
        if (drawAllowed && standIn) draws.add(new Draw(e, near < outer));
        int cl = level - 1, cx = tx * 2, cz = tz * 2;
        boolean childDraw = drawAllowed && !standIn;
        visit(cl, cx, cz, childDraw);
        visit(cl, cx + 1, cz, childDraw);
        visit(cl, cx, cz + 1, childDraw);
        visit(cl, cx + 1, cz + 1, childDraw);
    }

    /** All four children are complete, or left out (beyond the reach, inside vanilla's area). */
    private boolean childrenComplete(int level, int tx, int tz) {
        int cl = level - 1, span = FarTile.span(cl);
        for (int k = 0; k < 4; k++) {
            int cx = tx * 2 + (k & 1), cz = tz * 2 + (k >> 1);
            double x0 = (double) cx * span, z0 = (double) cz * span;
            if (nearest(x0, z0, span) > reach || farthest(x0, z0, span) < inner) continue;
            TileEntry child = store.peek(FarTile.key(cl, cx, cz));
            if (child == null || !child.complete()) return false;
        }
        return true;
    }

    private double nearest(double x0, double z0, int span) {
        double dx = Math.max(0, Math.max(x0 - camX, camX - (x0 + span)));
        double dz = Math.max(0, Math.max(z0 - camZ, camZ - (z0 + span)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double farthest(double x0, double z0, int span) {
        double dx = Math.max(Math.abs(x0 - camX), Math.abs(x0 + span - camX));
        double dz = Math.max(Math.abs(z0 - camZ), Math.abs(z0 + span - camZ));
        return Math.sqrt(dx * dx + dz * dz);
    }
}
