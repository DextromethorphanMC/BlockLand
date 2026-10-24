#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:rt_common.glsl>

// Ray traced diffuse light and ambient occlusion (ray tracing resolution): a few cosine-weighted rays per pixel from the
// surface into the voxels. rgb = average light arriving from the faces they hit (rt_radiance: one bounce of Minecraft's
// own light, the blocks' glow, sun bounce), the light Minecraft's lighting leaves out; misses add nothing (the sky's
// light is in Minecraft's already). a = how open the surface is within the ambient occlusion radius.

uniform sampler2D DepthSampler;
uniform sampler2D GuideSampler;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 uv = (vec2(p) + 0.5) / RtSize.zw;
    ivec2 full = clamp(ivec2(uv * RtSize.xy), ivec2(0), ivec2(RtSize.xy) - 1);
    vec4 guide = texelFetch(GuideSampler, full, 0);
    if (guide.a <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 normal = guide.xyz;
    vec3 world = mat3(RtViewToWorld) * rt_viewPosition((vec2(full) + 0.5) / RtSize.xy, texelFetch(DepthSampler, full, 0).r);
    vec3 origin = RtCamera.xyz + world + normal * 0.03;
    uint seed = rt_seed(p, 0);
    int rays = int(RtTrace.x);
    vec3 light = vec3(0.0);
    float occlusion = 0.0;
    for (int i = 0; i < rays; i++) {
        vec3 dir = rt_cosine(normal, rt_random(seed), rt_random(seed));
        float t;
        ivec3 cell, front;
        vec3 face;
        if (rt_march(origin, dir, RtTrace.y, int(RtTrace.z), seed, t, cell, front, face)) {
            light += rt_radiance(origin + dir * t, cell, front, face, seed);
            occlusion += 1.0 - clamp(t / RtTrace.w, 0.0, 1.0);
        }
    }
    fragColor = vec4(light / float(rays), 1.0 - occlusion / float(rays));
}
