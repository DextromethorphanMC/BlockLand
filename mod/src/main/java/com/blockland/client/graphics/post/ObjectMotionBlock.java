package com.blockland.client.graphics.post;

import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.gpu.Ubo;
import com.blockland.client.render.ObjectMotion;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import org.joml.Matrix4f;

import java.util.List;

/**
 * The frame's object motion ({@link ObjectMotion}, rendering spec §4) as one uniform block, {@code BlObjectMotion} of
 * {@code blockland:bl_object_motion.glsl}: the current projection's inverse, the camera reprojection, and each mover's
 * box transforms. Written once per composited frame before the anti-aliasing; TAA, the DLSS motion input and the motion
 * blur's velocity read it, so a world pixel on a moving vehicle is reprojected by the vehicle's own motion.
 */
public final class ObjectMotionBlock {
    public static final String BLOCK = "BlObjectMotion";
    private static final int SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putVec4().get()
            + ObjectMotion.MAX * (64 + 64 + 16);
    private static Ubo ubo;

    private ObjectMotionBlock() {
    }

    /** This frame's movers into the block (call once per frame, before the passes that read it). */
    public static void update(FrameContext ctx) {
        List<ObjectMotion.Mover> movers = ObjectMotion.collect(ctx.camX, ctx.camY, ctx.camZ);
        Matrix4f invCurrent = new Matrix4f(ctx.projection).mul(ctx.viewRotation).invert();
        Matrix4f prevFromCurrent = new Matrix4f(ctx.prevProjection).mul(ctx.prevViewRotation)
                .translate((float) (ctx.camX - ctx.prevCamX), (float) (ctx.camY - ctx.prevCamY), (float) (ctx.camZ - ctx.prevCamZ));
        ubo().write(b -> {
            b.putMat4f(invCurrent);
            b.putMat4f(prevFromCurrent);
            b.putVec4(movers.size(), 0, 0, 0);
            for (int i = 0; i < ObjectMotion.MAX; i++) b.putMat4f(i < movers.size() ? movers.get(i).local() : new Matrix4f());
            for (int i = 0; i < ObjectMotion.MAX; i++) b.putMat4f(i < movers.size() ? movers.get(i).back() : new Matrix4f());
            for (int i = 0; i < ObjectMotion.MAX; i++) {
                if (i < movers.size()) b.putVec4(movers.get(i).half().x, movers.get(i).half().y, movers.get(i).half().z, 0);
                else b.putVec4(0, 0, 0, 0);
            }
        });
    }

    /** The block of the last {@link #update} (no movers before the first). */
    public static GpuBufferSlice slice() {
        return ubo().buffer().slice();
    }

    private static Ubo ubo() {
        if (ubo == null) {
            ubo = new Ubo("object motion", SIZE).skipUnchanged();
            ubo.write(b -> {
                b.putMat4f(new Matrix4f());
                b.putMat4f(new Matrix4f());
                b.putVec4(0, 0, 0, 0);
                for (int i = 0; i < ObjectMotion.MAX * 2; i++) b.putMat4f(new Matrix4f());
                for (int i = 0; i < ObjectMotion.MAX; i++) b.putVec4(0, 0, 0, 0);
            });
        }
        return ubo;
    }
}
