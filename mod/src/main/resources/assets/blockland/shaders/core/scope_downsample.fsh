#version 150

// BlockLand scope blur: the world at a quarter of the screen's size, each pixel the mean of the 4x4 it covers
// (fetched texel by texel: the main target is not filtered).

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 base = ivec2(floor(texCoord * vec2(size) / 4.0)) * 4;
    vec3 sum = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            sum += texelFetch(InSampler, clamp(base + ivec2(x, y), ivec2(0), size - 1), 0).rgb;
        }
    }
    fragColor = vec4(sum / 16.0, 1.0);
}
