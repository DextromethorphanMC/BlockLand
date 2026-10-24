#version 150

// Shared by the bullet impact shaders (client/impact, rendering spec §12): per-draw parameters and procedural noise.
// Positions are camera-relative world space in blocks.
//
// Per-draw parameters ride in Minecraft's DynamicTransforms block (packed by ImpactRenderer):
//   TextureMat[0]   camera right (xyz), time in seconds (w)
//   TextureMat[1]   camera up (xyz), 1 when DepthSampler holds the world's depth for soft edges (w)
//   TextureMat[2]   towards the camera (xyz), mode (w): dust 0 = puffs, 1 = rings lying on a surface

vec3 impact_right() {
    return TextureMat[0].xyz;
}

vec3 impact_up() {
    return TextureMat[1].xyz;
}

vec3 impact_back() {
    return TextureMat[2].xyz;
}

float impact_time() {
    return TextureMat[0].w;
}

bool impact_softDepth() {
    return TextureMat[1].w > 0.5;
}

float impact_mode() {
    return TextureMat[2].w;
}

// Integer-free hash of a lattice point (stable across drivers), 0..1.
float impact_hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

// Smooth value noise, 0..1.
float impact_noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(impact_hash(i + vec3(0, 0, 0)), impact_hash(i + vec3(1, 0, 0)), f.x);
    float b = mix(impact_hash(i + vec3(0, 1, 0)), impact_hash(i + vec3(1, 1, 0)), f.x);
    float c = mix(impact_hash(i + vec3(0, 0, 1)), impact_hash(i + vec3(1, 0, 1)), f.x);
    float d = mix(impact_hash(i + vec3(0, 1, 1)), impact_hash(i + vec3(1, 1, 1)), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

// Two octaves turned against each other, 0..1 (dust is small on screen: two are enough).
float impact_fbm(vec3 p) {
    const mat3 turn = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);
    float sum = 0.62 * impact_noise(p);
    p = turn * p * 2.07;
    sum += 0.38 * impact_noise(p);
    return sum;
}

// Three octaves turned against each other, 0..1: billowing smoke edges need the finer detail.
float impact_billows(vec3 p) {
    const mat3 turn = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);
    float sum = 0.55 * impact_noise(p);
    p = turn * p * 2.07;
    sum += 0.3 * impact_noise(p);
    p = turn * p * 2.13;
    sum += 0.15 * impact_noise(p);
    return sum;
}

// View distance (positive, blocks) of a depth-buffer value with the level's projection.
float impact_viewDistance(float depth) {
    return ProjMat[3][2] / ((depth * 2.0 - 1.0) + ProjMat[2][2]);
}
