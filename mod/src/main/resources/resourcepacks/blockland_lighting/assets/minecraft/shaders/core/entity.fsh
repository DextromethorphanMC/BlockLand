#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's entity fragment shader with the sun, its
// shadows and dynamic lights on top, and emission written to alpha by opaque programs. Unpatched (BlRole 0) or without
// world lighting and emission encoding, the result is exactly vanilla's.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 worldPos;
in vec3 worldNormal;
in vec4 blockLightColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    bool patched = BlRole != 0 && (bl_worldLit() || bl_encodeEmission());
    float emission = 0.0;
#ifdef EMISSIVE
    // glowing layers (eyes, glow squid, …) are light of their own
    emission = smoothstep(0.15, 0.6, bl_luminance(color.rgb));
#endif
    color *= vertexColor * ColorModulator;
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    if (patched && bl_worldLit()) {
        vec3 n = normalize(worldNormal);
        float sun = bl_sunVisibility(worldPos, n, gl_FragCoord.xy);
        vec3 light = bl_skyShade(lightMapColor.rgb, blockLightColor.rgb, sun) + bl_pointLightsDisplay(worldPos, n);
        color *= vec4(min(light, vec3(1.0)), lightMapColor.a);
    } else {
        color *= lightMapColor;
    }
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    if (patched && BlRole == 1 && bl_encodeEmission()) {
        float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd) * FogColor.a;
        fragColor.a = bl_emissionAlpha(emission * (1.0 - fog));
    }
}
