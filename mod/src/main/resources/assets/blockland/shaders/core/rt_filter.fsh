#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:rt_common.glsl>

// Edge-avoiding à-trous filter of the traced light (ray tracing resolution, Dammertz et al. 2010, with SVGF's luminance
// stopping): a 5×5 B3 spline kernel spread by the step (1, 2, 4, 8 over four passes), each tap weighted by how alike its
// surface is (linear depth, normal) and its light (luminance, against how noisy this pixel still is: the variance of
// its accumulated luminance, larger while its history is short). Where the light is settled, edges in it — contact
// shadows, the rim of a lit patch — stay sharp; where it is still noisy, the filter reaches wide.
//   Params[0].x = step (pixels)

uniform sampler2D InSampler;
uniform sampler2D GuideSampler;
uniform sampler2D MetaSampler;

out vec4 fragColor;

vec4 guideAt(ivec2 p) {
    vec2 uv = (vec2(p) + 0.5) / RtSize.zw;
    return texelFetch(GuideSampler, clamp(ivec2(uv * RtSize.xy), ivec2(0), ivec2(RtSize.xy) - 1), 0);
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Variance of the accumulated luminance around p (3×3 Gaussian), boosted while the history is short.
float varianceAt(ivec2 p, ivec2 size) {
    float sum = 0.0, weights = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec4 meta = texelFetch(MetaSampler, clamp(p + ivec2(x, y), ivec2(0), size - 1), 0);
            if (meta.x <= 0.0) continue;
            float w = (x == 0 ? 2.0 : 1.0) * (y == 0 ? 2.0 : 1.0);
            sum += max(meta.w - meta.z * meta.z, 0.0) * max(1.0, 4.0 / meta.x) * w;
            weights += w;
        }
    }
    return weights > 0.0 ? sum / weights : 1.0;
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
    float ownLuminance = luminance(own.rgb);
    float sigma = 4.0 * sqrt(varianceAt(p, size)) + 1e-3 + 0.02 * ownLuminance;
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            ivec2 q = clamp(p + ivec2(x, y) * stepSize, ivec2(0), size - 1);
            vec4 g = guideAt(q);
            if (g.a <= 0.0) continue;
            vec4 value = texelFetch(InSampler, q, 0);
            float w = kernel[abs(x)] * kernel[abs(y)];
            w *= exp(-abs(g.a - centre.a) / (0.03 * centre.a + 0.05));
            w *= pow(max(dot(g.xyz, centre.xyz), 0.0), 32.0);
            w *= exp(-abs(luminance(value.rgb) - ownLuminance) / sigma);
            sum += value * w;
            weights += w;
        }
    }
    fragColor = weights > 1e-5 ? sum / weights : own;
}
