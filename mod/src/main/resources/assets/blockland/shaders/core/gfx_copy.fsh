#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand frame copy (client/graphics/frame/FrameHooks): the scene stretched onto the window's target as it is, used
// when the post chain cannot run this frame, so the picture is never left behind in the scene target.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0);
}
