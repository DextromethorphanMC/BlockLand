#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:rt_common.glsl>

// Edge-avoiding à-trous filter of the traced light (ray tracing resolution, Dammertz et al. 2010): a 5×5 B3 spline kernel
// spread by the step (1, 2, 4 over three passes), each tap weighted by how alike its surface is (linear depth, normal).
//   Params[0].x = step (pixels)

uniform sampler2D InSampler;
uniform sampler2D GuideSampler;

out vec4 fragColor;

vec4 guideAt(ivec2 p) {
    vec2 uv = (vec2(p) + 0.5) / RtSize.zw;
    return texelFetch(GuideSampler, clamp(ivec2(uv * RtSize.xy), ivec2(0), ivec2(RtSize.xy) - 1), 0);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 centre = guideAt(p);
    vec4 own = texelFetch(InSampler, p, 0);
    if (centre.a <= 0.0) {
        fragColor = own;
        return;
    }
    float kernel[3] = float[3](3.0 / 8.0, 1.0 / 4.0, 1.0 / 16.0);
    int stepSize = int(Params[0].x);
    ivec2 size = ivec2(RtSize.zw);
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            ivec2 q = clamp(p + ivec2(x, y) * stepSize, ivec2(0), size - 1);
            vec4 g = guideAt(q);
            if (g.a <= 0.0) continue;
            float w = kernel[abs(x)] * kernel[abs(y)];
            w *= exp(-abs(g.a - centre.a) / (0.03 * centre.a + 0.05));
            w *= pow(max(dot(g.xyz, centre.xyz), 0.0), 32.0);
            sum += texelFetch(InSampler, q, 0) * w;
            weights += w;
        }
    }
    fragColor = weights > 1e-5 ? sum / weights : own;
}
