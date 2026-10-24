package com.blockland.client.render;

/**
 * True while the graphics module renders entities into its sun shadow map (rendering spec §6). Entity, layer and
 * special item renderers run as usual then, but into a capturing buffer source: anything that draws with a pipeline of
 * its own instead of the given {@code MultiBufferSource} (BlockLand's retained models) must take its vanilla-render-type
 * path while this is set, or it would draw into the frame. Render thread only.
 */
public final class ShadowPass {
    private static boolean active;

    private ShadowPass() {
    }

    public static boolean active() {
        return active;
    }

    /** Graphics module only: around the shadow map's entity capture. */
    public static void set(boolean value) {
        active = value;
    }
}
