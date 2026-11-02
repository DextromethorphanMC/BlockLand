#version 430

// FAR_GPU_CULLING (client/graphics/far/GpuCull, rendering spec §23): one thread per command of this frame's far chunk
// draws. A cluster is a run of a unit's quads in the mesh's order with their box (region space, blocks); a job is one
// region layer's draw: its region's corner relative to the camera, its clusters, where its commands go. The cluster is
// drawn - its indirect command gets its index count - unless its box lies wholly outside the view frustum (by more than
// a margin) or wholly behind the hierarchical depth of what vanilla drew (far_hzb.csh: every corner in front of the
// camera's plane, and the box's nearest depth beyond the largest depth under its screen rectangle). Both tests only
// leave out clusters no fragment of which could reach the screen; the commands keep the clusters' order, so what is
// drawn is drawn in the order it always was. Beside each command goes its region's corner, the pages vertex shader's
// offset for the draw (what DynamicTransforms.ModelOffset would hold).
layout(local_size_x = 64) in;

struct Cluster {
    vec4 lo;   // the box's minimum; w: the first of the cluster's indices (uint bits)
    vec4 hi;   // the box's maximum; w: how many indices (uint bits; 0 draws nothing)
};
struct Job {
    vec4 origin;   // the region's corner relative to the camera
    uvec4 range;   // the first cluster, the cluster count, the first command, the index buffer's first index
};
struct Command {
    uint count, instanceCount, firstIndex, baseVertex, baseInstance;
};

layout(std430, binding = 0) readonly buffer Clusters { Cluster clusters[]; };
layout(std430, binding = 1) readonly buffer Jobs { Job jobs[]; };
layout(std430, binding = 2) writeonly buffer Commands { Command commands[]; };
layout(std430, binding = 3) writeonly buffer Offsets { vec4 offsets[]; };

uniform int JobCount;
uniform int CommandCount;
uniform mat4 ViewProj;      // camera-relative world -> clip: the far projection times the view rotation
uniform vec4 Planes[6];     // the frustum's planes, camera-relative, unit normals: inside where dot(n, p) + d >= 0
uniform sampler2D Hzb;      // the hierarchical far depth (far_hzb.csh)
uniform ivec2 HzbSize;      // its level 0
uniform int HzbLevels;      // its levels; 0: no occlusion test this frame

// Blocks a box may lie outside a plane and still count as inside (the clip math of the real draw rounds differently).
const float MARGIN = 0.01;
// Depth a box's nearest point may lie beyond the depth under it and still count as visible (rounding of the depth
// written by the rasteriser against the one computed here; about a tenth of a block at 1000 blocks).
const float DEPTH_SLACK = 1e-6;

// The box is wholly outside the plane when its corner farthest along the normal is.
bool outside(vec4 plane, vec3 lo, vec3 hi) {
    vec3 v = mix(lo, hi, greaterThan(plane.xyz, vec3(0.0)));
    return dot(plane.xyz, v) + plane.w < -MARGIN;
}

bool occluded(vec3 lo, vec3 hi) {
    vec2 rmin = vec2(1.0), rmax = vec2(-1.0);
    float zmin = 1.0;
    for (int k = 0; k < 8; k++) {
        vec3 corner = vec3((k & 1) != 0 ? hi.x : lo.x, (k & 2) != 0 ? hi.y : lo.y, (k & 4) != 0 ? hi.z : lo.z);
        vec4 clip = ViewProj * vec4(corner, 1.0);
        // a corner at or behind the camera's plane: the box has no screen rectangle
        if (clip.w <= 0.0) return false;
        vec3 ndc = clip.xyz / clip.w;
        rmin = min(rmin, ndc.xy);
        rmax = max(rmax, ndc.xy);
        zmin = min(zmin, ndc.z);
    }
    // the nearest depth any fragment of the box could have (the depth is monotonic in the view distance, so the box's
    // nearest point is a corner), a little nearer still
    float depth = zmin * 0.5 + 0.5 - DEPTH_SLACK;
    // the level-0 texels the rectangle can touch (pixel centres inside it lie within these)
    vec2 uv0 = clamp(rmin * 0.5 + 0.5, 0.0, 1.0), uv1 = clamp(rmax * 0.5 + 0.5, 0.0, 1.0);
    ivec2 t0 = clamp(ivec2(floor(uv0 * vec2(HzbSize))), ivec2(0), HzbSize - 1);
    ivec2 t1 = clamp(ivec2(ceil(uv1 * vec2(HzbSize))) - 1, t0, HzbSize - 1);
    ivec2 span = t1 - t0 + 1;
    // the level whose texels are at least as wide as the rectangle: it covers at most 2 x 2 of them
    int level = clamp(int(ceil(log2(float(max(span.x, span.y))))), 0, HzbLevels - 1);
    ivec2 last = max(HzbSize >> level, ivec2(1)) - 1;
    ivec2 a = min(t0 >> level, last), b = min(t1 >> level, last);
    float far = max(max(texelFetch(Hzb, a, level).r, texelFetch(Hzb, ivec2(b.x, a.y), level).r),
                    max(texelFetch(Hzb, ivec2(a.x, b.y), level).r, texelFetch(Hzb, b, level).r));
    return depth > far;
}

void main() {
    uint t = gl_GlobalInvocationID.x;
    if (t >= uint(CommandCount)) return;
    // the job whose commands hold t: the last whose first command is at most t
    int lo = 0, hi = JobCount - 1;
    while (lo < hi) {
        int mid = (lo + hi + 1) >> 1;
        if (jobs[mid].range.z <= t) lo = mid;
        else hi = mid - 1;
    }
    Job job = jobs[lo];
    uint i = t - job.range.z;
    // the alignment gap between two jobs' commands: nothing is drawn from it
    if (i >= job.range.y) {
        commands[t] = Command(0u, 1u, 0u, 0u, 0u);
        offsets[t] = vec4(0.0);
        return;
    }
    Cluster c = clusters[job.range.x + i];
    uint count = floatBitsToUint(c.hi.w);
    vec3 bmin = c.lo.xyz + job.origin.xyz, bmax = c.hi.xyz + job.origin.xyz;
    bool visible = count != 0u;
    for (int p = 0; p < 6 && visible; p++) {
        if (outside(Planes[p], bmin, bmax)) visible = false;
    }
    if (visible && HzbLevels > 0 && occluded(bmin, bmax)) visible = false;
    commands[t] = Command(visible ? count : 0u, 1u, floatBitsToUint(c.lo.w) + job.range.w, 0u, 0u);
    offsets[t] = vec4(job.origin.xyz, 0.0);
}
