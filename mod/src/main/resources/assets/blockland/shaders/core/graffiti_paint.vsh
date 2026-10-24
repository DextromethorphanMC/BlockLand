#version 150

// Wall graffiti paint (client/graffiti/GraffitiRenderer, rendering spec §13): a piece lying on block faces, clipped to
// each face it covers. Polygons built on the CPU in vanilla's block layout:
//   Position  corner, camera-relative world (already lifted a hair off the face)
//   Color     rgb the face's vanilla shade (as terrain bakes it), a 1
//   UV0       texture coordinates (the current frame of a strip)
//   UV2       vanilla lightmap coordinates in front of the face
//   Normal    the face's outward normal

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out vec2 texCoord;
out vec4 shade;
out vec3 position;
out vec3 faceNormal;
out vec3 ambient;
out float sunShadow;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    shade = Color;
    position = Position;
    faceNormal = Normal;
    ambient = texelFetch(Sampler2, UV2 / 16, 0).rgb;
    sunShadow = bl_worldLit() ? bl_shadow(Position, Normal, vec2(0.0)) : 1.0;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
