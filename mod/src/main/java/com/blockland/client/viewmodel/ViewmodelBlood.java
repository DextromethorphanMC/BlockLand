package com.blockland.client.viewmodel;

import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.GunPipelines;
import com.blockland.client.render.MaterialLayer;
import com.blockland.client.render.WeaponBlood;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Blood splashed onto the first-person weapon and the hands (rendering spec §19, the weapon). Every splash
 * {@link WeaponBlood} recorded is laid as a few drops where they land: each drop picks a point on a surface turned towards
 * the blood's flight (more on the front of the weapon, none where something nearer took it), and becomes a small decal
 * clipped onto the triangles of the part it hit — a weapon node's mesh, a hand's or arm's mesh — kept as barycentric
 * weights of that mesh's vertices. Rebuilt from the mesh's vertices every frame, it lies on the surface through every
 * animation (the skinned arms' bends too) and never moves; the shader (gun.fsh, effect 7) draws the drop in the decal's
 * square, drawn out the way it flew. Each drop dries and fades on its own. Kept per holder — the hands keep theirs across
 * weapons — and per weapon model. Render thread only.
 */
final class ViewmodelBlood {
    /** Drop pieces kept per holder at most (the oldest go first). */
    private static final int MAX_PIECES = 160;
    /** Drops of a splash: this many plus up to {@link #DROPS_PER_AMOUNT} for a full one. */
    private static final int MIN_DROPS = 5, DROPS_PER_AMOUNT = 16;
    /** How much the drops fall on their way (× down, added to the way they fly). */
    private static final float FALL = 0.9f;
    /** How far past square-on a surface still catches drops (a glancing spray wets the sides too). */
    private static final float GLANCE = 0.6f;
    /**
     * Every surface the eye sees takes blood: this much of a triangle's share it has whatever way it faces, the spray's own
     * facing adds the rest (so the sides of the weapon — most of what the eye sees of it — get their part, not only tops).
     */
    private static final float SEEN_SHARE = 0.9f;
    /** Only what lies this close in front of a drop on the spray's way back shields it (blocks). */
    private static final float SHIELD_REACH = 0.35f;
    /** A decal sits a hair above its surface. */
    private static final float LIFT = 0.00025f;
    /** Body radius in the decal's square (gun.fsh bloodSplat) and how far behind its middle the body sits. */
    private static final float BODY = 0.42f, BODY_BACK = 0.22f;

    private static final Map<Integer, Holder> holders = new HashMap<>();
    @Nullable
    private static ClientLevel holdersLevel;

    /** One drop on one primitive of one mesh (a drop over two primitives is two pieces with the same square). */
    private static final class Piece {
        /** The model the mesh belongs to (the weapon's, or the arms'). */
        final GltfModel model;
        /** Weapon pieces: the node; arm pieces: the mesh (the arms are drawn per mesh). */
        final int key;
        final int primitive;
        final WeaponBlood.Splash splash;
        final float seed, stretch;
        /** Per decal vertex: the source triangle's three vertex indices, their weights, uv, facing fade. */
        int[] corners;
        float[] weights, uvs, fade;
        int[] indices;
        final float[] params = new float[4];

        Piece(GltfModel model, int key, int primitive, WeaponBlood.Splash splash, float seed, float stretch) {
            this.model = model;
            this.key = key;
            this.primitive = primitive;
            this.splash = splash;
            this.seed = seed;
            this.stretch = stretch;
        }

        int vertexCount() {
            return weights.length / 3;
        }
    }

    /** What lies on one holder's weapons and hands, and its decal model. */
    private static final class Holder {
        final List<Piece> pieces = new ArrayList<>();
        boolean changed;
        @Nullable
        GpuModel decals;
        @Nullable
        GpuBuffer vertices;
        int vertexCount;

        void release() {
            if (decals != null) decals.close();
            if (vertices != null) vertices.close();
            decals = null;
            vertices = null;
            vertexCount = 0;
        }
    }

    /** An arm mesh drawn this frame: the rig node it is drawn at and its skinned vertices (null = rigid). */
    private record ArmDraw(int node, @Nullable List<GltfModel.Primitive> skinned) {
    }

    private final Map<Integer, ArmDraw> arms = new HashMap<>();
    @Nullable
    private GpuModel armsModel;
    @Nullable
    private Piece styling;
    private final GpuModel.Style style = new GpuModel.Style() {
        @Override
        public MaterialLayer layer(String material, String region) {
            Piece p = styling;
            return p == null ? null : new MaterialLayer(null, 0, null, MaterialLayer.BLOOD, null, p.params);
        }
    };
    @Nullable
    private ByteBuffer staging;

    // scratch
    private final Matrix4f modelView = new Matrix4f(), inverse = new Matrix4f();
    private final Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), centre = new Vector3f();

    // ---- the frame's arms ------------------------------------------------------------------------------

    /** The rig starts drawing the arms (of {@code model}, the arms file). */
    void beginArms(GpuModel model) {
        armsModel = model;
        arms.clear();
    }

    /** An arms mesh was drawn at rig node {@code node}; {@code skinned} = its skinned vertices this frame (null: rigid). */
    void arm(int mesh, int node, @Nullable List<GltfModel.Primitive> skinned) {
        arms.put(mesh, new ArmDraw(node, skinned));
    }

    // ---- laying and drawing ----------------------------------------------------------------------------

    /**
     * Lays the holder's new splashes on what is drawn now, then queues every drop on it. Called after the weapon and the
     * arms are posed and queued; {@code base} maps viewmodel space to view space.
     */
    void draw(ViewmodelRig<?> rig, Entity holder, Matrix4f base, @Nullable boolean[] hidden, int blockLight, int skyLight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || rig.model() == null) return;
        if (mc.level != holdersLevel) {
            holders.values().forEach(Holder::release);
            holders.clear();
            holdersLevel = mc.level;
        }
        long now = holder.level().getGameTime();
        Holder h = holders.computeIfAbsent(holder.getId(), k -> new Holder());
        List<WeaponBlood.Splash> splashes = WeaponBlood.take(holder);
        if (!splashes.isEmpty()) {
            List<Target> targets = targets(rig, hidden);
            if (!targets.isEmpty()) {
                // the strongest few of a frame (a burst of hits in one frame lays like one big splash)
                splashes = new ArrayList<>(splashes);
                splashes.sort((x, y) -> Float.compare(y.amount(), x.amount()));
                Vector3f down = toViewmodel(new Vec3(0, -1, 0), base, mc);
                for (int i = 0; i < Math.min(3, splashes.size()); i++) {
                    lay(h, targets, splashes.get(i), toViewmodel(splashes.get(i).direction(), base, mc), down);
                }
            }
        }
        if (h.pieces.removeIf(p -> p.splash.gone(now))) h.changed = true;
        while (h.pieces.size() > MAX_PIECES) {
            h.pieces.remove(0);
            h.changed = true;
        }
        if (h.pieces.isEmpty()) {
            if (h.changed) h.release();
            h.changed = false;
            return;
        }
        if (h.changed || h.decals == null || h.decals.isClosed()) rebuild(h);
        if (h.decals == null || h.vertices == null) return;
        write(rig, h);
        for (int i = 0; i < h.pieces.size(); i++) {
            Piece p = h.pieces.get(i);
            int node = drawnAt(rig, p, hidden);
            if (node < 0) continue;
            float opacity = p.splash.opacity(now);
            if (opacity <= 0.003f) continue;
            p.params[0] = p.seed;
            p.params[1] = p.splash.wetness(now);
            p.params[2] = opacity;
            p.params[3] = p.stretch;
            styling = p;
            modelView.set(base).mul(rig.world(node));
            h.decals.addMesh(rig.draws(), i, h.vertices, null, modelView, style, blockLight, skyLight, 1);
        }
        styling = null;
    }

    /** The rig node a piece is drawn at this frame, or -1 (its weapon is not the one drawn, its part is hidden). */
    private int drawnAt(ViewmodelRig<?> rig, Piece p, @Nullable boolean[] hidden) {
        if (p.model == rig.model().source) return rig.drawsNode(p.key, hidden) ? p.key : -1;
        if (armsModel != null && p.model == armsModel.source) {
            ArmDraw arm = arms.get(p.key);
            return arm == null ? -1 : arm.node;
        }
        return -1;
    }

    /** A world direction in viewmodel space (through the camera this frame, then {@code base}). */
    private Vector3f toViewmodel(Vec3 world, Matrix4f base, Minecraft mc) {
        Vector3f v = new Vector3f((float) world.x, (float) world.y, (float) world.z);
        mc.gameRenderer.getMainCamera().rotation().conjugate(new Quaternionf()).transform(v);
        base.invert(inverse).transformDirection(v);
        return v.lengthSquared() > 1e-12f ? v.normalize() : v.set(0, 0, 1);
    }

    // ---- what blood can land on --------------------------------------------------------------------------

    /** One primitive drawn this frame, posed in viewmodel space. */
    private record Target(GltfModel model, int key, int primitive, GltfModel.Primitive local, float[] posed, float[] normals,
                          float[] box) {
        /** Whether the ray from {@code o} along {@code dir} passes through this part's bounds within {@code reach}. */
        boolean mayHit(Vector3f o, Vector3f dir, float reach) {
            float near = 0, far = reach;
            for (int axis = 0; axis < 3; axis++) {
                float origin = o.get(axis), d = dir.get(axis), lo = box[axis], hi = box[axis + 3];
                if (Math.abs(d) < 1e-9f) {
                    if (origin < lo || origin > hi) return false;
                    continue;
                }
                float t0 = (lo - origin) / d, t1 = (hi - origin) / d;
                near = Math.max(near, Math.min(t0, t1));
                far = Math.min(far, Math.max(t0, t1));
                if (near > far) return false;
            }
            return true;
        }
    }

    private List<Target> targets(ViewmodelRig<?> rig, @Nullable boolean[] hidden) {
        List<Target> out = new ArrayList<>();
        GltfModel weapon = rig.model().source;
        for (int node = 0; node < weapon.nodes.size(); node++) {
            int mesh = weapon.nodes.get(node).mesh;
            if (mesh < 0 || !rig.drawsNode(node, hidden)) continue;
            List<GltfModel.Primitive> prims = weapon.meshes.get(mesh).primitives;
            for (int p = 0; p < prims.size(); p++) {
                GltfModel.Primitive prim = prims.get(p);
                if (!solid(weapon, prim)) continue;
                out.add(posed(weapon, node, p, prim, rig.world(node)));
            }
        }
        if (armsModel != null) {
            GltfModel source = armsModel.source;
            for (Map.Entry<Integer, ArmDraw> e : arms.entrySet()) {
                List<GltfModel.Primitive> prims = e.getValue().skinned != null ? e.getValue().skinned : source.meshes.get(e.getKey()).primitives;
                for (int p = 0; p < prims.size(); p++) out.add(posed(source, e.getKey(), p, prims.get(p), rig.world(e.getValue().node)));
            }
        }
        return out;
    }

    /** Glass, effects and see-through parts take no blood. */
    private static boolean solid(GltfModel model, GltfModel.Primitive prim) {
        if (prim.material < 0 || prim.material >= model.materials.size()) return true;
        GltfModel.Material m = model.materials.get(prim.material);
        return !m.isBlend() && !"ADD".equals(m.alphaMode) && !m.isEmissive();
    }

    private Target posed(GltfModel model, int key, int primitive, GltfModel.Primitive local, Matrix4f world) {
        int n = local.vertexCount();
        float[] pos = new float[n * 3], nor = new float[n * 3];
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = 0; v < n; v++) {
            world.transformPosition(local.positions[v * 3], local.positions[v * 3 + 1], local.positions[v * 3 + 2], a);
            pos[v * 3] = a.x;
            pos[v * 3 + 1] = a.y;
            pos[v * 3 + 2] = a.z;
            for (int axis = 0; axis < 3; axis++) {
                box[axis] = Math.min(box[axis], a.get(axis));
                box[axis + 3] = Math.max(box[axis + 3], a.get(axis));
            }
            world.transformDirection(local.normals[v * 3], local.normals[v * 3 + 1], local.normals[v * 3 + 2], a);
            if (a.lengthSquared() > 1e-12f) a.normalize();
            nor[v * 3] = a.x;
            nor[v * 3 + 1] = a.y;
            nor[v * 3 + 2] = a.z;
        }
        return new Target(model, key, primitive, local, pos, nor, box);
    }

    // ---- laying a splash -----------------------------------------------------------------------------------

    /**
     * The drops of one splash: each flies along the splash's way, falling and spread, and lands on a point of a surface
     * the eye sees (blood on the faces turned to the target — the muzzle, the front of the blade — is never seen from
     * behind the weapon) that it reaches, if only glancing, and that nothing nearer covers; tops (where the falling drops
     * land) and the front of the weapon (nearer the struck body) get more.
     */
    private void lay(Holder h, List<Target> targets, WeaponBlood.Splash splash, Vector3f way, Vector3f down) {
        Random random = new Random(splash.seed());
        Vector3f mean = new Vector3f(way).fma(FALL, down).normalize();
        // every triangle's share: its area, how squarely it faces the blood, how far forward it is
        int total = 0;
        for (Target t : targets) total += t.local.indices.length / 3;
        float[] cumulative = new float[total];
        int[] owner = new int[total], first = new int[total];
        float sum = 0;
        int k = 0;
        for (int ti = 0; ti < targets.size(); ti++) {
            Target t = targets.get(ti);
            int[] idx = t.local.indices;
            for (int i = 0; i + 2 < idx.length; i += 3, k++) {
                corner(t.posed, idx[i], a);
                corner(t.posed, idx[i + 1], b).sub(a);
                corner(t.posed, idx[i + 2], c).sub(a);
                Vector3f n = b.cross(c);
                float area = n.length() * 0.5f;
                float facing = area > 1e-12f ? -n.dot(mean) / (area * 2) : 0;
                // seen from the eye (at the origin of view space): the face is turned towards it
                centre.set(a).add(t.posed[idx[i + 1] * 3], t.posed[idx[i + 1] * 3 + 1], t.posed[idx[i + 1] * 3 + 2])
                        .add(t.posed[idx[i + 2] * 3], t.posed[idx[i + 2] * 3 + 1], t.posed[idx[i + 2] * 3 + 2]).div(3);
                float seen = area > 1e-12f && centre.lengthSquared() > 1e-12f ? -n.dot(centre) / (area * 2 * centre.length()) : 0;
                float z = centre.z;
                float front = 0.4f + Math.max(0, Math.min(0.5f, -z - 0.2f)) * 2.4f;
                float reach = SEEN_SHARE + Math.max(0, facing);
                sum += seen > 0.05f ? area * seen * reach * front : 0;
                cumulative[k] = sum;
                owner[k] = ti;
                first[k] = i;
            }
        }
        if (sum <= 0) return;
        int drops = MIN_DROPS + Math.round(splash.amount() * DROPS_PER_AMOUNT);
        for (int d = 0; d < drops; d++) {
            Vector3f travel = new Vector3f(way).fma(0.45f, down)
                    .add((random.nextFloat() - 0.5f) * 0.7f, (random.nextFloat() - 0.5f) * 0.7f, (random.nextFloat() - 0.5f) * 0.7f).normalize();
            for (int attempt = 0; attempt < 12; attempt++) {
                int pick = search(cumulative, random.nextFloat() * sum);
                Target t = targets.get(owner[pick]);
                int i = first[pick];
                float u = random.nextFloat(), v = random.nextFloat();
                if (u + v > 1) {
                    u = 1 - u;
                    v = 1 - v;
                }
                int[] idx = t.local.indices;
                Vector3f point = blend(t.posed, idx[i], idx[i + 1], idx[i + 2], 1 - u - v, u, v, new Vector3f());
                Vector3f normal = blend(t.normals, idx[i], idx[i + 1], idx[i + 2], 1 - u - v, u, v, new Vector3f());
                if (normal.lengthSquared() < 1e-12f) continue;
                normal.normalize();
                if (-normal.dot(travel) < -GLANCE || normal.dot(point) > 0) continue;
                if (covered(targets, point, normal, travel)) continue;
                // mostly drops of a few millimetres, many of up to a centimetre, a big splat now and then (more from a heavy one)
                float size = random.nextFloat();
                float radius = size < 0.45f ? 0.003f + random.nextFloat() * 0.003f
                        : size < 0.85f || splash.amount() < 0.3f ? 0.006f + random.nextFloat() * 0.006f
                        : 0.012f + random.nextFloat() * 0.010f;
                stamp(h, targets, t, point, normal, travel, radius, splash, random.nextFloat() * 97.0f);
                break;
            }
        }
    }

    /** Whether something drawn lies between the point and where the blood came from. */
    private static boolean covered(List<Target> targets, Vector3f point, Vector3f normal, Vector3f travel) {
        Vector3f back = new Vector3f(travel).negate();
        // lifted off its own surface first: a ray along a side panel would otherwise graze the panel's own triangles
        Vector3f origin = new Vector3f(point).fma(0.003f, normal);
        for (Target t : targets) {
            if (!t.mayHit(origin, back, SHIELD_REACH)) continue;
            int[] idx = t.local.indices;
            float[] p = t.posed;
            for (int i = 0; i + 2 < idx.length; i += 3) {
                float dist = intersect(origin, back, p, idx[i], idx[i + 1], idx[i + 2]);
                if (dist > 0 && dist < SHIELD_REACH) return true;
            }
        }
        return false;
    }

    /**
     * The decal of one drop: a square on the surface at {@code point} (its u the way the drop slid over the surface, drawn
     * out the more glancing it came in), every triangle of the hit part in a thin slab round the surface and turned the same
     * way clipped to it, kept as weights of the triangles' vertices.
     */
    private void stamp(Holder h, List<Target> targets, Target hit, Vector3f point, Vector3f normal, Vector3f travel, float radius,
                       WeaponBlood.Splash splash, float seed) {
        float glance = Math.abs(normal.dot(travel));
        float stretch = Math.max(1, Math.min(2.6f, 1 / Math.max(glance, 0.35f)));
        Vector3f along = new Vector3f(travel).fma(-travel.dot(normal), normal);
        if (along.lengthSquared() < 1e-8f) along.set(Math.abs(normal.x) < 0.9f ? 1 : 0, Math.abs(normal.x) < 0.9f ? 0 : 1, 0).cross(normal);
        along.normalize();
        Vector3f across = new Vector3f(normal).cross(along).normalize();
        float half = radius / BODY;
        // the body sits behind the square's middle: the square's middle ahead of the point
        Vector3f centre = new Vector3f(point).fma(BODY_BACK * half, along);
        float slab = Math.max(0.003f, 0.6f * half);
        for (Target t : targets) {
            if (t.model != hit.model || t.key != hit.key) continue;
            Clip clip = new Clip();
            int[] idx = t.local.indices;
            for (int i = 0; i + 2 < idx.length; i += 3) clipTriangle(t, idx[i], idx[i + 1], idx[i + 2], centre, point, along, across, normal, half, slab, clip);
            if (clip.vertices == 0) continue;
            Piece piece = new Piece(t.model, t.key, t.primitive, splash, seed, stretch);
            piece.corners = java.util.Arrays.copyOf(clip.corners, clip.vertices * 3);
            piece.weights = java.util.Arrays.copyOf(clip.weights, clip.vertices * 3);
            piece.uvs = java.util.Arrays.copyOf(clip.uvs, clip.vertices * 2);
            piece.fade = java.util.Arrays.copyOf(clip.fade, clip.vertices);
            piece.indices = java.util.Arrays.copyOf(clip.indices, clip.indexCount);
            h.pieces.add(piece);
            h.changed = true;
        }
    }

    /** Collected decal vertices of one piece. */
    private static final class Clip {
        int[] corners = new int[96], indices = new int[96];
        float[] weights = new float[96], uvs = new float[64], fade = new float[32];
        int vertices, indexCount;

        void vertex(int i0, int i1, int i2, float w0, float w1, float w2, float u, float v, float f) {
            if (vertices + 1 > fade.length) {
                corners = java.util.Arrays.copyOf(corners, corners.length * 2);
                weights = java.util.Arrays.copyOf(weights, weights.length * 2);
                uvs = java.util.Arrays.copyOf(uvs, uvs.length * 2);
                fade = java.util.Arrays.copyOf(fade, fade.length * 2);
            }
            int o = vertices * 3;
            corners[o] = i0;
            corners[o + 1] = i1;
            corners[o + 2] = i2;
            weights[o] = w0;
            weights[o + 1] = w1;
            weights[o + 2] = w2;
            uvs[vertices * 2] = u;
            uvs[vertices * 2 + 1] = v;
            fade[vertices] = f;
            vertices++;
        }

        void triangle(int x, int y, int z) {
            if (indexCount + 3 > indices.length) indices = java.util.Arrays.copyOf(indices, indices.length * 2);
            indices[indexCount++] = x;
            indices[indexCount++] = y;
            indices[indexCount++] = z;
        }
    }

    /** A polygon vertex while clipping: weights of the triangle's corners, square coordinates (s, t), height over the surface. */
    private record W(float w0, float w1, float w2, float s, float t, float h) {
        W lerp(W o, float f) {
            return new W(w0 + (o.w0 - w0) * f, w1 + (o.w1 - w1) * f, w2 + (o.w2 - w2) * f, s + (o.s - s) * f, t + (o.t - t) * f,
                    h + (o.h - h) * f);
        }

        float coordinate(int axis) {
            return axis == 0 ? s : axis == 1 ? t : h;
        }
    }

    private void clipTriangle(Target t, int i0, int i1, int i2, Vector3f centre, Vector3f point, Vector3f along, Vector3f across,
                              Vector3f normal, float half, float slab, Clip out) {
        corner(t.posed, i0, a);
        corner(t.posed, i1, b);
        corner(t.posed, i2, c);
        // quick reject: the triangle's box away from the square
        float reach = half * 1.5f + slab;
        if (Math.max(a.x, Math.max(b.x, c.x)) < centre.x - reach || Math.min(a.x, Math.min(b.x, c.x)) > centre.x + reach
                || Math.max(a.y, Math.max(b.y, c.y)) < centre.y - reach || Math.min(a.y, Math.min(b.y, c.y)) > centre.y + reach
                || Math.max(a.z, Math.max(b.z, c.z)) < centre.z - reach || Math.min(a.z, Math.min(b.z, c.z)) > centre.z + reach) return;
        Vector3f e1 = new Vector3f(b).sub(a), e2 = new Vector3f(c).sub(a);
        Vector3f geometric = e1.cross(e2, new Vector3f());
        if (geometric.lengthSquared() < 1e-16f) return;
        geometric.normalize();
        Vector3f shading = blend(t.normals, i0, i1, i2, 1 / 3f, 1 / 3f, 1 / 3f, new Vector3f());
        if (shading.dot(geometric) < 0) geometric.negate();
        if (geometric.dot(normal) < 0.2f) return;
        List<W> poly = new ArrayList<>(8);
        poly.add(project(a, 1, 0, 0, centre, point, along, across, normal, half));
        poly.add(project(b, 0, 1, 0, centre, point, along, across, normal, half));
        poly.add(project(c, 0, 0, 1, centre, point, along, across, normal, half));
        poly = clip(poly, 0, -1, true);
        poly = clip(poly, 0, 1, false);
        poly = clip(poly, 1, -1, true);
        poly = clip(poly, 1, 1, false);
        poly = clip(poly, 2, -slab, true);
        poly = clip(poly, 2, slab, false);
        if (poly.size() < 3) return;
        int start = out.vertices;
        for (W w : poly) {
            Vector3f n = blend(t.normals, i0, i1, i2, w.w0, w.w1, w.w2, new Vector3f());
            float facing = n.lengthSquared() > 1e-12f ? n.normalize().dot(normal) : 1;
            float fade = smoothstep(0.15f, 0.45f, Math.max(facing, geometric.dot(normal) * 0.999f));
            out.vertex(i0, i1, i2, w.w0, w.w1, w.w2, w.s * 0.5f + 0.5f, w.t * 0.5f + 0.5f, fade);
        }
        for (int k = 1; k + 1 < poly.size(); k++) out.triangle(start, start + k, start + k + 1);
    }

    private static W project(Vector3f p, float w0, float w1, float w2, Vector3f centre, Vector3f point, Vector3f along,
                             Vector3f across, Vector3f normal, float half) {
        float rx = p.x - centre.x, ry = p.y - centre.y, rz = p.z - centre.z;
        float s = (rx * along.x + ry * along.y + rz * along.z) / half;
        float t = (rx * across.x + ry * across.y + rz * across.z) / half;
        float h = (p.x - point.x) * normal.x + (p.y - point.y) * normal.y + (p.z - point.z) * normal.z;
        return new W(w0, w1, w2, s, t, h);
    }

    /** Sutherland–Hodgman against one plane: keep {@code coordinate >= limit} ({@code keepAbove}) or {@code <= limit}. */
    private static List<W> clip(List<W> poly, int axis, float limit, boolean keepAbove) {
        if (poly.size() < 3) return poly;
        List<W> result = new ArrayList<>(poly.size() + 2);
        for (int k = 0; k < poly.size(); k++) {
            W p = poly.get(k), q = poly.get((k + 1) % poly.size());
            float dp = keepAbove ? p.coordinate(axis) - limit : limit - p.coordinate(axis);
            float dq = keepAbove ? q.coordinate(axis) - limit : limit - q.coordinate(axis);
            if (dp >= 0) result.add(p);
            if (dp >= 0 != dq >= 0) result.add(p.lerp(q, dp / (dp - dq)));
        }
        return result;
    }

    // ---- the decal model and its vertices ------------------------------------------------------------------

    /** The pieces' topology as a model (one mesh per piece, one blended material) and a vertex buffer laid out like it. */
    private static void rebuild(Holder h) {
        h.release();
        h.changed = false;
        GltfModel source = new GltfModel();
        GltfModel.Material material = new GltfModel.Material();
        material.name = "blood";
        material.region = "blood";
        material.alphaMode = "BLEND";
        material.metallic = 0;
        material.roughness = 0.5f;
        source.materials.add(material);
        int count = 0;
        for (Piece p : h.pieces) {
            GltfModel.Mesh mesh = new GltfModel.Mesh();
            GltfModel.Primitive prim = new GltfModel.Primitive();
            int n = p.vertexCount();
            prim.positions = new float[n * 3];
            prim.normals = new float[n * 3];
            for (int v = 0; v < n; v++) prim.normals[v * 3 + 1] = 1;
            prim.uvs = p.uvs;
            prim.ao = p.fade;
            prim.indices = p.indices;
            prim.material = 0;
            mesh.primitives.add(prim);
            source.meshes.add(mesh);
            count += n;
        }
        h.decals = GpuModel.upload("BlockLand weapon blood", source);
        h.vertexCount = count;
        h.vertices = RenderSystem.getDevice().createBuffer(() -> "BlockLand weapon blood", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                Math.max(1, count) * GunPipelines.FORMAT.getVertexSize());
    }

    /** Every piece's vertices from its mesh's vertices as they are now (skinned arms: as skinned this frame). */
    private void write(ViewmodelRig<?> rig, Holder h) {
        int stride = GunPipelines.FORMAT.getVertexSize();
        ByteBuffer data = staging(h.vertexCount * stride);
        Vector3f n = new Vector3f();
        for (int i = 0; i < h.pieces.size(); i++) {
            Piece p = h.pieces.get(i);
            GltfModel.Primitive from = source(rig, p);
            int count = p.vertexCount();
            data.position(h.decals.parts(i).get(0).baseVertex() * stride);
            for (int v = 0; v < count; v++) {
                float x = 0, y = 0, z = 0;
                n.set(0, 1, 0);
                if (from != null) {
                    int o = v * 3;
                    int i0 = p.corners[o], i1 = p.corners[o + 1], i2 = p.corners[o + 2];
                    float w0 = p.weights[o], w1 = p.weights[o + 1], w2 = p.weights[o + 2];
                    blend(from.normals, i0, i1, i2, w0, w1, w2, n);
                    if (n.lengthSquared() > 1e-12f) n.normalize();
                    else n.set(0, 1, 0);
                    blend(from.positions, i0, i1, i2, w0, w1, w2, a);
                    x = a.x + n.x * LIFT;
                    y = a.y + n.y * LIFT;
                    z = a.z + n.z * LIFT;
                }
                GpuModel.putVertex(data, x, y, z, p.uvs[v * 2], p.uvs[v * 2 + 1], n.x, n.y, n.z, p.fade[v]);
            }
        }
        data.position(h.vertexCount * stride);
        data.flip();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(h.vertices.slice(0, h.vertexCount * stride), data);
    }

    /** The piece's mesh primitive with its vertices as drawn now, or null when it is not drawn. */
    @Nullable
    private GltfModel.Primitive source(ViewmodelRig<?> rig, Piece p) {
        if (p.model == rig.model().source) {
            int mesh = p.key >= 0 && p.key < p.model.nodes.size() ? p.model.nodes.get(p.key).mesh : -1;
            return mesh < 0 ? null : p.model.meshes.get(mesh).primitives.get(p.primitive);
        }
        if (armsModel != null && p.model == armsModel.source) {
            ArmDraw arm = arms.get(p.key);
            if (arm == null) return null;
            List<GltfModel.Primitive> prims = arm.skinned != null ? arm.skinned : p.model.meshes.get(p.key).primitives;
            return p.primitive < prims.size() ? prims.get(p.primitive) : null;
        }
        return null;
    }

    private ByteBuffer staging(int bytes) {
        if (staging == null || staging.capacity() < bytes) {
            staging = ByteBuffer.allocateDirect(Math.max(bytes, 16 * 1024)).order(ByteOrder.nativeOrder());
        }
        staging.clear();
        return staging;
    }

    // ---- maths ---------------------------------------------------------------------------------------------

    private static Vector3f corner(float[] p, int v, Vector3f into) {
        return into.set(p[v * 3], p[v * 3 + 1], p[v * 3 + 2]);
    }

    private static Vector3f blend(float[] p, int i0, int i1, int i2, float w0, float w1, float w2, Vector3f into) {
        return into.set(p[i0 * 3] * w0 + p[i1 * 3] * w1 + p[i2 * 3] * w2,
                p[i0 * 3 + 1] * w0 + p[i1 * 3 + 1] * w1 + p[i2 * 3 + 1] * w2,
                p[i0 * 3 + 2] * w0 + p[i1 * 3 + 2] * w1 + p[i2 * 3 + 2] * w2);
    }

    private static int search(float[] cumulative, float value) {
        int lo = 0, hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < value) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Möller–Trumbore: distance along {@code dir} (unit) to the triangle (a, b, c of {@code p}), or −1. Two-sided. */
    private static float intersect(Vector3f o, Vector3f dir, float[] p, int ia, int ib, int ic) {
        float ax = p[ia * 3], ay = p[ia * 3 + 1], az = p[ia * 3 + 2];
        float e1x = p[ib * 3] - ax, e1y = p[ib * 3 + 1] - ay, e1z = p[ib * 3 + 2] - az;
        float e2x = p[ic * 3] - ax, e2y = p[ic * 3 + 1] - ay, e2z = p[ic * 3 + 2] - az;
        float px = dir.y * e2z - dir.z * e2y, py = dir.z * e2x - dir.x * e2z, pz = dir.x * e2y - dir.y * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-14f) return -1;
        float inv = 1 / det;
        float tx = o.x - ax, ty = o.y - ay, tz = o.z - az;
        float u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0 || u > 1) return -1;
        float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        float v = (dir.x * qx + dir.y * qy + dir.z * qz) * inv;
        if (v < 0 || u + v > 1) return -1;
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
