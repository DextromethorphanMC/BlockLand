#version 150

// Bullet impact sparks (client/impact/ImpactRenderer, rendering spec §12). Quads built on the CPU, stretched along each
// spark's motion over about a tick:
//   Position  corner, camera-relative world
//   UV0       the corner in the quad's own frame, -1..1 (x along the streak, y across)
//   Color     rgb colour (display), a intensity 0..1 (×2 in the fragment shader: the hot core burns out to white)
//   UV2       unused (fullbright)

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

out vec2 corner;
out vec4 sparkColor;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    corner = UV0;
    sparkColor = Color;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
