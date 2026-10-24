#version 150

#moj_import <blockland:gfx_screen.glsl>

// Temporal accumulation of the volumetric clouds (cloud resolution): last frame's clouds reprojected by the camera's
// turn (the clouds are far: rotation only), clamped to the colours around the pixel this frame (no ghosts where the
// world moved in front), blended with this frame's march.
//   Matrix    = current NDC (far plane) -> previous clip
//   Params[0] = (history valid, weight of this frame, -, -)

uniform sampler2D CurrentSampler;
uniform sampler2D HistorySampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(CurrentSampler, 0);
    vec4 current = texelFetch(CurrentSampler, p, 0);
    if (Params[0].x < 0.5) {
        fragColor = current;
        return;
    }
    vec4 lo = current, hi = current;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec4 v = texelFetch(CurrentSampler, clamp(p + ivec2(x, y), ivec2(0), size - 1), 0);
            lo = min(lo, v);
            hi = max(hi, v);
        }
    }
    vec4 previous = Matrix * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec2 uv = previous.xy / previous.w * 0.5 + 0.5;
    if (previous.w <= 0.0 || any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        fragColor = current;
        return;
    }
    vec4 history = clamp(texture(HistorySampler, uv), lo, hi);
    fragColor = mix(history, current, Params[0].y);
}
