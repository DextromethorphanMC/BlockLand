#version 150

// Bullet impact debris (client/impact/ImpactRenderer, rendering spec §12): chips, flakes, splinters, shards and grains
// of the struck block, drops of water and blood, bubbles. Quads built on the CPU, already turned by each piece's tumble:
//   Position  corner, camera-relative world
//   UV0       block atlas coordinates (a quarter of the block's own sprite, or a plain sprite for drops)
//   Color     rgb tint (the block's colour, wet darkening, blood), a how much of its life is left (it dissolves)
//   UV2       vanilla lightmap coordinates at the piece
// The sun's shadow is looked up per vertex (the pieces are tiny).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2;

out vec2 texCoord;
out vec4 tint;
out vec3 position;
out vec3 ambient;
out float sunShadow;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    tint = Color;
    position = Position;
    ambient = texelFetch(Sampler2, UV2 / 16, 0).rgb;
    // looked up a little towards the sun: a piece on a surface must not sample inside it (acne flickers frame to frame)
    sunShadow = bl_worldLit() ? bl_shadow(Position + normalize(BlSunDir.xyz) * 0.12, normalize(BlSunDir.xyz), vec2(0.0)) : 1.0;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
