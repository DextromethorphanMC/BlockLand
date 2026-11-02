#version 150

#extension GL_ARB_shader_draw_parameters : require
#extension GL_ARB_shader_storage_buffer_object : require

// TERRAIN_PAGES (client/perf/gl/PagedTerrain): the terrain vertex shader (the lighting pack's, so the fragment shader
// it is paired with - minecraft:core/terrain, overridden by the pack or vanilla's own - gets the same inputs either
// way) with the section's origin read by draw id from the batch's storage buffer.
#define BL_PAGES

#moj_import <blockland:terrain_vertex.glsl>
