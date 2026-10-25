#version 150

// Blood lying on things (client/impact/BloodStains, rendering spec §19): quads built on the CPU, cut to the faces they lie
// on, the shape, its edges and its relief made in impact_blood.fsh. A stain on something the wind moves — a leaf block,
// a blade of grass, a flower — moves exactly as the terrain shader moves that plant there (blockland:bl_wind.glsl,
// bits from client/render/PlantWind): the whole stain with the plant at its anchor.
//   UV0     position in the stain's own frame: a splat's (+x the way the drop struck, its rim near 1, to 1.35), a run's
//           (x across, y 0..1 down to its tip), a pool's (like a splat's, round)
//   UV2     lightmap coordinates
//   Stain   x, y = distance (blocks) to the nearest real edge of the surface along its two axes (≥ 8: none), linear over
//           the quad; z seed; w wetness (1 fresh … 0 dry)
//   Shape   x = kind × 16 + face × 2 + (1 on a run), y = wind bits (0 still), z = radius (blocks), w = gloss
//   Anchor  xyz = the point the wind moves the stain with (camera-relative)
//   Atlas   block atlas uv of the plant under the stain (its texture's alpha cuts the stain to the blades); x < 0 = none
//   Color   rgb = the blood's colour (darkened as it dried), a = coverage

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_wind.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec4 Stain;
in vec4 Shape;
in vec4 Anchor;
in vec2 Atlas;

uniform sampler2D Sampler2;

out vec3 position;
out vec4 tint;
out vec2 uv;
out vec4 stain;
out vec4 shape;
out vec2 atlas;
out vec3 ambient;
out float skyLight;
out float sunShadow;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

const int KIND_PLANT = 2;

void main() {
    vec3 pos = Position;
    int wind = int(Shape.y + 0.5);
    if (wind != 0 && bl_worldLit() && BlWind.w > 0.0) {
        int kind = int(Shape.x + 0.5) / 16;
        vec3 anchor = Anchor.xyz;
        vec3 world = anchor + vec3(BlCamera.x, bl_windCameraY(), BlCamera.z);
        // section-local: only its place in its block matters to the wind (the plant's stem, the height in it)
        vec3 local = world - floor(world / 16.0) * 16.0;
        vec3 normal = vec3(0.70710678, 0.0, 0.70710678);
        if (kind == KIND_PLANT) {
            // as the top corner of a cross quad at the anchor's height (at least a little over the plant's foot), the face
            // set back half its width along its tangent, so the stem bl_wind finds is the anchor's column
            local.y = floor(local.y) + max(local.y - floor(local.y), 0.12);
            local.xz += normalize(vec2(-normal.z, normal.x)) * 0.70710678;
        }
        pos += bl_wind(anchor, local, wind, 0, normal) - anchor;
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    position = pos;
    tint = Color;
    uv = UV0;
    stain = Stain;
    shape = Shape;
    atlas = Atlas;
    ambient = texelFetch(Sampler2, UV2 / 16, 0).rgb;
    skyLight = clamp(float(UV2.y) / 240.0, 0.0, 1.0);
    // looked up a little towards the sun: what lies on a surface must not sample inside it
    sunShadow = bl_worldLit() ? bl_shadow(pos + normalize(BlSunDir.xyz) * 0.12, normalize(BlSunDir.xyz), vec2(0.0)) : 1.0;
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
}
