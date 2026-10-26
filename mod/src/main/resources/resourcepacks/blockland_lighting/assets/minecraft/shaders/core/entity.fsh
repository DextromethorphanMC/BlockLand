#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's entity fragment shader with the sun, its
// shadows and dynamic lights on top, emission written to alpha by opaque programs, and BlockLand's fog (§14). Unpatched
// (BlRole 0) or without world lighting and emission encoding, the result is exactly vanilla's.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>
#moj_import <blockland:bl_fog.glsl>

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
in float skyExposure;

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
        // colour bleeding: what faces the ground takes on the ground's colour (BlGround), as much as the sky reaches it
        vec3 groundTint = BlGround.rgb / max(bl_luminance(BlGround.rgb), 1e-3);
        float below = (0.5 - 0.5 * n.y) * 0.35 * BlGround.a * skyExposure;
        light *= mix(vec3(1.0), clamp(groundTint, vec3(0.5), vec3(1.6)), below);
        color *= vec4(min(light, vec3(1.0)), lightMapColor.a);
        // rain (spec §15): fur, skin and cloth darken when soaked, and a sheen of water catches the sky
        float wet = bl_wetness(skyExposure, n);
        if (wet > 0.01) {
            vec3 v = normalize(-worldPos);
            color.rgb *= mix(1.0, 0.72, wet);
            float sheen = clamp(bl_fresnel(max(dot(n, v), 0.0), 0.02) * wet * 0.5, 0.0, 0.5);
            color.rgb = mix(color.rgb, bl_reflection(reflect(-v, n), skyExposure, FogColor.rgb), sheen);
        }
    } else {
        color *= lightMapColor;
    }
#endif
    if (!patched) {
        fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        return;
    }
    float fog;
    fragColor = bl_fog(color, sphericalVertexDistance, cylindricalVertexDistance, worldPos, fog);
    if (BlRole == 1 && bl_encodeEmission()) {
        fragColor.a = bl_emissionAlpha(emission * (1.0 - fog));
    }
}
