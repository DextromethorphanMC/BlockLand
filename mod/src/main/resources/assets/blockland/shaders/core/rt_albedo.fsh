#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:rt_common.glsl>

// Ray tracing albedo (internal resolution, linear): the colour of the block under each pixel from the voxels, or the
// scene's own colour where no voxel is (entities, items, beyond the box). Multiplies the traced light in the resolve.
// Params[0].x = 1: Ray Reconstruction's diffuse albedo guide instead (the scene's colour with Minecraft's light in it,
// so its texture detail is never mistaken for noise; floored by the voxel's colour so dark corners, where the traced light
// matters most, are not divided by almost nothing). The resolve always multiplies by the plain albedo (Params 0).

uniform sampler2D DepthSampler;
uniform sampler2D GuideSampler;
uniform sampler2D SceneSampler;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 guide = texelFetch(GuideSampler, p, 0);
    vec3 scene = pow(max(texelFetch(SceneSampler, p, 0).rgb, vec3(0.0)), vec3(2.2));
    if (guide.a <= 0.0) {
        fragColor = vec4(scene, 1.0);
        return;
    }
    vec2 uv = (vec2(p) + 0.5) / RtSize.xy;
    vec3 world = mat3(RtViewToWorld) * rt_viewPosition(uv, texelFetch(DepthSampler, p, 0).r);
    ivec3 cell = ivec3(floor(RtCamera.xyz + world - guide.xyz * 0.02));
    vec3 albedo = scene;
    if (rt_inside(cell)) {
        vec4 voxel = rt_color(cell);
        if (voxel.a > 0.0) albedo = pow(voxel.rgb, vec3(2.2));
    }
    if (Params[0].x > 0.5) albedo = max(scene, albedo * 0.15);
    fragColor = vec4(albedo, 1.0);
}
