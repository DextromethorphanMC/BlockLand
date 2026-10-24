#version 150

// Wall graffiti paint (see graffiti_paint.vsh). Paint is lit like the wall under it: the face's shade and the lightmap,
// and with world lighting the sun (its shadow, how squarely it falls on the face) and BlockLand's dynamic lights, so a
// piece never glows on a dark wall nor looks flat in the sun. It builds up while being sprayed (graffiti_built) and
// thins out as it goes. Premultiplied alpha over the world; the target's alpha (emission, rendering spec §3) is left
// alone. Mode 1: the glass of a neon tube (graffiti_glow adds its light).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:graffiti_fx.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord;
in vec4 shade;
in vec3 position;
in vec3 faceNormal;
in vec3 ambient;
in float sunShadow;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord);
    vec3 albedo = texel.rgb;
    float alpha = texel.a;
    if (graffiti_mode() == 1) {
        // the tube's glass: pale and a little of its colour, only where the tube runs
        alpha = texel.r * 0.85;
        albedo = mix(vec3(0.55, 0.58, 0.62), graffiti_color(), 0.35);
    }
    alpha *= graffiti_built(texCoord) * graffiti_fade();
    if (alpha < 0.004) {
        discard;
    }
    vec3 normal = normalize(faceNormal);
    vec3 light = ambient * shade.rgb;
    if (bl_worldLit()) {
        float ndl = max(dot(normal, BlSunDir.xyz), 0.0);
        vec3 sun = bl_display(BlSunColor.rgb) * sunShadow * ndl;
        light = ambient * shade.rgb * 0.62 + sun * 0.7 + bl_display(bl_pointLights(position, normal)) * 0.8;
    }
    // a little sheen of fresh paint where the light falls
    vec3 color = albedo * light * (1.0 + 0.04 * graffiti_noise(texCoord * 400.0));
    color = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor).rgb;
    fragColor = vec4(color * alpha, alpha);
}
