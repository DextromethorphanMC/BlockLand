#version 150

// BlockLand lighting pack helpers (tools/assets/RENDERING_SPEC.md §3, §7): the per-program role, the block material map
// and the shared lighting model of the vanilla overrides. Import after <blockland:scene.glsl>.

// What the patcher made this program (client/graphics/light/VanillaPrograms): 0 unpatched = exactly vanilla,
// 1 opaque (lit, emission in alpha), 2 blended (lit, alpha untouched), 3 translucent terrain (water and glass),
// 4 the Fabulous transparency composite.
uniform int BlRole;

// Block material map (texture unit 13): one texel per atlas cell, RGBA = (emission, emission luminance threshold,
// kind / 3, reflectivity). Only valid for the block atlas.
uniform sampler2D BlMaterialMap;

// Material of the block atlas texel at uv: (emission, threshold, kind, reflectivity).
vec4 bl_blockMaterial(vec2 uv) {
    ivec2 size = textureSize(BlMaterialMap, 0);
    if (size.x <= 1) {
        return vec4(0.0);
    }
    ivec2 cell = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
    vec4 m = texelFetch(BlMaterialMap, cell, 0);
    return vec4(m.r, m.g, floor(m.b * 3.0 + 0.5), m.a);
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

// Vanilla's light (lightmap × vertex shade) split into its block-light part and its sky part; only the sky part is shaded
// by the sun: in full sun it keeps vanilla's brightness (warm), in full shadow BlAmbient.a of it (cool). With shadow
// strength 0 (overcast, a moonless night) the result is vanilla's.
vec3 bl_skyShade(vec3 full, vec3 blockOnly, float sun) {
    vec3 sky = max(full - blockOnly, vec3(0.0));
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

// Slope of small world-anchored waves at a horizontal world position (blocks): four travelling directional waves,
// steepness from BlWater.x (1 = default), speed from BlWater.y.
vec2 bl_waveSlope(vec2 p, float t) {
    const vec3 WAVES[4] = vec3[4](vec3(0.8, 0.6, 2.3), vec3(-0.5, 0.866, 1.35), vec3(0.2, -0.98, 0.72), vec3(-0.9, -0.436, 0.43));
    vec2 slope = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 d = WAVES[i].xy;
        float k = 6.2831853 / WAVES[i].z;
        float phase = dot(d, p) * k + t * BlWater.y * sqrt(9.8 * k) * 0.5;
        slope += d * cos(phase);
    }
    return slope * (0.05 * BlWater.x);
}

vec3 bl_waterNormal(vec3 worldPos, float t) {
    vec2 s = bl_waveSlope(worldPos.xz, t);
    return normalize(vec3(-s.x, 1.0, -s.y));
}
