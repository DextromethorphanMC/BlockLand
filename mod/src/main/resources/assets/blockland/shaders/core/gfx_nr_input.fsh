#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS 5 neural rendering input: the upscaled linear-HDR picture with its exposure, mapped into SDR (gfx_sdr), rows flipped
// for the Vulkan side (upscale/NeuralRenderer).
//   Params[0].x = exposure multiplier

uniform sampler2D InSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec3 hdr = texelFetch(InSampler, ivec2(texel.x, size.y - 1 - texel.y), 0).rgb;
    fragColor = vec4(gfx_sdr(max(hdr, vec3(0.0)) * Params[0].x), 1.0);
}
