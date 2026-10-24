#version 150

// Bomb explosion puffs (see bomb_smoke.vsh). Each puff is shaded as a ball of gas: the quad is the silhouette of a
// sphere, its thickness along the view ray and world-anchored noise give the density, the noise erodes the outline into
// curls. Smoke is lit by the vanilla lightmap at the puff, the sun (with the puff's shadow, light wrapping round the
// ball and darkening on its far side), BlockLand's dynamic lights and the fireball inside it; hot gas glows with the
// colour of its temperature. Premultiplied alpha, so glowing parts add light while dark smoke covers what is behind.
// Where the world's depth is at hand the puff fades out as it meets geometry (no hard cut through floor and walls), and
// it fades in front of the camera.
// GLOW_PASS: the hot puffs again, writing only the emission of the hot gas into the target's alpha (rendering spec §3).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bomb_fx.glsl>

uniform sampler2D DepthSampler;

in vec2 corner;
flat in vec3 center;
flat in float radius;
flat in vec4 params;
flat in vec3 ambient;
flat in float sunShadow;
flat in float centerDepth;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

void main() {
    float heat = params.r, densityScale = params.g, seed = params.b * 64.0, soot = params.a;
    bool ring = bomb_mode() > 0.5;
    vec2 q = corner / radius;
    float d2 = dot(q, q);
    if (d2 >= 1.0) {
        discard;
    }
    // cheap fades first, so pixels that end up invisible never pay for the noise:
    // the puff meets the world softly (by how far the scene lies behind its front)...
    float fade = 1.0;
    if (bomb_softDepth()) {
        float scene = bomb_viewDistance(texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r);
        float front = ring ? centerDepth : centerDepth - radius * sqrt(1.0 - d2);
        fade *= clamp((scene - front) / max(0.35, radius * 0.45), 0.0, 1.0);
    }
    // ...and is gone before the camera is inside it (no flat wall over the screen, no full-screen overdraw)
    if (!ring) {
        fade *= clamp((centerDepth - radius * 0.45) / max(0.5, radius * 0.75), 0.0, 1.0);
    }
    if (fade < 0.003) {
        discard;
    }
    float time = bomb_time();
    vec3 normal;
    float thickness;
    float shape;
    if (ring) {
        // the dust ring racing over the floor: a band that is thick at its front and thins out behind it
        // (the ring's Color: r opacity, g where the band starts 0..1)
        float rho = sqrt(d2);
        float inner = densityScale;
        float opacity = heat;
        heat = 0.0;
        float band = smoothstep(inner, mix(inner, 1.0, 0.65), rho) * (1.0 - smoothstep(0.86, 1.0, rho));
        vec3 local = vec3(q.x, 0.0, q.y) * radius;
        shape = bomb_fbm(local * 0.55 + vec3(seed, time * 0.6, -seed));
        thickness = band * (0.35 + 0.9 * shape) * opacity;
        normal = vec3(0.0, 1.0, 0.0);
        densityScale = 1.0;
    } else {
        float z = sqrt(1.0 - d2);
        normal = normalize(bomb_right() * q.x + bomb_up() * q.y + bomb_back() * z);
        // noise on the ball's surface, in world axes (no swimming when the camera turns), rising and churning slowly
        vec3 p = normal * 1.35 + vec3(seed, seed * 0.37, -seed * 0.71) + vec3(0.0, -time * 0.22, time * 0.05);
        shape = bomb_fbm(p);
        float detail = bomb_noise(p * 3.1 + vec3(time * 0.4));
        // thick in the middle, curling apart at the outline
        thickness = clamp(z * 1.55 - (1.0 - shape) * 1.05 + (detail - 0.5) * 0.25, 0.0, 1.0);
        thickness = thickness * thickness * (3.0 - 2.0 * thickness);
    }
    float alpha = (1.0 - exp(-thickness * densityScale * 3.2)) * fade;
    if (alpha < 0.003) {
        discard;
    }

    // temperature: hotter in the core and where the noise piles the gas up
    float temperature = heat * (0.45 + 0.85 * shape);
    vec3 emission = bomb_blackbody(temperature) * smoothstep(0.02, 0.2, temperature);

#ifdef GLOW_PASS
    // alpha only, overwritten (front-most gas wins, back to front): a multiplying blend would push overlapping puffs
    // below 0.5, which the post chain reads as no emission at all
    float glow = clamp(bl_luminance(emission) * 0.9, 0.0, 1.0) * alpha;
    if (!bl_encodeEmission() || glow < 0.02) {
        discard;
    }
    fragColor = vec4(0.0, 0.0, 0.0, bl_emissionAlpha(glow));
#else
    vec3 pos = center + normal * radius * 0.8;
    vec3 albedo = mix(vec3(0.66, 0.63, 0.59), vec3(0.075, 0.068, 0.062), soot);
    // light through a ball of smoke: the lit side bright, the far side and the thick middle darker
    vec3 lightDir = bl_worldLit() ? BlSunDir.xyz : normalize(vec3(0.3, 1.0, 0.2));
    float wrap = clamp(dot(normal, lightDir) * 0.5 + 0.5, 0.0, 1.0);
    float self = mix(0.38, 1.0, wrap) * mix(0.8, 1.15, shape);
    vec3 light = ambient * mix(0.55, 1.0, self);
    if (bl_worldLit()) {
        vec3 sun = bl_display(BlSunColor.rgb) * sunShadow * self;
        light = ambient * (0.45 + 0.25 * shape) + sun * 0.55 + bl_display(bl_pointLights(pos, normal)) * 0.6;
    }
    // the fireball lights its own smoke from inside / below
    vec3 toFire = ModelOffset - pos;
    float fireDist2 = dot(toFire, toFire);
    float reach = max(ColorModulator.a, 1e-3);
    float fireFalloff = clamp(1.0 - fireDist2 / (reach * reach), 0.0, 1.0);
    float facing = clamp(dot(normal, toFire * inversesqrt(max(fireDist2, 1e-4))) * 0.6 + 0.4, 0.0, 1.0);
    light += bl_display(ColorModulator.rgb) * fireFalloff * fireFalloff * facing;

    vec3 color = albedo * light + emission;
    color = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor).rgb;
    fragColor = vec4(color * alpha, alpha);
#endif
}
