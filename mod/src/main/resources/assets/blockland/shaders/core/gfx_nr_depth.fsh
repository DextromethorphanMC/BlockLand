#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS 5 neural rendering input: the scene's hardware depth after the hand phase (0 near .. 1 far, render size), rows
// flipped for the Vulkan side. Unlike Super Resolution's depth, the hand and the weapon keep their own depth: the model is
// 3D-guided and relights what it can see the shape of.

uniform sampler2D DepthSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(DepthSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    fragColor = vec4(texelFetch(DepthSampler, ivec2(texel.x, size.y - 1 - texel.y), 0).r, 0.0, 0.0, 1.0);
}
