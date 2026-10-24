package com.blockland.client.spray;

import com.blockland.BlockLand;
import com.blockland.client.craft.WeaponLook;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.MaterialLayer;
import com.blockland.client.render.TextureRef;
import com.blockland.craft.Build;
import com.blockland.craft.Craftable;
import com.blockland.craft.Spray;
import com.blockland.craft.SprayCatalog;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sprays of one build as GPU geometry (asset spec §29): {@link SprayProjector}'s pieces packed into a small model of
 * their own, one mesh per (spray, node) in the node's space, so the viewmodels draw each at its node's animated matrix
 * and the 工匠 preview at the rest pose. Drawn alpha-blended like paint, later sprays on top; the vanilla-render-type
 * path (the shadow map's entity capture) draws the same triangles. Cached per weapon look and sprays; evicted entries free their buffers.
 */
public final class SprayDecals implements AutoCloseable {
    private static final int CACHE = 8;
    /** Nothing to draw (no sprays, model missing). */
    public static final SprayDecals EMPTY = new SprayDecals(null, null, new Matrix4f(), List.of(), List.of());
    private static final MaterialLayer PAINT = new MaterialLayer(null, 0, null, MaterialLayer.DECAL, null, new float[4]);

    private static final Map<Key, SprayDecals> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, SprayDecals> eldest) {
            if (size() <= CACHE) return false;
            eldest.getValue().close();
            return true;
        }
    };

    private record Key(ResourceLocation model, String weapon, Build build) {
    }

    /** One mesh of the decal model: which node of which model it belongs to. */
    private record Mesh(int mesh, String key, int node, Matrix4f rest) {
    }

    @Nullable
    private final GpuModel model;
    @Nullable
    private final GltfModel weapon;
    /** The weapon root's rest matrix (the pieces' rest matrices are relative to the root). */
    private final Matrix4f rootRest;
    private final List<Mesh> meshes;
    private final List<TextureRef> textures;
    private final int[] weaponNodes;
    private final Map<String, int[]> attachmentNodes = new HashMap<>();
    /** suffix → node → its twin (a dual gun's left-hand copy with the same mesh), -1 when there is none. */
    private final Map<String, int[]> twins = new HashMap<>();
    private final GpuModel.Style style;

    private SprayDecals(@Nullable GpuModel model, @Nullable GltfModel weapon, Matrix4f rootRest, List<Mesh> meshes,
                        List<TextureRef> textures) {
        this.model = model;
        this.weapon = weapon;
        this.rootRest = rootRest;
        this.meshes = meshes;
        this.textures = textures;
        this.weaponNodes = meshes.stream().filter(m -> m.key.isEmpty()).mapToInt(Mesh::node).distinct().toArray();
        Map<String, List<Integer>> byKey = new HashMap<>();
        for (Mesh m : meshes) if (!m.key.isEmpty()) byKey.computeIfAbsent(m.key, k -> new ArrayList<>()).add(m.node);
        byKey.forEach((key, nodes) -> attachmentNodes.put(key, nodes.stream().mapToInt(Integer::intValue).distinct().toArray()));
        this.style = new GpuModel.Style() {
            @Override
            public TextureRef textureRef(String material) {
                int index = materialIndex(material);
                return index >= 0 && index < textures.size() ? textures.get(index) : null;
            }

            @Override
            public MaterialLayer layer(String material, String region) {
                return PAINT;
            }
        };
    }

    /** The sprays of a build on its weapon (cheap when there are none, cached otherwise). */
    public static SprayDecals of(Craftable weapon, Build build) {
        if (build.sprays().isEmpty()) return EMPTY;
        Key key = new Key(WeaponLook.modelOf(weapon, build), weapon.id(), build);
        SprayDecals decals = cache.get(key);
        if (decals != null) return decals;
        SprayScene scene = SprayScene.of(weapon, build);
        if (scene == null) return EMPTY;
        decals = build(weapon.id(), scene, build.sprays());
        cache.put(key, decals);
        return decals;
    }

    /** Resource reload: every decal model goes (and the scenes they were made from). */
    public static void clear() {
        cache.values().forEach(SprayDecals::close);
        cache.clear();
        SprayScene.clear();
    }

    private static SprayDecals build(String weaponId, SprayScene scene, List<Spray> sprays) {
        GltfModel source = new GltfModel();
        List<Mesh> meshes = new ArrayList<>();
        List<TextureRef> textures = new ArrayList<>();
        for (int s = 0; s < sprays.size(); s++) {
            Spray spray = sprays.get(s);
            SprayCatalog.Def def = SprayCatalog.get().get(spray.id());
            if (def == null || def.texture.isBlank()) continue;
            TextureRef texture;
            try {
                texture = TextureRef.resource(BlockLand.id(def.texture));
            } catch (RuntimeException e) {
                continue;
            }
            int material = source.materials.size();
            GltfModel.Material paint = new GltfModel.Material();
            paint.name = "spray_" + material;
            paint.metallic = 0;
            paint.roughness = 0.62f;
            paint.alphaMode = "BLEND";
            paint.region = "spray";
            source.materials.add(paint);
            textures.add(texture);
            for (SprayProjector.Piece piece : SprayProjector.project(scene, spray)) {
                SprayScene.Target target = scene.targets.get(piece.target());
                GltfModel.Mesh mesh = new GltfModel.Mesh();
                mesh.name = weaponId + "_spray_" + s + "_" + target.key() + target.node();
                mesh.primitives.add(piece.primitive(material));
                meshes.add(new Mesh(source.meshes.size(), target.key(), target.node(), target.rest()));
                source.meshes.add(mesh);
            }
        }
        if (meshes.isEmpty()) return new SprayDecals(null, scene.weapon, scene.rootRest(), List.of(), List.of());
        GpuModel gpu = GpuModel.upload("BlockLand sprays " + weaponId, source);
        return new SprayDecals(gpu, scene.weapon, scene.rootRest(), List.copyOf(meshes), List.copyOf(textures));
    }

    private static int materialIndex(String material) {
        if (!material.startsWith("spray_")) return -1;
        try {
            return Integer.parseInt(material.substring(6));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public boolean isEmpty() {
        return model == null || meshes.isEmpty();
    }

    /** Whether these decals were made for this weapon model (the viewmodel may draw another one for a moment). */
    public boolean madeFor(@Nullable GpuModel weaponModel) {
        return weaponModel != null && weaponModel.source == weapon;
    }

    /** Nodes of the weapon model that carry decals. */
    public int[] weaponNodes() {
        return weaponNodes;
    }

    /** Nodes of the attachment with this key that carry decals. */
    public int[] attachmentNodes(String key) {
        return attachmentNodes.getOrDefault(key, new int[0]);
    }

    /**
     * A dual gun's left-hand copy of a weapon node ({@code name + suffix}) when it has the same mesh, so the decal fits
     * it as well; -1 otherwise.
     */
    public int twin(int node, String suffix) {
        if (weapon == null) return -1;
        int[] map = twins.computeIfAbsent(suffix, s -> twinMap(weapon, s));
        return node >= 0 && node < map.length ? map[node] : -1;
    }

    private static int[] twinMap(GltfModel source, String suffix) {
        int[] map = new int[source.nodes.size()];
        for (int i = 0; i < map.length; i++) {
            GltfModel.Node node = source.nodes.get(i);
            int twin = node.mesh < 0 ? -1 : source.node(node.name + suffix);
            map[i] = twin >= 0 && sameMesh(source, node.mesh, source.nodes.get(twin).mesh) ? twin : -1;
        }
        return map;
    }

    private static boolean sameMesh(GltfModel source, int a, int b) {
        if (a == b) return true;
        if (b < 0) return false;
        List<GltfModel.Primitive> pa = source.meshes.get(a).primitives, pb = source.meshes.get(b).primitives;
        if (pa.size() != pb.size()) return false;
        for (int i = 0; i < pa.size(); i++) {
            float[] x = pa.get(i).positions, y = pb.get(i).positions;
            if (x.length != y.length) return false;
            for (int k = 0; k < x.length; k++) if (Math.abs(x[k] - y[k]) > 1e-5f) return false;
        }
        return true;
    }

    /** Queues the decals of a weapon node at {@code modelView} (that node's matrix in view space). */
    public void drawWeaponNode(DrawList list, int node, Matrix4f modelView, int blockLight, int skyLight) {
        draw(list, "", node, modelView, blockLight, skyLight);
    }

    /** Queues the decals of a node of the attachment with this key. */
    public void drawAttachmentNode(DrawList list, String key, int node, Matrix4f modelView, int blockLight, int skyLight) {
        draw(list, key, node, modelView, blockLight, skyLight);
    }

    private void draw(DrawList list, String key, int node, Matrix4f modelView, int blockLight, int skyLight) {
        if (model == null) return;
        for (Mesh m : meshes) {
            if (m.node == node && m.key.equals(key)) model.addMesh(list, m.mesh, modelView, style, blockLight, skyLight, 1);
        }
    }

    /** Queues every decal at the rest pose ({@code view} maps the weapon's rest-pose space to view space: the preview). */
    public void drawRest(DrawList list, Matrix4f view, int blockLight, int skyLight) {
        if (model == null) return;
        Matrix4f modelView = new Matrix4f();
        for (Mesh m : meshes) {
            model.addMesh(list, m.mesh, modelView.set(view).mul(rootRest).mul(m.rest), style, blockLight, skyLight, 1);
        }
    }

    @Override
    public void close() {
        if (model != null) model.close();
    }
}
