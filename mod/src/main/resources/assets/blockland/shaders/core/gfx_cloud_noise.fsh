#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:scene.glsl>

// The volumetric clouds' noise (client/graphics/sky/VolumetricClouds, rendering spec §14), made once on the GPU. Every
// volume tiles seamlessly; 3D volumes are stored as their z slices side by side in a 2D atlas.
//   mode 0, shape (128³, 16 × 8 slices): r Perlin-Worley (billowy cells), g b a inverted Worley fbm at 4, 8, 16 cells
//   mode 1, detail (32³, 8 × 4 slices): r g b inverted Worley fbm at 2, 4, 8 cells (erodes the edges)
//   mode 2, weather (1024², 4 blocks per texel over the 4096-block cloud noise period): r the raw coverage noise of
//           bl_cloudCoverage (so the clouds and their shadows agree), g cloud type (0 flat … 1 towering)
//   Params[0] = (size, atlas columns, mode, -)

out vec4 fragColor;

vec3 hash3(ivec3 c, int period) {
    uvec3 w = uvec3((c % period + period) % period);
    uint h = bl_cloudHash(w);
    uint h2 = bl_cloudHash(w + uvec3(71u, 13u, 173u));
    uint h3 = bl_cloudHash(w + uvec3(19u, 241u, 37u));
    return vec3(float(h & 0xFFFFu), float(h2 & 0xFFFFu), float(h3 & 0xFFFFu)) / 65535.0;
}

// Inverted Worley noise (1 at the feature points) with `cells` cells per tile, tiling.
float worley(vec3 p, int cells) {
    vec3 q = p * float(cells);
    ivec3 i = ivec3(floor(q));
    vec3 f = q - vec3(i);
    float d = 1e9;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                ivec3 o = ivec3(x, y, z);
                vec3 v = vec3(o) + hash3(i + o, cells) - f;
                d = min(d, dot(v, v));
            }
        }
    }
    return 1.0 - clamp(sqrt(d), 0.0, 1.0);
}

float worleyFbm(vec3 p, int cells) {
    return worley(p, cells) * 0.625 + worley(p, cells * 2) * 0.25 + worley(p, cells * 4) * 0.125;
}

// Gradient noise with `cells` cells per tile, tiling, about -1..1.
float perlin(vec3 p, int cells) {
    vec3 q = p * float(cells);
    ivec3 i = ivec3(floor(q));
    vec3 f = q - vec3(i);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n[8];
    for (int k = 0; k < 8; k++) {
        ivec3 o = ivec3(k & 1, (k >> 1) & 1, k >> 2);
        vec3 g = normalize(hash3(i + o, cells) * 2.0 - 1.0 + 1e-4);
        n[k] = dot(g, f - vec3(o));
    }
    float x0 = mix(n[0], n[1], u.x), x1 = mix(n[2], n[3], u.x), x2 = mix(n[4], n[5], u.x), x3 = mix(n[6], n[7], u.x);
    return mix(mix(x0, x1, u.y), mix(x2, x3, u.y), u.z) * 1.6;
}

float remap(float v, float lo, float hi, float newLo, float newHi) {
    return newLo + (v - lo) / max(hi - lo, 1e-4) * (newHi - newLo);
}

void main() {
    int size = int(Params[0].x + 0.5), columns = int(Params[0].y + 0.5), mode = int(Params[0].z + 0.5);
    ivec2 t = ivec2(gl_FragCoord.xy);
    if (mode == 2) {
        vec2 xz = (vec2(t) + 0.5) * 4.0;
        float n = bl_cloudNoise2(xz, 2048.0) * 0.45 + bl_cloudNoise2(xz, 1024.0) * 0.3 + bl_cloudNoise2(xz, 512.0) * 0.17
                + bl_cloudNoise2(xz, 256.0) * 0.08;
        float type = bl_cloudNoise2(xz + vec2(1531.0, 709.0), 1024.0) * 0.7 + bl_cloudNoise2(xz + vec2(91.0, 2403.0), 512.0) * 0.3;
        fragColor = vec4(n, smoothstep(0.3, 0.7, type), 0.0, 1.0);
        return;
    }
    ivec3 v = ivec3(t.x % size, t.y % size, t.x / size + (t.y / size) * columns);
    vec3 p = (vec3(v) + 0.5) / float(size);
    if (mode == 0) {
        float perlinFbm = perlin(p, 4) * 0.5 + perlin(p, 8) * 0.25 + perlin(p, 16) * 0.125;
        float cells = worleyFbm(p, 4);
        float perlinWorley = remap(clamp(perlinFbm * 0.5 + 0.5, 0.0, 1.0), 0.0, 1.0, cells, 1.0);
        fragColor = vec4(clamp(perlinWorley, 0.0, 1.0), cells, worleyFbm(p, 8), worleyFbm(p, 16));
    } else {
        fragColor = vec4(worleyFbm(p, 2), worleyFbm(p, 4), worleyFbm(p, 8), 1.0);
    }
}
