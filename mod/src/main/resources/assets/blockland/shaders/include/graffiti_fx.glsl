#version 150

// Shared by the wall graffiti shaders (client/graffiti/GraffitiRenderer, rendering spec §13). One draw per piece; its
// parameters ride in Minecraft's DynamicTransforms block (TextureMat, packed by GraffitiRenderer):
//   TextureMat[0]   x time in seconds, y reveal 0..1 (the spraying), z fade 0..1 (going away), w mode
//   TextureMat[1]   rgb the piece's colour (neon, hologram, points; display), a flicker 0..1
//   TextureMat[2]   xy the centre of the current frame in texture space, z seed, w frames in the texture's strip (1 = square)
// Paint modes: 0 paint, 1 a neon tube's glass. Glow modes: 0 neon light, 1 hologram, 2 points of light, 3 projector ring.

float graffiti_time() {
    return TextureMat[0].x;
}

float graffiti_reveal() {
    return TextureMat[0].y;
}

float graffiti_fade() {
    return TextureMat[0].z;
}

int graffiti_mode() {
    return int(TextureMat[0].w + 0.5);
}

vec3 graffiti_color() {
    return TextureMat[1].rgb;
}

float graffiti_flicker() {
    return TextureMat[1].a;
}

float graffiti_seed() {
    return TextureMat[2].z;
}

// Integer-free hash (stable across drivers), 0..1.
float graffiti_hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}

float graffiti_noise(vec2 x) {
    vec2 i = floor(x);
    vec2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float a = graffiti_hash(i), b = graffiti_hash(i + vec2(1.0, 0.0));
    float c = graffiti_hash(i + vec2(0.0, 1.0)), d = graffiti_hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// How much of the paint at this texel is on the wall yet: the spray builds up from the middle outwards, in blotches.
float graffiti_built(vec2 uv) {
    float reveal = graffiti_reveal();
    if (reveal >= 1.0) {
        return 1.0;
    }
    // texture space to the frame's own square (a strip of n frames is n times taller than wide)
    vec2 local = (uv - TextureMat[2].xy) * vec2(1.0, max(TextureMat[2].w, 1.0));
    float dist = length(local) * 1.6;
    float blotch = graffiti_noise(local * 9.0 + graffiti_seed() * 17.0) * 0.45 + graffiti_noise(local * 31.0) * 0.2;
    return smoothstep(0.0, 0.18, reveal * 1.65 - dist - blotch + 0.3);
}

// A neon tube's flicker: mostly steady, now and then a stutter.
float graffiti_neonFlicker(float t, float amount) {
    float slow = 0.92 + 0.08 * sin(t * 2.3 + graffiti_seed() * 6.0);
    float stutter = step(0.965, graffiti_noise(vec2(t * 7.0, graffiti_seed() * 13.0)));
    float buzz = 0.97 + 0.03 * sin(t * 377.0);
    return mix(1.0, slow * buzz * (1.0 - 0.7 * stutter), amount);
}
