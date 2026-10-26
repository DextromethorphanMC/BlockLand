#version 150

// BlockLand lighting pack helpers (tools/assets/RENDERING_SPEC.md §3, §7): the per-program role, the block material map
// and the shared lighting model of the vanilla overrides. Import after <blockland:scene.glsl>.

// What the patcher made this program (client/graphics/light/VanillaPrograms): 0 unpatched = exactly vanilla,
// 1 opaque (lit, emission in alpha), 2 blended (lit, alpha untouched), 3 translucent terrain (water and glass),
// 4 the Fabulous transparency composite.
uniform int BlRole;

// Block material map (texture unit 13): one texel per atlas cell, RGBA = (emission, emission luminance threshold,
// packed bits, reflectivity). Only valid for the block atlas. The bits of B (0..255): 0-1 kind, 2-3 wind mode (spec §18:
// 0 none, 1 bend, 2 sway, 3 leaves), 4 the upper half of a two-block plant, 5 a water plant, 6-7 flex (0.5, 1, 1.5, 2).
uniform sampler2D BlMaterialMap;

int bl_materialBits(vec4 m) {
    return int(m.b * 255.0 + 0.5);
}

// Material of the block atlas texel at uv: (emission, threshold, kind, reflectivity).
vec4 bl_blockMaterial(vec2 uv) {
    ivec2 size = textureSize(BlMaterialMap, 0);
    if (size.x <= 1) {
        return vec4(0.0);
    }
    ivec2 cell = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
    vec4 m = texelFetch(BlMaterialMap, cell, 0);
    return vec4(m.r, m.g, float(bl_materialBits(m) & 3), m.a);
}

// The wind bits of the atlas cell at uv (bits 2-7 shifted down: mode, upper half, water, flex), 0 = still.
int bl_blockWind(vec2 uv) {
    ivec2 size = textureSize(BlMaterialMap, 0);
    if (size.x <= 1) {
        return 0;
    }
    ivec2 cell = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
    return bl_materialBits(texelFetch(BlMaterialMap, cell, 0)) >> 2;
}

// Emission of a texel of a material: only texels whose brightest channel reaches the threshold glow (a torch's flame,
// not its stick; the brightest channel, so saturated reds and blues count like whites).
float bl_texelEmission(vec4 material, vec3 texel) {
    if (material.x <= 0.0) {
        return 0.0;
    }
    return material.x * smoothstep(material.y, material.y + 0.12, max(texel.r, max(texel.g, texel.b)));
}

// Warm direct light / cool shadow tints around 1 (display space).
vec3 bl_sunTint() {
    vec3 c = BlSunColor.rgb;
    float l = max(bl_luminance(c), 1e-4);
    return mix(vec3(1.0), clamp(c / l, vec3(0.0), vec3(2.0)), 0.55);
}

// How much the sun (or moon) reaches a surface: soft facing term (blocky faces at a grazing sun stay mostly lit) times
// the shadow map.
float bl_sunVisibility(vec3 pos, vec3 normal, vec2 pixel) {
    float facing = clamp(dot(normal, BlSunDir.xyz) * 2.5 + 0.35, 0.0, 1.0);
    if (facing <= 0.0 || BlSunColor.a <= 0.0) {
        return facing;
    }
    return facing * bl_shadow(pos, normal, pixel);
}

// The player's block light colour and brightness (BlBlockLight; 1 without world lighting, so unlit stays vanilla).
vec3 bl_blockLightTint() {
    return bl_worldLit() ? BlBlockLight.rgb : vec3(1.0);
}

// Vanilla's light (lightmap × vertex shade) split into its block-light part and its sky part; only the sky part is shaded
// by the sun: in full sun it keeps vanilla's brightness (warm), in full shadow BlAmbient.a of it (cool). With shadow
// strength 0 (overcast, a moonless night) the result is vanilla's. The block-light part takes the player's tint.
vec3 bl_skyShade(vec3 full, vec3 blockOnly, float sun) {
    vec3 sky = max(full - blockOnly, vec3(0.0));
    blockOnly *= bl_blockLightTint();
    float strength = clamp(BlSunColor.a, 0.0, 1.0);
    float k = mix(mix(1.0, BlAmbient.a, strength), 1.0, sun);
    vec3 tint = mix(BlAmbient.rgb, bl_sunTint(), sun);
    return blockOnly + sky * k * mix(vec3(1.0), tint, strength);
}

// Dynamic lights as a display-space addition (like block light).
vec3 bl_pointLightsDisplay(vec3 pos, vec3 normal) {
    if (BlFlags.z < 0.5) {
        return vec3(0.0);
    }
    return bl_display(bl_pointLights(pos, normal));
}

// ---- water and rain (spec §15) ------------------------------------------------------------------------------------

// Rain drops landing on water or a puddle: every cell of two offset grids holds a drop that lands at its own moment of
// a 0.77 s cycle and spreads a ring; light rain leaves most cells without one. Slope of the surface (xz).
vec2 bl_rainSlope(vec2 p, float t) {
    float rain = BlWeather.y;
    if (rain <= 0.01) {
        return vec2(0.0);
    }
    vec2 slope = vec2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        vec2 q = p * 1.6 + vec2(0.37, 0.71) * float(layer);
        ivec2 c0 = ivec2(floor(q));
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                ivec2 c = c0 + ivec2(i, j);
                uint h = bl_cloudHash(uvec3(uint(c.x + 65536), uint(c.y + 65536), uint(layer) + 91u));
                if (float(h >> 24u) / 255.0 > rain) {
                    continue;
                }
                vec2 centre = vec2(c) + vec2(float(h & 255u), float((h >> 8u) & 255u)) / 255.0;
                float age = fract(t * 1.3 + float((h >> 16u) & 255u) / 255.0);
                vec2 d = q - centre;
                float dist = length(d);
                float x = (dist - age * 0.55) / 0.07;
                float ring = exp(-x * x) * (1.0 - age) * (1.0 - age);
                slope += d / max(dist, 1e-3) * ring * sin(x * 2.2);
            }
        }
    }
    return slope * 0.22;
}

// Rings from things that disturbed the water (client/render/WaterRipples): a wave packet spreading at about a block a
// second, widening, fading with age and distance, stronger ahead of the disturber's heading.
vec2 bl_rippleSlope(vec3 pos) {
    int count = int(BlWeather.w + 0.5);
    vec2 slope = vec2(0.0);
    for (int i = 0; i < BL_MAX_RIPPLES; i++) {
        if (i >= count) {
            break;
        }
        vec4 a = BlRipples[i * 2];
        vec4 b = BlRipples[i * 2 + 1];
        if (abs(pos.y - a.y) > 0.7) {
            continue;
        }
        vec2 d = pos.xz - a.xz;
        float dist = length(d);
        float age = a.w;
        float width = 0.18 + age * 0.35;
        float x = dist - (0.25 + age * 1.1);
        if (abs(x) > width * 3.0) {
            continue;
        }
        vec2 dir = d / max(dist, 1e-3);
        float lean = 1.0 + 0.7 * dot(dir, b.yz);
        float amp = b.x * lean * exp(-age * 0.9) / (1.0 + dist * 1.5) * exp(-(x * x) / (width * width));
        slope += dir * amp * sin(x * 14.0) * 0.6;
    }
    return slope;
}

// What a reflection on water or a wet surface shows (display colours): the sky above, the horizon's fog colour towards
// it, darker where the sky is hidden (skyAccess 0..1).
vec3 bl_reflection(vec3 r, float skyAccess, vec3 horizon) {
    vec3 env = mix(horizon, bl_display(BlSkyColor.rgb), smoothstep(-0.05, 0.45, r.y));
    return env * mix(0.3, 1.0, skyAccess);
}

// The sun's (moon's) highlight on a smooth dielectric surface: GGX with Smith visibility and a water Fresnel, capped so
// a glint never turns into a firefly.
float bl_sunSpecular(vec3 n, vec3 v, float roughness) {
    vec3 l = BlSunDir.xyz;
    vec3 h = normalize(v + l);
    float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 1e-3), nh = max(dot(n, h), 0.0);
    float a = roughness * roughness;
    float a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    float k = a * 0.5;
    float vis = 0.25 / ((nl * (1.0 - k) + k) * (nv * (1.0 - k) + k));
    return min(a2 / (3.14159265 * d * d) * vis * bl_fresnel(max(dot(h, v), 0.0), 0.02) * nl, 24.0);
}

// How wet the rain makes a surface: the world's wetness (BlWeather.x) where the sky reaches it (sky light), tops soaked,
// sides streaked, undersides dry. 0 without world lighting.
float bl_wetness(float skyLight, vec3 normal) {
    if (BlWeather.x <= 0.0 || !bl_worldLit()) {
        return 0.0;
    }
    float facing = clamp(normal.y * 0.5 + 0.5, 0.0, 1.0);
    return BlWeather.x * smoothstep(0.86, 0.99, skyLight) * facing * facing;
}

// Puddles in the low spots of flat ground (world-anchored), filling as the wetness grows: 0 dry … 1 standing water.
float bl_puddle(vec3 worldAbs, float wet) {
    float n = bl_cloudNoise2(worldAbs.xz, 8.0) * 0.6 + bl_cloudNoise2(worldAbs.xz, 2.0) * 0.4;
    float level = 1.0 - wet * 0.55;
    return smoothstep(level, level + 0.06, n);
}

// ---- foliage and smooth blocks (spec §3, §7) ----------------------------------------------------------------------

// Share of the direct light a sunlit leaf lets through to its other side.
const float BL_FOLIAGE_TRANSMIT = 0.4;

// Whether the wind bits of a sprite (bl_blockWind) mark foliage that lets light through: leaves, grass, flowers, crops,
// vines (any wind mode), not water plants (under water the composite's absorption owns the light).
bool bl_isFoliage(int windBits) {
    return (windBits & 3) != 0 && (windBits & 8) == 0;
}

// The sun (or moon) on a leaf or plant face and through it, with a single shadow lookup. `n` is the face's true normal
// turned towards the viewer, `v` the direction to the viewer, `thickness` how far the face's back lies behind it (a leaf
// block's other side 1, a blade of grass 0). x: what reaches the front — a wider wrap than bl_sunVisibility (a clump of
// leaves scatters light round to faces turned a little away), y: what comes through from behind — the shadow at the back
// (offset towards the light) × a forward-scattering lobe round the sun plus a small diffuse part. With shadow strength 0
// (overcast) nothing comes through and x is the bare facing, like bl_sunVisibility.
vec2 bl_foliageSun(vec3 pos, vec3 n, vec3 v, float thickness, vec2 pixel) {
    vec3 l = BlSunDir.xyz;
    float nl = dot(n, l);
    float facing = clamp(nl * 1.5 + 0.55, 0.0, 1.0);
    if (BlSunColor.a <= 0.0) {
        return vec2(facing, 0.0);
    }
    if (nl >= 0.0) {
        return vec2(facing * bl_shadow(pos, n, pixel), 0.0);
    }
    // the sun lights the back: light reaching that side (the faces turned slightly away take theirs from there too)
    float back = bl_shadow(pos - n * thickness, -n, pixel);
    float lobe = pow(max(dot(-v, l), 0.0), 6.0);
    return vec2(facing * back, back * (lobe + 0.35 * -nl));
}

// Sunlight let through a leaf, as display-space light added to bl_skyShade's: the face's vanilla sky light `sky` (biome
// tint, shade and the sky's brightness) in the sun's tint × `through` (bl_foliageSun().y) × the shadow strength × the
// sky access, coloured like light that has passed through a leaf — more saturated and yellower than its surface (the
// chlorophyll takes the red and blue). `leaf` is the surface colour (texel × tint).
vec3 bl_foliageTransmission(vec3 sky, vec3 leaf, float through, float skyAccess) {
    float amount = through * clamp(BlSunColor.a, 0.0, 1.0) * skyAccess * BL_FOLIAGE_TRANSMIT;
    if (amount <= 0.0) {
        return vec3(0.0);
    }
    vec3 deep = max(mix(vec3(bl_luminance(leaf)), leaf, 1.4) * vec3(1.06, 1.04, 0.82), vec3(0.0));
    vec3 chroma = clamp(deep / max(leaf, vec3(1e-3)), vec3(0.5), vec3(1.6));
    return sky * bl_sunTint() * chroma * amount;
}

// How rough a dry block is from its material's reflectivity (spec §7: on plain blocks a smoothness hint, 0 = no sheen).
float bl_blockRoughness(float smoothness) {
    return mix(0.7, 0.08, clamp(smoothness, 0.0, 1.0));
}

// The sun's (moon's) highlight on a dielectric of reflectance f0 (Schlick): GGX with Smith visibility like
// bl_sunSpecular, capped lower (dry blocks are flat faces, a highlight spans them).
float bl_sunSpecularF0(vec3 n, vec3 v, float roughness, float f0) {
    vec3 l = BlSunDir.xyz;
    vec3 h = normalize(v + l);
    float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 1e-3), nh = max(dot(n, h), 0.0);
    float a = roughness * roughness;
    float a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    float k = a * 0.5;
    float vis = 0.25 / ((nl * (1.0 - k) + k) * (nv * (1.0 - k) + k));
    return min(a2 / (3.14159265 * d * d) * vis * bl_fresnel(max(dot(h, v), 0.0), f0) * nl, 8.0);
}

// ---- water optics (spec §15): one model for what is seen through the surface, under it and from below ----------------

// Per block of water: what it absorbs (red goes first, then green; the biome's colour takes a little more of what it
// lacks) and what it scatters (the water's own colour comes from that). Clearer than Bliss's: a floor two blocks down
// reads plainly, ten down is dim, twenty down is gone.
const vec3 BL_WATER_ABSORB = vec3(0.30, 0.075, 0.045);
const float BL_WATER_SCATTER = 0.035;
// The surface's own grey before the biome colour (the shader draws the water, not a texture) and its alpha.
const float BL_WATER_GREY = 0.62;
const float BL_WATER_ALPHA = 0.72;

// The biome's water colour (BlWaterColor), brightest channel 1.
vec3 bl_waterTint() {
    vec3 c = BlWaterColor.rgb;
    return c / max(max(c.r, max(c.g, c.b)), 1e-3);
}

vec3 bl_waterAbsorb() {
    return BL_WATER_ABSORB + 0.06 * (1.0 - bl_waterTint());
}

// The light the water body scatters towards the eye where it is deep (linear): its colour × the light that reaches into
// it from the sky (the camera's openness) and the sun.
vec3 bl_waterBody() {
    vec3 sigmaT = bl_waterAbsorb() + BL_WATER_SCATTER;
    vec3 light = BlSkyColor.rgb * mix(0.15, 1.0, BlWeather.z) + BlSunColor.rgb * 0.35 * BlWeather.z;
    return BL_WATER_SCATTER / sigmaT * bl_waterTint() * light * 0.6;
}

// How strong the caustics are on what lies `depth` blocks under the surface: none right at it (the waves have not
// focused the light yet), strongest a block or two down, gone by about fifteen.
float bl_causticStrength(float depth) {
    return smoothstep(0.05, 0.9, depth) * exp(-depth / 5.0);
}

// ---- lava (spec §16): drawn by the shader, not a texture ------------------------------------------------------------

float bl_lavaHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float bl_lavaNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(bl_lavaHash(i), bl_lavaHash(i + vec2(1.0, 0.0)), f.x),
            mix(bl_lavaHash(i + vec2(0.0, 1.0)), bl_lavaHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Distance to the edge between the nearest two cells of a jittered grid (0 on a crack), cells of size 1.
float bl_lavaEdge(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    float d1 = 8.0, d2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = vec2(bl_lavaHash(i + g), bl_lavaHash(i + g + 17.3)) * 0.85 + 0.075;
            float d = length(g + o - f);
            if (d < d1) {
                d2 = d1;
                d1 = d;
            } else if (d < d2) {
                d2 = d;
            }
        }
    }
    return d2 - d1;
}

// Lava at a world position on a face (normal n), time t: a cooled crust broken into plates, light only in the cracks
// between them and in molten pools welling up; falling faces stream down. rgb display colour, a how much it glows.
vec4 bl_lava(vec3 p, vec3 n, float t) {
    vec2 q = abs(n.y) > 0.5 ? p.xz : (abs(n.x) > abs(n.z) ? p.zy : p.xy);
    if (abs(n.y) < 0.9) {
        q.y += t * 0.9;
    }
    vec2 warp = vec2(bl_lavaNoise(q * 0.7 + vec2(t * 0.05, 0.0)), bl_lavaNoise(q * 0.7 + vec2(7.3, -t * 0.04))) - 0.5;
    float big = bl_lavaEdge(q * 0.9 + warp * 0.6);
    float fine = bl_lavaEdge(q * 2.6 + warp * 1.4 + 11.0);
    float crack = 1.0 - smoothstep(0.015, 0.16, big);
    crack = max(crack, (1.0 - smoothstep(0.01, 0.07, fine)) * 0.6);
    float churn = 0.7 + 0.3 * bl_lavaNoise(q * 2.2 + vec2(t * 0.35, t * 0.21));
    float molten = smoothstep(0.55, 0.72, bl_lavaNoise(q * 0.22 + vec2(t * 0.015, -t * 0.012)) * 0.6
            + bl_lavaNoise(q * 0.5 - vec2(t * 0.03)) * 0.4);
    float heat = clamp(max(crack * churn, molten * (0.8 + 0.2 * churn)), 0.0, 1.0);
    vec3 crust = mix(vec3(0.05, 0.035, 0.03), vec3(0.17, 0.07, 0.035), bl_lavaNoise(q * 3.1) * smoothstep(0.0, 0.3, 1.0 - big));
    vec3 hot = mix(vec3(0.78, 0.16, 0.02), vec3(1.0, 0.78, 0.32), heat * heat);
    return vec4(mix(crust, hot, smoothstep(0.0, 0.6, heat)), heat);
}
