#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:rt_common.glsl>

// Temporal accumulation of the traced light (ray tracing resolution): the previous result reprojected by the camera's
// motion, kept while the surface it saw is the one seen now (linear depth within a few percent), blended with this
// frame's rays by 1 / history length. The history carries the light's first and second luminance moments too, so the
// filter knows how noisy each pixel still is (SVGF, Schied et al. 2017). The history is read from its four nearest texels,
// each kept only if it saw the same surface (no light leaking across silhouettes), and a new sample far brighter than
// the pixel's settled light is clamped towards it (fireflies).
//   Params[0] = (0: the accumulated light, 1: the history (length, linear depth, moments); longest history in frames)

uniform sampler2D DepthSampler;
uniform sampler2D GuideSampler;
uniform sampler2D CurrentSampler;
uniform sampler2D HistorySampler;
uniform sampler2D MetaSampler;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 uv = (vec2(p) + 0.5) / RtSize.zw;
    ivec2 full = clamp(ivec2(uv * RtSize.xy), ivec2(0), ivec2(RtSize.xy) - 1);
    vec4 current = texelFetch(CurrentSampler, p, 0);
    vec4 guide = texelFetch(GuideSampler, full, 0);
    float luminance = dot(current.rgb, vec3(0.2126, 0.7152, 0.0722));
    float frames = 1.0;
    vec4 history = current;
    vec2 moments = vec2(luminance, luminance * luminance);
    if (guide.a > 0.0) {
        float depth = texelFetch(DepthSampler, full, 0).r;
        vec4 previous = RtReprojection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        if (previous.w > 1e-4) {
            vec2 previousUv = previous.xy / previous.w * 0.5 + 0.5;
            if (all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThan(previousUv, vec2(1.0)))) {
                ivec2 size = ivec2(RtSize.zw);
                vec2 lp = previousUv * RtSize.zw - 0.5;
                ivec2 base = ivec2(floor(lp));
                vec2 f = lp - vec2(base);
                vec4 sumHistory = vec4(0.0), sumMeta = vec4(0.0);
                float weights = 0.0;
                for (int y = 0; y <= 1; y++) {
                    for (int x = 0; x <= 1; x++) {
                        ivec2 q = base + ivec2(x, y);
                        if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
                        vec4 meta = texelFetch(MetaSampler, q, 0);
                        if (meta.x <= 0.0 || abs(meta.y - previous.w) >= 0.04 * previous.w + 0.05) continue;
                        float w = (x == 1 ? f.x : 1.0 - f.x) * (y == 1 ? f.y : 1.0 - f.y) + 1e-4;
                        sumHistory += texelFetch(HistorySampler, q, 0) * w;
                        sumMeta += meta * w;
                        weights += w;
                    }
                }
                if (weights > 1e-3) {
                    vec4 meta = sumMeta / weights;
                    frames = min(floor(meta.x + 0.5) + 1.0, Params[0].y);
                    history = sumHistory / weights;
                    // fireflies: a sample far above the settled light is pulled down to it
                    float mean = meta.z, sigma = sqrt(max(meta.w - meta.z * meta.z, 0.0));
                    float ceiling = mean + 3.0 * sigma + 0.05;
                    if (frames > 4.0 && luminance > ceiling) {
                        current.rgb *= ceiling / luminance;
                        luminance = ceiling;
                        moments = vec2(luminance, luminance * luminance);
                    }
                    // the moments settle faster than the light: a short history still gives a usable variance
                    moments = mix(meta.zw, moments, max(1.0 / frames, 0.2));
                }
            }
        }
    }
    if (Params[0].x > 0.5) {
        fragColor = guide.a > 0.0 ? vec4(frames, guide.a, moments) : vec4(0.0);
    } else {
        fragColor = mix(history, current, 1.0 / frames);
    }
}
