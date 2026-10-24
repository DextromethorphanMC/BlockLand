#version 150

// Wall graffiti light (client/graffiti/GraffitiRenderer, rendering spec §13): additive quads built on the CPU in
// vanilla's particle layout:
//   Position  corner, camera-relative world
//   UV0       texture coordinates (neon, hologram) or the corner in the quad's own frame, -1..1 (points, ring)
//   Color     rgb colour (display), a intensity 0..1
//   UV2       unused

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

out vec2 texCoord;
out vec4 glowColor;
out vec3 position;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    glowColor = Color;
    position = Position;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
