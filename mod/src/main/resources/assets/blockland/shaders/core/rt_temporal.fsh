#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:rt_common.glsl>

// Temporal accumulation of the traced light (ray tracing resolution): the previous result reprojected by the camera's
// motion, kept while the surface it saw is the one seen now (linear depth within a few percent), blended with this
// frame's rays by 1 / history length.
//   Params[0] = (0: the accumulated light, 1: the history (length, linear depth); longest history in frames)

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
    float frames = 1.0;
    vec4 history = current;
    if (guide.a > 0.0) {
        float depth = texelFetch(DepthSampler, full, 0).r;
        vec4 previous = RtReprojection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        if (previous.w > 1e-4) {
            vec2 previousUv = previous.xy / previous.w * 0.5 + 0.5;
            if (all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThan(previousUv, vec2(1.0)))) {
                vec4 meta = texture(MetaSampler, previousUv);
                if (meta.x > 0.0 && abs(meta.y - previous.w) < 0.04 * previous.w + 0.05) {
                    frames = min(meta.x + 1.0, Params[0].y);
                    history = texture(HistorySampler, previousUv);
                }
            }
        }
    }
    if (Params[0].x > 0.5) {
        fragColor = vec4(guide.a > 0.0 ? frames : 0.0, guide.a, 0.0, 1.0);
    } else {
        fragColor = mix(history, current, 1.0 / frames);
    }
}
