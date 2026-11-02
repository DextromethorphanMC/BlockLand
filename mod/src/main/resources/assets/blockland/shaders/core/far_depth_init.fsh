#version 150

// Far terrain drawn after vanilla's opaque terrain (client/graphics/far/FarRenderer, FAR_GPU_CULLING, rendering spec
// §23): the far depth starts out as the level's own depth carried into the far projection, so the far chunks' and tiles'
// fragments behind what vanilla has drawn fail the depth test before they are shaded. The same value goes into the
// colour target (R32F, the marker): a texel the far pass never wrote still holds it, and far_depth.fsh leaves the level's
// depth alone there; the GPU culling's hierarchical depth is built from it (far_hzb.csh). Where the level drew nothing
// (depth 1) the far depth is 1, as a clear would leave it; a level fragment nearer than the far projection's near plane
// lands at 0 (nothing far can be nearer).
//   Matrix = the far terrain's projection x the inverse of the level's (both jittered alike): level NDC -> far clip

#moj_import <blockland:gfx_screen.glsl>

uniform sampler2D SceneDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float scene = texelFetch(SceneDepthSampler, ivec2(gl_FragCoord.xy), 0).r;
    float init = 1.0;
    if (scene < 1.0) {
        vec4 clip = Matrix * vec4(texCoord * 2.0 - 1.0, scene * 2.0 - 1.0, 1.0);
        init = clip.w > 0.0 ? clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0) : 0.0;
    }
    gl_FragDepth = init;
    fragColor = vec4(init, 0.0, 0.0, 1.0);
}
