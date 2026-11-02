#version 150

// FAR_GPU_CULLING (client/graphics/far/FarRenderer, rendering spec §20): the far depth as the pass before vanilla's terrain
// leaves it without the culling - the far fragments where the far pass drew, 1 elsewhere - from the far depth seeded
// with the level's (far_depth_init.fsh) and its marker: a texel still holding its seed was never drawn. The write-back
// reads this with far_depth.fsh, the very program it runs without the culling (another variant of it would round the
// conversion differently by a unit in the last place), and the post chain reads it as the far terrain's depth.

#moj_import <blockland:gfx_screen.glsl>

uniform sampler2D FarDepthSampler;
uniform sampler2D FarInitSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(FarDepthSampler, p, 0).r;
    fragColor = vec4(depth == texelFetch(FarInitSampler, p, 0).r ? 1.0 : depth, 0.0, 0.0, 1.0);
}
