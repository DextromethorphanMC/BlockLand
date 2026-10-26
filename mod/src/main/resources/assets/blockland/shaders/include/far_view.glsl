#version 150

// The far terrain's view (client/graphics/far/FarRenderer, tools/assets/RENDERING_SPEC.md §22), one block per view (the
// main view and a scope's picture each have their own).

layout(std140) uniform FarView {
    mat4 FarProjection;  // the level's projection with the far terrain's near and far planes (and the frame's jitter)
    vec4 FarMask;        // xy the camera's chunk modulo w, z 1 = the coverage mask is bound, w its size in chunks
    vec4 FarCamera;      // x, z the camera's position within its chunk (blocks), y its height, w the far distance
    vec4 FarAtlas;       // the block atlas: width, height (texels), its highest mip level, -
    vec4 FarMaskCoarse;  // the coarse coverage mask: xy the camera's chunk modulo z * w, z chunks per texel, w its size
};
