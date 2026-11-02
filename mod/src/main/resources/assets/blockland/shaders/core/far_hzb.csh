#version 430

// FAR_GPU_CULLING (client/graphics/far/GpuCull, rendering spec §23): one level of the hierarchical depth of what vanilla
// drew, in the far projection's depth (far_depth_init.fsh's marker). COPY: level 0, the marker texel for texel. Else a
// level from the one above it: every texel the farthest (largest) depth of the 2 x 2 texels under it - plus the last
// column / row of an odd-sized level, which the 2 x 2 blocks do not reach, folded into the last texel - so every texel of
// level 0 lies under the texels that cover its place at every level, and a box whose nearest depth is beyond the
// largest depth under its screen rectangle can show no pixel.
layout(local_size_x = 8, local_size_y = 8) in;

#ifdef COPY
uniform sampler2D Src;
#else
layout(r32f, binding = 0) uniform readonly image2D Src;
#endif
layout(r32f, binding = 1) uniform writeonly image2D Dst;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(Dst);
    if (p.x >= size.x || p.y >= size.y) return;
#ifdef COPY
    imageStore(Dst, p, vec4(texelFetch(Src, p, 0).r));
#else
    ivec2 src = imageSize(Src);
    ivec2 q = p * 2;
    int x1 = min(q.x + 1, src.x - 1), y1 = min(q.y + 1, src.y - 1);
    float m = max(max(imageLoad(Src, q).r, imageLoad(Src, ivec2(x1, q.y)).r),
                  max(imageLoad(Src, ivec2(q.x, y1)).r, imageLoad(Src, ivec2(x1, y1)).r));
    bool oddX = (src.x & 1) != 0 && p.x == size.x - 1, oddY = (src.y & 1) != 0 && p.y == size.y - 1;
    if (oddX) {
        int x2 = src.x - 1;
        m = max(m, max(imageLoad(Src, ivec2(x2, q.y)).r, imageLoad(Src, ivec2(x2, y1)).r));
    }
    if (oddY) {
        int y2 = src.y - 1;
        m = max(m, max(imageLoad(Src, ivec2(q.x, y2)).r, imageLoad(Src, ivec2(x1, y2)).r));
        if (oddX) m = max(m, imageLoad(Src, ivec2(src.x - 1, y2)).r);
    }
    imageStore(Dst, p, vec4(m));
#endif
}
