#version 150

// BlockLand adrenaline rush (client/equipment/effect/AdrenalineRush): the edges of the view stream outward — a radial
// zoom blur with the colours splitting apart, speed lines rushing out from the middle — and the vignette closes in red
// on every heartbeat. The middle of the screen stays sharp, so aiming is untouched.

layout(std140) uniform EffectInfo {
    vec4 Amount;  // x: intensity 0..1, y: heartbeat pulse 0..1, z: aspect, w: time (s)
    vec4 Extra;   // x: speed lines 0..1 (how fast the player moves), y: onset kick 0..1
};

uniform sampler2D InSampler;  // the frame

in vec2 texCoord;

out vec4 fragColor;

float hash(float n) {
    return fract(sin(n * 127.1) * 43758.5453);
}

void main() {
    float intensity = Amount.x, pulse = Amount.y, aspect = Amount.z, time = Amount.w;
    float lines = Extra.x, kick = Extra.y;
    vec2 d = texCoord - 0.5;
    vec2 p = vec2(d.x * aspect, d.y);
    float r = length(p) / length(vec2(0.5 * aspect, 0.5));        // 0 middle .. 1 corner
    float edge = smoothstep(0.22, 1.0, r) * intensity;

    // radial zoom blur towards the middle, red and blue drawn apart
    float spread = 0.055 * edge + 0.05 * kick * smoothstep(0.1, 1.0, r);
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < 10; i++) {
        float t = float(i) / 9.0;
        float s = 1.0 - spread * t;
        float w = 1.0 - t * 0.6;
        float ca = 0.012 * edge + 0.01 * kick;
        sum.r += texture(InSampler, 0.5 + d * (s + ca)).r * w;
        sum.g += texture(InSampler, 0.5 + d * s).g * w;
        sum.b += texture(InSampler, 0.5 + d * (s - ca)).b * w;
        weight += w;
    }
    vec3 color = sum / weight;

    // speed lines: thin streaks in angular cells, each rushing outward at its own pace
    float a = atan(p.y, p.x) / 6.2831853 + 0.5;
    float cells = 140.0;
    float cell = floor(a * cells);
    float h = hash(cell), h2 = hash(cell + 31.7);
    float across = abs(fract(a * cells) - 0.5) * 2.0;
    float thin = smoothstep(0.35, 0.0, across);
    float run = fract(r * (1.2 + h2) - time * (1.6 + h * 2.4) + h * 7.0);
    float streak = smoothstep(0.0, 0.08, run) * smoothstep(0.55, 0.15, run);
    float on = step(0.62, h) * smoothstep(0.38, 0.8, r);
    color += vec3(1.0, 0.96, 0.92) * thin * streak * on * lines * intensity * 0.42;

    // vignette closing in on each beat, tinting the rim red
    float v = smoothstep(0.48, 1.18, r - pulse * 0.1) * (0.45 + 0.55 * pulse) * intensity;
    color = mix(color, color * vec3(1.1, 0.42, 0.38), clamp(v * 1.1, 0.0, 1.0));
    color *= 1.0 - v * 0.55;
    // a touch more contrast in the middle: everything sharpens up
    color = mix(color, (color - 0.5) * 1.08 + 0.5, intensity * (1.0 - r) * 0.5);
    fragColor = vec4(color, 1.0);
}
