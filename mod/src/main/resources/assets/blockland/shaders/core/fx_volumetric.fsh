#version 150

// BlockLand volumetric fog and light (half resolution, rendering spec §14): the air between the eye and the scene as a
// participating medium, marched with energy-conserving integration (Hillaire 2015). The medium: the haze (aerosols,
// BlFog: its density at the camera's height, thinning with height), ground mist lying in the low land at dawn, at dusk
// and in rain (drifting noise), and a thin base medium for light shafts (体积光). It is lit by the sun or moon through the
// shadow map and the clouds' shadow (shafts through trees, windows, ridges and gaps in the clouds; Henyey-Greenstein
// forward scattering), by the sky (isotropic, by how open the camera stands to it) and by the dynamic lights (torches,
// muzzle flashes glow in the fog). Beyond the shadow distance the haze is integrated in closed form up to the scene (or
// the render distance through the sky). Under water: a dense, blue, light-only medium (vanilla's water fog keeps the
// visibility). The start of the march is jittered per pixel and per frame (the blur and temporal anti-aliasing hide the
// steps). Output: rgb light scattered towards the eye (linear), a transmittance the picture behind is multiplied by.

#moj_import <blockland:world_fx.glsl>
#moj_import <blockland:scene.glsl>

layout(std140) uniform VolumetricInfo {
    mat4 ViewToWorld;       // view -> camera-relative world (rotation)
    mat4 Cascade[4];        // camera-relative world -> cascade-local [0,1]^3
    vec4 Splits;            // cascade reach (blocks)
    vec4 SunDirection;      // xyz towards the light, w how much it reaches (shadow strength, or the camera's openness)
    vec4 SunFallback;       // rgb light colour when the scene uniforms carry none, a frame index
    vec4 Fog;               // x shaft medium density per block, y march distance, z steps, w camera world y
    vec4 Extra;             // x under water, y cascades, z sun scattering strength, w forward anisotropy g
    vec4 Medium;            // x mist density per block, y mist height scale, z sea level, w sky light strength
    vec4 Far;               // x farthest distance (render distance), y dynamic lights on, zw -
    vec4 FarTerrain;        // spec §22: x near, y far plane of its depth, z 1 = drawn this frame, w its reach (blocks)
};

uniform sampler2D FarDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

const int MAX_STEPS = 48;

float shadowAt(vec3 pos) {
    int count = int(Extra.y + 0.5);
    float dist = length(pos);
    for (int c = 0; c < 4; c++) {
        if (c >= count) {
            break;
        }
        if (dist >= Splits[c]) {
            continue;
        }
        vec4 s = Cascade[c] * vec4(pos, 1.0);
        vec3 local = s.xyz / s.w;
        if (local.x < 0.0 || local.y < 0.0 || local.x > 1.0 || local.y > 1.0 || local.z > 1.0) {
            continue;
        }
        vec2 tile = vec2(float(c & 1), float(c >> 1)) * 0.5;
        return texture(BlShadowMap, vec3(tile + local.xy * 0.5, local.z - 0.0004));
    }
    return 1.0;
}

float henyeyGreenstein(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.5663706 * pow(max(1.0 + g2 - 2.0 * g * c, 1e-4), 1.5));
}

// Value noise for the mist's patches (world-anchored, not periodic).
float hash31(vec3 p) {
    return float(bl_cloudHash(uvec3(ivec3(floor(p)) + 32768)) & 0xFFFFu) / 65535.0;
}

float noise3(vec3 p) {
    vec3 i = floor(p), f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(i), hash31(i + vec3(1, 0, 0)), f.x), mix(hash31(i + vec3(0, 1, 0)), hash31(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash31(i + vec3(0, 0, 1)), hash31(i + vec3(1, 0, 1)), f.x), mix(hash31(i + vec3(0, 1, 1)), hash31(i + vec3(1, 1, 1)), f.x), f.y),
               f.z);
}

// Haze density at a camera-relative position (BlFog: x at the camera's height, y falloff with height).
float haze(vec3 p) {
    return BlFog.x * exp(-BlFog.y * p.y);
}

// Ground mist: thick close to the low land (sea level), thinning fast above it, broken into drifting patches.
float mist(vec3 p, float worldY) {
    if (Medium.x <= 0.0) {
        return 0.0;
    }
    float above = worldY - Medium.z;
    float layer = exp(-max(above, 0.0) / Medium.y);
    if (layer < 0.01) {
        return 0.0;
    }
    vec3 w = BlCamera.xyz + p + vec3(bl_time() * 0.6, 0.0, bl_time() * 0.25);
    float n = noise3(w * 0.05) * 0.65 + noise3(w * 0.13) * 0.35;
    return Medium.x * layer * smoothstep(0.25, 0.75, n);
}

vec3 pointLights(vec3 p) {
    vec3 sum = vec3(0.0);
    int count = int(BlFlags.z + 0.5);
    for (int i = 0; i < BL_MAX_LIGHTS; i++) {
        if (i >= count) {
            break;
        }
        vec4 light = BlLights[i * 3];
        vec3 d = light.xyz - p;
        float dist2 = dot(d, d);
        float r2 = light.w * light.w;
        if (dist2 >= r2) {
            continue;
        }
        float window = 1.0 - dist2 / r2;
        float falloff = window * window / (1.0 + dist2);
        // a spot lights the haze only inside its cone: a visible beam
        float cone = bl_spot(i, d, dist2, window, falloff);
        sum += BlLights[i * 3 + 1].rgb * (falloff * cone);
    }
    // isotropic scattering
    return sum * 0.0795775;
}

void main() {
    float depth = fx_depth(texCoord);
    vec3 view = fx_viewPosition(texCoord, min(depth, 0.999999));
    vec3 pos = mat3(ViewToWorld) * view;
    vec3 dir = normalize(pos);
    float sceneDist = depth >= 1.0 ? max(Far.x, FarTerrain.w) : min(length(pos), Far.x);
    if (depth >= 1.0 && FarTerrain.z > 0.5) {
        // the far terrain lies behind the sky's depth: its own depth says how far the ray goes
        ivec2 texel = clamp(ivec2(texCoord * FxSize.xy), ivec2(0), ivec2(FxSize.xy) - 1);
        float farDepth = texelFetch(FarDepthSampler, texel, 0).r;
        if (farDepth < 1.0) sceneDist = fx_farDistance(texCoord, farDepth, FarTerrain.x, FarTerrain.y);
    }
    float marchDist = min(sceneDist, Fog.y);
    int steps = int(Fog.z + 0.5);
    float stepLength = marchDist / float(steps);
    float jitter = fx_noise(gl_FragCoord.xy, SunFallback.a);

    bool underwater = Extra.x > 0.5;
    vec3 sunColor = (bl_worldLit() ? BlSunColor.rgb : SunFallback.rgb) * Extra.z * SunDirection.w;
    vec3 tint = underwater ? vec3(0.35, 0.75, 1.0) : vec3(1.0);
    float cosTheta = dot(dir, SunDirection.xyz);
    float sunPhase = mix(henyeyGreenstein(cosTheta, Extra.w), 0.0795775, 0.25);
    // the sky lights the fog from everywhere, and sunlight scattered many times inside it adds a soft glow of its own;
    // scaled by how open the camera stands to the sky (none in caves)
    vec3 skyLight = (BlSkyColor.rgb * 0.85 + (bl_worldLit() ? BlSunColor.rgb : SunFallback.rgb) * 0.12) * Medium.w;
    bool lights = Far.y > 0.5;

    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);
    for (int i = 0; i < MAX_STEPS; i++) {
        if (i >= steps) {
            break;
        }
        float t = (float(i) + jitter) * stepLength;
        vec3 p = dir * t;
        float worldY = Fog.w + p.y;
        float shaft = Fog.x * (underwater ? 1.0 : exp(-max(worldY - 62.0, 0.0) / 72.0));
        float fog = underwater ? 0.0 : haze(p) + mist(p, worldY);
        float sigma = shaft + fog;
        if (sigma <= 1e-6) {
            continue;
        }
        float sun = shadowAt(p) * bl_cloudShadowFog(p);
        vec3 inscatter = sunColor * tint * (sun * sunPhase);
        // the sky and the lamps light the fog, not the thin shaft medium (which only shows the sun's shafts)
        inscatter += skyLight * (fog / sigma);
        if (lights) inscatter += pointLights(p);
        // energy-conserving step: what this stretch scatters towards the eye, dimmed by what lies in front of it
        float stepT = exp(-sigma * stepLength);
        scattered += transmittance * inscatter * (1.0 - stepT);
        transmittance *= underwater ? 1.0 : exp(-fog * stepLength);
    }
    // beyond the shadow distance: the haze in closed form (unshadowed, only the clouds' shadow at the far end)
    if (!underwater && sceneDist > marchDist && BlFog.x > 0.0) {
        vec3 a = dir * marchDist;
        float x = BlFog.y * dir.y;
        float shape = abs(x) > 1e-5 ? (exp(-x * marchDist) - exp(-x * sceneDist)) / x : (sceneDist - marchDist);
        float tau = BlFog.x * shape;
        float farT = exp(-tau);
        float sun = bl_cloudShadowFog(dir * min(sceneDist, 512.0)) *(Extra.y > 0.5 ? 1.0 : SunDirection.w);
        vec3 inscatter = sunColor * (sun * sunPhase) + skyLight;
        scattered += transmittance * inscatter * (1.0 - farT);
        transmittance *= farT;
    }
    fragColor = vec4(scattered, transmittance);
}
