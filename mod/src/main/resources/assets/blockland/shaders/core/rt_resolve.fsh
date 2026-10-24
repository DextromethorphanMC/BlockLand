#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:rt_common.glsl>

// Ray traced lighting for the post chain (internal resolution): the filtered light brought up from the ray tracing
// resolution (the four nearest texels, weighted by how alike their surfaces are), times the albedo and the strength;
// alpha the ambient occlusion with its strength applied. The decode pass adds rgb and multiplies by alpha. Both fade out
// towards the end of the box around the camera (where rays leave it early), so no edge shows where ray tracing ends.

uniform sampler2D InSampler;
uniform sampler2D GuideSampler;
uniform sampler2D AlbedoSampler;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 centre = texelFetch(GuideSampler, p, 0);
    if (centre.a <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 low = (vec2(p) + 0.5) / RtSize.xy * RtSize.zw - 0.5;
    ivec2 base = ivec2(floor(low));
    vec2 f = fract(low);
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    for (int y = 0; y <= 1; y++) {
        for (int x = 0; x <= 1; x++) {
            ivec2 q = clamp(base + ivec2(x, y), ivec2(0), ivec2(RtSize.zw) - 1);
            vec2 uv = (vec2(q) + 0.5) / RtSize.zw;
            vec4 g = texelFetch(GuideSampler, clamp(ivec2(uv * RtSize.xy), ivec2(0), ivec2(RtSize.xy) - 1), 0);
            float w = (x == 1 ? f.x : 1.0 - f.x) * (y == 1 ? f.y : 1.0 - f.y) + 1e-3;
            w *= g.a > 0.0 ? exp(-abs(g.a - centre.a) / (0.03 * centre.a + 0.05)) * max(dot(g.xyz, centre.xyz), 0.05) : 0.0;
            sum += texelFetch(InSampler, q, 0) * w;
            weights += w;
        }
    }
    vec4 traced = weights > 1e-5 ? sum / weights
            : texelFetch(InSampler, clamp(ivec2(low + 0.5), ivec2(0), ivec2(RtSize.zw) - 1), 0);
    vec3 albedo = texelFetch(AlbedoSampler, p, 0).rgb;
    // how far inside the part of the box the camera always has around it (the box follows the camera by whole
    // sections, so measuring from its faces would jump by 16 blocks each time it moves)
    vec2 ndc = (vec2(p) + 0.5) / RtSize.xy * 2.0 - 1.0;
    vec3 view = vec3(ndc.x * centre.a / RtProj.x, ndc.y * centre.a / RtProj.y, -centre.a);
    vec3 offset = abs(mat3(RtViewToWorld) * view);
    float reachXZ = floor(RtVolume.x * 0.5) * 16.0 - 16.0, reachY = floor(RtVolume.y * 0.5) * 16.0 - 16.0;
    float inside = min(reachXZ - max(offset.x, offset.z), reachY - offset.y);
    // full strength while rays are mostly whole, gone where the box may end (up to a section beyond the guaranteed reach)
    float edge = smoothstep(-16.0, 0.15 * RtTrace.y, inside);
    fragColor = vec4(albedo * traced.rgb * RtLight.y * edge, mix(1.0, traced.a, RtSunColor.a * edge));
}
