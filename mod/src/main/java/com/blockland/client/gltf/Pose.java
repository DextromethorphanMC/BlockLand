package com.blockland.client.gltf;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Local TRS of every node plus the resolved model-space matrices. Preallocated per model instance:
 * sampling and matrix updates never allocate, which keeps the per-frame cost flat.
 */
public final class Pose {
    private final GltfModel model;
    public final float[] t;
    public final float[] r;
    public final float[] s;
    /** Model-space (or caller-space when a root matrix is given) transform per node. */
    public final Matrix4f[] world;
    private final Quaternionf q = new Quaternionf();
    private final Quaternionf q2 = new Quaternionf();

    public Pose(GltfModel model) {
        this.model = model;
        int n = model.nodes.size();
        t = new float[n * 3];
        r = new float[n * 4];
        s = new float[n * 3];
        world = new Matrix4f[n];
        for (int i = 0; i < n; i++) world[i] = new Matrix4f();
        reset();
    }

    public GltfModel model() {
        return model;
    }

    /** Back to the rest pose stored in the file. */
    public void reset() {
        for (int i = 0; i < model.nodes.size(); i++) {
            GltfModel.Node node = model.nodes.get(i);
            System.arraycopy(node.translation, 0, t, i * 3, 3);
            System.arraycopy(node.rotation, 0, r, i * 4, 4);
            System.arraycopy(node.scale, 0, s, i * 3, 3);
        }
    }

    public void copyFrom(Pose other) {
        System.arraycopy(other.t, 0, t, 0, t.length);
        System.arraycopy(other.r, 0, r, 0, r.length);
        System.arraycopy(other.s, 0, s, 0, s.length);
    }

    /** this = lerp(this, other, w) per node (slerp for rotations). */
    public void blend(Pose other, float w) {
        if (w <= 0) return;
        if (w >= 1) {
            copyFrom(other);
            return;
        }
        for (int i = 0; i < t.length; i++) {
            t[i] += (other.t[i] - t[i]) * w;
            s[i] += (other.s[i] - s[i]) * w;
        }
        for (int i = 0; i < r.length; i += 4) {
            q.set(r[i], r[i + 1], r[i + 2], r[i + 3]);
            q2.set(other.r[i], other.r[i + 1], other.r[i + 2], other.r[i + 3]);
            q.slerp(q2, w);
            r[i] = q.x;
            r[i + 1] = q.y;
            r[i + 2] = q.z;
            r[i + 3] = q.w;
        }
    }

    /** Resolves every node's matrix: {@code root × parent chain × local TRS}. */
    public void computeWorld(Matrix4f root) {
        for (int index : model.order) {
            GltfModel.Node node = model.nodes.get(index);
            Matrix4f m = world[index];
            if (node.parent < 0) m.set(root);
            else m.set(world[node.parent]);
            int ti = index * 3, ri = index * 4;
            q.set(r[ri], r[ri + 1], r[ri + 2], r[ri + 3]);
            m.translate(t[ti], t[ti + 1], t[ti + 2]).rotate(q).scale(s[ti], s[ti + 1], s[ti + 2]);
        }
    }

    public void setTranslation(int node, float x, float y, float z) {
        t[node * 3] = x;
        t[node * 3 + 1] = y;
        t[node * 3 + 2] = z;
    }

    public void addTranslation(int node, float x, float y, float z) {
        t[node * 3] += x;
        t[node * 3 + 1] += y;
        t[node * 3 + 2] += z;
    }

    public void setRotation(int node, Quaternionf rotation) {
        r[node * 4] = rotation.x;
        r[node * 4 + 1] = rotation.y;
        r[node * 4 + 2] = rotation.z;
        r[node * 4 + 3] = rotation.w;
    }

    public Quaternionf rotation(int node, Quaternionf into) {
        return into.set(r[node * 4], r[node * 4 + 1], r[node * 4 + 2], r[node * 4 + 3]);
    }

    /** Effective visibility: a node scaled to zero by an animation (spec §4) hides its subtree. */
    public boolean isCollapsed(int node) {
        return Math.abs(s[node * 3]) < 1e-4f && Math.abs(s[node * 3 + 1]) < 1e-4f && Math.abs(s[node * 3 + 2]) < 1e-4f;
    }
}
