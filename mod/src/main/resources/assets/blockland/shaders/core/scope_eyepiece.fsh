#version 150

// BlockLand scope eyepiece (see scope_eyepiece.vsh and com.blockland.client.scope.ScopeOptics).

layout(std140) uniform ScopeInfo {
    mat4 ScopeView;
    vec4 WorldTan;
    vec4 Eye;
    vec4 Reticle;
    vec4 ReticleColor;
    vec4 Glass;
    vec4 Optics;
    vec4 Sun;
    vec4 Lens;
    vec4 Coating;
};

uniform sampler2D Sampler0; // the scope's picture of the world
uniform sampler2D Sampler1; // reticle (mipmapped)

noperspective in vec3 scopeRay;
in vec2 lensPos;
in vec3 viewPos;
in vec3 viewNormal;

out vec4 fragColor;

const float PUPIL_SHIFT = 0.25;
const float PARALLAX_DRIFT = 0.15;
const float TRANSMISSION = 0.93;
const vec3 SUN_COLOR = vec3(1.0, 0.95, 0.84);

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), f.x), mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), f.x), f.y);
}

// smudges on the glass: soft blotches and a few wiped streaks, fixed to the glass
float smudges(vec2 p) {
    float n = 0.55 * noise2(p * 3.1) + 0.3 * noise2(p * 7.3 + 11.0) + 0.15 * noise2(p * 17.0 + 3.0);
    float wipe = noise2(vec2(p.x * 1.4 + p.y * 0.6, p.y * 9.0) + 5.0);
    return smoothstep(0.5, 0.85, n) + 0.35 * smoothstep(0.72, 0.95, wipe);
}

float disc(vec2 p, vec2 centre, float radius, float soft) {
    return 1.0 - smoothstep(radius - soft, radius + soft, length(p - centre));
}

void main() {
    // where this ray points against the optical axis, in field stops (1 = the edge of the picture)
    vec2 t = scopeRay.xy / max(-scopeRay.z, 1e-4);
    vec2 q = t / WorldTan.z;
    float r = length(q);
    float aa = max(fwidth(r), 1e-4);

    // the picture: angles magnified about the axis, slight barrel distortion and lateral colour towards the rim, and a
    // touch of sharpening (it is rendered at about the eyepiece's own pixels, so bilinear filtering softens it)
    vec2 d = q * (1.0 + Optics.z * r * r);
    vec2 uv = 0.5 + 0.5 * d * WorldTan.w;
    vec2 fringe = d * WorldTan.w * (0.5 * Optics.y * r * r);
    vec3 centre = texture(Sampler0, uv).rgb;
    vec2 texel = vec2(Lens.z);
    vec3 around = texture(Sampler0, uv + vec2(texel.x, 0.0)).rgb + texture(Sampler0, uv - vec2(texel.x, 0.0)).rgb
            + texture(Sampler0, uv + vec2(0.0, texel.y)).rgb + texture(Sampler0, uv - vec2(0.0, texel.y)).rgb;
    // sharpened on brightness only, as a gain: subtracting a blur per channel shifts hues at dark edges (blue clips first)
    float luma = luminance(centre);
    float gain = clamp(1.0 + Lens.w * (luma - luminance(around) * 0.25) / max(luma, 0.04), 0.75, 1.35);
    vec3 picture = vec3(texture(Sampler0, uv + fringe).r, centre.g, texture(Sampler0, uv - fringe).b) * gain;
    picture = clamp(picture, 0.0, 1.0) * Eye.w;

    // what reaches the eye: the field stop (anchored to the axis, so it slides against the glass when the eye or the
    // rifle is off axis), the exit pupil (eye box and eye relief; its shadow softens as the eye wanders) and the rim
    // vignette
    float field = 1.0 - smoothstep(1.0 - 1.5 * aa, 1.0, r);
    float offset = length(Eye.xy);
    float pupilRadius = 1.12 - 1.8 * max(Eye.z, 0.0) - 1.2 * max(-Eye.z, 0.0) - 0.3 * max(offset - 1.0, 0.0);
    float pupilEdge = 0.18 + 0.12 * min(offset, 2.0);
    float pupil = 1.0 - smoothstep(pupilRadius - pupilEdge, pupilRadius + 0.02, length(q + Eye.xy * PUPIL_SHIFT));
    float vignette = 1.0 - Optics.x * pow(min(r, 1.0), 3.0);
    float light = field * pupil * vignette;
    vec3 color = picture * light * TRANSMISSION;

    // the reticle lies in a focal plane: it stays on the axis, drifts against the picture by its parallax when the eye is
    // off axis and cants with the scope; grey pixels are etched lines, coloured ones are illuminated and glow a little
    // (sampled unconditionally: the mip level comes from screen derivatives, undefined inside a divergent branch)
    vec2 p = q + Eye.xy * (Reticle.y * PARALLAX_DRIFT);
    p = vec2(Reticle.z * p.x + Reticle.w * p.y, -Reticle.w * p.x + Reticle.z * p.y);
    vec2 ruv = vec2(0.5 + 0.5 * p.x * Reticle.x, 0.5 - 0.5 * p.y * Reticle.x);
    vec4 mark = texture(Sampler1, ruv);
    vec4 haze = texture(Sampler1, ruv, 3.0);
    vec2 inside = step(vec2(0.0), ruv) * step(ruv, vec2(1.0));
    float present = inside.x * inside.y * Optics.w;
    mark.a *= present;
    haze.a *= present;
    float saturation = max(mark.r, max(mark.g, mark.b)) - min(mark.r, min(mark.g, mark.b));
    float lit = mark.a * max(smoothstep(0.2, 0.45, saturation), smoothstep(0.85, 0.95, luminance(mark.rgb)));
    float hazeSaturation = max(haze.r, max(haze.g, haze.b)) - min(haze.r, min(haze.g, haze.b));
    float glow = haze.a * smoothstep(0.15, 0.4, hazeSaturation);
    float etched = max(mark.a - lit, 0.0);
    color *= 1.0 - etched * field;
    color += ReticleColor.rgb * (ReticleColor.a * (lit + 0.35 * glow) * field * max(pupil, 0.2));

    // the sun near the field of view: veiling glare around it, ghosts mirrored through the centre (tinted by the
    // coating), and smudges on the glass lighting up in it
    float ahead = -Sun.z;
    vec2 s = Sun.xy / max(ahead, 0.05) / WorldTan.z;
    float sun = Sun.w * Lens.y * smoothstep(0.0, 0.2, ahead);
    float toSun = length(q - s);
    float near = exp(-dot(s, s) * 0.06);
    float inField = 1.0 - smoothstep(0.8, 1.8, length(s));
    float veil = sun * (0.3 * exp(-toSun * toSun * 3.0) * (0.35 + 0.65 * inField) + 0.05 * near);
    float ghosts = disc(q, -s * 0.35, 0.10, 0.04) * 0.35 + disc(q, -s * 0.75, 0.22, 0.10) * 0.18 + disc(q, s * 0.45, 0.06, 0.03) * 0.25;
    float smudge = smudges(lensPos * 2.4 + 1.7) * Lens.x;
    vec3 ghostColor = mix(vec3(1.0), Coating.rgb, 0.6);
    color += (SUN_COLOR * veil * (1.0 + 1.5 * smudge) + ghostColor * ghosts * sun * near * 0.25) * field * pupil;

    // the glass: a fresnel reflection of what is around the player (their sky over their ground, as lit as they are),
    // faintly tinted by the coating; lost in a lit picture, seen over the dark rim, the scope shadow or a lowered scope
    vec3 v = normalize(-viewPos);
    vec3 n = normalize(viewNormal);
    if (dot(n, v) < 0.0) {
        n = -n;
    }
    float facing = max(dot(n, v), 0.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
    vec3 reflected = reflect(-v, n);
    vec3 environment = mix(Glass.rgb * 0.16, Glass.rgb, smoothstep(-0.3, 0.7, reflected.y));
    vec3 reflection = environment * fresnel * mix(vec3(1.0), Coating.rgb, 0.25) * (1.0 + 1.5 * smudge);
    color += reflection * (1.0 - 0.9 * light * Eye.w);
    fragColor = vec4(color, 1.0);
}
