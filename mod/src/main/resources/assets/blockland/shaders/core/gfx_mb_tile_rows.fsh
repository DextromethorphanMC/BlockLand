#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:gfx_motion_tiles.glsl>

// BlockLand motion blur, pass 2 in two halves (client/graphics/post/MotionBlur, MOTION_BLUR_TILES), first half: the
// longest blur of each tile's row, one fragment per tile column and pixel row (tiles wide x pixels high). Ties keep the
// leftmost texel, as gfx_mb_tile's scan does.
//   Params[0] = (tile size in pixels, velocity texture size, -)

uniform sampler2D VelocitySampler;

out vec4 fragColor;

void main() {
    int tile = int(Params[0].x);
    ivec2 size = ivec2(Params[0].yz);
    ivec2 at = ivec2(gl_FragCoord.xy);
    int origin = at.x * tile;
    vec2 longest = vec2(0.0);
    float best = 0.0;
    for (int x = 0; x < 96; x++) {
        if (x >= tile) break;
        int px = origin + x;
        if (px >= size.x) break;
        vec2 v = texelFetch(VelocitySampler, ivec2(px, at.y), 0).rg;
        float len = gfx_mb_rank(v);
        if (len > best) {
            best = len;
            longest = v;
        }
    }
    fragColor = vec4(longest, 0.0, 1.0);
}
