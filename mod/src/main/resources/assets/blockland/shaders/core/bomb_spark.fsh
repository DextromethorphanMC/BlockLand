#version 150

// Bomb explosion sparks and glows (see bomb_spark.vsh). Additive light: a white-hot core inside a coloured halo, a
// streak tapering at both ends (mode 0) or a round glow (mode 1: the flash, the glow over the fireball). The alpha it
// writes lowers the target's alpha, which is how BlockLand's post chain reads emission (rendering spec §3), so sparks
// bloom; the fog takes them away with distance.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bomb_fx.glsl>

in vec2 corner;
in vec4 sparkColor;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

void main() {
    float shape;
    float core;
    if (bomb_mode() > 0.5) {
        float r2 = dot(corner, corner);
        if (r2 >= 1.0) {
            discard;
        }
        shape = exp(-r2 * 4.5) * (1.0 - r2);
        core = exp(-r2 * 26.0);
    } else {
        float along = abs(corner.x), across = abs(corner.y);
        float taper = 1.0 - smoothstep(0.35, 1.0, along);
        shape = taper * exp(-across * across * 5.0);
        core = taper * exp(-across * across * 30.0);
    }
    float intensity = sparkColor.a * 2.0;
    vec3 color = (sparkColor.rgb * shape + vec3(1.0, 0.96, 0.85) * core * 0.8) * intensity;
    float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd) * FogColor.a;
    color *= 1.0 - fog;
    if (max(color.r, max(color.g, color.b)) < 0.002) {
        discard;
    }
    // the target's alpha × (1 − a): at most 0.3 per spark, so where two cross it stays a glow (≥ 0.5, rendering spec §3)
    float glow = clamp(bl_luminance(color) * 0.8, 0.0, 1.0);
    fragColor = vec4(color, bl_encodeEmission() ? 0.3 * glow : 0.0);
}
