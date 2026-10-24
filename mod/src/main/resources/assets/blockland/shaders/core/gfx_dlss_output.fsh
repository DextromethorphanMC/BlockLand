#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS output back into the post chain: rows flipped back to OpenGL's order, and the hand / viewmodel mask (alpha 1)
// restored from the render-size colour, so the passes after it (motion blur) still leave the weapon alone.

uniform sampler2D InSampler;
uniform sampler2D HandSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec3 color = texelFetch(InSampler, ivec2(texel.x, size.y - 1 - texel.y), 0).rgb;
    float hand = texture(HandSampler, texCoord).a > 0.5 ? 1.0 : 0.0;
    fragColor = vec4(max(color, vec3(0.0)), hand);
}
