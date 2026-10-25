package com.blockland.client.graphics.gpu;

import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * A small uniform buffer owned by one pass and rewritten every frame with {@code glBufferSubData} (driver-synchronised,
 * so a draw still queued with the old contents is never disturbed — unlike Minecraft's persistently mapped rings).
 * {@link #pass} makes the {@code GfxPass} block every post shader uses: one mat4 and six vec4 ({@code gfx_screen.glsl}).
 */
public final class Ubo implements AutoCloseable {
    /** Size of the shared {@code GfxPass} block: mat4 Matrix, vec4 Params[6]. */
    public static final int PASS_SIZE = new Std140SizeCalculator().putMat4f()
            .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();

    private final GpuBuffer buffer;
    private final int size;
    /** Writes of the bytes the buffer already holds are skipped ({@link #skipUnchanged}). */
    private boolean skipUnchanged;
    /** What the buffer holds from offset 0 (the last write's bytes) while {@link #known}. */
    @Nullable
    private ByteBuffer contents;
    private boolean known;

    public Ubo(String label, int size) {
        this.size = size;
        buffer = RenderSystem.getDevice().createBuffer(() -> "BlockLand " + label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size);
    }

    /** A {@code GfxPass} block. */
    public static Ubo pass(String label) {
        return new Ubo(label, PASS_SIZE);
    }

    public GpuBuffer buffer() {
        return buffer;
    }

    /**
     * With {@link Optimization#POST_UNIFORMS}, a write of exactly the bytes the buffer already holds is skipped: for blocks
     * written every frame whose numbers mostly stay the same (sizes, constants). The comparison is byte for byte over
     * everything the write would send, and only this object writes its buffer, so the GPU reads the same bytes either way.
     */
    public Ubo skipUnchanged() {
        skipUnchanged = true;
        return this;
    }

    /** Rewrites the whole buffer. */
    public Ubo write(Consumer<Std140Builder> writer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, size);
            writer.accept(builder);
            ByteBuffer data = builder.get();
            if (skipUnchanged && Optimization.POST_UNIFORMS.on()) {
                if (known && contents.equals(data)) return this;
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data);
                if (contents == null) contents = ByteBuffer.allocate(size);
                contents.clear();
                contents.put(data.duplicate()).flip();
                known = true;
            } else {
                // written without keeping a copy: what the buffer holds is not known any more
                known = false;
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data);
            }
        }
        return this;
    }

    /** Writes a {@code GfxPass} block: the matrix and up to 24 numbers (the rest stay 0), four per vec4. */
    public Ubo pass(Matrix4fc matrix, float... params) {
        return write(b -> {
            b.putMat4f(matrix);
            for (int i = 0; i < 6; i++) {
                b.putVec4(get(params, i * 4), get(params, i * 4 + 1), get(params, i * 4 + 2), get(params, i * 4 + 3));
            }
        });
    }

    private static float get(float[] values, int index) {
        return index < values.length ? values[index] : 0;
    }

    @Override
    public void close() {
        buffer.close();
    }
}
