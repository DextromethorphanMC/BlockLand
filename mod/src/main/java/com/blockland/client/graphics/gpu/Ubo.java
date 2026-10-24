package com.blockland.client.graphics.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

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

    /** Rewrites the whole buffer. */
    public Ubo write(Consumer<Std140Builder> writer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, size);
            writer.accept(builder);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), builder.get());
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
