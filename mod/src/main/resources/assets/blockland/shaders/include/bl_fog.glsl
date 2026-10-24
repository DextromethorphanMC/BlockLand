#version 150

// BlockLand's distance fog for the lighting pack's lit programs (tools/assets/RENDERING_SPEC.md §14). Import after
// <minecraft:fog.glsl> and <blockland:scene.glsl>. With BlFog.w = 0 (fog off, no world lighting, under water, in a cave)
// it is exactly vanilla's apply_fog. Otherwise, in front of vanilla's fog: a haze whose density falls off with height
// (integrated along the view ray in closed form), coloured like vanilla's fog and brightened towards the sun, and the
// render-distance fade with a smooth edge instead of a linear one.

// Haze between the camera and pos (camera-relative world): 1 - e^-τ, τ = ∫ density along the ray, density =
// BlFog.x · e^(-BlFog.y · height above the camera).
float bl_haze(vec3 pos) {
    float x = BlFog.y * pos.y;
    float shape = abs(x) > 1e-3 ? (1.0 - exp(-x)) / x : 1.0;
    return 1.0 - exp(-BlFog.x * length(pos) * shape);
}

// The haze's colour: vanilla's fog colour, plus the sun's light scattered forward by it (Henyey-Greenstein, g = 0.6).
vec3 bl_hazeColor(vec3 pos, vec3 fogColor) {
    float c = dot(normalize(pos), BlSunDir.xyz);
    const float g = 0.6;
    float phase = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * c, 1.5);
    vec3 sun = bl_display(BlSunColor.rgb) * step(0.0, BlSunDir.y);
    return fogColor + sun * phase * 0.12 * BlFog.z;
}

// The fog over a lit fragment; amount = how much of the fragment the fog hides (for its emission).
vec4 bl_fog(vec4 color, float spherical, float cylindrical, vec3 pos, out float amount) {
    if (BlFog.w < 0.5 || !bl_worldLit()) {
        amount = total_fog_value(spherical, cylindrical, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart,
                FogRenderDistanceEnd) * FogColor.a;
        return apply_fog(color, spherical, cylindrical, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart,
                FogRenderDistanceEnd, FogColor);
    }
    // BlFog.w = 2: the volumetric fog pass is the haze (main view), only the soft edge is drawn here
    float haze = BlFog.w < 1.5 ? bl_haze(pos) * FogColor.a : 0.0;
    float far = max(linear_fog_value(spherical, FogEnvironmentalStart, FogEnvironmentalEnd),
            smoothstep(FogRenderDistanceStart, FogRenderDistanceEnd, cylindrical)) * FogColor.a;
    vec3 rgb = mix(color.rgb, bl_hazeColor(pos, FogColor.rgb), haze);
    rgb = mix(rgb, FogColor.rgb, far);
    amount = 1.0 - (1.0 - haze) * (1.0 - far);
    return vec4(rgb, color.a);
}
