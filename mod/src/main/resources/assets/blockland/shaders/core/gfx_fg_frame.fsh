#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS Frame Generation: the finished frame with its HUD, rows flipped for the Vulkan side (upscale/FrameGenerator).
// The window's target carries the HUD's coverage in its alpha (see gfx_alpha_clear): the copy is opaque.

uniform sampler2D InSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    fragColor = vec4(texelFetch(InSampler, ivec2(texel.x, size.y - 1 - texel.y), 0).rgb, 1.0);
}
