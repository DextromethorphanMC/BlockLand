#version 150

// BlockLand scene lighting (tools/assets/RENDERING_SPEC.md §2). Written once per frame by client/graphics/light and read
// by the lighting pack's vanilla shader overrides (uniform binding point 15, shadow atlas on texture unit 12: see
// client/render/SceneLighting), by BlockLand's own pipelines (bound by name) and by the post passes.
// Positions are camera-relative world space in blocks unless noted. std140: the member order and sizes must match
// SceneLighting.layoutSize() and the writer in client/graphics/light.

#define BL_MAX_LIGHTS 32
#define BL_MAX_RIPPLES 24
#define BL_MAX_MATERIALS 96
#define BL_CASCADES 4
#define BL_MAX_GUSTS 6
#define BL_MAX_PUSHERS 16
#define BL_MAX_SWAYS 48

// material kinds (BlMaterials[2i+1].z)
#define BL_KIND_PLAIN 0
#define BL_KIND_WATER 1
#define BL_KIND_GLASS 2
#define BL_KIND_METAL 3

layout(std140) uniform BlockLandScene {
    vec4 BlFlags;          // x world lighting on, y emission encoding on, z dynamic light count, w time in seconds (wraps)
    vec4 BlSunDir;         // xyz unit vector towards the light that casts the shadows (sun by day, moon by night); w 1 sun, 0 moon
    vec4 BlSunColor;       // rgb direct light colour × strength (linear, time of day and weather applied); a shadow strength 0..1
    vec4 BlAmbient;        // rgb tint of light in full shadow (linear, about 1); a share of skylight left in full shadow
    vec4 BlSkyColor;       // rgb sky colour for reflections (linear); a rain wetness 0..1
    vec4 BlCamera;         // xyz camera world position wrapped to 0..2048 (world-anchored noise); w 1 = camera under water
    mat4 BlViewRotation;   // camera-relative world -> view (rotation)
    mat4 BlProjection;     // the level's projection, without jitter (view -> clip)
    mat4 BlShadowMatrix[BL_CASCADES]; // camera-relative world -> cascade-local [0,1]^3 (xy within its tile, z depth)
    vec4 BlShadowSplits;   // per cascade: distance from the camera (blocks) it covers; 0 = unused
    vec4 BlShadowParams;   // x 1 / atlas size, y PCF radius (texels, cascade 0), z normal offset (blocks, cascade 0), w cascades
    vec4 BlWater;          // x wave steepness (1 = default), y wave speed, z reflection strength, w refraction strength
    vec4 BlLights[BL_MAX_LIGHTS * 2];       // light i: [2i] (position xyz, radius), [2i+1] (colour rgb linear × intensity, 0)
    vec4 BlMaterialInfo;   // x material count, yz block atlas size in texels
    vec4 BlMaterials[BL_MAX_MATERIALS * 2]; // material i: [2i] sprite uv rect (u0, v0, u1, v1),
                                            //             [2i+1] (emission 0..1, brightest-channel threshold, kind, reflectivity)
    vec4 BlBlockLight;     // rgb block light tint × strength (1 = vanilla's, used with world lighting); a 1 = BlockLand's
                           // volumetric clouds replace vanilla's this frame
    vec4 BlFog;            // BlockLand's haze (spec §14): x extinction at the camera's height (1/blocks), y height falloff
                           // (1/blocks), z sun in-scatter strength, w 0 vanilla's fog alone, 1 haze and soft edge, 2 soft
                           // edge only (the volumetric fog pass is the haze)
    vec4 BlClouds;         // the cloud layer (spec §14): xy cloud noise position of the camera (wraps at 4096), zw layer
                           // bottom and top relative to the camera (blocks)
    vec4 BlCloudShape;     // x coverage 0..1, y density, z shadow strength 0..1 (0 = no clouds), w drift time (s, wrapped)
    vec4 BlWeather;        // spec §15: x wetness 0..1 (rain where the biome rains, built up and dried over time, × the
                           // setting), y rain falling now 0..1, z how open the camera stands to the sky, w ripple count
    vec4 BlRipples[BL_MAX_RIPPLES * 2]; // ripple i: [2i] (camera-relative x, surface y, z, age s),
                                        //           [2i+1] (strength, heading x, heading z, -)
    mat4 BlPrevView;       // spec §17: camera-relative world now -> last frame's view (screen-space reflections)
    vec4 BlPrevProj;       // last frame's projection P00, P11; z most steps of a reflected ray, w 1 = reflections on
    vec4 BlWind;           // spec §18: xy the wind's heading (unit, xz), z steady strength (0 calm .. ~1 storm), w the
                           // player's strength 0..2 (0 = no wind: nothing moves)
    vec4 BlWindInfo;       // x gust count, y pusher count, z sway count, w strength of the water's sway
    vec4 BlWindBend;       // graphics/wind.json bend: lean, flutter (blocks per unit of wind), height exponent, lag
    vec4 BlWindStack;      // stack: lean, flutter, most a body pushes a column (blocks); w 1 = BlPlantColumns is valid
    vec4 BlWindPush;       // push: lean (radians), flatten (share of 90°), core (share of the radius at full push), -
    vec4 BlWindSpring;     // the spring back: angular frequency (rad/s), decay (1/s), trail wobble, -
    vec4 BlWindKinds;      // push per mode: bend, sway, leaves, water plants
    vec4 BlGusts[BL_MAX_GUSTS * 2];     // gust i: [2i] (centre x, z camera-relative, half length along its heading,
                                        //           strength), [2i+1] (heading x, z, half width across it, seed)
    vec4 BlPushers[BL_MAX_PUSHERS * 2]; // entity i: [2i] (feet x, y, z camera-relative, radius), [2i+1] (velocity x, z
                                        //           blocks/s, height, presence 0..1)
    vec4 BlSways[BL_MAX_SWAYS * 2];     // sway i: [2i] (x, y, z camera-relative, radius), [2i+1] (heading x, z, age s,
                                        //           strength) — plants an entity went through, swinging back
};

// Shadow atlas, comparison sampling: 2x2 tiles, cascade c in tile ((c & 1) / 2, (c >> 1) / 2).
uniform sampler2DShadow BlShadowMap;

bool bl_worldLit() {
    return BlFlags.x > 0.5;
}

bool bl_encodeEmission() {
    return BlFlags.y > 0.5;
}

float bl_time() {
    return BlFlags.w;
}

vec3 bl_linear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 bl_display(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2));
}

float bl_luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Opaque draws store their emission e (0..1) in the main target's alpha as 1 - 0.5 e (rendering spec §3); blending a
// translucent layer over it attenuates the glow behind it on its own.
float bl_emissionAlpha(float emission) {
    return 1.0 - 0.5 * clamp(emission, 0.0, 1.0);
}

float bl_fresnel(float cosTheta, float f0) {
    float k = 1.0 - clamp(cosTheta, 0.0, 1.0);
    float k2 = k * k;
    return f0 + (1.0 - f0) * k2 * k2 * k;
}

// Interleaved gradient noise (Jimenez 2014): per-pixel rotation for filter taps.
float bl_noise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

const vec2 BL_POISSON[8] = vec2[8](
    vec2(-0.7071, 0.7071), vec2(-0.0000, -0.8750), vec2(0.5303, 0.5303), vec2(-0.6250, -0.0000),
    vec2(0.3536, -0.3536), vec2(-0.0000, 0.3750), vec2(-0.1768, -0.1768), vec2(0.1250, 0.0000));

// Shadow of one cascade (1 lit .. 0 shadowed), or -1 when the position is outside its box.
float bl_shadowCascade(int c, vec3 pos, vec3 normal, float noise) {
    // cascades grow with their reach: offsets and filter radius scale along (in world units they stay similar per texel)
    float k = BlShadowSplits[c] / max(BlShadowSplits.x, 1e-3);
    vec4 s = BlShadowMatrix[c] * vec4(pos + normal * (BlShadowParams.z * k), 1.0);
    vec3 local = s.xyz / s.w;
    if (local.x < 0.0 || local.y < 0.0 || local.x > 1.0 || local.y > 1.0 || local.z > 1.0) {
        return -1.0;
    }
    vec2 tile = vec2(float(c & 1), float(c >> 1)) * 0.5;
    float texel = BlShadowParams.x;
    float radius = BlShadowParams.y * texel;
    float angle = noise * 6.2831853;
    float ca = cos(angle), sa = sin(angle);
    mat2 rotation = mat2(ca, sa, -sa, ca);
    // every box spans a few hundred blocks along the light, so a constant depth offset is a few centimetres in each;
    // the normal offset above (grows with the cascade's texels) and the casters' slope-scaled bias do the rest
    float depth = local.z - 0.00005;
    float lit = 0.0;
    for (int i = 0; i < 8; i++) {
        vec2 uv = local.xy * 0.5 + rotation * BL_POISSON[i] * radius;
        uv = tile + clamp(uv, vec2(texel), vec2(0.5 - texel));
        lit += texture(BlShadowMap, vec3(uv, depth));
    }
    return lit * 0.125;
}

// ---- clouds (spec §14): the volumetric clouds' coverage map, here so their shadows fall where they are ----------

uint bl_cloudHash(uvec3 v) {
    uint h = v.x * 1597334677u ^ v.y * 3812015801u ^ v.z * 2798796415u;
    h = (h ^ (h >> 16u)) * 2246822519u;
    h = (h ^ (h >> 13u)) * 3266489917u;
    return h ^ (h >> 16u);
}

// Value noise over cells of `cell` blocks, repeating every 4096 blocks (cell divides 4096).
float bl_cloudNoise2(vec2 p, float cell) {
    int period = int(4096.0 / cell + 0.5);
    vec2 q = p / cell;
    ivec2 i = ivec2(floor(q));
    vec2 f = q - vec2(i);
    f = f * f * (3.0 - 2.0 * f);
    ivec2 a = (i % period + period) % period, b = ((i + 1) % period + period) % period;
    float h00 = float(bl_cloudHash(uvec3(uint(a.x), uint(a.y), 7u)) & 0xFFFFu);
    float h10 = float(bl_cloudHash(uvec3(uint(b.x), uint(a.y), 7u)) & 0xFFFFu);
    float h01 = float(bl_cloudHash(uvec3(uint(a.x), uint(b.y), 7u)) & 0xFFFFu);
    float h11 = float(bl_cloudHash(uvec3(uint(b.x), uint(b.y), 7u)) & 0xFFFFu);
    return mix(mix(h00, h10, f.x), mix(h01, h11, f.x), f.y) / 65535.0;
}

// Where clouds are, 0..1, at a horizontal position in cloud noise space: weather-scale patches against the coverage.
float bl_cloudCoverage(vec2 xz) {
    float n = bl_cloudNoise2(xz, 2048.0) * 0.45 + bl_cloudNoise2(xz, 1024.0) * 0.3 + bl_cloudNoise2(xz, 512.0) * 0.17
            + bl_cloudNoise2(xz, 256.0) * 0.08;
    float c = BlCloudShape.x;
    return clamp((n - (1.0 - c) * 0.95) / max(0.12 + 0.5 * c, 1e-3), 0.0, 1.0);
}

// Sunlight let through the clouds to a camera-relative position: the coverage where the ray towards the sun crosses the
// layer's lower third, as the optical depth of a cloud that thick. 1 without clouds.
float bl_cloudShadow(vec3 pos) {
    if (BlCloudShape.z <= 0.0 || BlSunDir.y <= 0.03) {
        return 1.0;
    }
    float through = mix(BlClouds.z, BlClouds.w, 0.3) - pos.y;
    if (through <= 0.0) {
        return 1.0;
    }
    vec2 xz = BlClouds.xy + pos.xz + BlSunDir.xz * (through / BlSunDir.y);
    float c = bl_cloudCoverage(xz);
    float depth = 7.0 * c * c * BlCloudShape.y;
    // a low sun reaches the ground past the cloud bases: the shadows fade out towards the horizon
    return mix(1.0, exp(-depth), BlCloudShape.z * smoothstep(0.03, 0.2, BlSunDir.y));
}

// The sun's (or moon's) shadow map alone at a camera-relative position with its surface normal: 1 lit .. 0 shadowed.
// `pixel` is gl_FragCoord.xy (rotates the filter per pixel; temporal anti-aliasing smooths the rest).
float bl_shadowMap(vec3 pos, vec3 normal, vec2 pixel) {
    int count = int(BlShadowParams.w + 0.5);
    if (count <= 0 || BlSunColor.a <= 0.0) {
        return 1.0;
    }
    float dist = length(pos);
    float noise = bl_noise(pixel);
    for (int c = 0; c < BL_CASCADES; c++) {
        if (c >= count) {
            break;
        }
        float split = BlShadowSplits[c];
        if (dist >= split) {
            continue;
        }
        float s = bl_shadowCascade(c, pos, normal, noise);
        if (s < 0.0) {
            continue;
        }
        if (c == count - 1) {
            s = mix(s, 1.0, smoothstep(split * 0.85, split, dist));
        }
        return s;
    }
    return 1.0;
}

// Sun (or moon) shadow at a camera-relative position: the shadow map times the clouds' shadow.
float bl_shadow(vec3 pos, vec3 normal, vec2 pixel) {
    return bl_shadowMap(pos, normal, pixel) * bl_cloudShadow(pos);
}

// Sum of the dynamic point lights at a camera-relative position (linear light). Half-Lambert wrap: blocky surfaces next
// to a light read better lit around their edges.
vec3 bl_pointLights(vec3 pos, vec3 normal) {
    vec3 sum = vec3(0.0);
    int count = int(BlFlags.z + 0.5);
    for (int i = 0; i < BL_MAX_LIGHTS; i++) {
        if (i >= count) {
            break;
        }
        vec4 light = BlLights[i * 2];
        vec3 d = light.xyz - pos;
        float dist2 = dot(d, d);
        float r2 = light.w * light.w;
        if (dist2 >= r2) {
            continue;
        }
        float window = 1.0 - dist2 / r2;
        float falloff = window * window / (1.0 + dist2);
        float wrap = clamp(dot(normal, d * inversesqrt(max(dist2, 1e-4))) * 0.5 + 0.5, 0.0, 1.0);
        sum += BlLights[i * 2 + 1].rgb * (falloff * wrap);
    }
    return sum;
}

// The block material whose sprite contains this atlas uv (data: graphics/block_materials.json), or -1.
int bl_material(vec2 uv) {
    int count = int(BlMaterialInfo.x + 0.5);
    for (int i = 0; i < BL_MAX_MATERIALS; i++) {
        if (i >= count) {
            break;
        }
        vec4 r = BlMaterials[i * 2];
        if (uv.x >= r.x && uv.y >= r.y && uv.x < r.z && uv.y < r.w) {
            return i;
        }
    }
    return -1;
}

// (emission, emission luminance threshold, kind, reflectivity) of a material; zeros for -1.
vec4 bl_materialParams(int material) {
    return material < 0 ? vec4(0.0) : BlMaterials[material * 2 + 1];
}

// View-space position of a depth-buffer value (0..1) at a screen uv (0..1), with BlProjection (perspective).
vec3 bl_viewPosition(vec2 uv, float depth) {
    vec3 ndc = vec3(uv, depth) * 2.0 - 1.0;
    float viewZ = -BlProjection[3][2] / (ndc.z + BlProjection[2][2]);
    return vec3(ndc.x * -viewZ / BlProjection[0][0], ndc.y * -viewZ / BlProjection[1][1], viewZ);
}

// Camera-relative world position of a view-space position.
vec3 bl_worldFromView(vec3 view) {
    return transpose(mat3(BlViewRotation)) * view;
}
