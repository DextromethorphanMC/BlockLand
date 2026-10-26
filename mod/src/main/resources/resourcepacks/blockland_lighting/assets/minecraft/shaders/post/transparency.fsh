#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's Fabulous transparency composite. Patched
// (BlRole 4), water bends the world behind it (refraction by its waves, red a little less than blue: dispersion) and
// swallows light with depth (absorption), and
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

// The opaque layer as seen through water (spec §15): refracted by the surface's waves (with dispersion), then seen
// through the water's real depth — BlockLand's volume (bl_lighting.glsl's water optics: absorption per channel, the
// biome's water colour scattered in) with Bliss's caustic pattern on the floor (ported from Bliss, Chocapic13' Shaders
// edit, by Xonk, based on Chocapic13's shaders; see assets/blockland/shaders/include/bliss/LICENSE.md), weighed by the
// sun's shadow at the floor and its depth under the surface.
// `surface` is the translucent layer's view position, `surfaceNormal` its world normal and `footprint` the blocks one pixel
// covers there (screen derivatives, taken in uniform control flow by the caller).
vec3 bl_throughWater(vec3 mainColor, float mainDepth, vec4 translucent, float translucentDepth, vec3 surface, vec3 surfaceNormal,
                     float footprint) {
    // water: the surface's alpha is odd (terrain.fsh marks it; glass panes and stained glass are even and stay as they are)
    bool water = mod(floor(translucent.a * 255.0 + 0.5), 2.0) > 0.5;
    if (!water) {
        return mainColor;
    }
    vec3 world = bl_worldFromView(surface);
    // the camera is above the water here (the caller checks): the surface faces up towards it
    vec3 waveNormal = bliss_waterNormal(world, vec3(0.0, 1.0, 0.0), footprint);
    vec3 bent = mat3(BlViewRotation) * vec3(waveNormal.x, 0.0, waveNormal.z);
    float thickness = length(bl_viewPosition(texCoord, mainDepth) - surface);
    vec2 offset = bent.xy * BlWater.w * 0.35 * clamp(thickness, 0.0, 1.5) / max(-surface.z, 1.0);
    vec2 uv = texCoord + offset;
    vec3 behind = mainColor;
    float behindDepth = mainDepth;
    if (uv.x > 0.0 && uv.y > 0.0 && uv.x < 1.0 && uv.y < 1.0) {
        float bentDepth = texture(MainDepthSampler, uv).r;
        // only what is really below the surface may be bent in (no objects in front of the water)
        if (bentDepth > translucentDepth) {
            behind = texture(MainSampler, uv).rgb;
            behindDepth = bentDepth;
            // dispersion: blue light bends a little more than red (water's index runs 1.331 red … 1.338 blue; widened
            // here so the fringe reads). Red and blue come from their own taps, the depth test of the green one stands
            // for them (they lie within a twentieth of the offset of it)
            if (dot(offset, offset) > 1e-10) {
                behind.r = texture(MainSampler, clamp(texCoord + offset * 0.95, vec2(0.0), vec2(1.0))).r;
                behind.b = texture(MainSampler, clamp(texCoord + offset * 1.05, vec2(0.0), vec2(1.0))).b;
            }
        }
    }
    // ---- BlockLand's water volume (spec §15): what lies under the surface is seen through the water's real depth.
    // The view ray runs `way` blocks through it (to the floor, or far when nothing is behind); light is absorbed per
    // channel (red first) and scattered into the biome's water colour on the way, so a shallow floor stays clear and a
    // deep one fades into the water body. The floor itself is lit by light that came down through its depth under the
    // surface, focused into caustics by the waves (none right under the surface, gone deep down).
    bool open = behindDepth >= 1.0;
    vec3 viewPos = open ? surface + normalize(surface) * 64.0 : bl_viewPosition(texCoord, behindDepth);
    vec3 surfaceWorld = world;
    vec3 floorWorld = bl_worldFromView(viewPos);
    float way = distance(viewPos, surface);
    float depth = max(surfaceWorld.y - floorWorld.y, 0.0);
    vec3 color = bl_linear(behind);
    vec3 absorb = bl_waterAbsorb();
    vec3 extinction = absorb + BL_WATER_SCATTER;
    if (!open) {
        // the light's way down: the sun's slanted, the sky's about straight
        float sunY = max(BlSunDir.y, 0.25);
        color *= exp(-absorb * depth * (0.5 + 0.5 / sunY));
        float caustics = bl_causticStrength(depth);
        if (caustics > 0.001 && BlSunDir.y > 0.0) {
            float sunShare = bl_shadow(floorWorld, vec3(0.0, 1.0, 0.0), gl_FragCoord.xy) * clamp(BlSunColor.a, 0.0, 1.0)
                    * max(bliss_lightSign(), 0.0);
            vec3 absolute = floorWorld + BlCamera.xyz;
            // Bliss's caustic pattern (lib/waterBump.glsl), its mean near 1: brighter lines, darker gaps, the light's colour
            color *= mix(1.0, waterCaustics(absolute, BlSunDir.xyz) * WATER_CAUSTICS_BRIGHTNESS + 0.25, caustics * sunShare);
        }
    }
    vec3 transmitted = exp(-extinction * way);
    color = color * transmitted + bl_waterBody() * (1.0 - transmitted);
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
