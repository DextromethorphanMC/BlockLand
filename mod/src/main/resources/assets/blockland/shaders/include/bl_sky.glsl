#version 150

// BlockLand's physical sky (tools/assets/RENDERING_SPEC.md §14, setting 物理天空): the overworld's sky pixels re-shaded in
// the decode pass by single Rayleigh + Mie scattering in an Earth-like atmosphere, anchored to what vanilla drew so biome
// skies, weather and the fog keep reading as they do:
//   * the day sky is vanilla's own colour at the zenith shaped by the physical model: its brightness by the light scattered
//     along the view ray over that scattered straight up (softened), its colour by their colour ratio and, lower down, more
//     and more by the scattered light's own colour, so the horizon pales, the zenith deepens, the sky round the sun
//     brightens (Mie) and sunsets redden while the zenith stays vanilla's;
//   * by night the moon (opposite the sun) lights a dark blue sky of its own, by its phase;
//   * towards the horizon it fades into vanilla's sky there, which is the fog colour the terrain fades into;
//   * everything vanilla drew over its gradient (stars, the sun and the moon, rain) is kept: vanilla's gradient (the fogged
//     sky disc and the sunrise fan, rebuilt here from the same numbers) is taken out of the pixel and the physical sky put
//     in its place.
// Distances in km; directions in camera-relative world space (y up). Colours "display" are Minecraft's (gamma 2.2).

const float BL_SKY_PI = 3.14159265;
const float BL_SKY_GROUND = 6360.0;
const float BL_SKY_TOP = 6420.0;
// the eye's height above the ground (the model's, not the player's: the sky is the same at any build height)
const float BL_SKY_EYE = 0.3;
const vec3 BL_SKY_RAYLEIGH = vec3(5.8e-3, 13.5e-3, 33.1e-3);
const float BL_SKY_RAYLEIGH_H = 8.0;
const float BL_SKY_MIE = 21e-3;
const float BL_SKY_MIE_H = 1.2;
// Mie extinction = scattering / albedo
const float BL_SKY_MIE_ALBEDO = 0.9;
const float BL_SKY_MIE_G = 0.76;
// samples along the view ray (spaced quadratically: dense near the eye, where the haze is)
const int BL_SKY_STEPS = 8;
// light below which ratios fall back to 1 (vanilla's colour), about 3 % of the clear zenith's red at noon
const float BL_SKY_EPS = 2e-4;
// the night sky lit by a full moon high up (linear), the zenith's luminance with the light 60° up it stands for, and the
// share of it left on a moonless night (airglow)
const vec3 BL_SKY_NIGHT = vec3(0.30, 0.45, 1.0) * 0.003;
const float BL_SKY_NIGHT_REF = 0.01;
const float BL_SKY_AIRGLOW = 0.12;
// vanilla's sky disc (SkyRenderer): an octagon fan 16 blocks above the eye, corners 512 out; fog distance interpolated
// from 16 at its centre to the corners' distance
const float BL_SKY_DISC_HEIGHT = 16.0;
const float BL_SKY_DISC_APOTHEM = 473.0237;
const float BL_SKY_DISC_RIM = 512.2499;

vec3 bl_skyLinear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 bl_skyDisplay(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2));
}

float bl_skyLuminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Chapman's function Ch(x, χ) for x = r / H and μ = cos χ ≥ 0 (Schüler's approximation, within ~12 %).
float bl_skyChapman(float x, float mu) {
    float c = sqrt(0.5 * BL_SKY_PI * x);
    return c / ((c - 1.0) * mu + 1.0);
}

// Air of one exponential layer (scale height h) from radius r out to space towards a direction at cosine mu to the
// local vertical, in km at ground density; `density` = the layer's density at r. The planet must not be in the way.
float bl_skyColumn(float r, float mu, float h, float density) {
    float x = r / h;
    if (mu >= 0.0) {
        return h * density * bl_skyChapman(x, mu);
    }
    // below the local horizontal: twice the column from the ray's lowest point, less the part behind r
    float x0 = sqrt(1.0 - mu * mu) * x;
    return h * (2.0 * sqrt(0.5 * BL_SKY_PI * x0) * exp(BL_SKY_GROUND / h - x0) - density * bl_skyChapman(x, -mu));
}

// Light singly scattered towards the eye along `dir` (y ≥ 0) from a light of unit irradiance towards `light`; `mie`
// scales the Mie haze, `g` its asymmetry. Linear rgb.
vec3 bl_skyScatter(vec3 dir, vec3 light, float mie, float g) {
    float r0 = BL_SKY_GROUND + BL_SKY_EYE;
    float b = r0 * dir.y;
    float len = -b + sqrt(b * b - r0 * r0 + BL_SKY_TOP * BL_SKY_TOP);
    float mieExtinction = mie * BL_SKY_MIE / BL_SKY_MIE_ALBEDO;
    vec3 depth = vec3(0.0);
    vec3 sumR = vec3(0.0), sumM = vec3(0.0);
    for (int i = 0; i < BL_SKY_STEPS; i++) {
        float u = (float(i) + 0.5) / float(BL_SKY_STEPS);
        float t = len * u * u;
        float dt = 2.0 * len * u / float(BL_SKY_STEPS);
        vec3 p = vec3(0.0, r0, 0.0) + dir * t;
        float r = length(p);
        float altitude = max(r - BL_SKY_GROUND, 0.0);
        float rhoR = exp(-altitude / BL_SKY_RAYLEIGH_H), rhoM = exp(-altitude / BL_SKY_MIE_H);
        vec3 extinction = BL_SKY_RAYLEIGH * rhoR + mieExtinction * rhoM;
        vec3 toSample = depth + 0.5 * extinction * dt;
        depth += extinction * dt;
        float mu = dot(p, light) / r;
        // in the planet's shadow
        if (mu < 0.0 && r * r * (1.0 - mu * mu) < BL_SKY_GROUND * BL_SKY_GROUND) {
            continue;
        }
        float columnR = bl_skyColumn(r, mu, BL_SKY_RAYLEIGH_H, rhoR);
        float columnM = bl_skyColumn(r, mu, BL_SKY_MIE_H, rhoM);
        vec3 transmittance = exp(-(toSample + BL_SKY_RAYLEIGH * columnR + mieExtinction * columnM));
        sumR += rhoR * transmittance * dt;
        sumM += rhoM * transmittance * dt;
    }
    float nu = dot(dir, light);
    float phaseR = 3.0 / (16.0 * BL_SKY_PI) * (1.0 + nu * nu);
    float gg = g * g;
    float phaseM = (1.0 - gg) / (4.0 * BL_SKY_PI * pow(max(1.0 + gg - 2.0 * g * nu, 1e-4), 1.5));
    return sumR * BL_SKY_RAYLEIGH * phaseR + sumM * (mie * BL_SKY_MIE) * phaseM;
}

// The colour of light relative to its luminance (1 where there is next to none).
vec3 bl_skyChroma(vec3 c) {
    return (c + BL_SKY_EPS) / (bl_skyLuminance(c) + BL_SKY_EPS);
}

// The physical sky (linear) along `dir`: the day anchored to `zenith` (vanilla's colour straight up, linear), the night
// lit by the moon (`moon` its brightness 0..1, opposite `sun`); `overcast` 0..1 greys it (more haze, less contrast).
vec3 bl_skyPhysical(vec3 dir, vec3 sun, vec3 zenith, float moon, float overcast) {
    dir = normalize(vec3(dir.x, max(dir.y, 0.0), dir.z));
    float mie = 1.0 + 5.0 * overcast;
    float g = BL_SKY_MIE_G - 0.25 * overcast;
    float contrast = mix(0.35, 0.12, overcast);
    vec3 sky = zenith;
    // the sun lights the air until it is 8° under the horizon (the top of the atmosphere's last light)
    if (sun.y > -0.15) {
        vec3 along = bl_skyScatter(dir, sun, mie, g);
        vec3 up = bl_skyScatter(vec3(0.0, 1.0, 0.0), sun, mie, g);
        float brightness = (bl_skyLuminance(along) + BL_SKY_EPS) / (bl_skyLuminance(up) + BL_SKY_EPS);
        vec3 vanilla = bl_skyChroma(zenith);
        vec3 colour = vanilla * clamp(bl_skyChroma(along) / bl_skyChroma(up), 0.05, 4.0);
        // towards the horizon the air's own colour (paler, or the sunset's) takes over from vanilla's saturated blue
        float own = 0.6 * (1.0 - dir.y) * (1.0 - dir.y);
        colour = mix(colour, bl_skyChroma(along), own);
        // overcast: vanilla's grey, nearly flat
        colour = mix(colour, vanilla, 0.75 * overcast);
        sky = bl_skyLuminance(zenith) * colour * min(pow(brightness, contrast), 2.2);
    }
    vec3 night = BL_SKY_NIGHT * BL_SKY_AIRGLOW * smoothstep(0.05, -0.2, sun.y);
    vec3 moonDir = -sun;
    if (moon > 0.0 && moonDir.y > -0.05) {
        vec3 along = bl_skyScatter(dir, moonDir, mie, g);
        float brightness = min(pow(bl_skyLuminance(along) / BL_SKY_NIGHT_REF, contrast), 2.2);
        night += BL_SKY_NIGHT * mix(vec3(1.0), bl_skyChroma(along), 0.5) * (brightness * moon * smoothstep(-0.05, 0.1, moonDir.y));
    }
    return sky + night * (1.0 - 0.6 * overcast);
}

// Vanilla's sky gradient along `dir` as SkyRenderer drew it (display): the sky disc in `skyColor` fogged towards
// `fogColor` by the sky's fog end, the clear colour (the fog's) round and below it, then the sunrise fan over it
// (`fan`: rgb, alpha signed by the side it stands on, 0 = none).
vec3 bl_skyVanilla(vec3 dir, vec3 skyColor, vec3 fogColor, float skyEnd, vec4 fan) {
    vec3 sky = fogColor;
    if (dir.y > 1e-4) {
        vec2 onDisc = dir.xz * (BL_SKY_DISC_HEIGHT / dir.y);
        // the octagon's triangle the ray lands in: its edge normal is the corner angle + 22.5°
        float angle = atan(onDisc.y, onDisc.x);
        float sector = (floor(angle / (0.25 * BL_SKY_PI)) + 0.5) * (0.25 * BL_SKY_PI);
        float s = length(onDisc) * cos(angle - sector) / BL_SKY_DISC_APOTHEM;
        if (s <= 1.0) {
            float distance = BL_SKY_DISC_HEIGHT + (BL_SKY_DISC_RIM - BL_SKY_DISC_HEIGHT) * s;
            sky = mix(skyColor, fogColor, clamp(distance / skyEnd, 0.0, 1.0));
        }
    }
    float alpha = abs(fan.w);
    if (alpha > 1e-3) {
        // the fan: centre 100 out on the horizon (−x, or +x), rim (−120 cos θ, 40 a cos θ, 120 sin θ); where the ray meets
        // it, t = 0 at the centre … 1 at the rim, its alpha a (1 − t)
        float x = dir.x * sign(fan.w);
        float q = length(vec2(dir.y / (40.0 * alpha), dir.z / 120.0));
        float denominator = 100.0 * q - 3.0 * dir.y / alpha - x;
        if (denominator > 0.0) {
            float t = 100.0 * q / denominator;
            if (t <= 1.0) {
                sky = mix(sky, fan.rgb, alpha * (1.0 - t));
            }
        }
    }
    return sky;
}

// How much of the horizon's fog the sky keeps at this height (`dirY` = sine of the elevation): 1 at and below the
// horizon, fading out over a band about 2.5 times as high as the one vanilla fully fogs at this render distance.
float bl_skyHorizon(float dirY, float skyEnd) {
    float fullRadius = min(BL_SKY_DISC_APOTHEM * max(skyEnd - BL_SKY_DISC_HEIGHT, 1.0) / (BL_SKY_DISC_RIM - BL_SKY_DISC_HEIGHT),
            BL_SKY_DISC_APOTHEM);
    float band = clamp(2.5 * BL_SKY_DISC_HEIGHT / fullRadius, 0.06, 0.4);
    return 1.0 - smoothstep(0.0, band, dirY);
}

// A sky pixel (display colour `scene`, view direction `dir`) with vanilla's gradient replaced by the physical sky; the
// rest of what vanilla drew there is kept. Returns display colour (may exceed 1).
vec3 bl_sky(vec3 dir, vec3 scene, vec3 skyColor, vec3 fogColor, float skyEnd, vec4 fan, vec3 sun, float moon, float overcast) {
    vec3 gradient = bl_skyVanilla(dir, skyColor, fogColor, skyEnd, fan);
    vec3 zenith = bl_skyLinear(mix(skyColor, fogColor, clamp(BL_SKY_DISC_HEIGHT / skyEnd, 0.0, 1.0)));
    vec3 physical = bl_skyDisplay(bl_skyPhysical(dir, sun, zenith, moon, overcast));
    vec3 sky = mix(physical, gradient, bl_skyHorizon(dir.y, skyEnd));
    // what vanilla drew over its gradient; differences within its 8-bit steps are dropped (no banding brought back)
    vec3 over = scene - gradient;
    vec3 size = abs(over);
    over *= smoothstep(1.5 / 255.0, 4.0 / 255.0, max(size.r, max(size.g, size.b)));
    return max(sky + over, vec3(0.0));
}
