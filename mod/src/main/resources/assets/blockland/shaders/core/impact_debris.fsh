#version 150

// Bullet impact debris (see impact_debris.vsh). Opaque, alpha-tested on the sprite: the block's own pixels, tinted. The
// facet's normal comes from the screen-space derivatives of the position (each quad is one flat face), turned towards
// the camera, so a tumbling chip flashes as it catches the sun. Lit like the world: the lightmap at the piece and, with
// world lighting, the sun with its shadow and BlockLand's dynamic lights. Near the end of its life a piece dissolves
// pixel by pixel (a stable per-texel pattern, so TAA keeps it calm) instead of popping out. Writes no emission.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:impact_fx.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord;
in vec4 tint;
in vec3 position;
in vec3 ambient;
in float sunShadow;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord);
    if (texel.a < 0.1) {
        discard;
    }
    // dissolve: the texel's own threshold against the life left
    if (impact_hash(vec3(floor(texCoord * 4096.0), 7.0)) > tint.a * 1.02) {
        discard;
    }
    vec3 albedo = texel.rgb * tint.rgb;
    vec3 normal = normalize(cross(dFdx(position), dFdy(position)));
    if (dot(normal, position) > 0.0) {
        normal = -normal;
    }
    vec3 light = ambient;
    if (bl_worldLit()) {
        float ndl = max(dot(normal, BlSunDir.xyz), 0.0);
        vec3 sun = bl_display(BlSunColor.rgb) * sunShadow * ndl;
        light = ambient * 0.62 + sun * 0.7 + bl_display(bl_pointLights(position, normal)) * 0.8;
    } else {
        // vanilla-like: a little shading by the facet so the chips have a body
        light *= mix(0.7, 1.0, abs(normal.y));
    }
    vec3 color = albedo * light;
    color = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor).rgb;
    fragColor = vec4(color, bl_encodeEmission() ? bl_emissionAlpha(0.0) : 1.0);
}
