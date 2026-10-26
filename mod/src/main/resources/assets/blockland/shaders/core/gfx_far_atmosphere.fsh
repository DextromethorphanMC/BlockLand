#version 150

#moj_import <blockland:gfx_screen.glsl>

// The distant atmosphere (client/graphics/post/FarAtmosphere, tools/assets/RENDERING_SPEC.md §24): how far land looks to
// the eye, on the decoded linear-HDR picture at the internal resolution, by each pixel's distance from the camera — the
// world depth, or the far terrain's depth where the world's is 1. In order: heat shimmer picks where the pixel is read
// (a fine, rising wobble over far ground near the horizon), a depth-aware blur softens far detail (only taps at least as
// far as the pixel: nothing nearer and no sky bleeds in), and aerial perspective lays the air in front of it (more blue
// scattered than red: Rayleigh). The sky, near things, the hand and the viewmodel are left as they are.
//   Matrix    = inverse(level projection × view rotation): NDC → camera-relative world
//   Params[0] = (far terrain near plane, far plane, 1 = far depth valid, internal width)
//   Params[1] = (aerial perspective strength, blur strength, shimmer strength × heat, time in seconds)
//   Params[2] = (the horizon's colour, linear rgb, internal height)

uniform sampler2D SceneSampler;
uniform sampler2D WorldDepthSampler;
uniform sampler2D HandDepthSampler;
uniform sampler2D FarDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

const float SKY = 1e9;
// distance (blocks) over which blue light has scattered by 1/e at strength 1; red goes 2.8 times as far
const float AIR_SCALE = 6000.0;
const vec3 RAYLEIGH = vec3(0.36, 0.6, 1.0);

vec3 worldRay(vec2 uv, float ndcZ) {
    vec4 p = Matrix * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    return p.xyz / p.w;
}

// Distance from the camera of what the pixel at uv shows (SKY for the sky).
float distanceAt(vec2 uv) {
    float depth = texture(WorldDepthSampler, uv).r;
    if (depth < 1.0) {
        return length(worldRay(uv, depth * 2.0 - 1.0));
    }
    if (Params[0].z > 0.5) {
        float farDepth = texture(FarDepthSampler, uv).r;
        if (farDepth < 1.0) {
            float near = Params[0].x, far = Params[0].y;
            float z = 2.0 * far * near / ((far + near) - (farDepth * 2.0 - 1.0) * (far - near));
            vec3 dir = normalize(worldRay(uv, 1.0));
            vec3 forward = normalize(worldRay(vec2(0.5), 1.0));
            return z / max(dot(dir, forward), 1e-3);
        }
    }
    return SKY;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec4 center = texture(SceneSampler, texCoord);
    if (texture(HandDepthSampler, texCoord).r < 1.0 || center.a > 0.5) {
        fragColor = center;
        return;
    }
    float dist = distanceAt(texCoord);
    if (dist >= SKY) {
        fragColor = center;
        return;
    }
    vec2 texel = 1.0 / vec2(Params[0].w, Params[2].w);
    vec3 dir = normalize(worldRay(texCoord, 1.0));

    // heat shimmer: over far ground just above and below the horizon line, rising ripples of refraction
    vec2 uv = texCoord;
    float shimmer = Params[1].z * smoothstep(250.0, 2500.0, dist) * (1.0 - smoothstep(0.0, 0.1, abs(dir.y)));
    if (shimmer > 0.001) {
        float t = Params[1].w;
        vec2 px = texCoord / texel;
        float wave = sin(px.y * 0.9 + t * 7.0 + sin(px.x * 0.07 + t * 1.3) * 2.0) + 0.5 * sin(px.y * 2.3 - t * 11.0 + px.x * 0.11);
        vec2 offset = vec2(wave * 0.9, abs(wave) * 0.45) * shimmer * texel;
        // only read from what is as far: the wobble never pulls near things or the sky over the land
        if (distanceAt(uv + offset) >= dist * 0.85 && distanceAt(uv + offset) < SKY) {
            uv += offset;
        }
    }

    // detail loss: a small blur growing from about a kilometre on, of taps at least as far as this pixel
    vec3 color = texture(SceneSampler, uv).rgb;
    float radius = Params[1].y * smoothstep(700.0, 7000.0, dist) * 2.2;
    if (radius > 0.15) {
        const vec2 DISK[8] = vec2[8](vec2(0.88, 0.12), vec2(0.33, 0.83), vec2(-0.42, 0.71), vec2(-0.93, -0.03),
                vec2(-0.47, -0.69), vec2(0.18, -0.92), vec2(0.66, -0.51), vec2(0.05, 0.36));
        float angle = hash(gl_FragCoord.xy) * 6.2831853;
        mat2 turn = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
        vec3 sum = color;
        float weight = 1.0;
        for (int i = 0; i < 8; i++) {
            vec2 tap = uv + turn * DISK[i] * radius * texel;
            float d = distanceAt(tap);
            if (d < SKY && d >= dist * 0.8 && texture(HandDepthSampler, tap).r >= 1.0) {
                sum += texture(SceneSampler, tap).rgb;
                weight += 1.0;
            }
        }
        color = sum / weight;
    }

    // aerial perspective: the air in front scatters the horizon's light in, blue more than red; contrast and colour fade
    float air = Params[1].x * max(dist - 150.0, 0.0) / AIR_SCALE;
    if (air > 0.0) {
        vec3 through = exp(-RAYLEIGH * air);
        float faded = 1.0 - through.g;
        float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
        color = mix(color, vec3(lum), 0.35 * faded);
        color = color * through + Params[2].rgb * (1.0 - through);
    }
    fragColor = vec4(max(color, vec3(0.0)), center.a);
}
