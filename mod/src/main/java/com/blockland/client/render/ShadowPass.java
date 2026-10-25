package com.blockland.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * True while the graphics module renders entities into its sun shadow map (rendering spec §6). Entity, layer and
 * special item renderers run as usual then, but into a capturing buffer source: anything that draws with a pipeline of
 * its own instead of the given {@code MultiBufferSource} (BlockLand's retained models) must take its vanilla-render-type
 * path while this is set, or it would draw into the frame. Render thread only.
 */
public final class ShadowPass {
    /**
     * What the capture hands out for render types that cast nothing (light, glow, overlays): it drops every vertex, so
     * code that builds its own vertices may skip building them for it.
     */
    public static final VertexConsumer DISCARD = new Discard();

    private static boolean active;

    /**
     * The capture's buffer source. Its vertices only feed the shadow map's depth pass, which reads their positions and
     * texture coordinates (alpha-tested against the bound texture) and nothing else of them.
     */
    public interface Capture extends MultiBufferSource {
    }

    private ShadowPass() {
    }

    public static boolean active() {
        return active;
    }

    /** Graphics module only: around the shadow map's entity capture. */
    public static void set(boolean value) {
        active = value;
    }

    /** Takes and forgets the vertices of what casts nothing. */
    private static final class Discard implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
