#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS input: the decoded linear-HDR colour at the render size, rows flipped so DLSS sees the picture upright (its
// textures run top to bottom, OpenGL's bottom to top). Drawn into the bridge's shared texture (upscale/DlssUpscaler).

uniform sampler2D InSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    fragColor = vec4(texelFetch(InSampler, ivec2(texel.x, size.y - 1 - texel.y), 0).rgb, 1.0);
}
