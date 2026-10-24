#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS Frame Generation: the frame before the HUD, rows flipped. Where the HUD covers nothing (alpha 0 in the window's
// target) the finished frame itself, so effects drawn without coverage (the vanilla vignette) stay part of the scene;
// under the HUD the picture taken right before it was drawn.

uniform sampler2D FrameSampler;
uniform sampler2D SceneSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(FrameSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 source = ivec2(texel.x, size.y - 1 - texel.y);
    vec4 frame = texelFetch(FrameSampler, source, 0);
    vec3 scene = frame.a > 0.002 ? texelFetch(SceneSampler, source, 0).rgb : frame.rgb;
    fragColor = vec4(scene, 1.0);
}
