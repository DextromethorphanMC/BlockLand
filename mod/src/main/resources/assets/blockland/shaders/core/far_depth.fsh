#version 150

// Far terrain (client/graphics/far/FarRenderer, tools/assets/RENDERING_SPEC.md §22): its depth carried into the level's
// own depth buffer, wherever it lies within the level projection's far plane, so what vanilla draws later — clouds, rain,
// particles, translucent layers — is hidden behind far terrain that stands in front of it, and the post chain sees it
// as the world. Beyond the far plane the level's depth stays 1 (all of vanilla's world is nearer there).
//   Matrix = the level's projection × the inverse of the far terrain's (both jittered alike): far NDC → level clip

#moj_import <blockland:gfx_screen.glsl>

uniform sampler2D FarDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texelFetch(FarDepthSampler, ivec2(gl_FragCoord.xy), 0).r;
    if (depth >= 1.0) {
        discard;
    }
    vec4 clip = Matrix * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float z = clip.z / clip.w;
    if (clip.w <= 0.0 || z >= 0.999999) {
        discard;
    }
    gl_FragDepth = z * 0.5 + 0.5;
    fragColor = vec4(0.0);
}
