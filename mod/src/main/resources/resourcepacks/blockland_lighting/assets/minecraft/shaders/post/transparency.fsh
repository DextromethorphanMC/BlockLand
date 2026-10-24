#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's Fabulous transparency composite. Patched
// (BlRole 4), water bends the world behind it (refraction by its waves) and swallows light with depth (absorption), and
// the opaque layer's emission alpha is carried through, dimmed by every layer in front of it. Unpatched, or without world
// lighting and emission encoding, the result is exactly vanilla's.

#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>

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

// The opaque layer as seen through water: refracted by the surface's waves and absorbed along the path below it.
// `surface` is the translucent layer's view position, `surfaceNormal` its world normal (from screen derivatives, taken in
// uniform control flow by the caller).
vec3 bl_throughWater(vec3 mainColor, float mainDepth, vec4 translucent, float translucentDepth, vec3 surface, vec3 surfaceNormal) {
    // water: a level, bluish translucent surface (glass panes and stained glass stay as they are)
    bool water = abs(surfaceNormal.y) > 0.85 && translucent.b > translucent.r * 1.15;
    if (!water) {
        return mainColor;
    }
    vec3 world = bl_worldFromView(surface);
    vec2 slope = bl_waveSlope(world.xz + BlCamera.xz, bl_time());
    vec3 bent = mat3(BlViewRotation) * vec3(slope.x, 0.0, slope.y);
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
    thickness = behindDepth >= 1.0 ? 24.0 : length(bl_viewPosition(texCoord, behindDepth) - surface);
    // clear water keeps blue-green longest
    vec3 absorbed = exp(-vec3(0.42, 0.11, 0.065) * thickness * BlWater.w);
    return behind * absorbed;
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
        vec3 surfaceNormal = bl_worldFromView(normalize(cross(dFdx(surface), dFdy(surface)) + vec3(0.0, 0.0, 1e-6)));
        if (translucent.a > 0.0 && translucentDepth < mainDepth) {
            base = bl_throughWater(base, mainDepth, translucent, translucentDepth, surface, surfaceNormal);
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
