#version 150

// Bomb explosion sparks and glows (client/bomb/fx/BombFxRenderer, rendering spec §10). Quads built on the CPU:
//   Position  corner, camera-relative world (a spark is stretched along its screen-space motion)
//   UV0       the corner in the quad's own frame, -1..1 (x along the streak, y across)
//   Color     rgb colour (display), a intensity 0..1 (×2 in the fragment shader: hot cores burn out to white)
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
