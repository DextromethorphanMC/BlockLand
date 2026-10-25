#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:gfx_motion_tiles.glsl>

// BlockLand motion blur, pass 2 in two halves (client/graphics/post/MotionBlur, MOTION_BLUR_TILES), second half: the
// longest of a tile's row maxima (gfx_mb_tile_rows), top row first, so ties keep the first row: the tile's longest blur
// exactly as gfx_mb_tile finds it (the row maxima are half floats read back as they were written).
//   Params[0] = (tile size in pixels, velocity texture size, -)

uniform sampler2D RowSampler;

out vec4 fragColor;

void main() {
    int tile = int(Params[0].x);
    ivec2 size = ivec2(Params[0].yz);
    ivec2 at = ivec2(gl_FragCoord.xy);
    int origin = at.y * tile;
    vec2 longest = vec2(0.0);
    float best = 0.0;
    for (int y = 0; y < 96; y++) {
        if (y >= tile) break;
        int py = origin + y;
        if (py >= size.y) break;
        vec2 v = texelFetch(RowSampler, ivec2(at.x, py), 0).rg;
        float len = gfx_mb_rank(v);
        if (len > best) {
            best = len;
            longest = v;
        }
    }
    fragColor = vec4(longest, 0.0, 1.0);
}
