#version 150

// Bullet impacts into fluids (see impact_fluid.vsh). Water is shaded the way the lighting pack shades the water surface
// itself (terrain.fsh, BL_KIND_WATER): the biome-tinted body lit like the world, the sky reflected by Fresnel (f0 0.02,
// strength BlWater.z) towards grazing angles, the sun's glint off the surface normal — so the splash belongs to the
// water around it. Without world lighting the fog colour stands in for the sky and a soft highlight for the sun, the
// same look vanilla's water has then. Thin parts (the sheet's ragged rim, small drops) are more transparent; rings on
// the surface are only the change they make to its reflection and glint; white water is aerated foam. Premultiplied
// alpha, no emission. Lava (modes 4, 5, drawn opaque): molten rock glowing by its temperature, crusting dark as it
// cools, writing its glow as emission. Blood (modes 6, 7) is a dark, glossy liquid: drops are spheres with the sky and
// the sun on their wet film; stains lie on the faces of blocks with a ragged rim and satellite droplets ahead of the way
// they struck, runs down walls ending in a bead, drying matte and darker. Bubbles (mode 8) are air in water: clear in
// the middle, bright at the rim where light is bent and reflected, a glint.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:impact_fx.glsl>

in vec3 position;
in vec4 tint;
in vec2 uv;
in vec3 normal;
in vec3 ambient;
in float skyLight;
in float sunShadow;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

vec3 fogged(vec3 color) {
    return apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor).rgb;
}

// The water's own look at a point with normal n: body, reflected sky, glint. Returns the colour and (w) its Fresnel term.
vec4 water(vec3 n, float thinness) {
    vec3 v = normalize(-position);
    if (dot(n, v) < 0.0) {
        n = -n;
    }
    float cosv = clamp(dot(n, v), 0.0, 1.0);
    float outdoors = smoothstep(0.35, 0.9, skyLight) * (1.0 - BlCamera.w);
    float fres = bl_fresnel(cosv, 0.02);
    // the body: the biome's tint over the light at the spot, a thin sheet lets more of what is behind through
    vec3 body = tint.rgb * ambient * mix(0.82, 0.62, thinness);
    vec3 sky;
    float strength;
    vec3 sunColor;
    vec3 sunDir;
    if (bl_worldLit()) {
        sky = bl_display(BlSkyColor.rgb);
        strength = BlWater.z;
        sunColor = BlSunColor.rgb * sunShadow * step(0.0, BlSunDir.y);
        sunDir = BlSunDir.xyz;
        body += tint.rgb * bl_display(bl_pointLights(position, n)) * 0.35;
    } else {
        sky = FogColor.rgb;
        strength = 0.6;
        // daylight read from the lightmap's sky row
        sunColor = vec3(0.9) * outdoors * smoothstep(0.2, 0.8, ambient.g);
        sunDir = normalize(vec3(0.3, 1.0, 0.2));
    }
    vec3 color = mix(body, sky, clamp(fres * strength * mix(0.3, 1.0, outdoors), 0.0, 1.0));
    vec3 r = reflect(-v, n);
    float glint = pow(max(dot(r, sunDir), 0.0), 120.0) * (bl_worldLit() ? 2.5 : 0.9);
    color += sunColor * glint;
    return vec4(color, clamp(fres + glint * 0.5, 0.0, 1.0));
}

// Blood's look at a point with normal n: the albedo lit like the world, and a wet film (gloss 1 fresh, 0 dry) that
// reflects the sky by Fresnel and catches the sun in a tight glint.
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

// The outward normal of a block face from its Direction ordinal (down, up, north, south, west, east).
vec3 faceNormal(int face) {
    if (face == 0) return vec3(0.0, -1.0, 0.0);
    if (face == 1) return vec3(0.0, 1.0, 0.0);
    if (face == 2) return vec3(0.0, 0.0, -1.0);
    if (face == 3) return vec3(0.0, 0.0, 1.0);
    if (face == 4) return vec3(-1.0, 0.0, 0.0);
    return vec3(1.0, 0.0, 0.0);
}

// Molten rock at temperature t (1 white-hot … 0 cold crust); returns colour and (w) emission.
vec4 molten(float t, float n, vec3 normal) {
    float crust = smoothstep(t * 1.2 - 0.12, t * 1.2 + 0.08, n);
    vec3 hot = mix(vec3(0.95, 0.22, 0.03), vec3(1.0, 0.78, 0.32), smoothstep(0.55, 1.0, t));
    float glow = (1.0 - crust) * smoothstep(0.04, 0.5, t);
    vec3 rockLight = ambient;
    if (bl_worldLit()) {
        rockLight = ambient * 0.62 + bl_display(BlSunColor.rgb) * sunShadow * max(dot(normal, BlSunDir.xyz), 0.0) * 0.7;
    }
    vec3 rock = vec3(0.13, 0.085, 0.07) * rockLight;
    return vec4(mix(rock, hot * (1.2 + t), glow), glow);
}

void main() {
    int mode = int(impact_mode() + 0.5);
    float seed = normal.y * 97.0;
    if (mode == 0) {
        // crown sheet / column: a ragged, thinning rim breaking into drops
        float rim = impact_fbm(vec3(uv.x * 18.0, uv.y * 3.0, impact_time() * 2.0));
        float coverage = tint.a * (1.0 - smoothstep(0.62, 1.0, uv.y + (rim - 0.5) * 0.5));
        if (coverage < 0.04) {
            discard;
        }
        vec4 w = water(normalize(normal), uv.y);
        float alpha = clamp(mix(0.32, 0.92, w.w) * coverage + uv.y * 0.08, 0.0, 1.0);
        fragColor = vec4(fogged(w.rgb) * alpha, alpha);
        return;
    }
    if (mode == 1) {
        // a drop: a small sphere of water
        float d2 = dot(uv, uv);
        if (d2 >= 1.0) {
            discard;
        }
        vec3 n = normalize(impact_right() * uv.x + impact_up() * uv.y + impact_back() * sqrt(1.0 - d2));
        vec4 w = water(n, 0.6);
        float alpha = mix(0.5, 0.95, w.w) * tint.a;
        fragColor = vec4(fogged(w.rgb) * alpha, alpha);
        return;
    }
    if (mode == 2) {
        // rings spreading on the surface: three crests (the leading one strongest), shown as the change of the
        // surface's normal — its reflection and glint move, nothing is painted on the water
        float rho = length(uv);
        float width = max(normal.x, 0.02);
        if (rho > 1.0 + width * 3.0) {
            discard;
        }
        float slope = 0.0;
        const vec3 RINGS = vec3(1.0, 0.7, 0.45);
        const vec3 AMPS = vec3(1.0, 0.55, 0.3);
        for (int i = 0; i < 3; i++) {
            float x = (rho - RINGS[i]) / width;
            slope += AMPS[i] * -2.0 * x * exp(-x * x);
        }
        slope *= tint.a * 0.35;
        vec2 radial = rho > 1e-4 ? uv / rho : vec2(0.0);
        vec3 n = normalize(vec3(-slope * radial.x, 1.0, -slope * radial.y));
        vec4 w = water(n, 0.0);
        float alpha = clamp(abs(slope) * 2.4, 0.0, 0.85) * clamp(1.4 - rho * 0.5, 0.0, 1.0);
        if (alpha < 0.004) {
            discard;
        }
        fragColor = vec4(fogged(w.rgb) * alpha, alpha);
        return;
    }
    if (mode == 3) {
        // white water: aerated foam, bubbly and breaking up as it fades
        float rho = length(uv);
        if (rho >= 1.0) {
            discard;
        }
        float f = impact_fbm(vec3(uv * 5.0, seed + impact_time() * 0.6));
        float cells = impact_noise(vec3(uv * 14.0, seed * 0.7));
        float coverage = smoothstep(0.42, 0.62, f * (1.0 - rho * rho) * tint.a * 2.0 + cells * 0.15);
        if (coverage < 0.02) {
            discard;
        }
        vec3 light = ambient;
        if (bl_worldLit()) {
            light = ambient * 0.62 + bl_display(BlSunColor.rgb) * sunShadow * max(BlSunDir.y, 0.0) * 0.7;
        }
        float alpha = coverage * 0.9;
        fragColor = vec4(fogged(vec3(0.92, 0.95, 0.97) * light) * alpha, alpha);
        return;
    }
    if (mode == 6) {
        // a flying drop of blood: a small glossy sphere
        float d2 = dot(uv, uv);
        if (d2 >= 1.0) {
            discard;
        }
        vec3 n = normalize(impact_right() * uv.x + impact_up() * uv.y + impact_back() * sqrt(1.0 - d2));
        vec3 c = bloodShade(n, tint.rgb, 1.0);
        float alpha = tint.a * (1.0 - smoothstep(0.82, 1.0, d2) * 0.6);
        fragColor = vec4(fogged(c) * alpha, alpha);
        return;
    }
    if (mode == 7) {
        // blood on the face of a block: Normal.x = face * 2 (+1 for a run) in sixteenths, .y seed, .z wetness
        int code = int(normal.x / 0.0625 + 0.5);
        bool run = (code & 1) == 1;
        vec3 n = faceNormal(code / 2);
        float gloss = clamp(normal.z, 0.0, 1.0);
        float cover;
        float thick;
        if (!run) {
            // the splat, uv in its own frame (+x the way the drop struck), its rim near 1: ragged with lobes and spines
            float rho = length(uv);
            float ang = atan(uv.y, uv.x);
            vec2 around = vec2(cos(ang), sin(ang));
            float rim = 0.74 + 0.2 * impact_noise(vec3(around * 2.2 + seed, seed * 0.37))
                    + 0.12 * impact_noise(vec3(around * 7.0, seed + 5.0)) + 0.1 * max(around.x, 0.0);
            float body = 1.0 - smoothstep(rim - 0.05, rim, rho);
            // satellite droplets beyond the rim, more of them ahead
            vec2 grid = uv * 4.5 + seed;
            vec2 cell = floor(grid);
            vec2 centre = vec2(impact_hash(vec3(cell, seed + 1.0)), impact_hash(vec3(cell, seed + 2.0))) * 0.6 + 0.2;
            float dotSize = mix(0.08, 0.2, impact_hash(vec3(cell, seed + 3.0)));
            float ahead = smoothstep(-0.3, 0.9, uv.x / max(rho, 1e-3));
            float sat = (1.0 - smoothstep(dotSize * 0.7, dotSize, length(fract(grid) - centre)))
                    * step(1.0 - 0.4 * ahead, impact_hash(vec3(cell, seed)))
                    * step(rim + 0.04, rho) * (1.0 - smoothstep(1.15, 1.35, rho));
            cover = max(body, sat);
            // a drying stain darkens at its rim (the coffee ring); the middle is the thickest
            thick = body * (1.0 - smoothstep(0.0, rim, rho) * 0.6);
            thick += smoothstep(rim - 0.18, rim - 0.04, rho) * body * (1.0 - gloss) * 0.8;
        } else {
            // a run down the wall: uv.x across (-1..1), uv.y from the splat (0) down to the tip (1), ending in a bead
            float w = mix(0.6, 0.25, uv.y) * (0.75 + 0.5 * impact_noise(vec3(uv.y * 5.0, seed, 1.0)));
            float bead = 1.0 - smoothstep(0.28, 0.4, length(vec2(uv.x * 0.55, (uv.y - 0.9) * 2.6)));
            cover = max(1.0 - smoothstep(w - 0.12, w, abs(uv.x)), bead) * (1.0 - smoothstep(0.97, 1.0, uv.y));
            thick = cover * mix(0.5, 1.0, uv.y);
        }
        if (cover < 0.02) {
            discard;
        }
        vec3 c = bloodShade(n, tint.rgb * mix(1.05, 0.7, clamp(thick, 0.0, 1.0)), gloss);
        float alpha = clamp(cover * tint.a * mix(0.72, 0.98, clamp(thick, 0.0, 1.0)), 0.0, 1.0);
        fragColor = vec4(fogged(c) * alpha, alpha);
        return;
    }
    if (mode == 8) {
        // an air bubble in water: clear in the middle, bright at the rim, a highlight towards the light
        float d2 = dot(uv, uv);
        if (d2 >= 1.0) {
            discard;
        }
        float rho = sqrt(d2);
        vec3 n = normalize(impact_right() * uv.x + impact_up() * uv.y + impact_back() * sqrt(1.0 - d2));
        vec3 light = ambient;
        vec3 sunDir = normalize(vec3(0.3, 1.0, 0.2));
        if (bl_worldLit()) {
            light = ambient * 0.7 + bl_display(BlSunColor.rgb) * sunShadow * max(BlSunDir.y, 0.0) * 0.45;
            sunDir = BlSunDir.xyz;
        }
        float rim = smoothstep(0.55, 0.97, rho);
        float spot = 1.0 - smoothstep(0.0, 0.28, length(uv - vec2(-0.32, 0.38)));
        float glint = pow(max(dot(reflect(normalize(position), n), sunDir), 0.0), 60.0);
        vec3 c = tint.rgb * light * (0.3 + 0.9 * rim) + light * (spot * 0.7 + glint);
        float alpha = tint.a * clamp(0.1 + 0.7 * rim + spot * 0.55, 0.0, 1.0);
        fragColor = vec4(fogged(c) * alpha, alpha);
        return;
    }
    // molten rock: drops (4) and splats on the ground or the crater on the pool (5)
    float d2 = dot(uv, uv);
    float rim = mode == 5 ? impact_noise(vec3(uv * 4.0, seed)) * 0.35 : 0.0;
    if (d2 >= 1.0 - rim) {
        discard;
    }
    vec3 n = mode == 4 ? normalize(impact_right() * uv.x + impact_up() * uv.y + impact_back() * sqrt(max(0.0, 1.0 - d2)))
            : vec3(0.0, 1.0, 0.0);
    float crustNoise = impact_fbm(vec3(uv * 3.0, seed + 3.1));
    vec4 m = molten(tint.a * (mode == 5 ? 1.0 - d2 * 0.4 : 1.0), crustNoise, n);
    fragColor = vec4(fogged(m.rgb), bl_encodeEmission() ? bl_emissionAlpha(m.w) : 1.0);
}
