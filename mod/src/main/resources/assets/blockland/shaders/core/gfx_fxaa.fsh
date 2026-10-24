#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand FXAA (client/graphics/post/PostChain, anti-aliasing "FXAA"): Lottes' FXAA 3.11 quality algorithm on the
// tone-mapped picture (luma in its alpha): find the edge direction, walk along the edge to both ends, blend across it.
//   Params[0] = (texel size xy, -, -)

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

const float EDGE_MIN = 0.0312;
const float EDGE_MAX = 0.125;
const float STEP[10] = float[10](1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 4.0, 8.0);

float luma(vec2 uv) {
    return texture(InSampler, uv).a;
}

void main() {
    vec2 t = Params[0].xy;
    vec2 uv = texCoord;
    vec4 center = texture(InSampler, uv);
    float lC = center.a;
    float lD = luma(uv + vec2(0.0, -t.y));
    float lU = luma(uv + vec2(0.0, t.y));
    float lL = luma(uv + vec2(-t.x, 0.0));
    float lR = luma(uv + vec2(t.x, 0.0));
    float lMin = min(lC, min(min(lD, lU), min(lL, lR)));
    float lMax = max(lC, max(max(lD, lU), max(lL, lR)));
    float range = lMax - lMin;
    if (range < max(EDGE_MIN, lMax * EDGE_MAX)) {
        fragColor = vec4(center.rgb, 1.0);
        return;
    }
    float lDL = luma(uv + vec2(-t.x, -t.y));
    float lUR = luma(uv + vec2(t.x, t.y));
    float lUL = luma(uv + vec2(-t.x, t.y));
    float lDR = luma(uv + vec2(t.x, -t.y));
    float lDU = lD + lU;
    float lLR = lL + lR;
    float left = lDL + lUL, down = lDL + lDR, right = lDR + lUR, up = lUR + lUL;
    float edgeH = abs(-2.0 * lL + left) + abs(-2.0 * lC + lDU) * 2.0 + abs(-2.0 * lR + right);
    float edgeV = abs(-2.0 * lU + up) + abs(-2.0 * lC + lLR) * 2.0 + abs(-2.0 * lD + down);
    bool horizontal = edgeH >= edgeV;
    float l1 = horizontal ? lD : lL;
    float l2 = horizontal ? lU : lR;
    float g1 = l1 - lC;
    float g2 = l2 - lC;
    bool steep1 = abs(g1) >= abs(g2);
    float gradient = 0.25 * max(abs(g1), abs(g2));
    float stepLength = horizontal ? t.y : t.x;
    float localAverage;
    if (steep1) {
        stepLength = -stepLength;
        localAverage = 0.5 * (l1 + lC);
    } else {
        localAverage = 0.5 * (l2 + lC);
    }
    vec2 edge = uv;
    if (horizontal) edge.y += stepLength * 0.5;
    else edge.x += stepLength * 0.5;
    vec2 offset = horizontal ? vec2(t.x, 0.0) : vec2(0.0, t.y);
    vec2 uv1 = edge - offset;
    vec2 uv2 = edge + offset;
    float end1 = luma(uv1) - localAverage;
    float end2 = luma(uv2) - localAverage;
    bool reached1 = abs(end1) >= gradient;
    bool reached2 = abs(end2) >= gradient;
    if (!reached1) uv1 -= offset;
    if (!reached2) uv2 += offset;
    for (int i = 2; i < 10; i++) {
        if (reached1 && reached2) break;
        if (!reached1) end1 = luma(uv1) - localAverage;
        if (!reached2) end2 = luma(uv2) - localAverage;
        reached1 = abs(end1) >= gradient;
        reached2 = abs(end2) >= gradient;
        if (!reached1) uv1 -= offset * STEP[i];
        if (!reached2) uv2 += offset * STEP[i];
    }
    float d1 = horizontal ? uv.x - uv1.x : uv.y - uv1.y;
    float d2 = horizontal ? uv2.x - uv.x : uv2.y - uv.y;
    bool towards1 = d1 < d2;
    float closest = min(d1, d2);
    float span = d1 + d2;
    float pixelOffset = -closest / max(span, 1e-6) + 0.5;
    bool centreDarker = lC < localAverage;
    bool correct = ((towards1 ? end1 : end2) < 0.0) != centreDarker;
    float finalOffset = correct ? pixelOffset : 0.0;
    float average = (2.0 * (lDU + lLR) + left + right) / 12.0;
    float sub1 = clamp(abs(average - lC) / range, 0.0, 1.0);
    float sub2 = (-2.0 * sub1 + 3.0) * sub1 * sub1;
    finalOffset = max(finalOffset, sub2 * sub2 * 0.75);
    vec2 result = uv;
    if (horizontal) result.y += finalOffset * stepLength;
    else result.x += finalOffset * stepLength;
    fragColor = vec4(texture(InSampler, result).rgb, 1.0);
}
