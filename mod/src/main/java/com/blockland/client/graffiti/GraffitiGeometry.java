package com.blockland.client.graffiti;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a graffiti square on the surface it was sprayed on: the square is cut to every visible block face of that plane
 * it covers (Sutherland–Hodgman against each face's rectangle), so a piece runs across neighbouring blocks of a wall,
 * stops cleanly at a gap, a doorway or the wall's edge, and never hangs in the air. Texture coordinates are cut along
 * with the corners.
 */
final class GraffitiGeometry {
    /** One cut piece on one face: corners relative to the graffiti's centre, texture coordinates 0..1 of the square. */
    record Polygon(float[] xyz, float[] uv, int count, int light, float shade) {
    }

    /** How far around the hit block faces are looked for (blocks, each way along the plane). */
    private static final int REACH = 2;

    private GraffitiGeometry() {
    }

    /**
     * @param cx,cy,cz the centre (on the face plane)
     * @param face     the surface's outward direction
     * @param rx..uz   right and up across the surface (unit), the square's axes
     */
    static List<Polygon> layOut(ClientLevel level, double cx, double cy, double cz, Direction face,
                                float rx, float ry, float rz, float ux, float uy, float uz, float size) {
        List<Polygon> out = new ArrayList<>();
        int axis = face.getAxis().ordinal();
        int sAxis = axis == 0 ? 1 : 0, tAxis = axis == 2 ? 1 : 2;
        double[] centre = {cx, cy, cz};
        double plane = centre[axis];
        // the block whose face was sprayed: just behind the plane
        double[] behind = centre.clone();
        behind[axis] -= face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0.01 : -0.01;
        BlockPos hit = BlockPos.containing(behind[0], behind[1], behind[2]);
        float half = size / 2;
        // the square's corners (relative to the centre) and their texture coordinates (v down)
        float[] square = {
                -rx * half + ux * half, -ry * half + uy * half, -rz * half + uz * half,
                rx * half + ux * half, ry * half + uy * half, rz * half + uz * half,
                rx * half - ux * half, ry * half - uy * half, rz * half - uz * half,
                -rx * half - ux * half, -ry * half - uy * half, -rz * half - uz * half};
        float[] squareUv = {0, 0, 1, 0, 1, 1, 0, 1};
        float shade = level.getShade(face, true);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ds = -REACH; ds <= REACH; ds++) {
            for (int dt = -REACH; dt <= REACH; dt++) {
                int[] p = {hit.getX(), hit.getY(), hit.getZ()};
                p[sAxis] += ds;
                p[tAxis] += dt;
                pos.set(p[0], p[1], p[2]);
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                BlockPos front = pos.relative(face);
                if (level.getBlockState(front).isCollisionShapeFullBlock(level, front)) continue;
                VoxelShape shape = state.getCollisionShape(level, pos);
                if (shape.isEmpty()) continue;
                int light = LevelRenderer.getLightColor(level, front);
                for (AABB box : shape.toAabbs()) {
                    double facePlane = (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max(box, axis) : min(box, axis)) + p[axis];
                    if (Math.abs(facePlane - plane) > 2e-3) continue;
                    // the face rectangle relative to the centre
                    float s0 = (float) (p[sAxis] + min(box, sAxis) - centre[sAxis]), s1 = (float) (p[sAxis] + max(box, sAxis) - centre[sAxis]);
                    float t0 = (float) (p[tAxis] + min(box, tAxis) - centre[tAxis]), t1 = (float) (p[tAxis] + max(box, tAxis) - centre[tAxis]);
                    Polygon polygon = clip(square, squareUv, sAxis, tAxis, s0, s1, t0, t1, light, shade);
                    if (polygon != null) out.add(polygon);
                }
            }
        }
        return out;
    }

    private static Polygon clip(float[] square, float[] squareUv, int sAxis, int tAxis, float s0, float s1, float t0, float t1,
                                int light, float shade) {
        float[] xyz = new float[16 * 3], uv = new float[16 * 2];
        System.arraycopy(square, 0, xyz, 0, 12);
        System.arraycopy(squareUv, 0, uv, 0, 8);
        int n = 4;
        n = clipAxis(xyz, uv, n, sAxis, s0, false);
        n = clipAxis(xyz, uv, n, sAxis, s1, true);
        n = clipAxis(xyz, uv, n, tAxis, t0, false);
        n = clipAxis(xyz, uv, n, tAxis, t1, true);
        if (n < 3) return null;
        float[] outXyz = new float[n * 3], outUv = new float[n * 2];
        System.arraycopy(xyz, 0, outXyz, 0, n * 3);
        System.arraycopy(uv, 0, outUv, 0, n * 2);
        // a sliver too thin to see is dropped
        float area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += outXyz[i * 3 + sAxis] * outXyz[j * 3 + tAxis] - outXyz[j * 3 + sAxis] * outXyz[i * 3 + tAxis];
        }
        return Math.abs(area) < 1e-5f ? null : new Polygon(outXyz, outUv, n, light, shade);
    }

    /** Keeps the part of the polygon on the inner side of {@code coordinate[axis] = limit}. */
    private static int clipAxis(float[] xyz, float[] uv, int n, int axis, float limit, boolean keepBelow) {
        if (n == 0) return 0;
        float[] inXyz = xyz.clone(), inUv = uv.clone();
        int out = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float a = inXyz[i * 3 + axis], b = inXyz[j * 3 + axis];
            boolean aIn = keepBelow ? a <= limit : a >= limit;
            boolean bIn = keepBelow ? b <= limit : b >= limit;
            if (aIn && out < 16) {
                System.arraycopy(inXyz, i * 3, xyz, out * 3, 3);
                System.arraycopy(inUv, i * 2, uv, out * 2, 2);
                out++;
            }
            if (aIn != bIn && out < 16) {
                float f = (limit - a) / (b - a);
                for (int k = 0; k < 3; k++) xyz[out * 3 + k] = inXyz[i * 3 + k] + (inXyz[j * 3 + k] - inXyz[i * 3 + k]) * f;
                for (int k = 0; k < 2; k++) uv[out * 2 + k] = inUv[i * 2 + k] + (inUv[j * 2 + k] - inUv[i * 2 + k]) * f;
                out++;
            }
        }
        return out;
    }

    private static double min(AABB box, int axis) {
        return box.min(Direction.Axis.values()[axis]);
    }

    private static double max(AABB box, int axis) {
        return box.max(Direction.Axis.values()[axis]);
    }
}
