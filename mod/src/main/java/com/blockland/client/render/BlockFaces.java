package com.blockland.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where block faces really are, for things that lie on them (blood stains, decals): the visible sides of the blocks'
 * outline shapes (slabs, stairs, carpets, snow layers at their own heights), and which parts of a plane they cover, so
 * whatever lies on a face can be cut at its edges instead of hanging over them into the air.
 *
 * <p>Coordinates on a face: {@code s} and {@code t} are the world axes across it, the first and second of x, y, z that
 * are not the face's own axis ({@link #sAxis}, {@link #tAxis}). Client thread only.
 */
public final class BlockFaces {
    private static final double EPS = 2e-3;
    /** How far in front of a struck plane a visible side may lie and still be the one drawn there (blocks). */
    private static final double OUT = 0.2;
    private static final Direction.Axis[] AXES = Direction.Axis.values();
    private static final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos(), front = new BlockPos.MutableBlockPos();

    private BlockFaces() {
    }

    public static int sAxis(Direction face) {
        return face.getAxis().ordinal() == 0 ? 1 : 0;
    }

    public static int tAxis(Direction face) {
        return face.getAxis().ordinal() == 2 ? 1 : 2;
    }

    /**
     * The outermost visible side facing {@code face} that holds the point {@code (s, t)} and lies from {@code reach} behind
     * {@code plane} to {@link #OUT} in front of it (what lies on a surface lies on top of what is drawn there: a snow
     * layer's top above its lower collision box, a fence post's top under its raised one), not covered by a full block
     * in front of it; NaN when there is none (the point is over air).
     */
    public static double visiblePlane(BlockGetter level, Direction face, double plane, double s, double t, double reach) {
        int a = face.getAxis().ordinal(), sa = sAxis(face), ta = tAxis(face);
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double sign = positive ? 1 : -1, best = Double.NaN, bestOut = -Double.MAX_VALUE;
        // the cells holding the point (both, on a block edge)
        for (int cs = Mth.floor(s - EPS); cs <= Mth.floor(s + EPS); cs++) {
            for (int ct = Mth.floor(t - EPS); ct <= Mth.floor(t + EPS); ct++) {
                double ls = s - cs, lt = t - ct;
                for (int ca = Mth.floor(plane - reach - OUT); ca <= Mth.floor(plane + reach + OUT); ca++) {
                    set(cell, a, ca, cs, ct);
                    BlockState state = level.getBlockState(cell);
                    if (state.isAir()) continue;
                    for (AABB box : shape(level, state).toAabbs()) {
                        if (ls < box.min(AXES[sa]) - EPS || ls > box.max(AXES[sa]) + EPS || lt < box.min(AXES[ta]) - EPS || lt > box.max(AXES[ta]) + EPS) continue;
                        double side = positive ? box.max(AXES[a]) : box.min(AXES[a]), p = ca + side;
                        double out = (p - plane) * sign;
                        if (out < -reach || out > OUT || out <= bestOut || covered(level, face, side, positive)) continue;
                        best = p;
                        bestOut = out;
                    }
                }
            }
        }
        return best;
    }

    /**
     * The parts of the plane {@code plane} (facing {@code face}) that visible block sides cover within {@code reachS} ×
     * {@code reachT} of the point {@code (s, t)}: rectangles {@code s0, t0, s1, t1} relative to that point, one per box side
     * (a floor of full blocks gives one per block, a slab's top only where the slab is), written into {@code out} (4
     * floats each, as many as fit). Sides covered by a full block in front of them are left out. Returns the count.
     */
    public static int surface(BlockGetter level, Direction face, double plane, double s, double t, float reachS, float reachT, float[] out) {
        int a = face.getAxis().ordinal(), sa = sAxis(face), ta = tAxis(face);
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        int ca = Mth.floor(plane + (positive ? -1e-3 : 1e-3));
        int n = 0;
        for (int cs = Mth.floor(s - reachS); cs <= Mth.floor(s + reachS); cs++) {
            for (int ct = Mth.floor(t - reachT); ct <= Mth.floor(t + reachT); ct++) {
                set(cell, a, ca, cs, ct);
                BlockState state = level.getBlockState(cell);
                if (state.isAir()) continue;
                for (AABB box : shape(level, state).toAabbs()) {
                    double side = positive ? box.max(AXES[a]) : box.min(AXES[a]);
                    if (Math.abs(ca + side - plane) > EPS || covered(level, face, side, positive)) continue;
                    float s0 = Math.max(-reachS, (float) (cs + box.min(AXES[sa]) - s)), s1 = Math.min(reachS, (float) (cs + box.max(AXES[sa]) - s));
                    float t0 = Math.max(-reachT, (float) (ct + box.min(AXES[ta]) - t)), t1 = Math.min(reachT, (float) (ct + box.max(AXES[ta]) - t));
                    if (s1 <= s0 || t1 <= t0) continue;
                    if (n * 4 + 4 > out.length) return n;
                    out[n * 4] = s0;
                    out[n * 4 + 1] = t0;
                    out[n * 4 + 2] = s1;
                    out[n * 4 + 3] = t1;
                    n++;
                }
            }
        }
        return n;
    }

    /** The block's visible outline (its collision shape where it has no outline). Uses {@link #cell}. */
    private static VoxelShape shape(BlockGetter level, BlockState state) {
        VoxelShape shape = state.getShape(level, cell);
        return shape.isEmpty() ? state.getCollisionShape(level, cell) : shape;
    }

    /** Whether a box side on the block's own boundary is hidden by a full block in front of it. Uses {@link #cell}. */
    private static boolean covered(BlockGetter level, Direction face, double side, boolean positive) {
        if (positive ? side < 1 - 1e-3 : side > 1e-3) return false;
        front.setWithOffset(cell, face);
        return level.getBlockState(front).isCollisionShapeFullBlock(level, front);
    }

    /** The cell at {@code va} along the face's axis {@code a} and {@code vs}, {@code vt} across it. */
    private static void set(BlockPos.MutableBlockPos pos, int a, int va, int vs, int vt) {
        switch (a) {
            case 0 -> pos.set(va, vs, vt);
            case 1 -> pos.set(vs, va, vt);
            default -> pos.set(vs, vt, va);
        }
    }
}
