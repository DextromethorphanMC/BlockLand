#version 150

// Bullet impact dust clouds (client/impact/ImpactRenderer, rendering spec §12). A hit's cloud is drawn as a few
// overlapping lobes; each quad is one lobe (a sphere) built on the CPU, camera-facing around its centre:
//   Position  corner, camera-relative world
//   UV0       the corner's offset from the lobe's centre in blocks (±r along camera right / up)
//   Color     rgb powder colour (display), a = index of the lobe's cloud in ImpactClouds (0..127, as a byte)
//   UV2       vanilla lightmap coordinates at the cloud
// The cloud itself (centre, radius, density, seed, age) is read from the ImpactClouds block in the fragment shader.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:impact_fx.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2;

out vec3 position;
flat out vec3 lobeCenter;
flat out float lobeRadius;
flat out vec3 albedo;
flat out int cloud;
flat out vec3 ambient;
flat out vec3 blockLight;
flat out float skyLight;
flat out float sunShadow;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

// The sun's shadow over the lobe's volume, not at one point: five samples spread across the sphere and pushed towards
// the sun (out of the surface the dust came off, which a single lookup at the centre would hit — the whole lobe then
// flipped between lit and shadowed from frame to frame as it drifted over the shadow map's texels). Averaged, the
// shadow changes smoothly as the cloud moves and edges of shade fall softly across it.
float lobeShadow(vec3 centre, float radius) {
    vec3 l = normalize(BlSunDir.xyz);
    vec3 side = normalize(abs(l.y) < 0.95 ? cross(l, vec3(0.0, 1.0, 0.0)) : cross(l, vec3(1.0, 0.0, 0.0)));
    vec3 other = cross(l, side);
    vec3 base = centre + l * (radius * 0.5 + 0.3);
    float spread = radius * 0.55;
    float lit = bl_shadow(base, l, vec2(0.0));
    lit += bl_shadow(base + side * spread, l, vec2(0.0));
    lit += bl_shadow(base - side * spread, l, vec2(0.0));
    lit += bl_shadow(base + other * spread, l, vec2(0.0));
    lit += bl_shadow(base - other * spread, l, vec2(0.0));
    return lit * 0.2;
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    position = Position;
    // the quad is a little larger than the sphere (its silhouette grows close up): the radius rides in the corner
    lobeRadius = max(length(UV0) * 0.70710678 / 1.08, 1e-3);
    lobeCenter = Position - impact_right() * UV0.x - impact_up() * UV0.y;
    albedo = Color.rgb;
    cloud = int(Color.a * 255.0 + 0.5);
    ambient = texelFetch(Sampler2, UV2 / 16, 0).rgb;
    // the lightmap without its sky part: the fragment shader shades only the sky part by the sun, like the world does
    blockLight = texelFetch(Sampler2, ivec2(UV2.x / 16, 0), 0).rgb;
    skyLight = clamp(float(UV2.y) / 240.0, 0.0, 1.0);
    sunShadow = bl_worldLit() ? lobeShadow(lobeCenter, lobeRadius) : 1.0;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
