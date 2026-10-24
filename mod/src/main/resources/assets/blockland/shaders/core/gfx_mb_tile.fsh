#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand motion blur, pass 2 (client/graphics/post/MotionBlur): the longest blur in each tile of tile x tile pixels.
//   Params[0] = (tile size in pixels, velocity texture size, -)

uniform sampler2D VelocitySampler;

out vec4 fragColor;

void main() {
    int tile = int(Params[0].x);
    ivec2 size = ivec2(Params[0].yz);
    ivec2 origin = ivec2(gl_FragCoord.xy) * tile;
    vec2 longest = vec2(0.0);
    float best = 0.0;
    for (int y = 0; y < 96; y++) {
        if (y >= tile) break;
        int py = origin.y + y;
        if (py >= size.y) break;
        for (int x = 0; x < 96; x++) {
            if (x >= tile) break;
            int px = origin.x + x;
            if (px >= size.x) break;
            vec2 v = texelFetch(VelocitySampler, ivec2(px, py), 0).rg;
            float len = dot(v, v);
            if (len > best) {
                best = len;
                longest = v;
            }
        }
    }
    fragColor = vec4(longest, 0.0, 1.0);
}
