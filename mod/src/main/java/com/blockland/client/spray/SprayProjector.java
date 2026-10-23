package com.blockland.client.spray;

import com.blockland.client.gltf.GltfModel;
import com.blockland.craft.Spray;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * The geometry of sprays (asset spec §29), pure maths over a {@link SprayScene}: a spray's square stands on its side
 * view and is projected along the view direction; the nearest surface under it fixes a slab, and every triangle in the
 * slab that faces the projector is clipped to the square and becomes decal geometry with the spray's texture
 * coordinates. Pieces come out per target node, in that node's own space, so they ride along with its animation.
 */
public final class SprayProjector {
    /** The slab around the surface the spray was put on: a little in front, enough behind to wrap round edges. */
    static final float SLAB_FRONT = 0.004f, SLAB_BACK = 0.030f;
    /** Faces turned further than this from the projector get nothing; the decal fades in up to {@link #FACING_FULL}. */
    static final float FACING_MIN = 0.25f, FACING_FULL = 0.5f;
    /** Decals sit a hair above their surface. */
    static final float LIFT = 0.0002f;
    private static final int GRID = 5;

    private SprayProjector() {
    }

    /**
     * The decal of one spray on one target node, in the node's own space.
     *
     * @param target index into the scene's targets
     */
    public record Piece(int target, float[] positions, float[] normals, float[] uvs, float[] fade, int[] indices) {
        public GltfModel.Primitive primitive(int material) {
            GltfModel.Primitive p = new GltfModel.Primitive();
            p.positions = positions;
            p.normals = normals;
            p.uvs = uvs;
            p.ao = fade;
            p.indices = indices;
            p.material = material;
            return p;
        }
    }

    /** The square of a spray: its centre on the side plane and its two edge directions (unit), in rest-pose space. */
    record Square(Vector3f centre, Vector3f across, Vector3f up, Vector3f direction, float half) {
    }

    static Square square(SprayScene scene, Spray spray) {
        Vector3f right = SprayScene.right(spray.side());
        Vector3f up = new Vector3f(0, 1, 0);
        double angle = Math.toRadians(spray.rotation());
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        // counter-clockwise as the viewer sees it: the square's x turns from the view's right towards its up
        Vector3f across = new Vector3f(right).mul(c).add(up.x * s, up.y * s, up.z * s);
        Vector3f squareUp = new Vector3f(right).mul(-s).add(up.x * c, up.y * c, up.z * c);
        return new Square(scene.centre(spray), across, squareUp, SprayScene.direction(spray.side()), spray.size() / 2);
    }

    /**
     * Where along the projection the spray meets the weapon (the projection coordinate of the nearest hit: the centre's,
     * else the nearest of a 5 × 5 grid over the square), or NaN when nothing is under it.
     */
    public static float depth(SprayScene scene, Spray spray) {
        Square sq = square(scene, spray);
        float centre = rayDepth(scene, sq, 0, 0, spray.side());
        if (!Float.isNaN(centre)) return centre;
        float best = Float.NaN;
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                float gx = (i / (GRID - 1f) - 0.5f) * 1.8f * sq.half(), gy = (j / (GRID - 1f) - 0.5f) * 1.8f * sq.half();
                float d = rayDepth(scene, sq, gx, gy, spray.side());
                if (!Float.isNaN(d) && (Float.isNaN(best) || d < best)) best = d;
            }
        }
        return best;
    }

    /** Whether the spray lands on anything at all. */
    public static boolean lands(SprayScene scene, Spray spray) {
        return !Float.isNaN(depth(scene, spray));
    }

    /** Projection coordinate (position along the direction) of the nearest hit of one ray through the square. */
    private static float rayDepth(SprayScene scene, Square sq, float gx, float gy, String side) {
        Vector3f origin = new Vector3f(sq.across()).mul(gx).add(new Vector3f(sq.up()).mul(gy)).add(sq.centre());
        origin.x = scene.startCoordinate(side);
        SprayScene.Hit hit = scene.pick(origin, sq.direction());
        return hit == null ? Float.NaN : hit.point().dot(sq.direction());
    }

    /** Every piece of one spray, or none when it lands on nothing. */
    public static List<Piece> project(SprayScene scene, Spray spray) {
        List<Piece> pieces = new ArrayList<>();
        float d0 = depth(scene, spray);
        if (Float.isNaN(d0)) return pieces;
        Square sq = square(scene, spray);
        float near = d0 - SLAB_FRONT, far = d0 + SLAB_BACK;
        Vector3f toProjector = new Vector3f(sq.direction()).negate();
        Vector3fc centre = sq.centre();
        for (int t = 0; t < scene.targets.size(); t++) {
            SprayScene.Target target = scene.targets.get(t);
            Matrix3f normalMatrix = SprayScene.normalMatrix(target);
            Matrix4f toLocal = new Matrix4f(target.rest()).invert();
            Matrix3f toLocalNormal = new Matrix3f(target.rest()).transpose();
            Builder out = new Builder();
            for (int p = 0; p < target.primitives().size(); p++) {
                GltfModel.Primitive primitive = target.primitives().get(p);
                float[] tris = target.world().get(p);
                for (int i = 0; i + 8 < tris.length; i += 9) {
                    clipTriangle(primitive, i / 3, tris, i, normalMatrix, sq, centre, toProjector, near, far, out);
                }
            }
            if (!out.isEmpty()) pieces.add(out.piece(t, toLocal, toLocalNormal));
        }
        return pieces;
    }

    // ---- clipping ---------------------------------------------------------------------------------

    /** A polygon vertex while clipping: rest-pose position, normal, and its coordinates on the square and the slab. */
    private record V(float px, float py, float pz, float nx, float ny, float nz, float sx, float sy, float depth) {
        V lerp(V o, float t) {
            return new V(px + (o.px - px) * t, py + (o.py - py) * t, pz + (o.pz - pz) * t,
                    nx + (o.nx - nx) * t, ny + (o.ny - ny) * t, nz + (o.nz - nz) * t,
                    sx + (o.sx - sx) * t, sy + (o.sy - sy) * t, depth + (o.depth - depth) * t);
        }

        float coordinate(int axis) {
            return switch (axis) {
                case 0 -> sx;
                case 1 -> sy;
                default -> depth;
            };
        }
    }

    private static void clipTriangle(GltfModel.Primitive primitive, int corner0, float[] tris, int i, Matrix3f normalMatrix,
                                     Square sq, Vector3fc centre, Vector3f toProjector, float near, float far, Builder out) {
        float ax = tris[i], ay = tris[i + 1], az = tris[i + 2];
        float bx = tris[i + 3], by = tris[i + 4], bz = tris[i + 5];
        float cx = tris[i + 6], cy = tris[i + 7], cz = tris[i + 8];
        Vector3f geometric = new Vector3f(bx - ax, by - ay, bz - az).cross(cx - ax, cy - ay, cz - az);
        if (geometric.lengthSquared() < 1e-16f) return;
        geometric.normalize();
        Vector3f[] normals = new Vector3f[3];
        Vector3f average = new Vector3f();
        for (int k = 0; k < 3; k++) {
            int v = primitive.indices[corner0 + k];
            Vector3f n = new Vector3f(primitive.normals[v * 3], primitive.normals[v * 3 + 1], primitive.normals[v * 3 + 2]);
            normalMatrix.transform(n);
            if (n.lengthSquared() > 1e-12f) n.normalize();
            normals[k] = n;
            average.add(n);
        }
        // the winding may disagree with the shading normals (double-sided parts): trust the normals
        if (average.dot(geometric) < 0) geometric.negate();
        if (geometric.dot(toProjector) <= FACING_MIN) return;

        List<V> poly = new ArrayList<>(8);
        float[][] corners = {{ax, ay, az}, {bx, by, bz}, {cx, cy, cz}};
        for (int k = 0; k < 3; k++) {
            float[] c = corners[k];
            float rx = c[0] - centre.x(), ry = c[1] - centre.y(), rz = c[2] - centre.z();
            float sx = rx * sq.across().x + ry * sq.across().y + rz * sq.across().z;
            float sy = rx * sq.up().x + ry * sq.up().y + rz * sq.up().z;
            float depth = c[0] * sq.direction().x + c[1] * sq.direction().y + c[2] * sq.direction().z;
            poly.add(new V(c[0], c[1], c[2], normals[k].x, normals[k].y, normals[k].z, sx, sy, depth));
        }
        float half = sq.half();
        poly = clip(poly, 0, -half, true);
        poly = clip(poly, 0, half, false);
        poly = clip(poly, 1, -half, true);
        poly = clip(poly, 1, half, false);
        poly = clip(poly, 2, near, true);
        poly = clip(poly, 2, far, false);
        if (poly.size() < 3) return;
        float size = half * 2;
        int first = out.vertexCount();
        for (V v : poly) {
            Vector3f n = new Vector3f(v.nx, v.ny, v.nz);
            if (n.lengthSquared() > 1e-12f) n.normalize();
            else n.set(geometric);
            float facing = n.dot(toProjector);
            float fade = smoothstep(FACING_MIN, FACING_FULL, Math.max(facing, geometric.dot(toProjector) * 0.999f));
            out.vertex(v.px + geometric.x * LIFT, v.py + geometric.y * LIFT, v.pz + geometric.z * LIFT, n,
                    v.sx / size + 0.5f, 0.5f - v.sy / size, fade);
        }
        for (int k = 1; k + 1 < poly.size(); k++) out.triangle(first, first + k, first + k + 1);
    }

    /** Sutherland–Hodgman against one plane: keep {@code coordinate >= limit} ({@code keepAbove}) or {@code <= limit}. */
    private static List<V> clip(List<V> poly, int axis, float limit, boolean keepAbove) {
        if (poly.size() < 3) return poly;
        List<V> result = new ArrayList<>(poly.size() + 2);
        for (int k = 0; k < poly.size(); k++) {
            V a = poly.get(k), b = poly.get((k + 1) % poly.size());
            float da = keepAbove ? a.coordinate(axis) - limit : limit - a.coordinate(axis);
            float db = keepAbove ? b.coordinate(axis) - limit : limit - b.coordinate(axis);
            if (da >= 0) result.add(a);
            if (da >= 0 != db >= 0) result.add(a.lerp(b, da / (da - db)));
        }
        return result;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** Möller–Trumbore: distance along {@code dir} (unit) to the triangle at {@code tris[i..i+8]}, or −1. Two-sided. */
    static float intersect(Vector3fc o, Vector3fc dir, float[] tris, int i) {
        float e1x = tris[i + 3] - tris[i], e1y = tris[i + 4] - tris[i + 1], e1z = tris[i + 5] - tris[i + 2];
        float e2x = tris[i + 6] - tris[i], e2y = tris[i + 7] - tris[i + 1], e2z = tris[i + 8] - tris[i + 2];
        float px = dir.y() * e2z - dir.z() * e2y, py = dir.z() * e2x - dir.x() * e2z, pz = dir.x() * e2y - dir.y() * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-12f) return -1;
        float inv = 1 / det;
        float tx = o.x() - tris[i], ty = o.y() - tris[i + 1], tz = o.z() - tris[i + 2];
        float u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0 || u > 1) return -1;
        float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        float v = (dir.x() * qx + dir.y() * qy + dir.z() * qz) * inv;
        if (v < 0 || u + v > 1) return -1;
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
    }

    /** Collects one piece's vertices (rest-pose space) and turns them into the node's space at the end. */
    private static final class Builder {
        private float[] positions = new float[96], normals = new float[96], uvs = new float[64], fade = new float[32];
        private int[] indices = new int[96];
        private int vertices, indexCount;

        boolean isEmpty() {
            return indexCount == 0;
        }

        int vertexCount() {
            return vertices;
        }

        void vertex(float x, float y, float z, Vector3f n, float u, float v, float alpha) {
            if (vertices * 3 + 3 > positions.length) {
                positions = java.util.Arrays.copyOf(positions, positions.length * 2);
                normals = java.util.Arrays.copyOf(normals, normals.length * 2);
                uvs = java.util.Arrays.copyOf(uvs, uvs.length * 2);
                fade = java.util.Arrays.copyOf(fade, fade.length * 2);
            }
            positions[vertices * 3] = x;
            positions[vertices * 3 + 1] = y;
            positions[vertices * 3 + 2] = z;
            normals[vertices * 3] = n.x;
            normals[vertices * 3 + 1] = n.y;
            normals[vertices * 3 + 2] = n.z;
            uvs[vertices * 2] = u;
            uvs[vertices * 2 + 1] = v;
            fade[vertices] = alpha;
            vertices++;
        }

        void triangle(int a, int b, int c) {
            if (indexCount + 3 > indices.length) indices = java.util.Arrays.copyOf(indices, indices.length * 2);
            indices[indexCount++] = a;
            indices[indexCount++] = b;
            indices[indexCount++] = c;
        }

        Piece piece(int target, Matrix4f toLocal, Matrix3f toLocalNormal) {
            float[] pos = new float[vertices * 3], nor = new float[vertices * 3];
            Vector3f p = new Vector3f();
            for (int v = 0; v < vertices; v++) {
                toLocal.transformPosition(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2], p);
                pos[v * 3] = p.x;
                pos[v * 3 + 1] = p.y;
                pos[v * 3 + 2] = p.z;
                toLocalNormal.transform(p.set(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2]));
                if (p.lengthSquared() > 1e-12f) p.normalize();
                nor[v * 3] = p.x;
                nor[v * 3 + 1] = p.y;
                nor[v * 3 + 2] = p.z;
            }
            return new Piece(target, pos, nor, java.util.Arrays.copyOf(uvs, vertices * 2), java.util.Arrays.copyOf(fade, vertices),
                    java.util.Arrays.copyOf(indices, indexCount));
        }
    }
}
