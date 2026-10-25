#version 150

// Blood lying on things (see impact_blood.vsh; rendering spec §19). Every stain's outline is made here from its seed — no
// texture: a splat's ragged rim with lobes and spines and its satellite droplets ahead of the way the drop struck, a
// pool's slow round lobes, a run's narrowing trail ending in a bead. The outline adapts to what it lies on: where the
// surface ends (the edge of a block's top, a step, a slab — the Stain distances), the blood stops a hair short of the
// edge and its outline turns to follow it, rounded where the two meet (a smooth intersection of the two distance
// fields), bulging a little against it; on a plant the plant's own texture cuts it to the blades. Its relief is a height
// field — a meniscus rising steeply at the outline to a low dome, the thicker against an edge, faint ripples while wet —
// turned into the normal from its screen-space derivatives (a normal map without a texture), so the sun, the sky and the
// lights catch the wet film; drying it flattens, darkens and goes matte with a darker ring at the rim.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:impact_fx.glsl>

uniform sampler2D Sampler0;

in vec3 position;
in vec4 tint;
in vec2 uv;
in vec4 stain;
in vec4 shape;
in vec2 atlas;
in vec3 ambient;
in float skyLight;
in float sunShadow;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

const int KIND_SPLAT = 0;
const int KIND_POOL = 1;
const int KIND_PLANT = 2;

vec3 fogged(vec3 color) {
    return apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor).rgb;
}

// Blood's look at a point with normal n: the albedo lit like the world, and a wet film (gloss 1 fresh, 0 dry) that
// reflects the sky by Fresnel and catches the sun in a tight glint (as impact_fluid's drops).
vec3 bloodShade(vec3 n, vec3 albedo, float gloss) {
    vec3 v = normalize(-position);
    if (dot(n, v) < 0.0) {
        n = -n;
    }
    float outdoors = smoothstep(0.35, 0.9, skyLight) * (1.0 - BlCamera.w);
    vec3 light;
    vec3 sky;
    vec3 sunColor;
    vec3 sunDir;
    if (bl_worldLit()) {
        float ndl = max(dot(n, BlSunDir.xyz), 0.0);
        light = ambient * 0.62 + bl_display(BlSunColor.rgb) * sunShadow * ndl * 0.7 + bl_display(bl_pointLights(position, n)) * 0.8;
        sky = bl_display(BlSkyColor.rgb);
        sunColor = BlSunColor.rgb * sunShadow * step(0.0, BlSunDir.y);
        sunDir = BlSunDir.xyz;
    } else {
        light = ambient * mix(0.75, 1.0, abs(n.y));
        sky = FogColor.rgb;
        sunColor = vec3(0.9) * outdoors * smoothstep(0.2, 0.8, ambient.g);
        sunDir = normalize(vec3(0.3, 1.0, 0.2));
    }
    vec3 color = albedo * light;
    float cosv = clamp(dot(n, v), 0.0, 1.0);
    float fres = bl_fresnel(cosv, 0.025) * gloss;
    color = mix(color, sky * mix(0.25, 1.0, outdoors), clamp(fres * 0.8, 0.0, 1.0));
    vec3 r = reflect(-v, n);
    float glint = pow(max(dot(r, sunDir), 0.0), mix(24.0, 180.0, gloss)) * gloss * (bl_worldLit() ? 2.2 : 0.8);
    return color + sunColor * glint;
}

// Smooth maximum (intersection of two distance fields), rounded over k.
float smax(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / max(k, 1e-6);
    return max(a, b) + h * h * k * 0.25;
}

void main() {
    int code = int(shape.x + 0.5);
    int kind = code / 16;
    bool run = (code & 1) == 1;
    float radius = max(shape.z, 0.002);
    float gloss = clamp(shape.w, 0.0, 1.0);
    float seed = stain.z;
    float wet = clamp(stain.w, 0.0, 1.0);
    // on a plant: only on the blades (the plant's own texture's alpha); sampled here, in uniform control flow
    float onPlant = atlas.x >= 0.0 ? step(0.1, texture(Sampler0, atlas).a) : 1.0;

    // ---- the outline, as a distance (blocks, < 0 inside) ------------------------------------------------------------
    float rho = length(uv);
    float shapeD;
    float sat = 0.0;
    float rim = 1.0;
    if (run) {
        // a run down the wall: x across (-1..1), y from the splat (0) down to the tip (1), ending in a bead
        float w = mix(0.6, 0.25, uv.y) * (0.75 + 0.5 * impact_noise(vec3(uv.y * 5.0, seed, 1.0)));
        float bead = length(vec2(uv.x * 0.55, (uv.y - 0.9) * 2.6)) - 0.34;
        float trail = max(abs(uv.x) - w, uv.y - 0.98);
        shapeD = min(trail * radius, bead * radius * 0.8);
        rho = abs(uv.x);
        rim = w;
    } else {
        float ang = atan(uv.y, uv.x);
        vec2 around = vec2(cos(ang), sin(ang));
        if (kind == KIND_POOL) {
            // a pool: slow, round lobes where it crept further, a little ripple of the rim
            rim = 0.82 + 0.12 * impact_noise(vec3(around * 1.6 + seed, seed * 0.37))
                    + 0.06 * impact_noise(vec3(around * 4.0, seed + 3.0));
        } else {
            // a splat: ragged with lobes and spines, longer ahead of the way it struck
            rim = 0.74 + 0.2 * impact_noise(vec3(around * 2.2 + seed, seed * 0.37))
                    + 0.12 * impact_noise(vec3(around * 7.0, seed + 5.0)) + 0.1 * max(around.x, 0.0);
            if (kind == KIND_PLANT) {
                rim *= 0.9;
            }
            // satellite droplets beyond the rim, more of them ahead
            vec2 grid = uv * 4.5 + seed;
            vec2 cell = floor(grid);
            vec2 centre = vec2(impact_hash(vec3(cell, seed + 1.0)), impact_hash(vec3(cell, seed + 2.0))) * 0.6 + 0.2;
            float dotSize = mix(0.08, 0.2, impact_hash(vec3(cell, seed + 3.0)));
            float ahead = smoothstep(-0.3, 0.9, uv.x / max(rho, 1e-3));
            sat = (1.0 - smoothstep(dotSize * 0.7, dotSize, length(fract(grid) - centre)))
                    * step(1.0 - 0.4 * ahead, impact_hash(vec3(cell, seed)))
                    * step(rim + 0.04, rho) * (1.0 - smoothstep(1.15, 1.35, rho));
        }
        shapeD = (rho - rim) * radius;
    }
    // the surface's real edges: the blood stops just short of them, its outline turning to run along them
    float edgeDist = min(stain.x, stain.y);
    float edgeD = 0.0012 - edgeDist;
    float d = smax(shapeD, edgeD, 0.22 * radius);
    float aa = max(fwidth(d), 1e-5);
    float cover = 1.0 - smoothstep(-aa, aa, d);
    // satellites stop at the edge too
    cover = max(cover, sat * step(0.003, edgeDist)) * onPlant;

    // ---- relief: meniscus, dome, against the edge, ripples -----------------------------------------------------------
    float inside = max(-d, 0.0);
    float meniscus = max(0.25 * radius, 0.0015);
    float thick = smoothstep(0.0, meniscus, inside);
    if (!run) {
        thick *= 0.75 + 0.25 * (1.0 - clamp(rho / max(rim, 1e-3), 0.0, 1.0));
    }
    // held back by the edge it bulges against it
    thick += 0.35 * (1.0 - smoothstep(0.0, 0.014, edgeDist)) * smoothstep(0.0, 0.5 * meniscus, inside);
    thick = max(thick, sat);
    float H = kind == KIND_POOL ? 0.0022 : kind == KIND_PLANT ? 0.0005 : run ? 0.0008 : 0.0011;
    H *= mix(0.35, 1.0, wet);
    float ripple = wet * 0.06 * impact_noise(vec3(position * 90.0 + seed));
    float h = H * (thick + ripple);
    // the flat surface's normal, and the height field's slope turned into it (surface gradient from the derivatives)
    vec3 dpdx = dFdx(position);
    vec3 dpdy = dFdy(position);
    vec3 base = normalize(cross(dpdx, dpdy));
    if (dot(base, -position) < 0.0) {
        base = -base;
    }
    vec3 r1 = cross(dpdy, base);
    vec3 r2 = cross(base, dpdx);
    float det = dot(dpdx, r1);
    vec3 n = base;
    if (abs(det) > 1e-12) {
        vec3 grad = (dFdx(h) * r1 + dFdy(h) * r2) / det;
        n = normalize(base - grad);
    }

    // ---- colour: thick and dark in the middle, a darker coffee ring as it dries -------------------------------------
    float t = clamp(thick, 0.0, 1.0);
    float ring = (1.0 - wet) * smoothstep(0.35, 0.95, rho / max(rim, 1e-3)) * (1.0 - smoothstep(0.95, 1.05, rho / max(rim, 1e-3)));
    vec3 albedo = tint.rgb * mix(1.05, 0.7, t) * (1.0 - 0.35 * ring);
    vec3 c = bloodShade(n, albedo, gloss);
    float alpha = clamp(cover * tint.a * mix(0.72, 0.98, t), 0.0, 1.0);
    if (alpha < 0.02) {
        discard;
    }
    fragColor = vec4(fogged(c) * alpha, alpha);
}
