package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.Textures;
import com.blockland.gun.GunCatalog;
import com.blockland.gun.GunData;
import com.blockland.gun.GunState;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** How a configured gun looks: which attachment nodes are visible and the skin's material overrides. */
final class GunLook {
    private static final Map<GunData.Skin, GpuModel.Style> styles = new IdentityHashMap<>();

    private GunLook() {
    }

    /**
     * Per node: hidden because its attachment is not fitted (or an ancestor is hidden).
     *
     * @param keepRoot only this root's subtree is drawn (e.g. {@code viewmodel}); -1 = all roots
     */
    static boolean[] hiddenNodes(GltfModel source, GunData.Gun gun, GunState state, int keepRoot) {
        GunCatalog catalog = GunCatalog.get();
        Set<String> all = new HashSet<>(), shown = new HashSet<>();
        for (String id : gun.magazines) {
            GunData.Magazine m = catalog.magazine(id);
            if (m == null || m.node.isEmpty()) continue;
            all.add(m.node);
            if (id.equals(state.magazine())) shown.add(m.node);
        }
        for (String id : gun.sights) {
            GunData.Sight s = catalog.sight(id);
            if (s == null) continue;
            all.addAll(s.nodes);
            if (id.equals(state.sight())) shown.addAll(s.nodes);
        }
        for (String id : gun.lasers) {
            GunData.Laser l = catalog.laser(id);
            if (l == null) continue;
            all.addAll(l.nodes);
            if (id.equals(state.laser())) shown.addAll(l.nodes);
        }
        boolean[] hidden = new boolean[source.nodes.size()];
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            boolean self = all.contains(node.name) && !shown.contains(node.name);
            boolean outside = keepRoot >= 0 && node.parent < 0 && index != keepRoot;
            hidden[index] = self || outside || (node.parent >= 0 && hidden[node.parent]);
        }
        return hidden;
    }

    /** Skin overrides from guns.json (per-material tints and replacement textures); cached per skin. */
    static GpuModel.Style skin(@Nullable GunData.Skin skin) {
        if (skin == null || skin.tints.isEmpty() && skin.textures.isEmpty()) return GpuModel.Style.NONE;
        return styles.computeIfAbsent(skin, s -> new GpuModel.Style() {
            @Override
            public float[] tint(String material) {
                return s.tints.get(material);
            }

            @Override
            public GpuTextureView texture(String material) {
                String path = s.textures.get(material);
                return path == null ? null : Textures.resource(BlockLand.id(path));
            }
        });
    }
}
