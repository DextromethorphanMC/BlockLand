#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS input: hardware depth (0 near .. 1 far) at the render size, rows flipped. The hand and the viewmodel (alpha 1 in
// the decoded colour) are drawn after the world's depth was taken: they count as nearest.

uniform sampler2D ColorSampler;
uniform sampler2D WorldDepthSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(ColorSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 source = ivec2(texel.x, size.y - 1 - texel.y);
    float hand = texelFetch(ColorSampler, source, 0).a;
    float depth = hand > 0.5 ? 0.0 : texelFetch(WorldDepthSampler, source, 0).r;
    fragColor = vec4(depth, 0.0, 0.0, 1.0);
}
