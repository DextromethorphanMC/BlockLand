#version 150

// Far terrain (client/graphics/far, tools/assets/RENDERING_SPEC.md §22): block columns of a far tile, 8 bytes a vertex.
//   Packed.x  x (bits 0-6) and z (7-13) in cells of the tile, y + 32768 (14-29) in blocks, 30 the foot of a wall
//   Packed.y  RGB (0-23, display colours: the tint with a sprite, else the average), face (24-26: up, north, south, west,
//             east), water (27), block light (28-31)
//   Packed.z  the face's sprite in the block atlas: x / 16 (0-11), y / 16 (12-23), log2(size / 16) (24-27), 28 = has one
// Per draw (DynamicTransforms): ModelViewMat = the view's rotation, ModelOffset = the tile's corner relative to the
// camera, TextureMat[0][0] = blocks per cell. Lit like the lighting pack's terrain with sky light 15.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:far_view.glsl>

in uvec3 Packed;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec3 worldPos;
flat out vec3 faceNormal;
flat out vec3 albedo;
flat out vec3 fullLight;
flat out vec3 blockLight;
flat out int water;
flat out uint sprite;

const vec3 NORMALS[5] = vec3[5](vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0), vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0));
// vanilla's face shading of blocks (up, north / south, west / east)
const float SHADES[5] = float[5](1.0, 0.8, 0.8, 0.6, 0.6);

vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

void main() {
    uint p = Packed.x;
    uint look = Packed.y;
    float cell = TextureMat[0][0];
    vec3 local = vec3(float(p & 127u) * cell, float((p >> 14) & 65535u) - 32768.0, float((p >> 7) & 127u) * cell);
    int face = min(int((look >> 24) & 7u), 4);
    water = int((look >> 27) & 1u);
    sprite = Packed.z;
    int emission = int(look >> 28);
    vec3 pos = local + ModelOffset;
    if (water != 0) {
        // a water source's surface lies 1/9 below its block's top
        pos.y -= 0.11;
    }
    // a hair farther than it is (same pixel): where a far tile and a real far chunk meet in the same surface the real one wins
    vec4 view = ModelViewMat * vec4(pos, 1.0);
    view.xyz *= 1.001;
    gl_Position = FarProjection * view;

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    worldPos = pos;
    faceNormal = NORMALS[face];
    albedo = vec3(float(look & 255u), float((look >> 8) & 255u), float((look >> 16) & 255u)) / 255.0;
    // walls darken towards their foot (light gathers less where a wall meets the ground), like vanilla's smooth lighting
    float shade = SHADES[face] * (((p >> 30) & 1u) != 0u ? 0.72 : 1.0);
    // in the open: sky light 15, the block's own light where it glows (lava, glowstone)
    fullLight = shade * minecraft_sample_lightmap(Sampler2, ivec2(emission * 16, 240)).rgb;
    blockLight = shade * minecraft_sample_lightmap(Sampler2, ivec2(emission * 16, 0)).rgb;
}
