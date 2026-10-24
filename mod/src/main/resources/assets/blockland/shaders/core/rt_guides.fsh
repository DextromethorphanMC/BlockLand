#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS Ray Reconstruction guides (render size, rows flipped for the Vulkan side, upscale/DlssUpscaler):
//   Params[0].x = 0: diffuse albedo (rgb, linear); 1: normal (world) with linear roughness 1 in alpha (Minecraft's
//   surfaces are matte); 2: specular albedo (a rough dielectric's 0.04)

uniform sampler2D InSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 value = texelFetch(InSampler, ivec2(texel.x, size.y - 1 - texel.y), 0);
    int which = int(Params[0].x + 0.5);
    if (which == 0) fragColor = vec4(value.rgb, 1.0);
    else if (which == 1) fragColor = vec4(value.a > 0.0 ? value.xyz : vec3(0.0, 0.0, 1.0), 1.0);
    else fragColor = vec4(0.04, 0.04, 0.04, 1.0);
}
