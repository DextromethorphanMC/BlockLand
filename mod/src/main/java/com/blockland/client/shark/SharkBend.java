package com.blockland.client.shark;

import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.GunPipelines;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The shark's body bent along its spine every frame (shark spec §4): a travelling wave that grows towards the tail, plus
 * a curve into the turn, computed on the CPU into a vertex buffer laid out like the model's own (as the skinned arms
 * are), drawn with the model's static index ranges. Every point of the body is carried by the spine point at its own
 * length — moved sideways and turned with the spine there — so the fins and the tail follow the body without seams.
 * <p>
 * Model space: metres, front −Z, the body's axis on Y = 0; the nose at {@link #NOSE}, the tail tip at {@link #TAIL}.
 * A small ring of buffers: a draw still waiting in a list never sees its vertices overwritten.
 */
final class SharkBend implements AutoCloseable {
    static final float NOSE = -1.75f, TAIL = 1.75f;
    private static final int RING = 4;

    /** The wave now: sideways sway at the tail (m), phase (rad), wavelength (m), the turn's curve at the tail (m). */
    record Wave(float sway, float phase, float length, float bend) {
        /** Share of the sway at a length along the body (0 nose … 1 tail): a little at the head, mostly at the tail. */
        private static float envelope(float s) {
            return 0.12f + 0.88f * s * s;
        }

        /** Sideways offset of the spine at model z. */
        float offset(float z) {
            float s = (z - NOSE) / (TAIL - NOSE);
            float k = (float) (2 * Math.PI / Math.max(0.1f, length));
            return sway * envelope(s) * (float) Math.sin(phase - k * (z - NOSE)) + bend * s * s;
        }

        /** The spine's slope dx/dz at model z (its angle's tangent). */
        float slope(float z) {
            float span = TAIL - NOSE, s = (z - NOSE) / span;
            float k = (float) (2 * Math.PI / Math.max(0.1f, length));
            float arg = phase - k * (z - NOSE);
            return sway * (1.76f * s / span * (float) Math.sin(arg) - envelope(s) * k * (float) Math.cos(arg)) + bend * 2 * s / span;
        }

        /**
         * The rigid motion the body has at model z (for a part riding on it, the jaw): translate to the bent spine,
         * turned by its slope.
         */
        Matrix4f at(float z, Matrix4f into) {
            float angle = (float) Math.atan(slope(z));
            return into.identity().translate(offset(z), 0, z).rotateY(angle).translate(0, 0, -z);
        }
    }

    @Nullable
    private GpuModel model;
    private final List<GpuBuffer> buffers = new ArrayList<>();
    private final List<List<GltfModel.Primitive>> sources = new ArrayList<>();
    private int next;
    @Nullable
    private ByteBuffer staging;

    /** Bends mesh {@code mesh} of {@code model} by {@code wave} and queues it with {@code modelView}. */
    void draw(DrawList draws, GpuModel model, int mesh, Wave wave, Matrix4f modelView, GpuModel.Style style, int blockLight, int skyLight) {
        prepare(model);
        int slot = next++ % RING;
        GpuBuffer buffer = buffer(slot);
        while (sources.size() <= slot) sources.add(null);
        List<GltfModel.Primitive> bent = sources.get(slot);
        if (bent == null || bent.size() != model.parts(mesh).size()) {
            bent = copies(model, mesh);
            sources.set(slot, bent);
        }
        List<GpuModel.Part> parts = model.parts(mesh);
        int stride = GunPipelines.FORMAT.getVertexSize();
        for (int i = 0; i < parts.size(); i++) {
            GpuModel.Part part = parts.get(i);
            GltfModel.Primitive from = part.primitive(), to = bent.get(i);
            int count = from.vertexCount();
            ByteBuffer data = staging(count * stride);
            bend(from, to, data, wave);
            data.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(part.baseVertex() * stride, count * stride), data);
        }
        model.addMesh(draws, mesh, buffer, bent, modelView, style, blockLight, skyLight, 1);
    }

    /** Every vertex carried by the spine point at its z: moved sideways with it and turned by its slope. */
    private static void bend(GltfModel.Primitive from, GltfModel.Primitive to, ByteBuffer data, Wave wave) {
        float[] p = from.positions, n = from.normals, uv = from.uvs;
        for (int v = 0, count = from.vertexCount(); v < count; v++) {
            int i = v * 3;
            float x = p[i], y = p[i + 1], z = p[i + 2];
            float slope = wave.slope(z), inv = (float) (1 / Math.sqrt(1 + slope * slope));
            float c = inv, s = slope * inv;
            // (x, 0, 0) turned about Y by the slope's angle: (x·cos, 0, −x·sin)
            float px = wave.offset(z) + x * c, pz = z - x * s;
            float nx = n[i] * c + n[i + 2] * s, nz = -n[i] * s + n[i + 2] * c;
            to.positions[i] = px;
            to.positions[i + 1] = y;
            to.positions[i + 2] = pz;
            to.normals[i] = nx;
            to.normals[i + 1] = n[i + 1];
            to.normals[i + 2] = nz;
            GpuModel.putVertex(data, px, y, pz, uv[v * 2], uv[v * 2 + 1], nx, n[i + 1], nz, from.ao == null ? 1 : from.ao[v]);
        }
    }

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

    /** A new model (first use, a resource reload): buffers sized for it. */
    private void prepare(GpuModel model) {
        if (model == this.model) return;
        close();
        this.model = model;
    }

    private GpuBuffer buffer(int slot) {
        while (buffers.size() <= slot) {
            int size = Math.max(1, model.vertexCount()) * GunPipelines.FORMAT.getVertexSize();
            buffers.add(RenderSystem.getDevice().createBuffer(() -> "BlockLand shark body", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size));
        }
        return buffers.get(slot);
    }

    private ByteBuffer staging(int bytes) {
        if (staging == null || staging.capacity() < bytes) {
            staging = ByteBuffer.allocateDirect(Math.max(bytes, 64 * 1024)).order(ByteOrder.nativeOrder());
        }
        staging.clear();
        return staging;
    }

    @Override
    public void close() {
        for (GpuBuffer buffer : buffers) buffer.close();
        buffers.clear();
        sources.clear();
        model = null;
        next = 0;
    }
}
