#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand bloom, downsampling (client/graphics/post/Bloom): 13 taps (Jimenez, "Next generation post processing in
// Call of Duty"), half the size of the source. The first level also weights its five boxes by 1 / (1 + luma) against
// fireflies and keeps only the light whose luminance is above a soft threshold, so Minecraft's own whites and its blue
// sky do not glow, emission and the sun do.
//   Params[0] = (source texel size xy, first level (0/1), threshold)
//   Params[1] = (soft knee, -, -, -)

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

vec3 tap(vec2 offset) {
    return texture(InSampler, texCoord + offset * Params[0].xy).rgb;
}

float karis(vec3 c) {
    return 1.0 / (1.0 + gfx_luminance(c));
}

void main() {
    vec3 a = tap(vec2(-2.0, 2.0));
    vec3 b = tap(vec2(0.0, 2.0));
    vec3 c = tap(vec2(2.0, 2.0));
    vec3 d = tap(vec2(-2.0, 0.0));
    vec3 e = tap(vec2(0.0, 0.0));
    vec3 f = tap(vec2(2.0, 0.0));
    vec3 g = tap(vec2(-2.0, -2.0));
    vec3 h = tap(vec2(0.0, -2.0));
    vec3 i = tap(vec2(2.0, -2.0));
    vec3 j = tap(vec2(-1.0, 1.0));
    vec3 k = tap(vec2(1.0, 1.0));
    vec3 l = tap(vec2(-1.0, -1.0));
    vec3 m = tap(vec2(1.0, -1.0));
    vec3 color;
    if (Params[0].z > 0.5) {
        vec3 box0 = (j + k + l + m) * 0.25;
        vec3 box1 = (a + b + d + e) * 0.25;
        vec3 box2 = (b + c + e + f) * 0.25;
        vec3 box3 = (d + e + g + h) * 0.25;
        vec3 box4 = (e + f + h + i) * 0.25;
        float w0 = karis(box0) * 0.5;
        float w1 = karis(box1) * 0.125;
        float w2 = karis(box2) * 0.125;
        float w3 = karis(box3) * 0.125;
        float w4 = karis(box4) * 0.125;
        color = (box0 * w0 + box1 * w1 + box2 * w2 + box3 * w3 + box4 * w4) / (w0 + w1 + w2 + w3 + w4);
        float threshold = Params[0].w;
        float knee = max(Params[1].x, 1e-4);
        float brightness = gfx_luminance(color);
        float soft = clamp(brightness - threshold + knee, 0.0, 2.0 * knee);
        soft = soft * soft / (4.0 * knee);
        color *= max(soft, brightness - threshold) / max(brightness, 1e-4);
    } else {
        color = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
    }
    fragColor = vec4(max(color, vec3(0.0)), 1.0);
}
