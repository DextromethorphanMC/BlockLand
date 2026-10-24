#version 150

// Bomb explosion puffs (client/bomb/fx/BombFxRenderer, rendering spec §10): the fireball that cools into smoke, the
// dust cloud and the ground ring. Each quad is one puff built on the CPU around its centre:
//   Position  corner, camera-relative world
//   UV0       the corner's offset from the centre along (camera right, camera up) in blocks (±r, ±r); the ring: along
//             world x and z
//   Color     r heat 0..1 (fire), g density 0..1, b seed, a soot 0..1 (white dust .. black smoke)
//   UV2       vanilla lightmap coordinates at the puff (block, sky light)
// Sun shadow and the lightmap are looked up once per puff here, not per pixel.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bomb_fx.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2;

out vec2 corner;
flat out vec3 center;
flat out float radius;
flat out vec4 params;
flat out vec3 ambient;
flat out float sunShadow;
flat out float centerDepth;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    corner = UV0;
    // corners sit at (±r, ±r): every vertex of the quad finds the same centre and radius
    radius = max(length(UV0) * 0.70710678, 1e-3);
    bool ring = bomb_mode() > 0.5;
    center = ring ? Position - vec3(UV0.x, 0.0, UV0.y) : Position - bomb_right() * UV0.x - bomb_up() * UV0.y;
    params = Color;
    ambient = texelFetch(Sampler2, UV2 / 16, 0).rgb;
    sunShadow = bl_worldLit() ? bl_shadow(center, vec3(0.0, 1.0, 0.0), vec2(0.0)) : 1.0;
    centerDepth = -(ModelViewMat * vec4(center, 1.0)).z;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
