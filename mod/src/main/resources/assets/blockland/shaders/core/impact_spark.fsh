#version 150

// Bullet impact sparks (see impact_spark.vsh). Additive light: a white-hot core inside a coloured halo, tapering at
// both ends of the streak. The alpha it writes lowers the target's alpha, which is how BlockLand's post chain reads
// emission (rendering spec §3), so sparks bloom; the fog takes them away with distance.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>

in vec2 corner;
in vec4 sparkColor;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

void main() {
    float along = abs(corner.x), across = abs(corner.y);
    float taper = 1.0 - smoothstep(0.3, 1.0, along);
    float shape = taper * exp(-across * across * 4.0);
    float core = taper * exp(-across * across * 28.0);
    float intensity = sparkColor.a * 2.0;
    vec3 color = (sparkColor.rgb * shape + vec3(1.0, 0.96, 0.86) * core * 0.85) * intensity;
    float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd) * FogColor.a;
    color *= 1.0 - fog;
    if (max(color.r, max(color.g, color.b)) < 0.002) {
        discard;
    }
    // the target's alpha × (1 − a): at most 0.3 per spark, so crossing sparks stay a glow (≥ 0.5, rendering spec §3)
    float glow = clamp(bl_luminance(color) * 0.8, 0.0, 1.0);
    fragColor = vec4(color, bl_encodeEmission() ? 0.3 * glow : 0.0);
}
