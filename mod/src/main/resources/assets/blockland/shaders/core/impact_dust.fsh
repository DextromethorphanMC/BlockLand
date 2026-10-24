#version 150

// Bullet impact dust clouds (see impact_dust.vsh). Every lobe integrates the same density field — its cloud's mass
// spread over a Gaussian, drawn out along the throw right after the hit and hanging lower as the heavy powder sinks,
// its edge eaten into billows by cloud-anchored noise that bites deeper as it ages (a puff breaks up into wisps before
// it is gone, it does not just fade) — along the view ray's chord through the lobe's sphere,
// clipped where the ray meets the world (the depth copy: dust thins where it touches a wall or the floor instead of
// cutting off). The optical depth of the chord gives the opacity (Beer–Lambert, 1 − e^−τ); lobes composited over one
// another in premultiplied alpha multiply their transmittances, so the overlapping lobes read as one cloud with a dense
// core that thins outward. Lighting follows the world's (bl_skyShade): the lightmap's block part as it is, its sky part
// shaded by how much sun reaches the dust — the lobe's shadow times the light let through the cloud (the closed-form
// optical depth of the Gaussian towards the light, with a multiple-scattering tail: powder is bright, light that gets in
// keeps bouncing) times a mild forward-scattering lobe — so dust in the sun is as bright as the sunlit wall it came off
// and never a dark film over it; the skylight dims under a thick cloud; BlockLand's dynamic lights. Without world
// lighting the lightmap with the same self-shadowing. Writes no emission. A cloud in water (a plume of blood) has no
// substance above the water's surface, and seen from above it is not cut off by the surface itself (the water's depth is
// in the depth copy): the ray goes on through it into the plume, which shows through the water a little dimmed.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>
#moj_import <blockland:impact_fx.glsl>

#define IMPACT_CLOUDS 128

// cloud i: [4i] (centre xyz camera-relative, radius), [4i+1] (lobe density, cloud density, seed, age 0..1),
// [4i+2] (throw direction × stretch along it (1 = round), sag below the centre (0 = round)),
// [4i+3] (water surface height camera-relative, 1 = in water, -, -)
layout(std140) uniform ImpactClouds {
    vec4 Clouds[IMPACT_CLOUDS * 4];
};

uniform sampler2D DepthSampler;

in vec3 position;
flat in vec3 lobeCenter;
flat in float lobeRadius;
flat in vec3 albedo;
flat in int cloud;
flat in vec3 ambient;
flat in vec3 blockLight;
flat in float skyLight;
flat in float sunShadow;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

// The cloud's density at a point (optical depth per block).
float density(vec3 p, vec3 c, float r, float rho, float seed, float age, vec4 shape, vec4 water) {
    // in water: nothing above the surface (a plume stays under it, flattening against it)
    if (water.y > 0.5 && p.y > water.x) {
        return 0.0;
    }
    vec3 q = p - c;
    // the heavy part sinks: below the centre the cloud hangs lower
    q.y /= q.y < 0.0 ? 1.0 + shape.w : 1.0;
    // the jet out of the hole: drawn out along the throw
    float stretch = max(length(shape.xyz), 1.0);
    vec3 axis = shape.xyz / stretch;
    vec3 e = (q - axis * (dot(q, axis) * (1.0 - 1.0 / stretch))) / r;
    float d2 = dot(e, e);
    // a Gaussian, cut off smoothly inside the lobes that cover it (no lobe's outline ever shows)
    float field = exp(-d2 * 1.6) * (1.0 - smoothstep(0.8, 1.2, sqrt(d2)));
    // billows anchored to the cloud in blocks (they grow slower than the cloud: it rolls outward instead of inflating),
    // rising slowly
    vec3 at = q / (0.16 + 0.4 * r) + vec3(seed, seed * 0.37, -seed * 0.61) + vec3(0.0, -impact_time() * 0.5, 0.0);
    float n = clamp((impact_billows(at) - 0.5) * 1.8 + 0.5, 0.0, 1.0);
    // the noise eats the cloud from its edge inwards, deeper as it ages
    float erosion = (1.0 - n) * (0.45 + 0.4 * age);
    float body = clamp((field - erosion) / max(1.0 - erosion, 0.1), 0.0, 1.0);
    return rho * body * (0.35 + 0.7 * n);
}

void main() {
    vec4 c0 = Clouds[cloud * 4];
    vec4 c1 = Clouds[cloud * 4 + 1];
    vec4 c2 = Clouds[cloud * 4 + 2];
    vec4 c3 = Clouds[cloud * 4 + 3];
    bool inWater = c3.y > 0.5;
    vec3 c = c0.xyz;
    float r = max(c0.w, 1e-3);
    float rhoLobe = c1.x;
    float rhoCloud = c1.y;
    float seed = c1.z;
    float age = c1.w;

    // the view ray through the lobe's sphere
    vec3 dir = normalize(position);
    float b = dot(dir, lobeCenter);
    float h = b * b - (dot(lobeCenter, lobeCenter) - lobeRadius * lobeRadius);
    if (h <= 0.0) {
        discard;
    }
    h = sqrt(h);
    float t0 = max(b - h, 0.05);
    float t1 = b + h;
    if (impact_softDepth()) {
        // where the ray meets the world (the depth buffer stores view depth: along the ray it is longer)
        float scene = impact_viewDistance(texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r);
        float tScene = scene / max(dot(dir, -impact_back()), 1e-3);
        // a plume seen from above: the water's surface in the depth copy does not stop the ray, the bed under it does
        // (approximated: the plume's own far side)
        bool throughSurface = inWater && c3.x < 0.0 && abs(dir.y * tScene - c3.x) < 0.12;
        if (!throughSurface) {
            t1 = min(t1, tScene);
        }
    }
    if (t1 <= t0 + 1e-4) {
        discard;
    }
    float seg = t1 - t0;
    // four samples along the chord (the eroded edge has more detail than three resolve without shimmering)
    float tau = 0.0;
    for (int i = 0; i < 4; i++) {
        tau += density(dir * (t0 + seg * (float(i) + 0.5) * 0.25), c, r, rhoLobe, seed, age, c2, c3);
    }
    tau *= seg * 0.25;
    vec3 pm = dir * (t0 + seg * 0.5);
    // a puff right in front of the camera fades instead of filling the screen
    tau *= clamp((t0 - 0.15) / 0.6, 0.0, 1.0) + step(0.75, t0);
    float alpha = 1.0 - exp(-tau);
    // under the surface, seen from above: dimmed by the water in front of it
    if (inWater && c3.x < 0.0) {
        alpha *= 0.8;
    }
    if (alpha < 0.003) {
        discard;
    }

    // light through the cloud: optical depth of the Gaussian from the middle sample towards the light,
    // ρ·e^(−2·d⊥²/r²)·(√(π/8)·r)·erfc(√2·s/r), erfc(x) ≈ 1 − tanh(1.2·x)
    vec3 lightDir = bl_worldLit() ? BlSunDir.xyz : normalize(vec3(0.3, 1.0, 0.2));
    vec3 v = pm - c;
    float s = dot(v, lightDir);
    float perp2 = max(dot(v, v) - s * s, 0.0);
    float tauLight = rhoCloud * exp(-2.0 * perp2 / (r * r)) * 0.6267 * r * (1.0 - tanh(1.2 * 1.4142 * s / r));
    float toLight = exp(-tauLight * 1.4);
    // multiple scattering: each further order carries half the light and sees half the optical depth, so the core and
    // the far side are shaded, never black
    float reach = (toLight + 0.5 * exp(-tauLight * 0.7) + 0.25 * exp(-tauLight * 0.35)) / 1.75;
    // skylight from above: the underside of a thick cloud is darker
    float tauUp = rhoCloud * exp(-2.0 * (dot(v.xz, v.xz)) / (r * r)) * 0.6267 * r * (1.0 - tanh(1.7 * v.y / r));
    float fromSky = mix(0.7, 1.0, exp(-tauUp * 0.9));
    // Henyey–Greenstein, g = 0.35 (dust scatters forward), ×4π so its average is 1; only partly: seen with the sun
    // behind you the powder still reads as lit as a matte surface, against the sun its thin edges glow
    float g = 0.35;
    float cosTheta = dot(dir, lightDir);
    float phase = mix(1.0, (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosTheta, 1.5), 0.35);

    vec3 light;
    if (bl_worldLit()) {
        float sun = sunShadow * reach * phase;
        vec3 sky = max(ambient - blockLight, vec3(0.0)) * fromSky;
        light = bl_skyShade(blockLight + sky, blockLight, min(sun, 1.0))
                + sky * bl_sunTint() * (max(sun - 1.0, 0.0) * clamp(BlSunColor.a, 0.0, 1.0))
                + bl_display(bl_pointLights(pm, -dir)) * 0.55;
    } else {
        light = ambient * fromSky * mix(0.72, 1.06, toLight);
    }
    vec3 color = albedo * light;
    color = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor).rgb;
    fragColor = vec4(color * alpha, alpha);
}
