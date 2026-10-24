#version 150

// Wall graffiti light (see graffiti_glow.vsh). Additive; the alpha written lowers the target's alpha, which BlockLand's
// post chain reads as emission (rendering spec §3), so what glows here blooms (at most 0.3 per layer, like sparks).
//   mode 0  a neon tube's light: the core line burns near white, its glow in the tube's colour; it flickers
//   mode 1  a hologram: the picture in the projector's colour, scan lines rolling up, a flicker, bands that glitch sideways
//   mode 2  a point of light (particle art): a soft round core and halo
//   mode 3  the projector's ring on the floor under a hologram, turning

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:graffiti_fx.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord;
in vec4 glowColor;
in vec3 position;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

void main() {
    float t = graffiti_time();
    int mode = graffiti_mode();
    vec3 color;
    if (mode == 0) {
        vec4 texel = texture(Sampler0, texCoord);
        float core = texel.r, glow = texel.g;
        float on = graffiti_neonFlicker(t, graffiti_flicker());
        color = (graffiti_color() * (glow * 0.85 + core * 1.3) + vec3(1.0) * core * core * 0.55) * on;
        color *= graffiti_built(texCoord);
    } else if (mode == 1) {
        // glitch: now and then a band of the picture slips sideways
        float band = floor(texCoord.y * 18.0);
        float slip = step(0.93, graffiti_noise(vec2(band, floor(t * 9.0)))) * (graffiti_hash(vec2(band, t)) - 0.5) * 0.06;
        vec4 texel = texture(Sampler0, texCoord + vec2(slip, 0.0));
        float scan = 0.62 + 0.38 * sin((texCoord.y * 140.0 + t * 9.0) * 3.14159);
        float roll = 0.85 + 0.15 * smoothstep(0.0, 0.08, abs(fract(texCoord.y * 0.6 - t * 0.35) - 0.5));
        float flicker = 0.88 + 0.12 * graffiti_noise(vec2(t * 13.0, 3.0));
        vec3 tint = mix(graffiti_color(), texel.rgb, 0.35);
        color = tint * texel.a * scan * roll * flicker * 0.9;
        // the projector's beam: brighter towards the bottom edge
        color += graffiti_color() * texel.a * smoothstep(0.65, 1.0, texCoord.y) * 0.25;
    } else if (mode == 2) {
        float r2 = dot(texCoord, texCoord);
        float core = exp(-r2 * 14.0), halo = exp(-r2 * 3.0) * 0.35;
        color = (glowColor.rgb * (halo + core) + vec3(1.0) * core * 0.35) * 1.4;
    } else {
        float r = length(texCoord);
        float ring = exp(-pow((r - 0.82) * 14.0, 2.0)) + exp(-pow((r - 0.55) * 22.0, 2.0)) * 0.5;
        float angle = atan(texCoord.y, texCoord.x);
        float dashes = 0.55 + 0.45 * step(0.5, fract(angle * 3.0 / 3.14159 + t * 0.4));
        color = graffiti_color() * (ring * dashes + exp(-r * r * 6.0) * 0.25) * step(r, 1.0);
    }
    color *= glowColor.a * graffiti_fade();
    float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd) * FogColor.a;
    color *= 1.0 - fog;
    if (max(color.r, max(color.g, color.b)) < 0.002) {
        discard;
    }
    float emission = clamp(bl_luminance(color) * 0.8, 0.0, 1.0);
    fragColor = vec4(color, bl_encodeEmission() ? 0.3 * emission : 0.0);
}
