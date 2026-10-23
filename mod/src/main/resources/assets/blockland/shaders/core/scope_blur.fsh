#version 150

// BlockLand scope blur: one direction of a separable Gaussian (binomial, sigma 2 texels), 17 taps folded into 9 bilinear fetches.

layout(std140) uniform BlurInfo {
    vec4 Dir;     // xy: one texel along the blur direction (uv)
    vec4 Window;  // unused here
    vec4 Amount;  // unused here
};

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    // linear-sampling offsets and weights of a 17-tap Gaussian folded into 9 fetches
    const float offsets[5] = float[5](0.0, 1.4117647, 3.2941176, 5.1764706, 7.0588235);
    const float weights[5] = float[5](0.1964825, 0.2969069, 0.0944703, 0.0103813, 0.0003799);
    vec3 sum = texture(InSampler, texCoord).rgb * weights[0];
    for (int i = 1; i < 5; i++) {
        vec2 delta = Dir.xy * offsets[i];
        sum += (texture(InSampler, texCoord + delta).rgb + texture(InSampler, texCoord - delta).rgb) * weights[i];
    }
    fragColor = vec4(sum, 1.0);
}
