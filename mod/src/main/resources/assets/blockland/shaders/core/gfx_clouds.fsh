#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:scene.glsl>

// BlockLand's volumetric clouds (client/graphics/sky/VolumetricClouds, rendering spec §14), marched at a share of the
// internal resolution: the view ray through the cloud layer (BlClouds, relative to the camera), stopped by the world.
// Modelled on real cumulus and stratocumulus (Schneider 2015, Nubis): a weather map says where clouds are (the same
// coverage as bl_cloudShadow, so their shadows on the ground fall under them) and how they are built — flat stratocumulus
// sheets or towering cumulus with flat bases and rounded tops; a tiling Perlin-Worley volume shapes them into billowing
// cells and a Worley volume erodes their edges, wispy at the base, billowy higher up. Lighting: the sun or moon
// (BlSunDir, BlSunColor) through a march towards it, with multiple scattering by octaves (Wrenninge 2013), a forward and
// a backward Henyey-Greenstein lobe (silver linings towards the sun), the powder darkening of thin edges seen from the
// sun's side, and ambient light from the sky above and the ground below; energy-conserving integration (Hillaire 2015);
// far clouds fade into the sky. Output: rgb light scattered towards the eye (linear), a transmittance.
//   Matrix    = NDC -> camera-relative world (homogeneous; the level projection without jitter)
//   Params[0] = (internal width, height, steps, light steps)
//   Params[1] = (frame index, -, -, -)

uniform sampler2D DepthSampler;
uniform sampler2D ShapeNoise;
uniform sampler2D DetailNoise;
uniform sampler2D WeatherMap;

in vec2 texCoord;

out vec4 fragColor;

const float FAR = 16000.0;
const float EXTINCTION = 0.055;
// noise tiles (blocks; each divides the 4096-block period): cloud cells, edge detail
const float SHAPE_TILE = 1024.0;
const float DETAIL_TILE = 128.0;

float remap(float v, float lo, float hi, float newLo, float newHi) {
    return newLo + (v - lo) / max(hi - lo, 1e-4) * (newHi - newLo);
}

// A tiling volume stored as slices: hardware bilinear inside the two nearest slices (kept off the slice borders).
vec4 volume(sampler2D atlas, vec3 p, float size, float columns) {
    vec3 q = fract(p) * size;
    float z = q.z - 0.5;
    float z0 = floor(z);
    float f = z - z0;
    vec2 xy = clamp(q.xy, vec2(0.5), vec2(size - 0.5));
    vec2 atlasSize = vec2(textureSize(atlas, 0));
    float s0 = mod(z0, size), s1 = mod(z0 + 1.0, size);
    vec2 a = (vec2(mod(s0, columns), floor(s0 / columns)) * size + xy) / atlasSize;
    vec2 b = (vec2(mod(s1, columns), floor(s1 / columns)) * size + xy) / atlasSize;
    return mix(texture(atlas, a), texture(atlas, b), f);
}

// The weather at a horizontal position (cloud noise space): coverage 0..1 (bl_cloudCoverage's), cloud type.
vec2 weather(vec2 xz) {
    vec2 w = texture(WeatherMap, fract(xz / 4096.0)).rg;
    float c = BlCloudShape.x;
    return vec2(clamp((w.r - (1.0 - c) * 0.95) / max(0.12 + 0.5 * c, 1e-3), 0.0, 1.0), w.g);
}

// Where in its height a cloud of this type has substance: stratocumulus a low sheet, cumulus a tall tower with a flat
// base and a rounded top (taller the more it covers).
float heightProfile(float h, float type, float coverage) {
    float sheet = smoothstep(0.0, 0.06, h) * (1.0 - smoothstep(0.12, 0.3, h));
    float top = mix(0.45, 1.0, coverage);
    float tower = smoothstep(0.0, 0.08, h) * (1.0 - smoothstep(top * 0.5, top, h));
    return mix(sheet, tower, type);
}

// Density at a point: xz in cloud noise space, y height above the layer's bottom (blocks).
float density(vec3 p, float thickness, bool detail) {
    float h = p.y / thickness;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    vec2 w = weather(p.xz);
    if (w.x <= 0.0) return 0.0;
    float profile = heightProfile(h, w.y, w.x);
    if (profile <= 0.0) return 0.0;
    vec4 shape = volume(ShapeNoise, p / SHAPE_TILE, 128.0, 16.0);
    float cells = shape.g * 0.625 + shape.b * 0.25 + shape.a * 0.125;
    float base = remap(shape.r, cells - 1.0, 1.0, 0.0, 1.0) * profile;
    // coverage cuts the cells: sparse weather leaves only the strongest cores
    float d = clamp(remap(base, 1.0 - w.x, 1.0, 0.0, 1.0), 0.0, 1.0) * w.x;
    if (detail && d > 0.0) {
        // the detail drifts through the cells (2 blocks/s; its clock wraps after one tile, 64 s)
        vec3 q = p + vec3(BlCloudShape.w * 2.0, 0.0, BlCloudShape.w * 2.0);
        vec3 n = volume(DetailNoise, q / DETAIL_TILE, 32.0, 8.0).rgb;
        float fine = n.r * 0.625 + n.g * 0.25 + n.b * 0.125;
        // wispy (inverted) near the base, billowy above
        float erode = mix(1.0 - fine, fine, clamp(h * 6.0, 0.0, 1.0));
        d = clamp(remap(d, erode * 0.38, 1.0, 0.0, 1.0), 0.0, 1.0);
    }
    return d * BlCloudShape.y;
}

float henyeyGreenstein(float c, float g) {
    return (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * c, 1e-4), 1.5);
}

float phase(float c, float k) {
    return mix(henyeyGreenstein(c, 0.8 * k), henyeyGreenstein(c, -0.3 * k), 0.25);
}

// Optical depth towards the sun from p: steps of growing length, cheap shape-only density.
float sunDepth(vec3 p, vec3 sun, float thickness) {
    int steps = int(Params[0].w);
    float depth = 0.0, ds = thickness * 0.04;
    vec3 q = p;
    for (int i = 0; i < steps; i++) {
        q += sun * ds;
        depth += density(q, thickness, false) * ds;
        ds *= 1.7;
    }
    return depth * EXTINCTION;
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 far = Matrix * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w);
    float bottom = BlClouds.z, top = BlClouds.w, thickness = top - bottom;
    if (BlCloudShape.z <= 0.0 || thickness <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // how far the world lets the ray go
    ivec2 depthTexel = clamp(ivec2(texCoord * Params[0].xy), ivec2(0), ivec2(Params[0].xy) - 1);
    float depth = texelFetch(DepthSampler, depthTexel, 0).r;
    float limit = FAR;
    if (depth < 1.0) {
        vec4 world = Matrix * vec4(ndc, depth * 2.0 - 1.0, 1.0);
        limit = length(world.xyz / world.w);
    }

    // the stretch of the ray inside the layer (the camera is at height 0)
    float t0, t1;
    if (abs(dir.y) < 1e-4) {
        if (bottom > 0.0 || top < 0.0) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        t0 = 0.0;
        t1 = FAR;
    } else {
        float a = bottom / dir.y, b = top / dir.y;
        t0 = max(min(a, b), 0.0);
        t1 = max(a, b);
    }
    t1 = min(min(t1, limit), FAR);
    if (t1 <= t0 || t0 >= FAR) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec3 sun = BlSunDir.xyz;
    vec3 sunColor = BlSunColor.rgb * 1.4;
    vec3 sky = BlSkyColor.rgb;
    // light bounced off the ground under the clouds (the sunlit land, a little green-brown)
    vec3 ground = vec3(0.32, 0.30, 0.26) * (bl_luminance(BlSunColor.rgb) * max(sun.y, 0.0) * 0.6 + bl_luminance(sky) * 0.4);
    float c = dot(dir, sun);

    int steps = int(Params[0].z);
    // longer stretches (towards the horizon) take longer steps, never more than the step count
    float ds = (t1 - t0) / float(steps);
    float jitter = fract(gfx_noise(gl_FragCoord.xy) + Params[1].x * 0.6180339887);
    vec3 origin = vec3(BlClouds.x, -bottom, BlClouds.y);
    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);
    float weightedT = 0.0, weights = 0.0;
    for (int i = 0; i < steps; i++) {
        float t = t0 + (float(i) + jitter) * ds;
        vec3 p = origin + dir * t;
        // empty sky: skip the volumes
        if (weather(p.xz).x <= 0.0) continue;
        float d = density(p, thickness, true);
        if (d <= 0.0) continue;
        float sigma = d * EXTINCTION;
        float h = p.y / thickness;
        float toSun = sun.y > -0.1 ? sunDepth(p, sun, thickness) : 1e3;
        // multiple scattering by octaves: each one reaches deeper, spreads more, carries half as much
        float direct = 0.0, a = 1.0, b = 1.0, k = 1.0;
        for (int o = 0; o < 3; o++) {
            direct += b * exp(-toSun * a) * phase(c, k);
            a *= 0.25;
            b *= 0.5;
            k *= 0.5;
        }
        // thin edges seen from the sun's side look darker (light has not yet been scattered towards the eye)
        float powder = mix(1.0, 1.0 - exp(-sigma * 40.0), clamp(0.5 - 0.5 * c, 0.0, 1.0) * 0.8);
        vec3 ambient = mix(ground, sky * 1.15, smoothstep(0.0, 0.8, h)) * (0.55 + 0.45 * h);
        vec3 light = sunColor * direct * powder + ambient;
        float stepT = exp(-sigma * ds);
        scattered += transmittance * light * (1.0 - stepT);
        weightedT += t * transmittance * (1.0 - stepT);
        weights += transmittance * (1.0 - stepT);
        transmittance *= stepT;
        if (transmittance < 0.01) break;
    }
    // aerial perspective: the air between the eye and the clouds tints and fades them into the sky
    float distance = weights > 0.0 ? weightedT / weights : t0;
    float air = exp(-distance / 9000.0);
    vec3 horizon = sky * 1.1;
    scattered = mix(horizon * (1.0 - transmittance), scattered, air);
    float fade = exp(-distance / 14000.0);
    fragColor = vec4(scattered * fade, 1.0 - (1.0 - transmittance) * fade);
}
