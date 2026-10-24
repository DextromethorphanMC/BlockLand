#version 150

// Shared by the bomb explosion shaders (client/bomb/fx, rendering spec §10): per-draw parameters, procedural noise and
// the fire's colour. Positions are camera-relative world space in blocks.
//
// Per-draw parameters ride in Minecraft's DynamicTransforms block (packed by BombFxRenderer):
//   ColorModulator  the fireball's light on the smoke: linear colour × intensity (rgb), its reach in blocks (a)
//   ModelOffset     where that light sits (camera-relative)
//   TextureMat[0]   camera right (xyz), time in seconds (w)
//   TextureMat[1]   camera up (xyz), 1 when DepthSampler holds the world's depth for soft edges (w)
//   TextureMat[2]   towards the camera (xyz), mode (w): smoke 0 = puffs, 1 = the ground ring; sparks 0 = streaks, 1 = glow discs
//   TextureMat[3]   unused

vec3 bomb_right() {
    return TextureMat[0].xyz;
}

vec3 bomb_up() {
    return TextureMat[1].xyz;
}

vec3 bomb_back() {
    return TextureMat[2].xyz;
}

float bomb_time() {
    return TextureMat[0].w;
}

bool bomb_softDepth() {
    return TextureMat[1].w > 0.5;
}

float bomb_mode() {
    return TextureMat[2].w;
}

// Integer-free hash of a lattice point (stable across drivers), 0..1.
float bomb_hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

// Smooth value noise, 0..1.
float bomb_noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(bomb_hash(i + vec3(0, 0, 0)), bomb_hash(i + vec3(1, 0, 0)), f.x);
    float b = mix(bomb_hash(i + vec3(0, 1, 0)), bomb_hash(i + vec3(1, 1, 0)), f.x);
    float c = mix(bomb_hash(i + vec3(0, 0, 1)), bomb_hash(i + vec3(1, 0, 1)), f.x);
    float d = mix(bomb_hash(i + vec3(0, 1, 1)), bomb_hash(i + vec3(1, 1, 1)), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

// Three octaves, each turned against the last so the lattice never lines up, 0..1.
float bomb_fbm(vec3 p) {
    const mat3 turn = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);
    float sum = 0.5 * bomb_noise(p);
    p = turn * p * 2.03;
    sum += 0.25 * bomb_noise(p);
    p = turn * p * 2.01;
    sum += 0.125 * bomb_noise(p);
    return sum / 0.875;
}

// Colour of burning gas at a temperature 0..1 (dark red embers .. white-yellow core), in display colours; past 1 it
// keeps getting brighter (the bloom picks that up through the emission alpha).
vec3 bomb_blackbody(float t) {
    t = max(t, 0.0);
    vec3 c = vec3(1.0, 0.25, 0.04) * smoothstep(0.0, 0.35, t);
    c = mix(c, vec3(1.0, 0.55, 0.12), smoothstep(0.3, 0.6, t));
    c = mix(c, vec3(1.0, 0.86, 0.45), smoothstep(0.55, 0.85, t));
    c = mix(c, vec3(1.0, 0.97, 0.86), smoothstep(0.85, 1.1, t));
    return c * (0.25 + 1.6 * t * t);
}

// View distance (positive, blocks) of a depth-buffer value with the level's projection.
float bomb_viewDistance(float depth) {
    return ProjMat[3][2] / ((depth * 2.0 - 1.0) + ProjMat[2][2]);
}
