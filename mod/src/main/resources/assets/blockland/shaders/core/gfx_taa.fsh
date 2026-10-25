#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand temporal anti-aliasing and upscaling (client/graphics/upscale/TemporalUpscaler), drawn at the output
// resolution. The current frame (internal resolution, jittered) is resampled at this output pixel with a Gaussian over
// its 3x3 nearest texels; the history (last output) is found by reprojecting the nearest world depth through the camera's
// motion, sampled with Catmull-Rom and clipped to the current neighbourhood's colour box (YCoCg, mean +- gamma sigma) in
// a tone-mapped space (bright pixels cannot dominate). Hand / viewmodel pixels move with the camera plus their own motion
// (sway, recoil, reloads: client/render/HandMotion), and a pixel switching between hand and world drops most of its
// history (no gun trails over the world).
//   Matrix    = current unjittered NDC -> previous clip (camera-relative, translation of the camera included)
//   Params[0] = (input size, 1 / input size)
//   Params[1] = (output size, 1 / output size)
//   Params[2] = (jitter in uv xy, history valid, base blend factor of the current frame)
// HAND_MOTION_IN_BRANCH (POST_DEAD_WORK): the hand motion is fetched only for hand pixels, the only ones that use it
// (an explicit level 0: the motion texture has one level and the same filter for both directions, as texture() samples it).

uniform sampler2D CurrentSampler;
uniform sampler2D HistorySampler;
uniform sampler2D WorldDepthSampler;
uniform sampler2D HandVelocitySampler;

in vec2 texCoord;

out vec4 fragColor;

vec3 toYCoCg(vec3 c) {
    return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)));
}

vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Karis' invertible tone map for resolving HDR without fireflies.
vec3 squash(vec3 c) {
    return c / (1.0 + max(c.r, max(c.g, c.b)));
}

vec3 unsquash(vec3 c) {
    return c / max(1.0 - max(c.r, max(c.g, c.b)), 1e-4);
}

vec4 historyCatmullRom(vec2 uv) {
    vec2 size = Params[1].xy;
    vec2 inv = Params[1].zw;
    vec2 samplePos = uv * size;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;
    vec2 texPos0 = (texPos1 - 1.0) * inv;
    vec2 texPos3 = (texPos1 + 2.0) * inv;
    vec2 texPos12 = (texPos1 + offset12) * inv;
    vec4 result = texture(HistorySampler, vec2(texPos12.x, texPos0.y)) * (w12.x * w0.y)
            + texture(HistorySampler, vec2(texPos0.x, texPos12.y)) * (w0.x * w12.y)
            + texture(HistorySampler, vec2(texPos12.x, texPos12.y)) * (w12.x * w12.y)
            + texture(HistorySampler, vec2(texPos3.x, texPos12.y)) * (w3.x * w12.y)
            + texture(HistorySampler, vec2(texPos12.x, texPos3.y)) * (w12.x * w3.y);
    float weight = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(result / weight, vec4(0.0));
}

vec3 clipToBox(vec3 lo, vec3 hi, vec3 history) {
    vec3 center = 0.5 * (hi + lo);
    vec3 extent = 0.5 * (hi - lo) + 1e-4;
    vec3 v = history - center;
    vec3 a = abs(v / extent);
    float m = max(a.x, max(a.y, a.z));
    return m > 1.0 ? center + v / m : history;
}

void main() {
    vec2 inSize = Params[0].xy;
    vec2 inInv = Params[0].zw;
    vec2 jitter = Params[2].xy;
    // this output pixel's centre in the jittered input, in texel units
    vec2 p = (texCoord + jitter) * inSize - 0.5;
    vec2 nearest = floor(p + 0.5);
    ivec2 maxTexel = ivec2(inSize) - 1;

    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    float weightMax = 0.0;
    vec3 m1 = vec3(0.0);
    vec3 m2 = vec3(0.0);
    float hand = 0.0;
    float closest = 1.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 t = nearest + vec2(float(x), float(y));
            ivec2 texel = clamp(ivec2(t), ivec2(0), maxTexel);
            vec4 s = texelFetch(CurrentSampler, texel, 0);
            vec3 c = toYCoCg(squash(s.rgb));
            vec2 d = t - p;
            float w = exp(-2.29 * dot(d, d));
            sum += c * w;
            weightSum += w;
            weightMax = max(weightMax, w);
            m1 += c;
            m2 += c * c;
            if (x == 0 && y == 0) hand = s.a;
            closest = min(closest, texelFetch(WorldDepthSampler, texel, 0).r);
        }
    }
    vec3 current = sum / max(weightSum, 1e-5);
    vec3 mean = m1 / 9.0;
    vec3 sigma = sqrt(max(m2 / 9.0 - mean * mean, vec3(0.0)));
    const float gamma = 1.25;
    vec3 lo = mean - gamma * sigma;
    vec3 hi = mean + gamma * sigma;

#ifdef HAND_MOTION_IN_BRANCH
    vec2 previous;
    if (hand < 0.5) {
        vec4 clip = Matrix * vec4(texCoord * 2.0 - 1.0, closest * 2.0 - 1.0, 1.0);
        previous = clip.w > 1e-5 ? clip.xy / clip.w * 0.5 + 0.5 : vec2(-1.0);
    } else {
        previous = texCoord - textureLod(HandVelocitySampler, texCoord + jitter, 0.0).rg;
    }
#else
    vec2 previous = texCoord - texture(HandVelocitySampler, texCoord + jitter).rg;
    if (hand < 0.5) {
        vec4 clip = Matrix * vec4(texCoord * 2.0 - 1.0, closest * 2.0 - 1.0, 1.0);
        previous = clip.w > 1e-5 ? clip.xy / clip.w * 0.5 + 0.5 : vec2(-1.0);
    }
#endif
    bool valid = Params[2].z > 0.5 && previous.x >= 0.0 && previous.y >= 0.0 && previous.x <= 1.0 && previous.y <= 1.0;
    if (!valid) {
        fragColor = vec4(unsquash(fromYCoCg(current)), hand);
        return;
    }
    vec4 h = historyCatmullRom(previous);
    vec3 history = clipToBox(lo, hi, toYCoCg(squash(h.rgb)));
    // the current frame counts more where one of its samples lies right on this output pixel (upscaling)
    float alpha = Params[2].w * mix(0.4, 1.0, weightMax);
    if (abs(h.a - hand) > 0.5) alpha = max(alpha, 0.65);
    vec3 result = mix(history, current, clamp(alpha, 0.0, 1.0));
    fragColor = vec4(unsquash(fromYCoCg(result)), hand);
}
