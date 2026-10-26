#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand post chain, step 1 (client/graphics/post/PostChain): the frame as drawn (display colours, emission in the
// alpha of opaque pixels, rendering spec §3) into linear HDR at the internal resolution. World pixels get the ambient
// occlusion, the ray traced light (client/graphics/raytrace: added, its ambient occlusion replacing the screen-space one)
// the volumetric clouds (client/graphics/sky: the picture behind them × their transmittance, plus their light) and the
// volumetric light; the hand and the viewmodel (drawn after the level: depth < 1 in the hand depth) get none; the sun and the moon (the brightest sky pixels) are lifted into HDR so they bloom. Output alpha = 1 on
// hand / viewmodel pixels (the temporal and blur passes keep them apart from the world).
//   Params[0] = (emission boost: HDR multiplier added at full emission, AO strength, AO present, volumetric light present)
//   Params[1] = (the alpha carries emission, ray traced light present, clouds present, far terrain drawn)
// The far terrain (rendering spec §22) lies where the world's depth is 1, like the sky; its own depth (FarDepthSampler,
// < 1 where it was drawn) keeps it from the sun's glow.

uniform sampler2D SceneSampler;
uniform sampler2D WorldDepthSampler;
uniform sampler2D HandDepthSampler;
uniform sampler2D AoSampler;
uniform sampler2D VolumetricSampler;
uniform sampler2D RayTracedSampler;
uniform sampler2D CloudSampler;
uniform sampler2D FarDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    vec3 color = gfx_linear(scene.rgb);
    float hand = texture(HandDepthSampler, texCoord).r < 1.0 ? 1.0 : 0.0;
    float worldDepth = texture(WorldDepthSampler, texCoord).r;
    // encoded alpha lies in 0.5..1; lower values are blended layers over the cleared (alpha 0) background, not glow
    if (Params[1].x > 0.5 && (hand > 0.5 || worldDepth < 1.0) && scene.a >= 0.5) {
        float emission = clamp(2.0 * (1.0 - scene.a), 0.0, 1.0);
        color *= 1.0 + emission * Params[0].x;
    }
    bool farTerrain = Params[1].w > 0.5 && worldDepth >= 1.0 && texture(FarDepthSampler, texCoord).r < 1.0;
    if (hand < 0.5 && worldDepth >= 1.0 && !farTerrain) {
        // the sky: only the sun's and the moon's discs are this bright, give them the glow they have in a camera
        color *= 1.0 + 2.0 * smoothstep(0.75, 1.0, gfx_luminance(scene.rgb));
    }
    if (hand < 0.5) {
        if (Params[1].y > 0.5 && worldDepth < 1.0) {
            vec4 traced = texture(RayTracedSampler, texCoord);
            color = color * traced.a + traced.rgb;
        } else if (Params[0].z > 0.5 && worldDepth < 1.0) {
            color *= mix(1.0, texture(AoSampler, texCoord).r, Params[0].y);
        }
        if (Params[1].z > 0.5) {
            vec4 clouds = texture(CloudSampler, texCoord);
            color = color * clouds.a + clouds.rgb;
        }
        if (Params[0].w > 0.5) {
            // the volumetric fog: what lies behind it dimmed by its transmittance, then the light it scatters
            vec4 fog = texture(VolumetricSampler, texCoord);
            color = color * fog.a + fog.rgb;
        }
    }
    fragColor = vec4(max(color, vec3(0.0)), hand);
}
