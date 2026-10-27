#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's terrain fragment shader with the sun, its
// shadows, dynamic lights, emissive blocks, water / glass / metal shading, the sun through leaves and plants and the
// sheen of smooth blocks on top (§7), rain wetness and puddles (§15), and
// BlockLand's fog (§14). The water surface is Bliss's (Chocapic13' Shaders edit, by Xonk, based on Chocapic13's shaders),
// ported: see blockland:bliss_water.glsl and assets/blockland/shaders/include/bliss/license.md. Unpatched (BlRole 0) or without world lighting and emission encoding, the result is exactly
// vanilla's.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>
#moj_import <blockland:bl_fog.glsl>
#moj_import <blockland:bl_ssr.glsl>
#moj_import <blockland:bliss_water.glsl>

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
    vec3 dx = dFdx(worldPos), dy = dFdy(worldPos);
    vec3 faceNormal = normalize(cross(dx, dy) + vec3(0.0, 1e-9, 0.0));
    if (dot(faceNormal, worldPos) > 0.0) {
        faceNormal = -faceNormal;
    }
    // how many blocks this pixel covers (the water's waves fade before they get finer than that)
    float footprint = max(length(dx), length(dy));
    if (BlRole == 0 || !(bl_worldLit() || bl_encodeEmission())) {
        fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        return;
    }

    vec4 material = bl_blockMaterial(texCoord0);
    float emission = bl_texelEmission(material, texel.rgb);
    bool isWater = BlRole == 3 && int(material.z + 0.5) == BL_KIND_WATER && material.x <= 0.0;
    if (bl_worldLit()) {
        int kind = int(material.z + 0.5);
        bool water = BlRole == 3 && kind == BL_KIND_WATER;
        // lava is marked as glowing water in the materials (spec §16): the shader draws both fluids, not textures
        bool lavaFluid = kind == BL_KIND_WATER && material.x > 0.0;
        if (water) {
            // one colour, the biome's (in the vertex colour); the waves carry the detail
            texel = vec4(vec3(BL_WATER_GREY), BL_WATER_ALPHA);
            color = texel * vertexColor * ColorModulator;
        } else if (lavaFluid) {
            vec4 lava = bl_lava(worldPos + BlCamera.xyz, faceNormal, bl_time());
            texel = vec4(lava.rgb, 1.0);
            emission = lava.a;
            color = texel * vertexColor * ColorModulator;
        }
        // lava comes with the same up normal on its falling faces: a glowing block whose "up" face is not level is one
        bool lava = material.x > 0.0 && worldNormal.y > 0.999 && faceNormal.y < 0.9;
        // leaves and plants (the sprites the wind moves) let light through: shaded on the face's true plane turned
        // towards the viewer (a crossed plant's vertex normal is only the nearest axis)
        int wind = bl_blockWind(texCoord0);
        bool foliage = bl_isFoliage(wind);
        vec3 v = normalize(-worldPos);
        vec3 n = water || lava || foliage ? faceNormal : normalize(worldNormal);
        float sun, through = 0.0;
        if (foliage) {
            // a leaf block's back is its other side, a blade's is itself
            vec2 leafSun = bl_foliageSun(worldPos, n, v, (wind & 3) == 3 ? 1.0 : 0.0, gl_FragCoord.xy);
            sun = leafSun.x;
            through = leafSun.y;
        } else {
            sun = bl_sunVisibility(worldPos, n, gl_FragCoord.xy);
        }
        vec3 light = bl_skyShade(vertexColor.rgb, blockColor, sun) + vertexShade * bl_pointLightsDisplay(worldPos, n);
        if (through > 0.0) {
            // the sun through the leaf, at most a fifth over vanilla's sky light on the face
            vec3 sky = max(vertexColor.rgb - blockColor, vec3(0.0));
            vec3 lit = light + bl_foliageTransmission(sky, texel.rgb * vertexShade, through, smoothstep(0.5, 0.95, lightLevel.y));
            light = min(lit, max(light, sky * 1.2));
        }
        color.rgb = texel.rgb * min(light, vec3(1.0)) * ColorModulator.rgb;
        // glowing texels stay at their own colour whatever the light around them
        color.rgb = max(color.rgb, texel.rgb * ColorModulator.rgb * emission);

        float outdoors = smoothstep(0.35, 0.9, lightLevel.y) * (1.0 - BlCamera.w);
        if (kind == BL_KIND_METAL) {
            // polished metal: a sun highlight and a hint of the sky
            vec3 h = normalize(v + BlSunDir.xyz);
            float spec = pow(max(dot(n, h), 0.0), 48.0) * sun;
            float fres = bl_fresnel(max(dot(n, v), 0.0), 0.5);
            color.rgb += (BlSunColor.rgb * spec * 0.55 + bl_display(BlSkyColor.rgb) * fres * outdoors * 0.12) * material.w;
        } else if (water) {
            // ---- ported from Bliss (Chocapic13' Shaders edit) by Xonk, based on Chocapic13's shaders; see
            // assets/blockland/shaders/include/bliss/license.md. The water parts of Bliss's dimensions/all_translucent.fsh
            // (NORMALS, ALBEDO with Vanilla_like_water, SPECULAR LIGHTING); BlockLand: positions camera-relative world
            // space instead of view space, colours from the lit vanilla colour (display → linear and back), the sky from
            // bl_reflection instead of colortex4, the screen-space part from bl_ssr (spec §17), BlWater.z switches
            // reflections. Spec §15.
            vec3 viewPos = worldPos;
            vec2 lightmap = lightLevel;
            // the surface's own colour: a thin film over the water body where the composite draws what lies under it by its
            // depth (spec §15), a little more seen from below (the composite does not run there), else vanilla's
            float UnchangedAlpha = BlCamera.w > 0.5 ? 0.18 : (BlWater.w > 0.0 ? 0.06 : color.a);

            // ALBEDO (Vanilla_like_water: the water keeps its texture and biome colour, a little darker)
            vec3 Albedo = bl_linear(texel.rgb * vertexShade);
            float albedoDim = sqrt(luma(Albedo));
            vec3 FinalColor = bl_linear(color.rgb) * albedoDim;

            // NORMALS
            vec3 normal = bliss_waterNormal(worldPos, n, footprint);

            // DIFFUSE LIGHTING (the direct light the sun specular uses)
            vec3 DirectLightColor = BlSunColor.rgb;
            float NdotL = clamp(dot(normal, BlSunDir.xyz),0.0,1.0); NdotL = clamp((-15 + NdotL*255.0) / 240.0  ,0.0,1.0);
            int cascades = int(BlShadowParams.w + 0.5);
            float shadowDistance = cascades > 0 ? BlShadowSplits[cascades - 1] : 0.0;
            float shadowMapFalloff2 = smoothstep(0.0, 1.0, min(max(1.0 - length(viewPos) / max(shadowDistance, 1e-3),0.0)*5.0,1.0));
            float LM_shadowMapFallback = min(max(lightmap.y-0.8, 0.0) * 25,1.0);
            float Shadows = bl_shadowMap(worldPos, n, gl_FragCoord.xy);
            Shadows = mix(LM_shadowMapFallback, Shadows, shadowMapFalloff2);
            Shadows *= pow(bl_cloudShadow(worldPos),3);
            vec3 Direct_lighting = DirectLightColor * NdotL * Shadows;

            // SPECULAR LIGHTING
            vec2 specularValues = vec2(1.0, 0.02);
            float roughness = pow(1.0-specularValues.r,2.0);
            float f0 = max(specularValues.g, 0.02);
            vec3 Metals = vec3(1.0);

            vec3 Reflections_Final = vec3(0.0);
            vec4 Reflections = vec4(0.0);
            vec3 BackgroundReflection = FinalColor;
            vec3 SunReflection = vec3(0.0);
            float indoors = pow(1.0-pow(1.0-min(max(lightmap.y-0.6,0.0)*3.0,1.0),0.5),2.0);

            vec3 reflectedVector = reflect(normalize(viewPos), normal);
            float normalDotEye = dot(normal, normalize(viewPos));

            float fresnel =  pow(clamp(1.0 + normalDotEye, 0.0, 1.0),5.0);

            #ifdef SNELLS_WINDOW
                // snells window looking thing
                if(BlCamera.w > 0.5) fresnel = pow(clamp(1.5 + normalDotEye,0.0,1.0), 25.0);
            #endif

            fresnel = mix(f0, 1.0, fresnel);

            // Sun, Sky, and screen-space reflections
            SunReflection = Direct_lighting * GGX(normal, -normalize(viewPos), BlSunDir.xyz, max(roughness,0.035), f0) * Metals;
            if(BlCamera.w < 0.5) BackgroundReflection = bl_linear(bl_reflection(reflectedVector, 1.0, FogColor.rgb)) * Metals;
            // BlockLand: under water, what the surface mirrors outside Snell's window is the water body round the eye (its
            // colour and the light that reaches into it), not a fixed blue
            if(BlCamera.w > 0.5) BackgroundReflection.rgb = bl_waterBody();

            vec4 seen = bl_ssr(worldPos + n * 0.03, reflectedVector, gl_FragCoord.xy);
            Reflections = vec4(bl_linear(seen.rgb) * Metals, seen.a);

            float visibilityFactor = clamp(exp2((pow(roughness,3.0) / f0) * -4),0,1);

            // BlockLand: the reflections setting
            fresnel *= BlWater.z;
            SunReflection *= BlWater.z;

            Reflections_Final = mix(mix(FinalColor, BackgroundReflection, indoors), Reflections.rgb, Reflections.a) * fresnel * visibilityFactor;
            Reflections_Final += SunReflection;

            //correct alpha channel with fresnel
            float alpha0 = UnchangedAlpha;
            float alpha = -alpha0 * fresnel + alpha0 + fresnel;

            // prevent reflections from being darkened by buffer blending
            vec3 rgb = clamp(FinalColor / alpha*alpha0*(1.0-fresnel) + Reflections_Final / alpha, 0.0, 65100.0);
            color = vec4(bl_display(rgb), clamp(alpha, 0.0, 1.0));
        } else if (kind == BL_KIND_PLAIN && material.w > 0.0) {
            // dry smooth blocks (polished stone, packed ice, glazed terracotta, concrete; spec §7: the reflectivity of a
            // plain material is its smoothness): the sky by Schlick's Fresnel (f0 0.04) where the sky reaches, and the
            // sun's GGX highlight in the direct light; rough blocks (no reflectivity) get neither
            float gloss = material.w * (1.0 - emission);
            float fres = bl_fresnel(max(dot(n, v), 0.0), 0.04);
            color.rgb = mix(color.rgb, bl_reflection(reflect(-v, n), outdoors, FogColor.rgb), fres * gloss * gloss * outdoors * 0.7);
            color.rgb += BlSunColor.rgb * bl_sunSpecularF0(n, v, bl_blockRoughness(gloss), 0.04) * sun * clamp(BlSunColor.a, 0.0, 1.0);
        }
        if (!water) {
            // rain (spec §15): porous blocks soak up water and darken, a film of water reflects the sky and the sun,
            // puddles stand in the low spots of flat ground with the rain's rings on them
            // glowing texels (lava, lamps) stay as they are
            float wet = lavaFluid ? 0.0 : bl_wetness(lightLevel.y, n) * (1.0 - emission);
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
    if (BlRole == 3 && bl_worldLit()) {
        // spec §15: the composite tells water from other translucent blocks by the alpha's lowest bit (water odd, the rest
        // even: one 255th at most), whatever colour the water reflects
        float a8 = floor(clamp(fogged.a, 0.0, 1.0) * 255.0 + 0.5);
        bool odd = mod(a8, 2.0) > 0.5;
        if (isWater != odd) a8 += a8 >= 255.0 ? -1.0 : 1.0;
        fogged.a = a8 / 255.0;
    }
    fragColor = fogged;
}
