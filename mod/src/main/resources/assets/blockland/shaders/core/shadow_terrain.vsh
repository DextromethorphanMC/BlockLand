#version 150

#ifdef BL_PAGES
#extension GL_ARB_shader_draw_parameters : require
#extension GL_ARB_shader_storage_buffer_object : require
#endif

// BlockLand sun shadow map: terrain sections (client/graphics/shadow). Section origin in ModelOffset (relative to the
// cascade's box centre), ShadowProj = the cascade's tile of the atlas x orthographic light projection x light view
// (box-relative world; both fixed with the cascade's placement, so a kept drawing is the same for any camera).

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform ShadowCascade {
    mat4 ShadowProj;
    vec4 ShadowTile;    // xy: the tile's centre in the atlas (NDC), z: alpha cut-off, w: unused
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

out vec2 texCoord0;

#ifdef BL_PAGES
// TERRAIN_PAGES (client/perf/gl/PagedDraws): the section's origin by draw id from the batch's storage buffer, the very
// floats ModelOffset would hold
layout(std430, binding = 0) readonly buffer BlSectionOffsets {
    vec4 blSectionOffsets[];
};
#define BL_MODEL_OFFSET blSectionOffsets[gl_DrawIDARB].xyz
#else
#define BL_MODEL_OFFSET ModelOffset
#endif

void main() {
    gl_Position = ShadowProj * vec4(Position + BL_MODEL_OFFSET, 1.0);
    texCoord0 = UV0;
}
