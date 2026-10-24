#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand bloom, upsampling (client/graphics/post/Bloom): the next smaller level blurred with a 3x3 tent and added to
// this level's downsample, so each level carries every wider glow below it.
//   Params[0] = (texel size of the smaller level xy, -, -)

uniform sampler2D LowSampler;
uniform sampler2D HighSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 t = Params[0].xy;
    vec3 sum = texture(LowSampler, texCoord).rgb * 4.0;
    sum += (texture(LowSampler, texCoord + vec2(-t.x, 0.0)).rgb + texture(LowSampler, texCoord + vec2(t.x, 0.0)).rgb
            + texture(LowSampler, texCoord + vec2(0.0, -t.y)).rgb + texture(LowSampler, texCoord + vec2(0.0, t.y)).rgb) * 2.0;
    sum += texture(LowSampler, texCoord + vec2(-t.x, -t.y)).rgb + texture(LowSampler, texCoord + vec2(t.x, -t.y)).rgb
            + texture(LowSampler, texCoord + vec2(-t.x, t.y)).rgb + texture(LowSampler, texCoord + vec2(t.x, t.y)).rgb;
    fragColor = vec4(texture(HighSampler, texCoord).rgb + sum / 16.0, 1.0);
}
