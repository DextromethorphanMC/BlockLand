package com.blockland.client.graphics.far;

import com.blockland.BlockLand;
import com.blockland.client.graphics.gpu.ComputeProgram;
import com.blockland.client.perf.gl.GlStateCache;
import com.blockland.client.perf.gl.TerrainDraws;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.VertexArrayCache;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code FAR_GPU_CULLING} (rendering spec §23): the real far chunks culled on the GPU and drawn by indirect multi-draws.
 * <ul>
 * <li><b>Cluster tables</b>: every region layer's clusters ({@link Clusters}, written by {@link RegionRenderer}) live in
 *     one shared storage buffer, a {@link Table} per layer and kind (every quad, or the quads of the face-group index
 *     list), first-fit ranges that grow with the buffer; {@link #generation} tells a layer its tables died with the
 *     buffer ({@link #close}).</li>
 * <li><b>Hierarchical depth</b> ({@link Hzb}, {@code far_hzb.csh}): the far-projection depth of what vanilla drew
 *     (the marker of {@code far_depth_init.fsh}) copied into level 0 and reduced level by level to its farthest texel,
 *     one per view.</li>
 * <li><b>The frame</b> ({@link #cull}, {@code far_cull.csh}): one job per region layer drawn this frame (its region's
 *     corner relative to the camera, its clusters, where its commands go), one compute thread per command, which
 *     tests its cluster's box against the frustum and the hierarchical depth and writes the indirect command (zero
 *     count when culled). Jobs and commands go through a three-slot ring; a slot is reused two frames later.</li>
 * <li><b>Draws</b> ({@link #multiDraw}): per job one {@code glMultiDrawElementsIndirect} over its clusters' commands with
 *     the pass's pipeline and uniforms as they are (the same vanilla pipeline and transform as the draw without the
 *     culling), the vertex array and element buffer bound as Blaze3D's own draws bind them.</li>
 * </ul>
 * Raw GL beside Blaze3D: compute programs bound and put back ({@link ComputeProgram}), texture unit {@link #HZB_UNIT}
 * bound and put back, image units and storage buffer bindings (which Blaze3D does not use or track), the indirect buffer
 * binding. Needs GL 4.5 (compute shaders, indirect multi-draws, direct state access); else {@link #ready} is false and
 * nothing here is used. Render thread.
 */
final class GpuCull implements AutoCloseable {
    private static final int JOB_BYTES = 32, COMMAND_BYTES = 20;
    private static final int SLOTS = 3;
    /** The texture unit the compute shaders sample through (one of BlockLand's own, bound and put back). */
    private static final int HZB_UNIT = 15;
    private static final int GROUP = 64, HZB_GROUP = 8;
    private static final List<String> NO_DYNAMIC_UNIFORMS = List.of();

    /** A cluster table's range in the shared buffer (in clusters); valid while its generation is the culling's. */
    static final class Table {
        int base = -1, capacity, count, generation = -1;
    }

    /** One view's hierarchical depth. */
    static final class Hzb {
        private int texture;
        private int width, height, levels;

        void close() {
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = 0;
            width = height = levels = 0;
        }
    }

    /** One culled frame of draws: where each draw's commands start (in commands) and the slot's byte offset. */
    static final class Frame {
        int[] commandBase = new int[256];
        int commands;
        long commandsAt;
    }

    @Nullable
    private static Boolean supported;
    private static int alignment = 256;

    private int generation;
    @Nullable
    private ComputeProgram hzbCopy, hzbReduce, cullProgram;
    private boolean programsFailed;

    // the shared cluster buffer: ranges allocated first-fit, grown by copying
    private int clusterBuffer;
    private int clusterCapacity;
    private final TreeMap<Integer, Integer> free = new TreeMap<>();

    // the frame rings
    private int slot;
    private int jobsBuffer, jobsRegion, jobsCursor;
    private int commandsBuffer, entriesRegion, entriesCursor;
    private ByteBuffer jobStaging = MemoryUtil.memAlloc(256 * JOB_BYTES);
    private final Frame frame = new Frame();
    private final float[] planes = new float[24];

    private final Hzb mainHzb = new Hzb(), extraHzb = new Hzb();

    /** Whether the GPU can cull this way (asked once, render thread). */
    static boolean supported() {
        Boolean known = supported;
        if (known != null) return known;
        boolean ok;
        try {
            GLCapabilities caps = GL.getCapabilities();
            ok = caps.OpenGL45;
            if (ok) alignment = Math.max(16, GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
        } catch (RuntimeException e) {
            ok = false;
        }
        supported = ok;
        return ok;
    }

    /** Supported and the compute programs compiled (tried once until {@link #reload}; a failure is logged). */
    boolean ready() {
        if (!supported()) return false;
        if (cullProgram != null) return true;
        if (programsFailed) return false;
        try {
            hzbCopy = ComputeProgram.compile("far_hzb", Map.of("COPY", ""));
            hzbReduce = ComputeProgram.compile("far_hzb", Map.of());
            cullProgram = ComputeProgram.compile("far_cull", Map.of());
            return true;
        } catch (IOException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Far chunk GPU culling is unavailable: {}", e.getMessage());
            closePrograms();
            programsFailed = true;
            return false;
        }
    }

    /** The shaders may have changed: compiled again on the next use. */
    void reload() {
        closePrograms();
        programsFailed = false;
    }

    private void closePrograms() {
        if (hzbCopy != null) hzbCopy.close();
        if (hzbReduce != null) hzbReduce.close();
        if (cullProgram != null) cullProgram.close();
        hzbCopy = hzbReduce = cullProgram = null;
    }

    /** Which lifetime of the cluster buffer this is; a table of another is gone. */
    int generation() {
        return generation;
    }

    /** A frame begins: the next ring slot (the one drawn two frames ago). */
    void beginFrame() {
        slot = (slot + 1) % SLOTS;
        jobsCursor = 0;
        entriesCursor = 0;
    }

    // ---- cluster tables ------------------------------------------------------------------------------------------------

    boolean valid(Table table) {
        return table.base >= 0 && table.generation == generation;
    }

    /** The table holds these clusters now ({@code data}: {@link Clusters#BYTES} each, position to limit). */
    void write(Table table, ByteBuffer data) {
        int count = data.remaining() / Clusters.BYTES;
        if (!valid(table) || table.capacity < count) {
            free(table);
            allocate(table, Math.max(16, (count + 15) / 16 * 16));
        }
        table.count = count;
        if (count > 0) GL45.glNamedBufferSubData(clusterBuffer, (long) table.base * Clusters.BYTES, data);
    }

    void free(Table table) {
        if (valid(table)) release(table.base, table.capacity);
        table.base = -1;
        table.generation = -1;
        table.count = 0;
        table.capacity = 0;
    }

    private void allocate(Table table, int capacity) {
        int at = take(capacity);
        if (at < 0) {
            grow(Math.max(clusterCapacity * 2, Math.max(4096, clusterCapacity + capacity)));
            at = take(capacity);
        }
        table.base = at;
        table.capacity = capacity;
        table.generation = generation;
    }

    private int take(int size) {
        for (Map.Entry<Integer, Integer> e : free.entrySet()) {
            if (e.getValue() < size) continue;
            int at = e.getKey(), left = e.getValue() - size;
            free.remove(at);
            if (left > 0) free.put(at + size, left);
            return at;
        }
        return -1;
    }

    private void release(int at, int size) {
        Map.Entry<Integer, Integer> before = free.floorEntry(at);
        if (before != null && before.getKey() + before.getValue() == at) {
            at = before.getKey();
            size += before.getValue();
            free.remove(at);
        }
        Integer after = free.get(at + size);
        if (after != null) {
            free.remove(at + size);
            size += after;
        }
        free.put(at, size);
    }

    /** A bigger cluster buffer with the old one's contents at the same places. */
    private void grow(int capacity) {
        int next = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(next, (long) capacity * Clusters.BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
        if (clusterBuffer != 0) {
            GL45.glCopyNamedBufferSubData(clusterBuffer, next, 0, 0, (long) clusterCapacity * Clusters.BYTES);
            GL15.glDeleteBuffers(clusterBuffer);
        }
        release(clusterCapacity, capacity - clusterCapacity);
        clusterBuffer = next;
        clusterCapacity = capacity;
    }

    // ---- the hierarchical depth ---------------------------------------------------------------------------------------

    /**
     * Builds the view's hierarchical depth from the far depth marker ({@code init}, R32F, {@code width} x {@code height}):
     * level 0 the marker, each level the farthest texel of the 2 x 2 under it. Nothing is bound afterwards that was not.
     */
    Hzb hzb(boolean main, GpuTexture init, int width, int height) {
        Hzb hzb = main ? mainHzb : extraHzb;
        if (hzb.texture == 0 || hzb.width != width || hzb.height != height) {
            hzb.close();
            hzb.texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            hzb.width = width;
            hzb.height = height;
            hzb.levels = 1 + (31 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height))));
            GL45.glTextureStorage2D(hzb.texture, hzb.levels, GL30.GL_R32F, width, height);
            GL45.glTextureParameteri(hzb.texture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
            GL45.glTextureParameteri(hzb.texture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(hzb.texture, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL45.glTextureParameteri(hzb.texture, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        // level 0: the marker, sampled (a render target just drawn is read coherently through a sampler)
        int active = GlStateManager._getActiveTexture();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + HZB_UNIT);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, ((GlTexture) init).glId());
        hzbCopy.bind();
        GL20.glUniform1i(hzbCopy.uniform("Src"), HZB_UNIT);
        GL42.glBindImageTexture(1, hzb.texture, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
        hzbCopy.dispatch((width + HZB_GROUP - 1) / HZB_GROUP, (height + HZB_GROUP - 1) / HZB_GROUP, 1);
        hzbCopy.unbind();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        GL13.glActiveTexture(active);
        // the levels below, each from the one above
        hzbReduce.bind();
        int w = width, h = height;
        for (int level = 1; level < hzb.levels; level++) {
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            GL42.glBindImageTexture(0, hzb.texture, level - 1, false, 0, GL15.GL_READ_ONLY, GL30.GL_R32F);
            GL42.glBindImageTexture(1, hzb.texture, level, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
            hzbReduce.dispatch((w + HZB_GROUP - 1) / HZB_GROUP, (h + HZB_GROUP - 1) / HZB_GROUP, 1);
        }
        hzbReduce.unbind();
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        return hzb;
    }

    // ---- the frame -----------------------------------------------------------------------------------------------------

    /**
     * Culls this frame's draws: a job per draw, a command per cluster. {@code viewProj} takes camera-relative positions
     * to clip space (the far projection times the view rotation); {@code hzb} (may be null: frustum only) is the view's
     * hierarchical depth in the same projection. Returns the frame's command bases ({@link #multiDraw} reads them).
     */
    Frame cull(List<RegionRenderer.Draw> draws, Matrix4f viewProj, @Nullable Hzb hzb) {
        int n = draws.size();
        if (frame.commandBase.length < n) frame.commandBase = new int[Math.max(n, frame.commandBase.length * 2)];
        int total = 0;
        for (int i = 0; i < n; i++) {
            frame.commandBase[i] = total;
            total += draws.get(i).clusterCount();
        }
        frame.commands = total;
        if (total == 0) return frame;
        // the jobs, written into the slot
        if (jobStaging.capacity() < n * JOB_BYTES) {
            jobStaging = MemoryUtil.memRealloc(jobStaging, Math.max(n * JOB_BYTES, jobStaging.capacity() * 2));
        }
        jobStaging.clear();
        for (int i = 0; i < n; i++) {
            RegionRenderer.Draw d = draws.get(i);
            jobStaging.putFloat(d.ox()).putFloat(d.oy()).putFloat(d.oz()).putFloat(0f);
            jobStaging.putInt(d.clusterBase()).putInt(d.clusterCount()).putInt(frame.commandBase[i]).putInt(d.indexBase());
        }
        jobStaging.flip();
        long jobsAt = reserveJobs(n * JOB_BYTES);
        GL45.glNamedBufferSubData(jobsBuffer, jobsAt, jobStaging);
        reserveEntries(total);
        // the dispatch
        ComputeProgram program = cullProgram;
        program.bind();
        GL20.glUniform1i(program.uniform("JobCount"), n);
        GL20.glUniform1i(program.uniform("CommandCount"), total);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer m = stack.mallocFloat(16);
            viewProj.get(m);
            GL20.glUniformMatrix4fv(program.uniform("ViewProj"), false, m);
            frustumPlanes(viewProj, planes);
            FloatBuffer p = stack.mallocFloat(24);
            p.put(planes).flip();
            GL20.glUniform4fv(program.uniform("Planes"), p);
        }
        int active = GlStateManager._getActiveTexture();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + HZB_UNIT);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (hzb != null && hzb.texture != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, hzb.texture);
            GL20.glUniform1i(program.uniform("Hzb"), HZB_UNIT);
            GL20.glUniform2i(program.uniform("HzbSize"), hzb.width, hzb.height);
            GL20.glUniform1i(program.uniform("HzbLevels"), hzb.levels);
        } else {
            GL20.glUniform1i(program.uniform("HzbLevels"), 0);
        }
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, clusterBuffer, 0, (long) clusterCapacity * Clusters.BYTES);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 1, jobsBuffer, jobsAt, (long) n * JOB_BYTES);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 2, commandsBuffer, frame.commandsAt, (long) total * COMMAND_BYTES);
        program.dispatch((total + GROUP - 1) / GROUP, 1, 1);
        program.unbind();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        GL13.glActiveTexture(active);
        GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        return frame;
    }

    /** Room for {@code bytes} of jobs in this frame's slot: the byte offset into the jobs buffer. */
    private long reserveJobs(int bytes) {
        int at = (jobsCursor + alignment - 1) / alignment * alignment;
        if (jobsBuffer == 0 || at + bytes > jobsRegion) {
            int need = Math.max(jobsRegion * 2, Math.max(1 << 16, at + bytes));
            need = (need + alignment - 1) / alignment * alignment;
            if (jobsBuffer != 0) GL15.glDeleteBuffers(jobsBuffer);
            jobsBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(jobsBuffer, (long) need * SLOTS, GL44.GL_DYNAMIC_STORAGE_BIT);
            jobsRegion = need;
            at = 0;
        }
        jobsCursor = at + bytes;
        return (long) slot * jobsRegion + at;
    }

    /** Room for {@code count} commands in this frame's slot ({@link Frame#commandsAt}). */
    private void reserveEntries(int count) {
        // the storage range bound for the compute pass starts aligned: in commands, the alignment over the stride
        int alignEntries = Math.max(1, alignment / 4);
        int at = (entriesCursor + alignEntries - 1) / alignEntries * alignEntries;
        if (commandsBuffer == 0 || at + count > entriesRegion) {
            int need = Math.max(entriesRegion * 2, Math.max(1 << 14, at + count));
            need = (need + alignEntries - 1) / alignEntries * alignEntries;
            if (commandsBuffer != 0) GL15.glDeleteBuffers(commandsBuffer);
            commandsBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(commandsBuffer, (long) need * SLOTS * COMMAND_BYTES, 0);
            entriesRegion = need;
            at = 0;
        }
        entriesCursor = at + count;
        frame.commandsAt = ((long) slot * entriesRegion + at) * COMMAND_BYTES;
    }

    /** The frustum's six planes (left, right, bottom, top, near, far) of a clip matrix, unit normals, inside >= 0. */
    static void frustumPlanes(Matrix4f m, float[] out) {
        // rows of the matrix: JOML's mCR is column C, row R
        float[] r0 = {m.m00(), m.m10(), m.m20(), m.m30()};
        float[] r1 = {m.m01(), m.m11(), m.m21(), m.m31()};
        float[] r2 = {m.m02(), m.m12(), m.m22(), m.m32()};
        float[] r3 = {m.m03(), m.m13(), m.m23(), m.m33()};
        plane(out, 0, r3, r0, 1);
        plane(out, 1, r3, r0, -1);
        plane(out, 2, r3, r1, 1);
        plane(out, 3, r3, r1, -1);
        plane(out, 4, r3, r2, 1);
        plane(out, 5, r3, r2, -1);
    }

    private static void plane(float[] out, int i, float[] a, float[] b, float sign) {
        float x = a[0] + sign * b[0], y = a[1] + sign * b[1], z = a[2] + sign * b[2], w = a[3] + sign * b[3];
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-12f) len = 1;
        out[i * 4] = x / len;
        out[i * 4 + 1] = y / len;
        out[i * 4 + 2] = z / len;
        out[i * 4 + 3] = w / len;
    }

    // ---- draws ---------------------------------------------------------------------------------------------------------

    /** Before the frame's multi-draws: the commands are the indirect buffer. */
    void beginDraws() {
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commandsBuffer);
    }

    /**
     * Blaze3D's draw setup for the pass's pipeline, uniforms and samplers as they are now (after a pipeline or uniform
     * change); false when the pipeline is not usable.
     */
    boolean setup(RenderPass pass) {
        TerrainDraws.Encoder encoder = (TerrainDraws.Encoder) RenderSystem.getDevice().createCommandEncoder();
        return encoder.blockland$trySetup((GlRenderPass) pass, NO_DYNAMIC_UNIFORMS);
    }

    /**
     * One job's draw: its clusters' commands ({@code count} from {@code commandBase}) as one indirect multi-draw from the
     * vertex buffer with the index buffer. After {@link #setup}.
     */
    void multiDraw(RenderPass pass, GpuBuffer vertexBuffer, GpuBuffer indexBuffer, VertexFormat.IndexType indexType, Frame frame,
                   int commandBase, int count) {
        if (count == 0) return;
        GlRenderPass glPass = (GlRenderPass) pass;
        GlRenderPipeline pipeline = ((TerrainDraws.Pass) glPass).blockland$pipeline();
        VertexFormat format = pipeline.info().getVertexFormat();
        int mode = GlConst.toGl(pipeline.info().getVertexFormatMode());
        VertexArrayCache arrays = ((GlDevice) RenderSystem.getDevice()).vertexArrayCache();
        pass.setVertexBuffer(0, vertexBuffer);
        pass.setIndexBuffer(indexBuffer, indexType);
        arrays.bindVertexArray(format, (GlBuffer) vertexBuffer);
        TerrainDraws.bindElementBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, GlStateCache.handle(indexBuffer));
        GL43.glMultiDrawElementsIndirect(mode, GlConst.toGl(indexType), frame.commandsAt + (long) commandBase * COMMAND_BYTES, count, 0);
    }

    @Override
    public void close() {
        closePrograms();
        programsFailed = false;
        if (clusterBuffer != 0) GL15.glDeleteBuffers(clusterBuffer);
        if (jobsBuffer != 0) GL15.glDeleteBuffers(jobsBuffer);
        if (commandsBuffer != 0) GL15.glDeleteBuffers(commandsBuffer);
        clusterBuffer = jobsBuffer = commandsBuffer = 0;
        clusterCapacity = jobsRegion = entriesRegion = 0;
        jobsCursor = entriesCursor = 0;
        free.clear();
        mainHzb.close();
        extraHzb.close();
        generation++;
    }
}
