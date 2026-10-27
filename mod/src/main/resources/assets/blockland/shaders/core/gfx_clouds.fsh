#version 150

// Ported from Bliss (Chocapic13' Shaders edit) by Xonk, based on Chocapic13's shaders; see
// assets/blockland/shaders/include/bliss/license.md. Bliss' cloud pass (dimensions/composite2.fsh: its dithers and the
// renderClouds call, with CLOUDS_INTERSECT_TERRAIN) around Bliss' clouds (include/bliss_clouds.glsl, imported by
// scene.glsl in full here). What BlockLand changed is marked "BlockLand:".
//
// BlockLand's volumetric clouds pass (client/graphics/sky/VolumetricClouds, rendering spec §14), marched at a share of
// the internal resolution, stopped by the world (and the far terrain). Output: rgb light scattered towards the eye
// (linear), a transmittance — Bliss' renderClouds result.
//   Matrix    = NDC -> camera-relative world (homogeneous; the level projection without jitter)
//   Params[0] = (internal width, height, Bliss' minRayMarchSteps, maxRayMarchSteps)
//   Params[1] = (frame index, the far terrain's near and far planes, 1 = it was drawn: rays stop at it, spec §22)

#define BLISS_CLOUDS_FULL
#define CLOUDS_INTERSECT_TERRAIN
#define BLISS_CLOUD_STEPS ivec2(Params[0].zw)

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:scene.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D FarDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

// BlockLand: Bliss' frameCounter
#define frameCounter int(Params[1].x)
// BlockLand: the light handed to renderClouds (Bliss: lightCol.rgb / 80, averageSkyCol / 30 in its own units)
const float SUN = 1.0;
const float SKY = 1.0;

float R2_dither(){
	vec2 coord = gl_FragCoord.xy + (frameCounter%40000) * 2.0;
	vec2 alpha = vec2(0.75487765, 0.56984026);
	return fract(alpha.x * coord.x + alpha.y * coord.y ) ;
}

float blueNoise(){
	return fract(texelFetch(noisetex, ivec2(gl_FragCoord.xy)%512, 0).a + 1.0/1.6180339887 * frameCounter);
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 farPoint = Matrix * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    if (BlCloudShape.z <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // BlockLand: Bliss' viewPos0 — where the world (or the far terrain) is along the ray; the sky is far beyond any cloud
    ivec2 depthTexel = clamp(ivec2(texCoord * Params[0].xy), ivec2(0), ivec2(Params[0].xy) - 1);
    float depth = texelFetch(DepthSampler, depthTexel, 0).r;
    float limit = 1e7;
    if (depth < 1.0) {
        vec4 world = Matrix * vec4(ndc, depth * 2.0 - 1.0, 1.0);
        limit = length(world.xyz / world.w);
    } else if (Params[1].w > 0.5) {
        float farDepth = texelFetch(FarDepthSampler, depthTexel, 0).r;
        if (farDepth < 1.0) {
            // the far terrain's depth: the level's projection with its own planes; along the ray to that view depth
            float near = Params[1].y, farPlane = Params[1].z;
            float z = 2.0 * farPlane * near / ((farPlane + near) - (farDepth * 2.0 - 1.0) * (farPlane - near));
            vec4 centre = Matrix * vec4(0.0, 0.0, 1.0, 1.0);
            vec3 forward = normalize(centre.xyz / centre.w);
            limit = z / max(dot(dir, forward), 1e-3);
        }
    }
    vec3 viewPos0 = dir * limit;

	float noise_1 = max(1.0 - R2_dither(),0.0015);
	float noise_2 = blueNoise();

	vec3 directLightColor = BlSunColor.rgb * SUN;
	vec3 indirectLightColor = BlSkyColor.rgb * SKY;

	vec3 cloudDepth = vec3(0.0);
	fragColor = renderClouds(viewPos0, vec2(noise_1,noise_2), directLightColor, indirectLightColor, cloudDepth);
}
