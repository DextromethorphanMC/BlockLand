package com.blockland.client.scope;

import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The eyepiece of a scope model, measured once from its rest pose (asset spec §22): the glass disc's centre and radius
 * in the eyepiece node's own space and the eye relief (how far behind the glass the optic's {@code aim} eye point is).
 *
 * @param centre centre of the eyepiece glass (node space)
 * @param radius radius of the glass disc (node space)
 * @param relief distance from the glass to the eye point along +Z (node space)
 */
public record ScopeLens(Vector3f centre, float radius, float relief) {
    private static final Map<GltfModel, Map<String, ScopeLens>> CACHE = new WeakHashMap<>();
    private static final ScopeLens NONE = new ScopeLens(new Vector3f(), 0, 0);

    /** The eyepiece of a scope model, or null when the model lacks the node, its mesh or an eye point behind it. */
    @Nullable
    public static ScopeLens of(GltfModel model, String lensNode, String aimNode) {
        ScopeLens lens = CACHE.computeIfAbsent(model, m -> new HashMap<>())
                .computeIfAbsent(lensNode + "|" + aimNode, key -> measure(model, lensNode, aimNode));
        return lens == NONE ? null : lens;
    }

    private static ScopeLens measure(GltfModel model, String lensNode, String aimNode) {
        int lens = model.node(lensNode), aim = model.node(aimNode);
        if (lens < 0 || aim < 0 || model.nodes.get(lens).mesh < 0) return NONE;
        Pose rest = new Pose(model);
        rest.computeWorld(new Matrix4f());
        Matrix4f toLens = new Matrix4f(rest.world[lens]).invert();
        Vector3f eye = toLens.transformPosition(rest.world[aim].getTranslation(new Vector3f()));
        // the glass: centroid and the furthest vertex from it in the disc's plane
        Vector3f sum = new Vector3f();
        int count = 0;
        for (GltfModel.Primitive primitive : model.meshes.get(model.nodes.get(lens).mesh).primitives) {
            float[] p = primitive.positions;
            for (int i = 0; i < p.length; i += 3) {
                sum.add(p[i], p[i + 1], p[i + 2]);
                count++;
            }
        }
        if (count == 0) return NONE;
        Vector3f centre = sum.div(count);
        float radius = 0;
        for (GltfModel.Primitive primitive : model.meshes.get(model.nodes.get(lens).mesh).primitives) {
            float[] p = primitive.positions;
            for (int i = 0; i < p.length; i += 3) {
                float dx = p[i] - centre.x, dy = p[i + 1] - centre.y;
                radius = Math.max(radius, (float) Math.sqrt(dx * dx + dy * dy));
            }
        }
        float relief = eye.z - centre.z;
        return radius > 1e-5f && relief > 1e-4f ? new ScopeLens(centre, radius, relief) : NONE;
    }
}
