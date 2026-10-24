#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand auto exposure (client/graphics/post/Exposure): mean log2 luminance. Mode 0 reads the HDR frame into a small
// square (four bilinear taps per texel); mode 1 averages 4x4 texels of the previous level (four bilinear taps on texel
// corners); mode 2 eases the adapted value towards the measured one (red of the result, log2 luminance).
//   Params[0] = (source texel size xy, mode, adaptation factor 0..1 for this frame (1 = jump))

uniform sampler2D InSampler;
uniform sampler2D PreviousSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 t = Params[0].xy;
    float mode = Params[0].z;
    if (mode < 0.5) {
        // a quarter of this texel's footprint each way; the frame's centre counts a little more
        float sum = 0.0;
        sum += log2(gfx_luminance(texture(InSampler, texCoord + vec2(-0.25, -0.25) * t).rgb) + 1e-4);
        sum += log2(gfx_luminance(texture(InSampler, texCoord + vec2(0.25, -0.25) * t).rgb) + 1e-4);
        sum += log2(gfx_luminance(texture(InSampler, texCoord + vec2(-0.25, 0.25) * t).rgb) + 1e-4);
        sum += log2(gfx_luminance(texture(InSampler, texCoord + vec2(0.25, 0.25) * t).rgb) + 1e-4);
        fragColor = vec4(sum * 0.25, 0.0, 0.0, 1.0);
    } else if (mode < 1.5) {
        float sum = texture(InSampler, texCoord + vec2(-1.0, -1.0) * t).r + texture(InSampler, texCoord + vec2(1.0, -1.0) * t).r
                + texture(InSampler, texCoord + vec2(-1.0, 1.0) * t).r + texture(InSampler, texCoord + vec2(1.0, 1.0) * t).r;
        fragColor = vec4(sum * 0.25, 0.0, 0.0, 1.0);
    } else {
        float measured = texture(InSampler, vec2(0.5)).r;
        float previous = texture(PreviousSampler, vec2(0.5)).r;
        fragColor = vec4(mix(previous, measured, clamp(Params[0].w, 0.0, 1.0)), 0.0, 0.0, 1.0);
    }
}
