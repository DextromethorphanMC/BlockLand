#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS Frame Generation: the HUD alone, premultiplied, rows flipped, so that frame = UI + (1 - UI alpha) * HUD-less.
// The HUD's coverage is the window target's alpha (cleared to 0 before the HUD, accumulated by its blending); its colour
// is what the HUD added over the picture taken right before it.

uniform sampler2D FrameSampler;
uniform sampler2D SceneSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(FrameSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 source = ivec2(texel.x, size.y - 1 - texel.y);
    vec4 frame = texelFetch(FrameSampler, source, 0);
    float alpha = frame.a > 0.002 ? frame.a : 0.0;
    vec3 scene = texelFetch(SceneSampler, source, 0).rgb;
    fragColor = alpha > 0.0 ? vec4(clamp(frame.rgb - (1.0 - alpha) * scene, 0.0, 1.0), alpha) : vec4(0.0);
}
