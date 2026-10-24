package com.blockland.client.graphics.frame;

import com.blockland.client.render.FrameJitter;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import org.joml.Matrix4f;

/**
 * Stand-ins for GameRenderer's two projection buffers (rendering spec §4): the level's and the hand's. Both shift their
 * matrix by {@link FrameJitter} (zero when no temporal pass runs, so they are exactly vanilla's then), and the level's
 * hands the unjittered matrix to {@code FrameHooks} for reprojection.
 */
final class JitteredProjections {
    private JitteredProjections() {
    }

    static final class Level extends PerspectiveProjectionMatrixBuffer {
        private final Matrix4f scratch = new Matrix4f();

        Level() {
            super("level (BlockLand)");
        }

        @Override
        public GpuBufferSlice getBuffer(Matrix4f projection) {
            FrameHooks.levelProjection(projection);
            return super.getBuffer(FrameJitter.apply(scratch.set(projection)));
        }
    }

    static final class Hand extends CachedPerspectiveProjectionMatrixBuffer {
        private static final float NEAR = 0.05F, FAR = 100.0F;
        private final PerspectiveProjectionMatrixBuffer buffer = new PerspectiveProjectionMatrixBuffer("3d hud (BlockLand)");
        private final Matrix4f scratch = new Matrix4f();

        Hand() {
            super("3d hud (BlockLand, unused)", NEAR, FAR);
        }

        /** Rebuilt every call (the jitter changes every frame), otherwise as vanilla's. */
        @Override
        public GpuBufferSlice getBuffer(int width, int height, float fov) {
            scratch.setPerspective(fov * (float) (Math.PI / 180.0), (float) width / height, NEAR, FAR);
            return buffer.getBuffer(FrameJitter.apply(scratch));
        }

        @Override
        public void close() {
            super.close();
            buffer.close();
        }
    }
}
