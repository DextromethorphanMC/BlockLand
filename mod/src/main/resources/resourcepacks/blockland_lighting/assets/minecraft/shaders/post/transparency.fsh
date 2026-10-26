#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's Fabulous transparency composite. Patched
// (BlRole 4), water bends the world behind it (refraction by its waves) and swallows light with depth (absorption), and
// the opaque layer's emission alpha is carried through, dimmed by every layer in front of it. Unpatched, or without world
// lighting and emission encoding, the result is exactly vanilla's.

#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>
#moj_import <blockland:bliss_water.glsl>

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D TranslucentSampler;
uniform sampler2D TranslucentDepthSampler;
uniform sampler2D ItemEntitySampler;
uniform sampler2D ItemEntityDepthSampler;
uniform sampler2D ParticlesSampler;
uniform sampler2D ParticlesDepthSampler;
uniform sampler2D WeatherSampler;
uniform sampler2D WeatherDepthSampler;
uniform sampler2D CloudsSampler;
uniform sampler2D CloudsDepthSampler;

in vec2 texCoord;

#define NUM_LAYERS 6

vec4 color_layers[NUM_LAYERS];
float depth_layers[NUM_LAYERS];
int active_layers = 0;

out vec4 fragColor;

void try_insert( vec4 color, float depth ) {
    if ( color.a == 0.0 ) {
        return;
    }

    color_layers[active_layers] = color;
    depth_layers[active_layers] = depth;

    int jj = active_layers++;
    int ii = jj - 1;
    while ( jj > 0 && depth_layers[jj] > depth_layers[ii] ) {
        float depthTemp = depth_layers[ii];
        depth_layers[ii] = depth_layers[jj];
        depth_layers[jj] = depthTemp;

        vec4 colorTemp = color_layers[ii];
        color_layers[ii] = color_layers[jj];
        color_layers[jj] = colorTemp;

        jj = ii--;
    }
}

vec3 blend( vec3 dst, vec4 src ) {
    return ( dst * ( 1.0 - src.a ) ) + src.rgb;
}

// The opaque layer as seen through water (spec §15): refracted by the surface's waves, then — ported from Bliss
// (Chocapic13' Shaders edit) by Xonk, based on Chocapic13's shaders; see assets/blockland/shaders/include/bliss/LICENSE.md —
// the under-water shading of Bliss's dimensions/composite1.fsh (Absorbtion and the caustics on the surface below) and
// the water volume of dimensions/fogBehindTranslucent_pass.fsh (waterVolumetrics_test). BlockLand: in linear light from
// the display colours; the caustics weigh the sun's share by its shadow at the point (Bliss multiplies only the direct
// light); the light map Bliss reads (sky light at the water) is the camera's openness to the sky (BlWeather.z).
// `surface` is the translucent layer's view position, `surfaceNormal` its world normal and `footprint` the blocks one pixel
// covers there (screen derivatives, taken in uniform control flow by the caller).
vec3 bl_throughWater(vec3 mainColor, float mainDepth, vec4 translucent, float translucentDepth, vec3 surface, vec3 surfaceNormal,
                     float footprint) {
    // water: a level, bluish translucent surface (glass panes and stained glass stay as they are)
    bool water = abs(surfaceNormal.y) > 0.85 && translucent.b > translucent.r * 1.15;
    if (!water) {
        return mainColor;
    }
    vec3 world = bl_worldFromView(surface);
    // the camera is above the water here (the caller checks): the surface faces up towards it
    vec3 waveNormal = bliss_waterNormal(world, vec3(0.0, 1.0, 0.0), footprint);
    vec3 bent = mat3(BlViewRotation) * vec3(waveNormal.x, 0.0, waveNormal.z);
    float thickness = length(bl_viewPosition(texCoord, mainDepth) - surface);
    vec2 uv = texCoord + bent.xy * BlWater.w * 0.35 * clamp(thickness, 0.0, 1.5) / max(-surface.z, 1.0);
    vec3 behind = mainColor;
    float behindDepth = mainDepth;
    if (uv.x > 0.0 && uv.y > 0.0 && uv.x < 1.0 && uv.y < 1.0) {
        float bentDepth = texture(MainDepthSampler, uv).r;
        // only what is really below the surface may be bent in (no objects in front of the water)
        if (bentDepth > translucentDepth) {
            behind = texture(MainSampler, uv).rgb;
            behindDepth = bentDepth;
        }
    }
    vec3 viewPos = behindDepth >= 1.0 ? surface + normalize(surface) * 24.0 : bl_viewPosition(texCoord, behindDepth);
    vec3 viewPos0 = surface;
    vec3 feetPlayerPos = bl_worldFromView(viewPos);
    vec3 feetPlayerPos_normalized = normalize(feetPlayerPos);
    vec3 color = bl_linear(behind);
    float lightmapY = BlWeather.z;

    // ---- composite1.fsh: UNDER WATER SHADING (isEyeInWater == 0 && isWater)
    float dirtAmount = Dirt_Amount + 0.01;
    vec3 waterEpsilon = vec3(Water_Absorb_R, Water_Absorb_G, Water_Absorb_B);
    vec3 dirtEpsilon = vec3(Dirt_Absorb_R, Dirt_Absorb_G, Dirt_Absorb_B);
    vec3 totEpsilon = dirtEpsilon*dirtAmount + waterEpsilon;

    float Vdiff = distance(viewPos, viewPos0)*mix(5.0,2.0,clamp(pow(lightmapY + lightmapY,2.0) ,0.0,1.0));
    float estimatedDepth = Vdiff * abs(feetPlayerPos_normalized.y);	//assuming water plane
    float depthfalloff = 1.0 - clamp(exp(-0.1*estimatedDepth),0.0,1.0);
    vec3 Absorbtion = mix(exp(-2.0 * totEpsilon * estimatedDepth), exp(-8.0 * totEpsilon), depthfalloff);

    // apply caustics to the lighting, and make sure they dont look weird
    if (behindDepth < 1.0 && BlSunDir.y > 0.0) {
        float sunShare = bl_shadow(feetPlayerPos, vec3(0.0, 1.0, 0.0), gl_FragCoord.xy) * clamp(BlSunColor.a, 0.0, 1.0)
                * max(bliss_lightSign(), 0.0);
        vec3 absolute = feetPlayerPos + BlCamera.xyz;
        color *= mix(1.0, waterCaustics(absolute, BlSunDir.xyz)*WATER_CAUSTICS_BRIGHTNESS + 0.25, clamp(estimatedDepth,0.0,1.0) * sunShare);
    }
    color *= Absorbtion;

    // ---- fogBehindTranslucent_pass.fsh: the water between the surface and what lies behind it
    float dirtAmount2 = Dirt_Amount + 0.1;
    vec3 totEpsilon2 = dirtEpsilon*dirtAmount2 + waterEpsilon;
    vec3 scatterCoef = dirtAmount2 * vec3(Dirt_Scatter_R, Dirt_Scatter_G, Dirt_Scatter_B) / 3.14;
    vec3 directLightColor = BlSunColor.rgb;
    vec3 indirectLightColor_dynamic = BlSkyColor.rgb;
    float Vdiff2 = distance(viewPos, viewPos0) * 2.0;
    float VdotU = feetPlayerPos_normalized.y;
    float estimatedDepth2 = Vdiff2 * abs(VdotU) ;	//assuming water plane
    float estimatedSunDepth = estimatedDepth2 / abs(BlSunDir.y); //assuming water plane
    indirectLightColor_dynamic *= ambient_brightness * pow(1.0-pow(1.0-lightmapY,0.5),3.0)	;
    float caveDetection = 1.0 - lightmapY;
    vec4 underwaterVlFog = waterVolumetrics_test(bl_worldFromView(viewPos0), feetPlayerPos, estimatedDepth2, estimatedSunDepth, Vdiff2,
            bl_noise(gl_FragCoord.xy), totEpsilon2, scatterCoef, indirectLightColor_dynamic, directLightColor* (1.0-caveDetection),
            dot(feetPlayerPos_normalized, BlSunDir.xyz * bliss_lightSign()));
    color = color * underwaterVlFog.a + underwaterVlFog.rgb;
    return bl_display(color);
}

void main() {
    vec4 mainColor = texture( MainSampler, texCoord );
    float mainDepth = texture( MainDepthSampler, texCoord ).r;
    vec4 translucent = texture( TranslucentSampler, texCoord );
    float translucentDepth = texture( TranslucentDepthSampler, texCoord ).r;

    vec3 base = mainColor.rgb;
    bool patched = BlRole != 0;
    if (patched && bl_worldLit() && BlWater.w > 0.0 && BlCamera.w < 0.5) {
        vec3 surface = bl_viewPosition(texCoord, translucentDepth);
        vec3 sx = dFdx(surface), sy = dFdy(surface);
        vec3 surfaceNormal = bl_worldFromView(normalize(cross(sx, sy) + vec3(0.0, 0.0, 1e-6)));
        // blocks per pixel on the surface, clamped where the derivative leaps across an edge
        float footprint = min(max(length(sx), length(sy)), -surface.z * 0.02);
        if (translucent.a > 0.0 && translucentDepth < mainDepth) {
            base = bl_throughWater(base, mainDepth, translucent, translucentDepth, surface, surfaceNormal, footprint);
        }
    }

    color_layers[0] = vec4( base, 1.0 );
    depth_layers[0] = mainDepth;
    active_layers = 1;

    try_insert( translucent, translucentDepth );
    try_insert( texture( ItemEntitySampler, texCoord ), texture( ItemEntityDepthSampler, texCoord ).r );
    try_insert( texture( ParticlesSampler, texCoord ), texture( ParticlesDepthSampler, texCoord ).r );
    try_insert( texture( WeatherSampler, texCoord ), texture( WeatherDepthSampler, texCoord ).r );
    try_insert( texture( CloudsSampler, texCoord ), texture( CloudsDepthSampler, texCoord ).r );

    vec3 texelAccum = color_layers[0].rgb;
    float keep = 1.0;
    for ( int ii = 1; ii < active_layers; ++ii ) {
        texelAccum = blend( texelAccum, color_layers[ii] );
        keep *= 1.0 - color_layers[ii].a;
    }

    float alpha = 1.0;
    if (patched && bl_encodeEmission() && mainDepth < 1.0) {
        alpha = bl_emissionAlpha(clamp((1.0 - mainColor.a) * 2.0, 0.0, 1.0) * keep);
    }
    fragColor = vec4( texelAccum.rgb, alpha );
}
