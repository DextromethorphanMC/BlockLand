#version 150

// The per-pass block of BlockLand's full-screen passes (client/graphics/gpu/Ubo.pass): one matrix and six vec4 whose
// meaning each pass documents.
layout(std140) uniform GfxPass {
    mat4 Matrix;
    vec4 Params[6];
};

// Display (sRGB-like, gamma 2.2) <-> linear light, matching blockland:scene.glsl.
vec3 gfx_linear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 gfx_display(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2));
}

float gfx_luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Interleaved gradient noise (Jimenez 2014).
float gfx_noise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// DLSS 5 neural rendering works in SDR: identity up to the knee (what the screen shows of an ordinary frame), a soft
// shoulder above it that never reaches 1; gfx_sdr_inverse undoes it exactly.
const float GFX_SDR_KNEE = 0.8;

vec3 gfx_sdr(vec3 c) {
    vec3 over = max(c - GFX_SDR_KNEE, 0.0);
    return min(c, vec3(GFX_SDR_KNEE)) + (1.0 - GFX_SDR_KNEE) * (1.0 - exp(-over / (1.0 - GFX_SDR_KNEE)));
}

vec3 gfx_sdr_inverse(vec3 s) {
    vec3 over = clamp(s - GFX_SDR_KNEE, 0.0, (1.0 - GFX_SDR_KNEE) * 0.9999);
    return min(s, vec3(GFX_SDR_KNEE)) - (1.0 - GFX_SDR_KNEE) * log(1.0 - over / (1.0 - GFX_SDR_KNEE));
}
