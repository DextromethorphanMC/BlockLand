#version 150

// FAR_GPU_CULLING (client/graphics/far/FarRenderer, rendering spec §20): the seeded far depth as stored, copied into the
// R32F marker. far_depth_init.fsh writes its seed through gl_FragDepth, and the depth texture keeps it at its own
// precision, which is not always the float the shader computed: the marker must hold the stored value, or a texel the far
// pass never wrote would not compare equal to it afterwards (far_depth_clean.fsh), and the hierarchical depth
// (far_hzb.csh) must hold what the depth test compares against.

#moj_import <blockland:gfx_screen.glsl>

uniform sampler2D FarDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(texelFetch(FarDepthSampler, ivec2(gl_FragCoord.xy), 0).r, 0.0, 0.0, 1.0);
}
