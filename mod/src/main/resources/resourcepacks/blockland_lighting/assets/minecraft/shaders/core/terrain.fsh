#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's terrain fragment shader with the sun, its
// shadows, dynamic lights, emissive blocks and water / glass / metal shading on top, rain wetness and puddles (§15), and
// BlockLand's fog (§14). Unpatched (BlRole 0) or without world lighting and emission encoding, the result is exactly
// vanilla's.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>
#moj_import <blockland:bl_fog.glsl>
#moj_import <blockland:bl_ssr.glsl>

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
    // the face's true normal: fluids come with an up normal on every face (LiquidBlockRenderer), so a waterfall's sheet
    // or the slope of flowing water would otherwise be shaded as a level surface seen edge-on (a glaring Fresnel)
    vec3 faceNormal = normalize(cross(dFdx(worldPos), dFdy(worldPos)) + vec3(0.0, 1e-9, 0.0));
    if (dot(faceNormal, worldPos) > 0.0) {
        faceNormal = -faceNormal;
    }
    if (BlRole == 0 || !(bl_worldLit() || bl_encodeEmission())) {
        fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        return;
    }

    vec4 material = bl_blockMaterial(texCoord0);
    float emission = bl_texelEmission(material, texel.rgb);
    if (bl_worldLit()) {
        int kind = int(material.z + 0.5);
        bool water = BlRole == 3 && kind == BL_KIND_WATER;
        // lava comes with the same up normal on its falling faces: a glowing block whose "up" face is not level is one
        bool lava = material.x > 0.0 && worldNormal.y > 0.999 && faceNormal.y < 0.9;
        vec3 n = water || lava ? faceNormal : normalize(worldNormal);
        float sun = bl_sunVisibility(worldPos, n, gl_FragCoord.xy);
        vec3 light = bl_skyShade(vertexColor.rgb, blockColor, sun) + vertexShade * bl_pointLightsDisplay(worldPos, n);
        color.rgb = texel.rgb * min(light, vec3(1.0)) * ColorModulator.rgb;
        // glowing texels stay at their own colour whatever the light around them
        color.rgb = max(color.rgb, texel.rgb * ColorModulator.rgb * emission);

        vec3 v = normalize(-worldPos);
        float outdoors = smoothstep(0.35, 0.9, lightLevel.y) * (1.0 - BlCamera.w);
        if (kind == BL_KIND_METAL) {
            // polished metal: a sun highlight and a hint of the sky
            vec3 h = normalize(v + BlSunDir.xyz);
            float spec = pow(max(dot(n, h), 0.0), 48.0) * sun;
            float fres = bl_fresnel(max(dot(n, v), 0.0), 0.5);
            color.rgb += (BlSunColor.rgb * spec * 0.55 + bl_display(BlSkyColor.rgb) * fres * outdoors * 0.12) * material.w;
        } else if (water) {
            // water (spec §15): level surfaces carry waves, rain drops and the rings of whatever disturbed them; a falling
            // sheet streaks downwards and churns white. The environment (sky above, horizon below, darker where the sky is
            // hidden) is reflected by Fresnel against the face's true normal, and the sun glints off it.
            float t = bl_time();
            vec3 wn;
            float foam = 0.0;
            if (n.y > 0.45) {
                vec2 s = bl_waterSlope(worldPos, t) * smoothstep(0.45, 0.9, n.y);
                wn = normalize(n + vec3(-s.x, 0.0, -s.y));
            } else {
                vec3 w = worldPos + BlCamera.xyz;
                vec3 across = normalize(vec3(-n.z, 0.0, n.x) + vec3(1e-4, 0.0, 0.0));
                float u = dot(w, across);
                float fall = w.y + t * 4.5;
                float streak = sin(u * 11.0 + sin(fall * 0.9) * 1.7) * 0.6 + sin(u * 23.0 - fall * 0.35) * 0.4;
                float churn = bl_cloudNoise2(vec2(u * 2.5, fall * 1.2), 1.0) * 0.65 + bl_cloudNoise2(vec2(u * 6.0, fall * 3.0), 1.0) * 0.35;
                wn = normalize(n + across * streak * 0.14 + vec3(0.0, (churn - 0.5) * 0.25, 0.0));
                foam = smoothstep(0.5, 0.8, churn) * 0.55 * (1.0 - abs(n.y));
            }
            float fres = bl_fresnel(max(dot(wn, v), 0.0), 0.02);
            vec3 r = reflect(-v, wn);
            vec3 env = bl_reflection(r, outdoors, FogColor.rgb);
            // what is really there (spec §17), where last frame's picture shows it
            vec4 seen = bl_ssr(worldPos + n * 0.03, r, gl_FragCoord.xy);
            env = mix(env, seen.rgb, seen.a);
            float reflection = clamp(fres * BlWater.z * max(material.w, 0.5), 0.0, 0.9);
            color.rgb = mix(color.rgb, env, reflection);
            color.a = mix(color.a, 1.0, reflection * 0.55);
            // aerated water scatters light like snow: white, lit like the world around it
            color.rgb = mix(color.rgb, min(light, vec3(1.0)) * 0.92, foam);
            color.a = mix(color.a, 0.92, foam);
            float spec = bl_sunSpecular(wn, v, 0.07 + foam * 0.5) * BlWater.z * outdoors * step(0.0, BlSunDir.y)
                    * bl_shadow(worldPos, n, gl_FragCoord.xy);
            color.rgb += BlSunColor.rgb * spec * 0.6;
            color.a = max(color.a, min(spec * 0.35, 1.0));
        }
        if (!water) {
            // rain (spec §15): porous blocks soak up water and darken, a film of water reflects the sky and the sun,
            // puddles stand in the low spots of flat ground with the rain's rings on them
            // glowing texels (lava, lamps) stay as they are
            float wet = bl_wetness(lightLevel.y, n) * (1.0 - emission);
            if (wet > 0.01) {
                bool hard = kind == BL_KIND_METAL || kind == BL_KIND_GLASS;
                vec3 worldAbs = worldPos + BlCamera.xyz;
                float puddle = n.y > 0.9 && BlRole == 1 ? bl_puddle(worldAbs, wet) : 0.0;
                color.rgb *= mix(1.0, 0.62, wet * (hard ? 0.15 : 1.0) * (1.0 - 0.5 * puddle));
                vec3 pn = n;
                if (puddle > 0.0) {
                    vec2 s = bl_rainSlope(worldAbs.xz, bl_time()) * puddle;
                    pn = normalize(n + vec3(-s.x, 0.0, -s.y));
                }
                float film = wet * mix(0.35, 1.0, puddle);
                float reflection = clamp(bl_fresnel(max(dot(pn, v), 0.0), 0.02) * film, 0.0, 0.85);
                vec3 r = reflect(-v, pn);
                vec3 env = bl_reflection(r, outdoors, FogColor.rgb);
                if (puddle > 0.05) {
                    // a puddle is a mirror: what really stands around it (spec §17); the thin film stays a blur of the sky
                    vec4 seen = bl_ssr(worldPos + n * 0.03, r, gl_FragCoord.xy);
                    env = mix(env, seen.rgb, seen.a * puddle);
                }
                color.rgb = mix(color.rgb, env, reflection);
                color.rgb += BlSunColor.rgb * bl_sunSpecular(pn, v, mix(0.3, 0.05, puddle)) * film * sun * 0.5;
            }
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

    float fog;
    vec4 fogged = bl_fog(color, sphericalVertexDistance, cylindricalVertexDistance, worldPos, fog);
    if (BlRole == 1 && bl_encodeEmission()) {
        fogged.a = bl_emissionAlpha(emission * (1.0 - fog));
    }
    fragColor = fogged;
}
