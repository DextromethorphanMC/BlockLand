#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's terrain fragment shader with the sun, its
// shadows, dynamic lights, emissive blocks and water / glass / metal shading on top. Unpatched (BlRole 0) or without
// world lighting and emission encoding, the result is exactly vanilla's.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 worldPos;
in vec3 worldNormal;
in vec3 vertexShade;
in vec3 blockColor;
in vec2 lightLevel;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    vec4 color = texel * vertexColor * ColorModulator;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    if (BlRole == 0 || !(bl_worldLit() || bl_encodeEmission())) {
        fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        return;
    }

    vec4 material = bl_blockMaterial(texCoord0);
    float emission = bl_texelEmission(material, texel.rgb);
    if (bl_worldLit()) {
        vec3 n = normalize(worldNormal);
        float sun = bl_sunVisibility(worldPos, n, gl_FragCoord.xy);
        vec3 light = bl_skyShade(vertexColor.rgb, blockColor, sun) + vertexShade * bl_pointLightsDisplay(worldPos, n);
        color.rgb = texel.rgb * min(light, vec3(1.0)) * ColorModulator.rgb;
        // glowing texels stay at their own colour whatever the light around them
        color.rgb = max(color.rgb, texel.rgb * ColorModulator.rgb * emission);

        int kind = int(material.z + 0.5);
        vec3 v = normalize(-worldPos);
        float outdoors = smoothstep(0.35, 0.9, lightLevel.y) * (1.0 - BlCamera.w);
        if (kind == BL_KIND_METAL) {
            // polished metal: a sun highlight and a hint of the sky
            vec3 h = normalize(v + BlSunDir.xyz);
            float spec = pow(max(dot(n, h), 0.0), 48.0) * sun;
            float fres = bl_fresnel(max(dot(n, v), 0.0), 0.5);
            color.rgb += (BlSunColor.rgb * spec * 0.55 + bl_display(BlSkyColor.rgb) * fres * outdoors * 0.12) * material.w;
        } else if (BlRole == 3 && kind == BL_KIND_WATER && n.y > 0.5) {
            // water surface: world-anchored waves, the sky reflected towards grazing angles and the sun's glint
            vec3 wn = bl_waterNormal(worldPos + BlCamera.xyz, bl_time());
            float fres = bl_fresnel(max(dot(wn, v), 0.0), 0.02);
            float reflection = clamp(fres * BlWater.z * outdoors * max(material.w, 0.5), 0.0, 1.0);
            color.rgb = mix(color.rgb, bl_display(BlSkyColor.rgb), reflection);
            color.a = mix(color.a, 1.0, reflection * 0.6);
            vec3 r = reflect(-v, wn);
            float glint = pow(max(dot(r, BlSunDir.xyz), 0.0), 240.0) * BlWater.z * outdoors
                    * bl_shadow(worldPos, vec3(0.0, 1.0, 0.0), gl_FragCoord.xy) * step(0.0, BlSunDir.y);
            color.rgb += BlSunColor.rgb * glint * 3.0;
            color.a = max(color.a, min(glint * 2.0, 1.0));
        } else if (BlRole == 3 && kind == BL_KIND_GLASS) {
            // glass and ice: a faint sky reflection and a sharp sun highlight
            float fres = bl_fresnel(max(dot(n, v), 0.0), 0.04);
            color.rgb = mix(color.rgb, bl_display(BlSkyColor.rgb), fres * outdoors * 0.5 * max(material.w, 0.3));
            vec3 h = normalize(v + BlSunDir.xyz);
            float spec = pow(max(dot(n, h), 0.0), 160.0) * sun * outdoors;
            color.rgb += BlSunColor.rgb * spec;
            color.a = max(color.a, min(spec, 1.0));
        }
    }

    vec4 fogged = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    if (BlRole == 1 && bl_encodeEmission()) {
        float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd) * FogColor.a;
        fogged.a = bl_emissionAlpha(emission * (1.0 - fog));
    }
    fragColor = fogged;
}
