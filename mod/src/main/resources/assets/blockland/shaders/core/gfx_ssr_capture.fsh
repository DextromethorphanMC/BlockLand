#version 150

#moj_import <blockland:gfx_screen.glsl>

// Screen-space reflections, the picture kept for the next frame (client/graphics/reflect, rendering spec §17): the
// world's colour as drawn (display) and its linear depth in blocks (0 where the sky is).
//   Params[0] = (the level projection's P22, P32, -, -)

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(DepthSampler, p, 0).r;
    float linear = depth >= 1.0 ? 0.0 : Params[0].y / ((depth * 2.0 - 1.0) + Params[0].x);
    fragColor = vec4(texelFetch(SceneSampler, p, 0).rgb, linear);
}
