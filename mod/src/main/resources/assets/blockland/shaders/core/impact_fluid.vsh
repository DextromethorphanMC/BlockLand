#version 150

// Bullet impacts into fluids (client/impact/ImpactRenderer, rendering spec §12, fluids): small meshes and impostors
// built on the CPU in the block vertex layout; the draw's mode (impact_fx.glsl) says what they are:
//   0 crown sheet / column   mesh, Normal = its surface normal, UV0 = (around 0..1, up 0..1)
//   1 water drop             camera-facing quad, UV0 = corner (−1..1), stretched along its motion
//   2 rings on the surface   flat quad, UV0 = position in units of the leading ring's radius, Normal.x = ring width, .y seed
//   3 white water            flat quad, UV0 = position in units of the patch's radius, Normal.y = seed
//   4 molten drop            camera-facing quad, UV0 = corner, Color.a = temperature 0..1
//   5 molten splat / crater  flat quad, UV0 = corner, Color.a = temperature
//   6 blood drop             camera-facing quad, UV0 = corner, stretched along its motion
//   7 blood stain            quad on a block face, UV0 = position in the splat's frame (rim near 1, to 1.35) or along a
//                            run (x across, y 0..1 down to its tip); Normal.x = face * 2 (+1 run) / 16, .y seed, .z wetness
//   8 bubble                 camera-facing quad, UV0 = corner
//   Color rgb = the fluid's colour (water: the biome's), a = coverage (or temperature); UV2 = lightmap coordinates.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:impact_fx.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out vec3 position;
out vec4 tint;
out vec2 uv;
out vec3 normal;
out vec3 ambient;
out float skyLight;
out float sunShadow;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    position = Position;
    tint = Color;
    uv = UV0;
    normal = Normal;
    ambient = texelFetch(Sampler2, UV2 / 16, 0).rgb;
    skyLight = clamp(float(UV2.y) / 240.0, 0.0, 1.0);
    // looked up a little towards the sun: a piece on a surface must not sample inside it (acne flickers frame to frame)
    sunShadow = bl_worldLit() ? bl_shadow(Position + normalize(BlSunDir.xyz) * 0.12, normalize(BlSunDir.xyz), vec2(0.0)) : 1.0;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
