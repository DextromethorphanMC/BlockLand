#version 150

// The sun and the moon (client/graphics/sky/CelestialBodies, tools/assets/RENDERING_SPEC.md §14), premultiplied alpha:
// the discs cover the stars behind them, their glow only adds.
//   sun  (uv −1..1):  colour = the light after the air it crossed (rgb, brightest channel 1), a = visibility (1 − rain)
//   moon (uv 3..5):   r = vanilla's phase / 8 (0 full, 0.5 new), a = visibility
// The sun: a limb-darkened disc (the photosphere is dimmer and redder towards its edge) in a corona that fades over the
// quad. The moon: a sphere lit from the phase's side with a soft terminator, its near-uniform brightness (Lommel–Seeliger,
// no limb darkening), dark maria and bright crater rays from fixed noise, faint earthshine on the dark side and a halo
// as bright as its lit share.

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// disc radii as fractions of the quads' half-sizes (CelestialBodies: 10° and 4.4°)
const float SUN_DISC = 0.16;
const float MOON_DISC = 0.56;
const float PI = 3.14159265;

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x), mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y);
    float b = mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x), mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y);
    return mix(a, b, f.z);
}

float fbm(vec3 p) {
    float sum = 0.0, amp = 0.5;
    for (int i = 0; i < 5; i++) {
        sum += amp * noise3(p);
        p = p * 2.03 + vec3(1.7, 9.2, 4.1);
        amp *= 0.5;
    }
    return sum;
}

// Craters on the sphere: a few cells of a 3D grid hold a bowl with a bright rim (the young ones a ray halo).
float craters(vec3 p) {
    vec3 cell = floor(p);
    float shade = 0.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 c = cell + vec3(x, y, z);
                float h = hash13(c);
                if (h < 0.55) continue;
                vec3 centre = c + vec3(hash13(c + 7.1), hash13(c + 3.3), hash13(c + 5.7));
                float radius = 0.18 + 0.32 * hash13(c + 11.0);
                float d = length(p - centre) / radius;
                if (d > 2.5) continue;
                float bowl = 1.0 - smoothstep(0.0, 0.9, d);
                float rim = exp(-pow((d - 1.0) * 5.0, 2.0));
                float rays = h > 0.93 ? 0.35 * exp(-d * 1.2) * (0.6 + 0.4 * noise3(p * 14.0)) : 0.0;
                shade += rim * 0.22 - bowl * 0.12 + rays;
            }
        }
    }
    return shade;
}

vec4 sun(vec2 p) {
    vec3 light = vertexColor.rgb;
    float r = length(p);
    float aa = max(fwidth(r), 1e-4);
    float edge = 1.0 - smoothstep(SUN_DISC - aa, SUN_DISC + aa, r);
    // limb darkening (quadratic law, the edge redder than the centre)
    float mu = sqrt(max(1.0 - (r / SUN_DISC) * (r / SUN_DISC), 0.0));
    vec3 limb = 1.0 - vec3(0.42, 0.56, 0.70) * (1.0 - mu) - vec3(0.20, 0.17, 0.12) * (1.0 - mu) * (1.0 - mu);
    vec3 disc = light * limb * edge;
    // the corona and the air's glow round it, gone at the quad's edge
    float out_ = max(r - SUN_DISC, 0.0);
    float glow = (0.55 * exp(-out_ * 38.0) + 0.16 * exp(-out_ * 9.0)) * (1.0 - smoothstep(0.75, 1.0, r));
    vec3 color = disc + light * glow * (1.0 - edge);
    return vec4(color, edge) * vertexColor.a;
}

vec4 moon(vec2 p) {
    float r = length(p);
    float aa = max(fwidth(r), 1e-4);
    float edge = 1.0 - smoothstep(MOON_DISC - aa, MOON_DISC + aa, r);
    // the phase: 0 full (lit from behind the viewer), 0.5 new; waning (0..0.5) lit from the left, waxing from the right
    float phase = vertexColor.r * 2.0 * PI;
    vec3 sunDir = vec3(-sin(phase), 0.0, cos(phase));
    float litShare = 0.5 + 0.5 * cos(phase);
    vec3 color = vec3(0.0);
    if (edge > 0.0) {
        vec2 q = p / MOON_DISC;
        vec3 n = vec3(q, sqrt(max(1.0 - dot(q, q), 0.0)));
        // the surface: dark maria (large low-frequency patches), the highlands' grain, craters
        float maria = smoothstep(0.42, 0.62, fbm(n * 1.6 + vec3(3.1, 0.7, 5.3)));
        float albedo = mix(0.82, 0.48, maria) * (0.9 + 0.2 * fbm(n * 9.0)) + craters(n * 5.0) * (1.0 - 0.5 * maria);
        albedo = clamp(albedo, 0.18, 1.1);
        float cosI = dot(n, sunDir);
        // Lommel–Seeliger: the full moon is as bright at its edge as at its centre
        float lit = smoothstep(-0.03, 0.06, cosI) * (cosI > 0.0 ? 2.0 * cosI / (cosI + max(n.z, 0.05)) : 0.0);
        vec3 moonLight = vec3(1.0, 0.97, 0.92);
        float earthshine = 0.035 * (1.0 - litShare);
        color = albedo * (moonLight * min(lit, 1.2) + vec3(0.55, 0.66, 0.9) * earthshine) * edge;
    }
    // a halo as bright as the lit share, outside the disc
    float out_ = max(r - MOON_DISC, 0.0);
    float halo = 0.10 * litShare * exp(-out_ * 7.0) * (1.0 - smoothstep(0.7, 1.0, r));
    color += vec3(0.75, 0.82, 1.0) * halo * (1.0 - edge);
    return vec4(color, edge) * vertexColor.a;
}

void main() {
    fragColor = texCoord0.x > 2.0 ? moon(texCoord0 - vec2(4.0, 0.0)) : sun(texCoord0);
}
