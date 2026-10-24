#version 150

// BlockLand volumetric light (half resolution): the ray from the eye to the scene (or to the shadow distance through the
// sky) is marched through the sun's shadow map; lit stretches of air scatter sunlight towards the eye (Henyey-Greenstein
// phase with a strong forward lobe), shadowed ones do not - shafts through trees, windows and over ridges. The haze
// thickens in the morning and evening, in rain and under water, and thins with height. The start of the march is
// jittered per pixel and per frame (the blur and temporal anti-aliasing hide the steps); the result is linear light
// that the post chain adds before tone mapping.

#moj_import <blockland:world_fx.glsl>
#moj_import <blockland:scene.glsl>

layout(std140) uniform VolumetricInfo {
    mat4 ViewToWorld;       // view -> camera-relative world (rotation)
    mat4 Cascade[4];        // camera-relative world -> cascade-local [0,1]^3
    vec4 Splits;            // cascade reach (blocks)
    vec4 SunDirection;      // xyz towards the light, w shadow strength
    vec4 SunFallback;       // rgb light colour when the scene uniforms carry none, a frame index
    vec4 Fog;               // x density per block, y max distance, z steps, w camera world y
    vec4 Extra;             // x under water, y cascades, z intensity, w forward anisotropy g
};

in vec2 texCoord;

out vec4 fragColor;

const int MAX_STEPS = 32;

float lightAt(vec3 pos) {
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

float phase(float cosTheta, float g) {
    float g2 = g * g;
    float hg = (1.0 - g2) / (12.5663706 * pow(max(1.0 + g2 - 2.0 * g * cosTheta, 1e-4), 1.5));
    return mix(hg, 0.0795775, 0.3);
}

void main() {
    float depth = fx_depth(texCoord);
    vec3 view = fx_viewPosition(texCoord, min(depth, 0.999999));
    vec3 pos = mat3(ViewToWorld) * view;
    float sceneDist = depth >= 1.0 ? 1e6 : length(pos);
    vec3 dir = normalize(pos);
    float maxDist = min(sceneDist, Fog.y);
    int steps = int(Fog.z + 0.5);
    float stepLength = maxDist / float(steps);
    float jitter = fx_noise(gl_FragCoord.xy, SunFallback.a);

    float underwater = Extra.x;
    float transmittance = 1.0;
    float scattered = 0.0;
    for (int i = 0; i < MAX_STEPS; i++) {
        if (i >= steps) {
            break;
        }
        float t = (float(i) + jitter) * stepLength;
        vec3 p = dir * t;
        // haze is densest near the ground and sea level and thins higher up (not under water)
        float height = Fog.w + p.y;
        float density = Fog.x * mix(exp(-max(height - 62.0, 0.0) / 72.0), 1.0, underwater);
        scattered += transmittance * density * stepLength * lightAt(p);
        transmittance *= exp(-density * stepLength);
    }
    vec3 sun = bl_worldLit() ? BlSunColor.rgb : SunFallback.rgb;
    vec3 tint = mix(vec3(1.0), vec3(0.35, 0.75, 1.0), underwater);
    float cosTheta = dot(dir, SunDirection.xyz);
    vec3 light = sun * tint * (scattered * phase(cosTheta, Extra.w) * Extra.z * SunDirection.w);
    fragColor = vec4(light, 1.0);
}
