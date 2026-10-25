#version 150

#moj_import <blockland:gfx_screen.glsl>

// Sets the alpha of the window's target back to 1 (written with alpha only) when the final pass left it at 0 for a
// Frame Generation HUD capture that did not happen after all (upscale/FrameGenerator, POST_DEAD_WORK): the alpha the
// final pass writes otherwise.

out vec4 fragColor;

void main() {
    fragColor = vec4(1.0);
}
