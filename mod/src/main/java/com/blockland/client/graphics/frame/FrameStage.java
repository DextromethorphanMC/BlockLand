package com.blockland.client.graphics.frame;

/**
 * A subsystem's hooks into a frame of the render pipeline (rendering spec §1), called in registration order by
 * {@code FrameHooks} on the render thread — only for frames the pipeline runs, and only for the main view (never for a
 * scope's extra level render). Every phase may draw.
 */
public interface FrameStage {
    /** Frame start: nothing of the world is drawn yet and the camera is not placed (settings, resources, jitter). */
    default void beginFrame(FrameContext frame) {
    }

    /** The camera is placed (position and final angles) and the level is about to be drawn: shadows, scene uniforms. */
    default void cameraReady(FrameContext frame) {
    }

    /**
     * The level is drawn into the scene target and its depth copied to {@link FrameContext#worldDepth}; the hand and
     * the viewmodel come next (screen-space effects of the world: ambient occlusion, volumetric light).
     */
    default void afterLevel(FrameContext frame) {
    }

    /** Hand and viewmodel are drawn; the post chain runs right after (and then the HUD, at output resolution). */
    default void beforeComposite(FrameContext frame) {
    }

    /** Frame end, also when it was not composited (clean-up, statistics). */
    default void endFrame(FrameContext frame) {
    }
}
