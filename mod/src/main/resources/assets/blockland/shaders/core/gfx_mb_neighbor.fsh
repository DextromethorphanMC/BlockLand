#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand motion blur, pass 3 (client/graphics/post/MotionBlur): the longest blur of each tile and its eight
// neighbours — the farthest any pixel of the tile may have to gather from.
//   Params[0] = (tile texture size, -, -)

uniform sampler2D TileSampler;

out vec4 fragColor;

void main() {
    ivec2 size = ivec2(Params[0].xy);
    ivec2 center = ivec2(gl_FragCoord.xy);
    vec2 longest = vec2(0.0);
    float best = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 at = clamp(center + ivec2(x, y), ivec2(0), size - 1);
            vec2 v = texelFetch(TileSampler, at, 0).rg;
            float len = dot(v, v);
            if (len > best) {
                best = len;
                longest = v;
            }
        }
    }
    fragColor = vec4(longest, 0.0, 1.0);
}
