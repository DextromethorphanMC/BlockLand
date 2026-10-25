package com.blockland.client.viewmodel;

import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.GunPipelines;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
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

/**
 * The skinned arms meshes of {@code arms.glb} (asset spec §7): the shoulder-to-wrist tube and the palm bend round at the
 * elbow and wrist instead of folding as rigid boxes. Skinned on the CPU every frame with dual quaternions (the elbow and
 * the spread forearm twist keep their volume), in the anchor joint's space, into a vertex buffer laid out like the arms
 * model's own; the draw keeps the static index ranges and the anchor's model-view, so the hand-motion pass
 * ({@link DrawList#motion}) measures the anchor's movement — only the change of the bend itself between two frames is
 * not in the motion vectors.
 * <p>
 * Every mesh writes its own vertex range; a mesh drawn again before the rig flushes (a second draw of the same frame)
 * takes the next buffer of a small pool, so no draw waiting in the list ever sees its vertices overwritten. One per
 * rig: different rigs never share buffers.
 */
final class ArmSkin implements AutoCloseable {
    private static final String UNTWISTED = ":untwisted";
    /** Draws of one mesh before a flush that still get a buffer of their own (more wrap around: stale but harmless). */
    private static final int MAX_GENERATIONS = 4;

    @Nullable
    private GpuModel arms;
    private final List<GpuBuffer> buffers = new ArrayList<>();
    /** Per arms mesh: how often it was drawn since the last flush. */
    private int[] drawn = new int[0];
    /** Per (generation, mesh): the skinned triangles for the vanilla path. */
    private final Map<Long, List<GltfModel.Primitive>> sources = new HashMap<>();
    /** Per mesh: its last skinned triangles (what lies on the arm, blood, rides on them: ViewmodelBlood). */
    private final Map<Integer, List<GltfModel.Primitive>> latest = new HashMap<>();
    @Nullable
    private ByteBuffer staging;

    // scratch
    private float[] real = new float[16], dual = new float[16];
    private final Matrix4f inverse = new Matrix4f(), skin = new Matrix4f(), joint = new Matrix4f(), local = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Quaternionf q = new Quaternionf(), twist = new Quaternionf();
    private final Vector3f t = new Vector3f();

    /** The rig flushed its draw list: every buffer is free again. */
    void flushed() {
        java.util.Arrays.fill(drawn, 0);
    }

    /**
     * Skins and queues mesh {@code mesh} of {@code model} (an arms.glb mesh node with {@code skin} extras) on the weapon's
     * pose. {@code anchor} is the weapon node of the mesh's {@code extras.joint} (drawn, not collapsed).
     */
    void draw(DrawList draws, GpuModel model, GltfModel.Node node, Pose pose, int anchor, Matrix4f base, GpuModel.Style style,
              int blockLight, int skyLight) {
        prepare(model);
        int mesh = node.mesh;
        int generation = drawn[mesh]++ % MAX_GENERATIONS;
        GpuBuffer buffer = buffer(generation);
        jointTransforms(node, pose, anchor);
        List<GltfModel.Primitive> skinned = sources.computeIfAbsent(((long) generation << 32) | mesh, k -> copies(model, mesh));
        List<GpuModel.Part> parts = model.parts(mesh);
        int stride = GunPipelines.FORMAT.getVertexSize();
        for (int i = 0; i < parts.size(); i++) {
            GpuModel.Part part = parts.get(i);
            GltfModel.Primitive from = part.primitive(), to = skinned.get(i);
            int count = from.vertexCount();
            ByteBuffer data = staging(count * stride);
            skin(from, to, data, node.skin.length);
            data.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(part.baseVertex() * stride, count * stride), data);
        }
        modelView.set(base).mul(pose.world[anchor]);
        model.addMesh(draws, mesh, buffer, skinned, modelView, style, blockLight, skyLight, 1);
        latest.put(mesh, skinned);
    }

    /** The mesh's vertices as last skinned (anchor space, one primitive per part), or null when it was not drawn. */
    @Nullable
    List<GltfModel.Primitive> skinned(int mesh) {
        return latest.get(mesh);
    }

    /**
     * Per skin joint, the dual quaternion of {@code anchor⁻¹ · joint · bind⁻¹}: where the joint has moved its bind-pose
     * vertices, in the anchor's space. A joint the weapon lacks (or that is collapsed) follows the anchor.
     */
    private void jointTransforms(GltfModel.Node node, Pose pose, int anchor) {
        int n = node.skin.length;
        if (real.length < n * 4) {
            real = new float[n * 4];
            dual = new float[n * 4];
        }
        GltfModel weapon = pose.model();
        pose.world[anchor].invertAffine(inverse);
        for (int i = 0; i < n; i++) {
            String name = node.skin[i];
            boolean untwisted = name.endsWith(UNTWISTED);
            int index = weapon.node(untwisted ? name.substring(0, name.length() - UNTWISTED.length()) : name);
            if (index < 0 || Math.abs(pose.world[index].determinant3x3()) < 1e-9f) {
                joint.set(pose.world[anchor]);
            } else {
                int parent = weapon.nodes.get(index).parent;
                if (untwisted && parent >= 0 && Math.abs(pose.world[parent].determinant3x3()) > 1e-9f) {
                    untwist(pose.world[parent], pose.world[index], joint);
                } else {
                    joint.set(pose.world[index]);
                }
            }
            skin.set(inverse).mul(joint).translate(-node.bind[i * 3], -node.bind[i * 3 + 1], -node.bind[i * 3 + 2]);
            skin.getNormalizedRotation(q);
            skin.getTranslation(t);
            int o = i * 4;
            real[o] = q.x;
            real[o + 1] = q.y;
            real[o + 2] = q.z;
            real[o + 3] = q.w;
            // dual part: ½ · (t, 0) · real
            dual[o] = 0.5f * (t.x * q.w + t.y * q.z - t.z * q.y);
            dual[o + 1] = 0.5f * (-t.x * q.z + t.y * q.w + t.z * q.x);
            dual[o + 2] = 0.5f * (t.x * q.y - t.y * q.x + t.z * q.w);
            dual[o + 3] = -0.5f * (t.x * q.x + t.y * q.y + t.z * q.z);
        }
    }

    /**
     * The joint's world matrix without its twist about its own Y relative to its parent (swing only): the elbow blends
     * into this forearm, the twist comes in down the forearm (asset spec §7).
     */
    private void untwist(Matrix4f parent, Matrix4f child, Matrix4f into) {
        parent.invertAffine(local).mul(child);
        local.getNormalizedRotation(q);
        float len = (float) Math.sqrt(q.y * q.y + q.w * q.w);
        if (len > 1e-6f) twist.set(0, q.y / len, 0, q.w / len);
        else twist.identity();
        q.mul(twist.conjugate());
        local.getTranslation(t);
        into.set(parent).translate(t).rotate(q);
    }

    /** Dual-quaternion blend of every vertex of {@code from} into {@code to} and {@code data} (anchor space). */
    private void skin(GltfModel.Primitive from, GltfModel.Primitive to, ByteBuffer data, int joints) {
        float[] p = from.positions, nrm = from.normals, uv = from.uvs;
        int[] jo = from.joints;
        float[] we = from.weights;
        for (int v = 0, count = from.vertexCount(); v < count; v++) {
            float rx = 0, ry = 0, rz = 0, rw = 0, dx = 0, dy = 0, dz = 0, dw = 0;
            int first = -1;
            for (int k = 0; k < 4; k++) {
                float w = we == null ? 0 : we[v * 4 + k];
                int j = jo == null ? 0 : jo[v * 4 + k];
                if (w <= 0 || j < 0 || j >= joints) continue;
                int o = j * 4;
                if (first < 0) first = o;
                // the same hemisphere as the first influence, or the blend takes the long way round
                float dot = real[o] * real[first] + real[o + 1] * real[first + 1] + real[o + 2] * real[first + 2] + real[o + 3] * real[first + 3];
                if (dot < 0) w = -w;
                rx += w * real[o];
                ry += w * real[o + 1];
                rz += w * real[o + 2];
                rw += w * real[o + 3];
                dx += w * dual[o];
                dy += w * dual[o + 1];
                dz += w * dual[o + 2];
                dw += w * dual[o + 3];
            }
            float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
            if (first < 0 || len < 1e-6f) {
                rx = ry = rz = dx = dy = dz = dw = 0;
                rw = 1;
            } else {
                rx /= len;
                ry /= len;
                rz /= len;
                rw /= len;
                dx /= len;
                dy /= len;
                dz /= len;
                dw /= len;
            }
            // translation 2 · (rw·d − dw·r + r × d)
            float tx = 2 * (rw * dx - dw * rx + ry * dz - rz * dy);
            float ty = 2 * (rw * dy - dw * ry + rz * dx - rx * dz);
            float tz = 2 * (rw * dz - dw * rz + rx * dy - ry * dx);
            int i = v * 3;
            rotate(rx, ry, rz, rw, p[i], p[i + 1], p[i + 2]);
            float px = t.x + tx, py = t.y + ty, pz = t.z + tz;
            rotate(rx, ry, rz, rw, nrm[i], nrm[i + 1], nrm[i + 2]);
            to.positions[i] = px;
            to.positions[i + 1] = py;
            to.positions[i + 2] = pz;
            to.normals[i] = t.x;
            to.normals[i + 1] = t.y;
            to.normals[i + 2] = t.z;
            GpuModel.putVertex(data, px, py, pz, uv[v * 2], uv[v * 2 + 1], t.x, t.y, t.z, from.ao == null ? 1 : from.ao[v]);
        }
    }

    /** v rotated by the unit quaternion (x, y, z, w), into {@link #t}. */
    private void rotate(float x, float y, float z, float w, float vx, float vy, float vz) {
        float ux = 2 * (y * vz - z * vy), uy = 2 * (z * vx - x * vz), uz = 2 * (x * vy - y * vx);
        t.set(vx + w * ux + (y * uz - z * uy), vy + w * uy + (z * ux - x * uz), vz + w * uz + (x * uy - y * ux));
    }

    /** Copies of a mesh's primitives whose positions and normals the skinning rewrites (uvs, indices shared). */
    private static List<GltfModel.Primitive> copies(GpuModel model, int mesh) {
        List<GltfModel.Primitive> out = new ArrayList<>();
        for (GpuModel.Part part : model.parts(mesh)) {
            GltfModel.Primitive from = part.primitive(), to = new GltfModel.Primitive();
            to.positions = from.positions.clone();
            to.normals = from.normals.clone();
            to.uvs = from.uvs;
            to.ao = from.ao;
            to.indices = from.indices;
            to.material = from.material;
            out.add(to);
        }
        return out;
    }

    /** New arms model (first use, a resource reload): buffers sized for it, nothing drawn yet. */
    private void prepare(GpuModel model) {
        if (model == arms) return;
        close();
        arms = model;
        drawn = new int[model.source.meshes.size()];
    }

    private GpuBuffer buffer(int generation) {
        while (buffers.size() <= generation) {
            int size = Math.max(1, arms.vertexCount()) * GunPipelines.FORMAT.getVertexSize();
            buffers.add(RenderSystem.getDevice().createBuffer(() -> "BlockLand skinned arms", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size));
        }
        return buffers.get(generation);
    }

    private ByteBuffer staging(int bytes) {
        if (staging == null || staging.capacity() < bytes) {
            staging = ByteBuffer.allocateDirect(Math.max(bytes, 16 * 1024)).order(ByteOrder.nativeOrder());
        }
        staging.clear();
        return staging;
    }

    @Override
    public void close() {
        for (GpuBuffer buffer : buffers) buffer.close();
        buffers.clear();
        sources.clear();
        latest.clear();
        arms = null;
    }
}
