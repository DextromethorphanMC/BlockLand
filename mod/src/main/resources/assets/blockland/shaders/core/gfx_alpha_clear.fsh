#version 150

#moj_import <blockland:gfx_screen.glsl>

// Clears the alpha of the window's target before the HUD (written with alpha only): the HUD's blending then leaves its
// coverage there, which DLSS Frame Generation needs (upscale/FrameGenerator).

out vec4 fragColor;

void main() {
    fragColor = vec4(0.0);
}
