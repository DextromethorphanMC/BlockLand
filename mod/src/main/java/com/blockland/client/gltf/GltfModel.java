package com.blockland.client.gltf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed glTF 2.0 model: plain arrays, no GPU objects. Supports what the gun asset contract needs
 * (tools/assets/VIEWMODEL_SPEC.md): node hierarchy with TRS, triangle meshes (position/normal/uv/indices, baked AO in
 * COLOR_0), metallic-roughness materials with embedded PNGs and the texture set / region extras of spec §20–21, TRS
 * animations (STEP, LINEAR and CUBICSPLINE) with event extras, string-valued node extras, and the skinned arms meshes of
 * spec §7 (JOINTS_0 / WEIGHTS_0 with the node's {@code skin} / {@code bind} extras; no glTF skins).
 */
public final class GltfModel {
    public final List<Node> nodes = new ArrayList<>();
    public final List<Mesh> meshes = new ArrayList<>();
    public final List<Material> materials = new ArrayList<>();
    /** PNG bytes per glTF image. */
    public final List<byte[]> images = new ArrayList<>();
    public final List<Animation> animations = new ArrayList<>();
    /** Node indices ordered so that every parent comes before its children. */
    public int[] order = new int[0];
    private final Map<String, Integer> nodeIndex = new HashMap<>();
    private final Map<String, Animation> animationIndex = new HashMap<>();

    public static final class Node {
        public String name = "";
        public int parent = -1;
        public int[] children = new int[0];
        public int mesh = -1;
        public final float[] translation = {0, 0, 0};
        public final float[] rotation = {0, 0, 0, 1};
        public final float[] scale = {1, 1, 1};
        /** String-valued {@code extras} of the node (e.g. which rig joint an arm mesh belongs to). */
        public final Map<String, String> extras = new HashMap<>();
        /**
         * A skinned mesh's joints ({@code extras.skin}, asset spec §7: indexed by its vertices' JOINTS_0; a name ending in
         * {@code :untwisted} is that joint without its twist about its own Y), or null when the mesh is rigid.
         */
        public String[] skin;
        /** Per skin joint, its translation in the anchor joint's space in the bind pose ({@code extras.bind}, 3 floats each). */
        public float[] bind;
    }

    public static final class Mesh {
        public String name = "";
        public final List<Primitive> primitives = new ArrayList<>();
    }

    public static final class Primitive {
        public float[] positions;
        public float[] normals;
        public float[] uvs;
        /** Baked ambient occlusion per vertex (COLOR_0 red, 1 = open), or null when the mesh has none. */
        public float[] ao;
        /** Four skin joint indices and weights per vertex (JOINTS_0 / WEIGHTS_0), or null when the mesh is rigid. */
        public int[] joints;
        public float[] weights;
        public int[] indices;
        public int material = -1;

        public int vertexCount() {
            return positions.length / 3;
        }
    }

    public static final class Material {
        public String name = "";
        public final float[] baseColor = {1, 1, 1, 1};
        /** Image index of the base colour texture, -1 = none. */
        public int image = -1;
        public float metallic = 1;
        public float roughness = 1;
        public final float[] emissive = {0, 0, 0};
        public String alphaMode = "OPAQUE";
        public float alphaCutoff = 0.5f;
        public boolean doubleSided;
        /** LabPBR texture set under {@code assets/blockland/} without {@code .png} (asset spec §20), or "". */
        public String pbr = "";
        /** Skin region (asset spec §21), or "" when the material does not say (the caller's default applies). */
        public String region = "";
        /** Multiplier on the material's UVs (texture set tiling). */
        public float uvScale = 1;
        /** How far its v runs per metre along a moving surface (a track's links: {@code extras.scroll.vPerMetre}), 0 = none. */
        public float scrollPerMetre;

        public boolean isEmissive() {
            return emissive[0] + emissive[1] + emissive[2] > 0.001f;
        }

        public boolean isBlend() {
            return "BLEND".equals(alphaMode);
        }
    }

    public enum Path { TRANSLATION, ROTATION, SCALE }

    public static final class Channel {
        public int node;
        public Path path;
        public float[] times;
        /** 3 (T/S) or 4 (R) floats per key; for {@link #cubic} three such groups per key: in-tangent, value, out-tangent. */
        public float[] values;
        public boolean step;
        /** CUBICSPLINE: Hermite curve through the keys with authored tangents (eases, overshoot) instead of straight lines. */
        public boolean cubic;
    }

    public record Event(float time, String name) {
    }

    public static final class Animation {
        public String name = "";
        public float duration;
        public final List<Channel> channels = new ArrayList<>();
        public final List<Event> events = new ArrayList<>();
    }

    // ---- lookups --------------------------------------------------------------------------------

    void index() {
        nodeIndex.clear();
        for (int i = 0; i < nodes.size(); i++) nodeIndex.putIfAbsent(nodes.get(i).name, i);
        animationIndex.clear();
        for (Animation animation : animations) animationIndex.putIfAbsent(animation.name, animation);
        // parents first: breadth-first from the roots
        List<Integer> ordered = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).parent < 0) ordered.add(i);
        for (int cursor = 0; cursor < ordered.size(); cursor++) {
            for (int child : nodes.get(ordered.get(cursor)).children) ordered.add(child);
        }
        order = ordered.stream().mapToInt(Integer::intValue).toArray();
    }

    /** @return the node index, or -1 */
    public int node(String name) {
        Integer index = nodeIndex.get(name);
        return index == null ? -1 : index;
    }

    public Animation animation(String name) {
        return animationIndex.get(name);
    }

    /** Whether {@code node} is {@code ancestor} or below it. */
    public boolean isUnder(int node, int ancestor) {
        for (int n = node; n >= 0; n = nodes.get(n).parent) if (n == ancestor) return true;
        return false;
    }
}
