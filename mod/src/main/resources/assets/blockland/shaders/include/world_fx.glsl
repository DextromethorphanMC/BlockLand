#version 150

// Shared by BlockLand's screen-space world effects (client/graphics/shadow/WorldFx): the FxInfo block and depth helpers.

layout(std140) uniform FxInfo {
    vec4 FxProj;     // the level projection's P00, P11, P22, P32 (view-space z from depth, x and y scale)
    vec4 FxSize;     // xy: world depth size in pixels, zw: this pass's output size in pixels
    vec4 FxAo;       // x: radius (blocks), y: intensity, z: frame index, w: largest radius on screen (uv)
    vec4 FxBlur;     // xy: one input texel along the blur direction (uv), z: depth sharpness, w: low / full resolution
};

uniform sampler2D DepthSampler;

// Depth at a uv, read without filtering (the half-resolution passes and the upsample all pick the same texel).
float fx_depth(vec2 uv) {
    ivec2 size = ivec2(FxSize.xy);
    ivec2 p = clamp(ivec2(uv * FxSize.xy), ivec2(0), size - 1);
    return texelFetch(DepthSampler, p, 0).r;
}

// View-space z (negative in front of the camera) of a depth value.
float fx_viewZ(float depth) {
    return -FxProj.w / ((depth * 2.0 - 1.0) + FxProj.z);
}

vec3 fx_viewPosition(vec2 uv, float depth) {
    float z = fx_viewZ(depth);
    vec2 ndc = uv * 2.0 - 1.0;
    return vec3(ndc.x * -z / FxProj.x, ndc.y * -z / FxProj.y, z);
}

// Distance from the camera of a point of the far terrain's depth (rendering spec §22): the same projection as the level's
// with other near and far planes.
float fx_farDistance(vec2 uv, float depth, float near, float far) {
    float z = 2.0 * far * near / ((far + near) - (depth * 2.0 - 1.0) * (far - near));
    vec2 ndc = uv * 2.0 - 1.0;
    return length(vec3(ndc.x * z / FxProj.x, ndc.y * z / FxProj.y, z));
}

// Interleaved gradient noise, shifted every frame (temporal anti-aliasing averages the pattern away).
float fx_noise(vec2 pixel, float frame) {
    pixel += frame * vec2(5.588238, 5.588238);
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}
