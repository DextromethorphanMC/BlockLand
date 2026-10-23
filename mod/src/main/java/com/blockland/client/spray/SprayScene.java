package com.blockland.client.spray;

import com.blockland.client.craft.WeaponLook;
import com.blockland.client.gltf.ClipSampler;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.craft.Build;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.blockland.craft.Spray;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What sprays land on (asset spec §29): a weapon as drawn for one build — the model actually drawn (a special skin's own),
 * the parts the build shows and the fitted attachments — in its rest pose (the idle's first frame, the same pose the
 * viewmodel and the 工匠 preview start from), with the frame a placement's (u, v) refer to. CPU only; cached per weapon
 * look (the sprays themselves are not part of the key).
 */
public final class SprayScene {
    private static final int CACHE = 8;
    private static final Map<Key, SprayScene> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, SprayScene> eldest) {
            return size() > CACHE;
        }
    };

    private record Key(ResourceLocation model, String weapon, Build look) {
    }

    /**
     * One mesh node sprays may land on, with the triangles of its sprayable materials, in the weapon root's frame.
     *
     * @param key   "" for the weapon's own node, else the attachment's key (its slot, as {@link WeaponLook#attachments})
     * @param rest  the node's rest-pose matrix in the weapon root's frame
     * @param world triangle corners in the root's frame, 9 floats per triangle, per primitive
     */
    public record Target(String key, int node, GltfModel source, Matrix4f rest, List<GltfModel.Primitive> primitives,
                         List<float[]> world) {
    }

    /**
     * An attachment fitted at a mount node of the weapon.
     *
     * @param region skin region of its materials that name none (its slot's; charms: none)
     */
    record Mount(String key, GltfModel source, int mount, String region) {
    }

    /** Where a ray met the weapon. */
    public record Hit(Vector3f point, float distance, int target) {
    }

    /** The weapon model the scene was made from (decals only fit that one). */
    final GltfModel weapon;
    /**
     * The weapon root's rest matrix in model space. Everything else in the scene lives in the root's own frame (+X right,
     * +Y up, −Z the muzzle), so the side views are square with the weapon whatever its hip-pose cant.
     */
    final Matrix4f rootRest;
    final List<Target> targets;
    /** The frame of (u, v): the rest-pose box around every mesh under the weapon root (attachments not). */
    final Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
    /** Everything sprayable, attachments included (rays start outside it). */
    final Vector3f outerMin = new Vector3f(Float.MAX_VALUE), outerMax = new Vector3f(-Float.MAX_VALUE);

    private SprayScene(GltfModel weapon, Matrix4f rootRest, List<Target> targets) {
        this.weapon = weapon;
        this.rootRest = rootRest;
        this.targets = targets;
    }

    /** The scene of a weapon wearing {@code build} (sprays ignored); null while its model is missing. */
    @Nullable
    public static SprayScene of(Craftable weapon, Build build) {
        ResourceLocation location = WeaponLook.modelOf(weapon, build);
        Key key = new Key(location, weapon.id(), build.withSprays(List.of()));
        SprayScene scene = cache.get(key);
        if (scene != null) return scene;
        GpuModel model = ModelCache.get(location);
        if (model == null) return null;
        GltfModel source = model.source;
        int root = source.node(weapon.modelRoot());
        if (root < 0) return null;
        boolean[] hidden = WeaponLook.hiddenNodes(source, weapon, build, -1);
        List<Mount> mounts = new ArrayList<>();
        for (ViewmodelRig.Attachment attachment : WeaponLook.attachments(weapon, build)) {
            int mount = source.node(attachment.mount());
            GpuModel att = mount < 0 ? null : ModelCache.get(attachment.model());
            if (att != null) mounts.add(new Mount(attachment.key(), att.source, mount, attachment.region()));
        }
        scene = assemble(source, root, hidden, mounts);
        if (scene != null) cache.put(key, scene);
        return scene;
    }

    /** Resource reload: the models are new objects. */
    public static void clear() {
        cache.clear();
    }

    /**
     * The scene from plain model data: the weapon (its root, the nodes the build hides) and the fitted attachments, all
     * in the rest pose.
     */
    @Nullable
    static SprayScene assemble(GltfModel source, int root, boolean[] hidden, List<Mount> mounts) {
        Pose rest = new Pose(source);
        GltfModel.Animation idle = source.animation("idle");
        if (idle != null) new ClipSampler(idle).sample(0, rest);
        rest.computeWorld(new Matrix4f());
        // the root's own frame: every rest matrix below is relative to it
        Matrix4f toRoot = new Matrix4f(rest.world[root]).invert();
        Matrix4f[] local = new Matrix4f[source.nodes.size()];
        for (int i = 0; i < local.length; i++) local[i] = new Matrix4f(toRoot).mul(rest.world[i]);

        List<Target> targets = new ArrayList<>();
        SprayScene scene = new SprayScene(source, new Matrix4f(rest.world[root]), targets);
        Vector3f p = new Vector3f();
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            if (node.mesh < 0 || !source.isUnder(index, root) || degenerate(local[index])) continue;
            // the frame: every mesh under the root, whatever the build shows (so placements stay put)
            for (GltfModel.Primitive primitive : source.meshes.get(node.mesh).primitives) {
                float[] pos = primitive.positions;
                for (int v = 0; v < pos.length; v += 3) {
                    local[index].transformPosition(pos[v], pos[v + 1], pos[v + 2], p);
                    scene.min.min(p);
                    scene.max.max(p);
                }
            }
            if (!hidden[index]) scene.add(targets, "", index, source, local[index], "");
        }
        // fitted attachments on parts of the weapon (a dual gun's left-hand twins are not under the root)
        for (Mount attachment : mounts) {
            int mount = attachment.mount();
            if (mount < 0 || mount >= hidden.length || hidden[mount] || !source.isUnder(mount, root)) continue;
            GltfModel src = attachment.source();
            Pose pose = new Pose(src);
            pose.computeWorld(local[mount]);
            int attRoot = src.node("attachment");
            for (int index : src.order) {
                GltfModel.Node node = src.nodes.get(index);
                if (node.mesh < 0 || attRoot >= 0 && !src.isUnder(index, attRoot) || degenerate(pose.world[index])) continue;
                scene.add(targets, attachment.key(), index, src, pose.world[index], attachment.region());
            }
        }
        if (scene.min.x > scene.max.x) return null;
        return scene;
    }

    private void add(List<Target> into, String key, int node, GltfModel source, Matrix4f rest, String defaultRegion) {
        List<GltfModel.Primitive> primitives = new ArrayList<>();
        List<float[]> world = new ArrayList<>();
        Vector3f p = new Vector3f();
        for (GltfModel.Primitive primitive : source.meshes.get(source.nodes.get(node).mesh).primitives) {
            GltfModel.Material material = primitive.material >= 0 && primitive.material < source.materials.size()
                    ? source.materials.get(primitive.material) : null;
            if (!sprayable(material, defaultRegion) || primitive.indices == null) continue;
            float[] tris = new float[primitive.indices.length * 3];
            for (int i = 0; i < primitive.indices.length; i++) {
                int v = primitive.indices[i];
                rest.transformPosition(primitive.positions[v * 3], primitive.positions[v * 3 + 1], primitive.positions[v * 3 + 2], p);
                tris[i * 3] = p.x;
                tris[i * 3 + 1] = p.y;
                tris[i * 3 + 2] = p.z;
                outerMin.min(p);
                outerMax.max(p);
            }
            primitives.add(primitive);
            world.add(tris);
        }
        if (!primitives.isEmpty()) into.add(new Target(key, node, source, new Matrix4f(rest), primitives, world));
    }

    /** Paint sticks to the weapon's own surfaces: not to glass, lenses, brass, glowing parts or charms (region none). */
    private static boolean sprayable(@Nullable GltfModel.Material material, String defaultRegion) {
        if (material == null) return true;
        if (material.isBlend() || "MASK".equals(material.alphaMode) || "ADD".equals(material.alphaMode) || material.isEmissive()) {
            return false;
        }
        String region = material.region.isEmpty() ? defaultRegion : material.region;
        return !CraftData.Skin.NO_REGION.equals(region);
    }

    private static boolean degenerate(Matrix4f m) {
        return Math.abs(m.determinant3x3()) < 1e-12f;
    }

    // ---- the side views -------------------------------------------------------------------------------

    /** Direction a spray of this side is projected in (left: from −X towards +X). */
    public static Vector3f direction(String side) {
        return Spray.RIGHT.equals(side) ? new Vector3f(-1, 0, 0) : new Vector3f(1, 0, 0);
    }

    /** The view's "right" on that side (left side: +Z, the muzzle on the left). */
    public static Vector3f right(String side) {
        return Spray.RIGHT.equals(side) ? new Vector3f(0, 0, -1) : new Vector3f(0, 0, 1);
    }

    /** The spray's centre on the side view, in rest-pose space (its projection coordinate is left at 0). */
    public Vector3f centre(Spray spray) {
        float z = spray.onRight() ? max.z - spray.u() * (max.z - min.z) : min.z + spray.u() * (max.z - min.z);
        float y = max.y - spray.v() * (max.y - min.y);
        return new Vector3f(0, y, z);
    }

    /** (u, v) of a rest-pose point seen from {@code side} (clamped to the frame). */
    public Vector2f uv(String side, Vector3fc point) {
        float dz = Math.max(1e-5f, max.z - min.z), dy = Math.max(1e-5f, max.y - min.y);
        float u = Spray.RIGHT.equals(side) ? (max.z - point.z()) / dz : (point.z() - min.z) / dz;
        float v = (max.y - point.y()) / dy;
        return new Vector2f(Math.max(0, Math.min(1, u)), Math.max(0, Math.min(1, v)));
    }

    /** The X of the frame's face on that side (where the side view's plane touches the weapon's box). */
    public float sidePlane(String side) {
        return Spray.RIGHT.equals(side) ? max.x : min.x;
    }

    /** The weapon root's rest matrix in model space (root frame → the preview's / viewmodel's rest space). */
    public Matrix4f rootRest() {
        return new Matrix4f(rootRest);
    }

    /**
     * The view rotation that shows {@code side} square on, for a camera in model space: the root's +X (left) or −X (right)
     * straight ahead, its +Y up.
     */
    public Quaternionf sideView(String side) {
        Quaternionf root = rootRest.getNormalizedRotation(new Quaternionf());
        return new Quaternionf().rotateY((float) Math.toRadians(Spray.RIGHT.equals(side) ? -90 : 90)).mul(root.conjugate());
    }

    /** Height and length of the frame (metres), for a sensible default spray size. */
    public float frameHeight() {
        return max.y - min.y;
    }

    public float frameLength() {
        return max.z - min.z;
    }

    /** Where a ray starts for this side: just outside everything sprayable. */
    float startCoordinate(String side) {
        return Spray.RIGHT.equals(side) ? outerMax.x + 0.01f : outerMin.x - 0.01f;
    }

    // ---- rays -----------------------------------------------------------------------------------------

    /** The nearest sprayable surface along a ray in the root's frame, or null. */
    @Nullable
    public Hit pick(Vector3fc origin, Vector3fc direction) {
        Vector3f dir = new Vector3f(direction).normalize();
        float best = Float.MAX_VALUE;
        int bestTarget = -1;
        for (int t = 0; t < targets.size(); t++) {
            for (float[] tris : targets.get(t).world()) {
                for (int i = 0; i + 8 < tris.length; i += 9) {
                    float d = SprayProjector.intersect(origin, dir, tris, i);
                    if (d > 0 && d < best) {
                        best = d;
                        bestTarget = t;
                    }
                }
            }
        }
        if (bestTarget < 0) return null;
        return new Hit(new Vector3f(dir).mul(best).add(origin), best, bestTarget);
    }

    /** The rest-pose normal matrix of a target (for its vertex normals). */
    static Matrix3f normalMatrix(Target target) {
        return new Matrix3f(target.rest()).invert().transpose();
    }
}
