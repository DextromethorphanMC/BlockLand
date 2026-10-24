#version 150

// BlockLand ray tracing (client/graphics/raytrace, rendering spec §4): the voxel box around the camera and the frame's
// numbers, shared by every ray tracing pass. Volume space: blocks from the box's lowest corner (cell (x, y, z) is the
// block whose corner is there); the box is nx × ny × nz chunk sections, each a 64×64 tile in two atlases (VoxelWorld).

layout(std140) uniform RtInfo {
    mat4 RtViewToWorld;   // view -> camera-relative world (rotation)
    mat4 RtReprojection;  // current unjittered NDC -> previous frame's clip
    vec4 RtProj;          // the level projection's P00, P11, P22, P32
    vec4 RtSize;          // xy internal resolution (world depth size), zw ray tracing resolution
    vec4 RtCamera;        // xyz camera position in volume space, w frame index
    vec4 RtVolume;        // xyz box size in sections, w atlas columns (tiles per row)
    vec4 RtWrap;          // xyz tile slot of the box's corner section (its coordinates modulo the box size), w -
    vec4 RtTrace;         // x rays per pixel, y longest ray (blocks), z most cells a ray crosses, w ambient occlusion radius
    vec4 RtLight;         // x daylight 0..1, y global illumination strength, z emission strength, w sun bounce on
    vec4 RtSun;           // xyz towards the sun or moon (world), w its direct strength (0 below the horizon / no world lighting)
    vec4 RtSunColor;      // rgb its colour (linear), a ambient occlusion strength
};

uniform sampler2D VoxelColor;   // rgb average block colour (display colours), a kind: 0 empty, 1 full, between: hit chance
uniform sampler2D VoxelLight;   // r sky light, g block light, b emission (levels / 15)

ivec3 rt_boxCells() {
    return ivec3(RtVolume.xyz) * 16;
}

bool rt_inside(ivec3 v) {
    return all(greaterThanEqual(v, ivec3(0))) && all(lessThan(v, rt_boxCells()));
}

ivec2 rt_texel(ivec3 v) {
    ivec3 n = ivec3(RtVolume.xyz);
    ivec3 slot = (ivec3(RtWrap.xyz) + (v >> 4)) % n;
    int tile = slot.x + n.x * (slot.z + n.z * slot.y);
    int columns = int(RtVolume.w);
    ivec3 l = v & 15;
    return ivec2((tile % columns) * 64 + l.x + 16 * (l.y & 3), (tile / columns) * 64 + l.z + 16 * (l.y >> 2));
}

vec4 rt_color(ivec3 v) {
    return texelFetch(VoxelColor, rt_texel(v), 0);
}

vec4 rt_light(ivec3 v) {
    return texelFetch(VoxelLight, rt_texel(v), 0);
}

// PCG hash (Jarzynski & Olano 2020)
uint rt_hash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float rt_random(inout uint seed) {
    seed = rt_hash(seed);
    return float(seed) * (1.0 / 4294967296.0);
}

uint rt_seed(ivec2 pixel, int salt) {
    return rt_hash(uint(pixel.x) * 1973u ^ uint(pixel.y) * 9277u ^ uint(RtCamera.w) * 26699u ^ uint(salt) * 112909u);
}

// A cosine-weighted direction around n.
vec3 rt_cosine(vec3 n, float u, float v) {
    float r = sqrt(u);
    float phi = 6.2831853 * v;
    vec3 t = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, t);
    return normalize(t * (r * cos(phi)) + b * (r * sin(phi)) + n * sqrt(max(0.0, 1.0 - u)));
}

// Marches a ray through the voxels (Amanatides & Woo). A cell stops it when its kind is full, or by its hit chance for
// leaves, slabs, plants. On a hit: t (blocks), the cell, the empty cell in front of it and the face's normal.
bool rt_march(vec3 origin, vec3 dir, float longest, int steps, inout uint seed,
              out float t, out ivec3 cell, out ivec3 front, out vec3 normal) {
    cell = ivec3(floor(origin));
    front = cell;
    normal = vec3(0.0);
    t = 0.0;
    ivec3 stepDir = ivec3(sign(dir));
    vec3 inv = 1.0 / max(abs(dir), vec3(1e-6));
    vec3 next = vec3(
        stepDir.x > 0 ? float(cell.x + 1) - origin.x : origin.x - float(cell.x),
        stepDir.y > 0 ? float(cell.y + 1) - origin.y : origin.y - float(cell.y),
        stepDir.z > 0 ? float(cell.z + 1) - origin.z : origin.z - float(cell.z)) * inv;
    for (int i = 0; i < steps; i++) {
        front = cell;
        if (next.x < next.y && next.x < next.z) {
            t = next.x;
            next.x += inv.x;
            cell.x += stepDir.x;
            normal = vec3(-float(stepDir.x), 0.0, 0.0);
        } else if (next.y < next.z) {
            t = next.y;
            next.y += inv.y;
            cell.y += stepDir.y;
            normal = vec3(0.0, -float(stepDir.y), 0.0);
        } else {
            t = next.z;
            next.z += inv.z;
            cell.z += stepDir.z;
            normal = vec3(0.0, 0.0, -float(stepDir.z));
        }
        if (t > longest || !rt_inside(cell)) return false;
        float kind = rt_color(cell).a;
        if (kind > 0.0 && (kind > 0.99 || rt_random(seed) < kind)) return true;
    }
    return false;
}

// Minecraft's brightness curve for a light level (0..1): how bright a level looks in the world.
float rt_level(float l) {
    return l / (3.0 * (1.0 - l) + 1.0);
}

// Light leaving a face a ray hit, towards the ray: the block's colour lit the way Minecraft lights the cell in front of
// it (its sky light by the daylight, its block light warm), the block's own glow, and — with sun bounce — direct sun on
// faces that see it (one shadow ray).
vec3 rt_radiance(vec3 hit, ivec3 cell, ivec3 front, vec3 normal, inout uint seed) {
    vec4 color = rt_color(cell);
    vec3 albedo = pow(max(color.rgb, vec3(0.0)), vec3(2.2));
    vec4 light = rt_light(front);
    vec3 incoming = vec3(0.78, 0.86, 1.0) * rt_level(light.r) * RtLight.x + vec3(1.0, 0.8, 0.55) * rt_level(light.g);
    vec3 radiance = albedo * incoming;
    radiance += albedo * rt_light(cell).b * 2.0 * RtLight.z;
    if (RtLight.w > 0.5 && RtSun.w > 0.0) {
        float facing = dot(normal, RtSun.xyz);
        if (facing > 0.0 && light.r > 0.2) {
            float t;
            ivec3 c, f;
            vec3 n;
            if (!rt_march(hit + normal * 0.01, RtSun.xyz, 96.0, 64, seed, t, c, f, n)) {
                radiance += albedo * RtSunColor.rgb * facing * RtSun.w;
            }
        }
    }
    return radiance;
}

// View-space z (negative in front) and position of a depth value at a uv of the internal resolution.
float rt_viewZ(float depth) {
    return -RtProj.w / ((depth * 2.0 - 1.0) + RtProj.z);
}

vec3 rt_viewPosition(vec2 uv, float depth) {
    float z = rt_viewZ(depth);
    vec2 ndc = uv * 2.0 - 1.0;
    return vec3(ndc.x * -z / RtProj.x, ndc.y * -z / RtProj.y, z);
}
