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
    vec4 BlLights[BL_MAX_LIGHTS * 3];       // light i: [3i] (position xyz, radius), [3i+1] (colour rgb linear × intensity,
                                            // a spot's inner cos), [3i+2] (a spot's direction xyz, outer cos; w -2 = a point
                                            // light)
    vec4 BlMaterialInfo;   // x material count, yz block atlas size in texels
    vec4 BlMaterials[BL_MAX_MATERIALS * 2]; // material i: [2i] sprite uv rect (u0, v0, u1, v1),
                                            //             [2i+1] (emission 0..1, brightest-channel threshold, kind, reflectivity)
    vec4 BlBlockLight;     // rgb block light tint × strength (1 = vanilla's, used with world lighting); a 1 = BlockLand's
                           // volumetric clouds replace vanilla's this frame
    vec4 BlFog;            // BlockLand's haze (spec §14): x extinction at the camera's height (1/blocks), y height falloff
                           // (1/blocks), z sun in-scatter strength, w 0 vanilla's fog alone, 1 haze and soft edge, 2 soft
                           // edge only (the volumetric fog pass is the haze)
    vec4 BlClouds;         // the clouds (spec §14, Bliss' cameraPosition and clock): xyz the camera's world position, y
                           // shifted by 192 - the dimension's cloud height; w game time in ticks modulo 100 days
    vec4 BlCloudShape;     // x the 云量 setting 0..1, y rain 0..1, z shadow strength 0..1 (0 = no clouds); w (not the
                           // clouds') the soft shadows' tan of the light's effective angular radius, 0 = fixed filter (§6)
    vec4 BlGround;         // the ground round the camera (colour bleeding): rgb its albedo (linear), a how much of it lies
                           // below (0 in the air)
    vec4 BlWaterColor;     // spec §15: the biome's water colour round the camera (linear, vanilla's blend); a unused
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
    vec4 BlWindPush;       // push: lean (radians), flatten (share of 90°), core (share of the radius at full push), w how far the turbulence has blown (x, blocks, wrapped at 2048)
    vec4 BlWindSpring;     // the spring back: angular frequency (rad/s), decay (1/s), trail wobble, w the turbulence's drift (z)
    vec4 BlWindKinds;      // push per mode: bend, sway, leaves, water plants
    vec4 BlGusts[BL_MAX_GUSTS * 2];     // gust i: [2i] (centre x, z camera-relative, half length along its heading,
                                        //           strength), [2i+1] (heading x, z, half width across it, seed)
    vec4 BlPushers[BL_MAX_PUSHERS * 2]; // entity i: [2i] (feet x, y, z camera-relative, radius), [2i+1] (velocity x, z
                                        //           blocks/s, height, presence 0..1)
    vec4 BlSways[BL_MAX_SWAYS * 2];     // sway i: [2i] (x, y, z camera-relative, radius), [2i+1] (heading x, z, age s,
                                        //           strength) — plants an entity went through, swinging back
    vec4 BlShadowSoft;     // x 1 = the soft shadows at low quality (fewer taps, spec §6), yzw unused
};

// Shadow atlas, comparison sampling: 2x2 tiles, cascade c in tile ((c & 1) / 2, (c >> 1) / 2).
uniform sampler2DShadow BlShadowMap;
// The same atlas as raw depth (nearest, no comparison; texture unit 17, set by client/graphics/light for every program):
// the soft shadows' blocker search. Only read while BlCloudShape.w > 0.
uniform sampler2D BlShadowDepth;

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

// ---- soft shadows (spec §6): percentage-closer soft shadows, the penumbra widening with the caster's distance --------

// Blocker search: (direction, share of the way from the filter's floor radius to the search radius). The centre and a
// ring at the floor radius first (a caster touching the receiver, a shadow edge right here), then a spiral outwards,
// denser near the middle where near casters are.
const vec3 BL_SEARCH[11] = vec3[11](
    vec3(-0.7336, 0.6796, 0.0), vec3(-0.2217, -0.9751, 0.0), vec3(0.9553, 0.2955, 0.0), vec3(0.5403, 0.8415, 0.1250),
    vec3(-0.9668, -0.2555, 0.2500), vec3(0.8855, -0.4647, 0.3750), vec3(-0.3391, 0.9408, 0.5000),
    vec3(-0.3855, -0.9227, 0.6250), vec3(0.9075, 0.4200, 0.7500), vec3(-0.9529, 0.3033, 0.8750), vec3(0.4977, -0.8673, 1.0000));
// The filter: a 16-tap Vogel disc (even over the area).
const vec2 BL_SOFT[16] = vec2[16](
    vec2(0.1768, 0.0000), vec2(-0.2258, 0.2068), vec2(0.0346, -0.3938), vec2(0.2846, 0.3712), vec2(-0.5222, -0.0924),
    vec2(0.4947, -0.3147), vec2(-0.1655, 0.6155), vec2(-0.3156, -0.6076), vec2(0.6846, 0.2500), vec2(-0.7123, 0.2940),
    vec2(0.3434, -0.7337), vec2(0.2537, 0.8089), vec2(-0.7647, -0.4432), vec2(0.8971, -0.1972), vec2(-0.5475, 0.7788),
    vec2(-0.1265, -0.9761));
// Low quality (BlShadowSoft.x, the 软阴影质量 setting): the centre and four taps up the spiral, an 8-tap Vogel disc.
const vec3 BL_SEARCH_LOW[4] = vec3[4](vec3(0.7648, 0.6442, 0.0000), vec3(-0.9991, 0.0416, 0.3333), vec3(0.7086, -0.7056, 0.6667), vec3(-0.0459, 0.9989, 1.0000));
const vec2 BL_SOFT_LOW[8] = vec2[8](
    vec2(0.2500, 0.0000), vec2(-0.3193, 0.2925), vec2(0.0489, -0.5569), vec2(0.4024, 0.5249),
    vec2(-0.7385, -0.1306), vec2(0.6996, -0.4450), vec2(-0.2340, 0.8705), vec2(-0.4463, -0.8593));
// Widest penumbra radius (blocks), and the caster distance (blocks) the blocker search is sized for.
#define BL_PENUMBRA_MAX 1.5
#define BL_PENUMBRA_REACH 48.0

// Soft shadow of one cascade at its local position (inside the box). `radius` is the fixed filter's radius (atlas uv),
// kept as the floor, `depth` the biased receiver depth, `tanSize` the tangent of the light's angular radius.
float bl_shadowSoft(int c, vec3 local, vec3 normal, vec2 tile, float texel, float radius, float depth, mat2 rotation,
                    float tanSize) {
    // the cascade matrix is BIAS × ortho × a rigid light view: its rows are the light's axes, x and y scaled by
    // 0.5 / half side, z by 1 / box depth — local units per block (client/graphics/shadow/Cascade)
    mat4 m = BlShadowMatrix[c];
    vec3 rowX = vec3(m[0][0], m[1][0], m[2][0]);
    vec3 rowY = vec3(m[0][1], m[1][1], m[2][1]);
    vec3 rowZ = vec3(m[0][2], m[1][2], m[2][2]);
    float perBlock = length(rowX);
    float depthPerBlock = length(rowZ);
    float uvPerBlock = 0.5 * perBlock; // the tile is half the atlas
    // receiver plane: the depth the surface itself has at a tap's offset (atlas uv), so wide taps neither shadow a
    // surface tilted against the light nor see through it
    vec2 slope = vec2(0.0);
    float n2 = dot(normal, normal);
    if (n2 > 0.25) {
        vec3 n = normal * inversesqrt(n2);
        vec3 nl = vec3(dot(n, rowX) / perBlock, dot(n, rowY) / perBlock, dot(n, rowZ) / depthPerBlock);
        float nz = nl.z < 0.0 ? min(nl.z, -0.25) : max(nl.z, 0.25);
        slope = -nl.xy / nz * (depthPerBlock / uvPerBlock);
    }
    vec2 centre = local.xy * 0.5;
    vec2 lo = vec2(texel), hi = vec2(0.5 - texel);
    float widest = max(radius, min(BL_PENUMBRA_MAX, tanSize * BL_PENUMBRA_REACH) * uvPerBlock);

    // blocker search: how far (along the light) the casters around this point are above the surface
    float blockers = 0.0;
    float gap = 0.0;
    float d = textureLod(BlShadowDepth, tile + clamp(centre, lo, hi), 0.0).r;
    if (d < depth) {
        blockers = 1.0;
        gap = depth - d;
    }
    if (BlShadowSoft.x > 0.5) {
        // low quality: the same estimate from five taps, the same penumbra from eight
        for (int i = 0; i < 4; i++) {
            vec2 o = rotation * BL_SEARCH_LOW[i].xy * mix(radius, widest, BL_SEARCH_LOW[i].z);
            float ref = min(depth + dot(slope, o), 1.0);
            d = textureLod(BlShadowDepth, tile + clamp(centre + o, lo, hi), 0.0).r;
            if (d < ref) {
                blockers += 1.0;
                gap += ref - d;
            }
        }
        if (blockers < 0.5) {
            return 1.0;
        }
        if (blockers > 4.5) {
            return 0.0;
        }
        float widthLow = clamp(gap / blockers / depthPerBlock * tanSize * uvPerBlock, radius, widest);
        float litLow = 0.0;
        for (int i = 0; i < 8; i++) {
            vec2 o = rotation * BL_SOFT_LOW[i] * widthLow;
            litLow += texture(BlShadowMap, vec3(tile + clamp(centre + o, lo, hi), min(depth + dot(slope, o), 1.0)));
        }
        return litLow * 0.125;
    }
    for (int i = 0; i < 11; i++) {
        vec2 o = rotation * BL_SEARCH[i].xy * mix(radius, widest, BL_SEARCH[i].z);
        float ref = min(depth + dot(slope, o), 1.0);
        d = textureLod(BlShadowDepth, tile + clamp(centre + o, lo, hi), 0.0).r;
        if (d < ref) {
            blockers += 1.0;
            gap += ref - d;
        }
    }
    if (blockers < 0.5) {
        return 1.0;
    }
    if (blockers > 11.5) {
        return 0.0;
    }
    // penumbra: the caster's distance above the surface (blocks) × the light's angular size
    float width = clamp(gap / blockers / depthPerBlock * tanSize * uvPerBlock, radius, widest);
    float lit = 0.0;
    for (int i = 0; i < 16; i++) {
        vec2 o = rotation * BL_SOFT[i] * width;
        lit += texture(BlShadowMap, vec3(tile + clamp(centre + o, lo, hi), min(depth + dot(slope, o), 1.0)));
    }
    return lit * 0.0625;
}

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
    if (BlCloudShape.w > 0.0) {
        return bl_shadowSoft(c, local, normal, tile, texel, radius, depth, rotation, BlCloudShape.w);
    }
    float lit = 0.0;
    for (int i = 0; i < 8; i++) {
        vec2 uv = local.xy * 0.5 + rotation * BL_POISSON[i] * radius;
        uv = tile + clamp(uv, vec2(texel), vec2(0.5 - texel));
        lit += texture(BlShadowMap, vec3(uv, depth));
    }
    return lit * 0.125;
}

// ---- clouds (spec §14): Bliss' cloud layers, here so their shadows fall where the clouds are ----------------------

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

// The clouds themselves are Bliss' code (bliss_clouds.glsl, ported): every program gets its densities and cloud
// shadows; only the clouds' own pass (gfx_clouds, which defines BLISS_CLOUDS_FULL) gets the lighting and the march.
#ifndef BLISS_CLOUDS_FULL
#define CLOUDSHADOWSONLY
#endif
#moj_import <blockland:bliss_clouds.glsl>

// Sunlight let through the clouds to a camera-relative position on a surface (Bliss' GetCloudShadow). 1 without clouds.
float bl_cloudShadow(vec3 pos) {
    // BlockLand: no clouds this frame, or the light on the horizon (Bliss divides by its height)
    if (BlCloudShape.z <= 0.0 || BlSunDir.y <= 0.001) {
        return 1.0;
    }
    return GetCloudShadow(pos);
}

// The same for a point of the air (Bliss' GetCloudShadow_VLFOG, what its volumetric fog takes).
float bl_cloudShadowFog(vec3 pos) {
    if (BlCloudShape.z <= 0.0 || BlSunDir.y <= 0.001) {
        return 1.0;
    }
    return GetCloudShadow_VLFOG(pos + BlClouds.xyz, BlSunDir.xyz);
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

// Light i's cone at offset d (from the point to the light), squared distance dist2: for a spot, soft from the inner to
// the outer cone, and it gives the spot a long throw (falloff: a focused beam does not spread like a bulb); a point
// light gets 1 and keeps its falloff.
#define BL_SPOT_THROW 0.05
float bl_spot(int i, vec3 d, float dist2, float window, inout float falloff) {
    vec4 cone = BlLights[i * 3 + 2];
    if (cone.w < -1.5) {
        return 1.0;
    }
    float c = dot(-d * inversesqrt(max(dist2, 1e-4)), cone.xyz);
    falloff = window * window / (1.0 + BL_SPOT_THROW * dist2);
    return smoothstep(cone.w, BlLights[i * 3 + 1].w, c);
}

// Sum of the dynamic lights (point lights and spots) at a camera-relative position (linear light). Half-Lambert wrap:
// blocky surfaces next to a light read better lit around their edges.
vec3 bl_pointLights(vec3 pos, vec3 normal) {
    vec3 sum = vec3(0.0);
    int count = int(BlFlags.z + 0.5);
    for (int i = 0; i < BL_MAX_LIGHTS; i++) {
        if (i >= count) {
            break;
        }
        vec4 light = BlLights[i * 3];
        vec3 d = light.xyz - pos;
        float dist2 = dot(d, d);
        float r2 = light.w * light.w;
        if (dist2 >= r2) {
            continue;
        }
        float window = 1.0 - dist2 / r2;
        float falloff = window * window / (1.0 + dist2);
        float cone = bl_spot(i, d, dist2, window, falloff);
        if (cone <= 0.0) {
            continue;
        }
        float wrap = clamp(dot(normal, d * inversesqrt(max(dist2, 1e-4))) * 0.5 + 0.5, 0.0, 1.0);
        sum += BlLights[i * 3 + 1].rgb * (falloff * wrap * cone);
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
