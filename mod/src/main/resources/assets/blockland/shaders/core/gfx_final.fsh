#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand post chain, last step (client/graphics/post/PostChain): HDR -> the window's picture. Contrast-adaptive
// sharpening (AMD CAS, on the HDR colour with weights from a tone-mapped copy), chromatic aberration, bloom, exposure,
// white balance, tone mapping, saturation / contrast, vignette, film grain and a tiny dither against banding.
// VANILLA tone mapping is the identity up to a knee and only rolls off what lies above it, so Minecraft's own colours
// come out as they went in.
//   Params[0] = (output texel size xy, sharpness 0..1, chromatic aberration 0..1)
//   Params[1] = (bloom strength, auto exposure on, manual exposure multiplier, tone map: 0 vanilla, 1 filmic, 2 ACES)
//   Params[2] = (saturation, contrast, temperature -1..1, vignette 0..1)
//   Params[3] = (film grain 0..1, frame seed, alpha = luma for FXAA (0/1), aspect)
//   Params[4] = (auto exposure strength, lowest EV, highest EV, key value)
// HUD_COVERAGE (POST_DEAD_WORK, Frame Generation capturing the HUD without FXAA): alpha 0, the coverage the HUD then adds
// to (otherwise cleared by gfx_alpha_clear right after); the colour is the same.

uniform sampler2D ColorSampler;
uniform sampler2D BloomSampler;
uniform sampler2D ExposureSampler;

in vec2 texCoord;

out vec4 fragColor;

float squashedGreen(vec3 c) {
    return c.g / (1.0 + max(c.r, max(c.g, c.b)));
}

vec3 sharpened(vec2 uv) {
    vec3 c = texture(ColorSampler, uv).rgb;
    float sharpness = Params[0].z;
    if (sharpness <= 0.001) {
        return c;
    }
    vec2 t = Params[0].xy;
    vec3 a = texture(ColorSampler, uv + vec2(0.0, -t.y)).rgb;
    vec3 b = texture(ColorSampler, uv + vec2(-t.x, 0.0)).rgb;
    vec3 d = texture(ColorSampler, uv + vec2(t.x, 0.0)).rgb;
    vec3 e = texture(ColorSampler, uv + vec2(0.0, t.y)).rgb;
    float ga = squashedGreen(a), gb = squashedGreen(b), gc = squashedGreen(c), gd = squashedGreen(d), ge = squashedGreen(e);
    float lo = min(gc, min(min(ga, gb), min(gd, ge)));
    float hi = max(gc, max(max(ga, gb), max(gd, ge)));
    float amp = sqrt(clamp(min(lo, 1.0 - hi) / max(hi, 1e-4), 0.0, 1.0));
    float w = -amp / mix(8.0, 5.0, sharpness);
    return max((c + (a + b + d + e) * w) / (1.0 + 4.0 * w), vec3(0.0));
}

// identity up to the knee, then a soft shoulder towards `white` (Minecraft's own white comes out at 0.97 linear, 0.99
// on screen); light beyond is carried by the bloom
vec3 tonemapVanilla(vec3 x) {
    const float knee = 0.85;
    const float white = 1.2;
    vec3 over = max(x - knee, vec3(0.0));
    return min(x, vec3(knee)) + (white - knee) * (1.0 - exp(-over / (white - knee)));
}

vec3 hable(vec3 x) {
    const float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

vec3 tonemapFilmic(vec3 x) {
    const float white = 11.2;
    vec3 curve = hable(x * 2.0);
    vec3 scale = vec3(1.0) / hable(vec3(white));
    return curve * scale;
}

vec3 tonemapAces(vec3 x) {
    x *= 0.8;
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec3 color = sharpened(texCoord);
    float aberration = Params[0].w;
    if (aberration > 0.001) {
        vec2 offset = (texCoord - 0.5) * aberration * 0.008;
        color.r = texture(ColorSampler, texCoord + offset).r;
        color.b = texture(ColorSampler, texCoord - offset).b;
    }
    color += texture(BloomSampler, texCoord).rgb * Params[1].x;

    float exposure = Params[1].z;
    if (Params[1].y > 0.5) {
        float average = exp2(texture(ExposureSampler, vec2(0.5)).r);
        float ev = clamp(log2(Params[4].w / max(average, 1e-4)) * Params[4].x, Params[4].y, Params[4].z);
        exposure *= exp2(ev);
    }
    color *= exposure;

    float temperature = Params[2].z;
    color *= vec3(1.0 + 0.10 * temperature, 1.0 + 0.01 * temperature, 1.0 - 0.10 * temperature);

    int mode = int(Params[1].w + 0.5);
    if (mode == 1) {
        color = tonemapFilmic(color);
    } else if (mode == 2) {
        color = tonemapAces(color);
    } else {
        color = tonemapVanilla(color);
    }

    // contrast around middle grey, saturation around the luminance
    float contrast = Params[2].y;
    if (abs(contrast - 1.0) > 0.001) {
        color = 0.18 * pow(max(color, vec3(0.0)) / 0.18, vec3(contrast));
    }
    float luma = gfx_luminance(color);
    color = max(mix(vec3(luma), color, Params[2].x), vec3(0.0));

    vec3 display = gfx_display(min(color, vec3(1.0)));

    float vignette = Params[2].w;
    if (vignette > 0.001) {
        vec2 v = (texCoord - 0.5) * vec2(Params[3].w, 1.0);
        float r = length(v) / length(vec2(Params[3].w, 1.0) * 0.5);
        display *= 1.0 - vignette * 0.6 * smoothstep(0.45, 1.15, r);
    }
    float grain = Params[3].x;
    if (grain > 0.001) {
        float n = hash12(gl_FragCoord.xy + vec2(Params[3].y * 37.0, Params[3].y * 91.0)) - 0.5;
        display += n * grain * 0.08 * (1.0 - 0.6 * gfx_luminance(display));
    }
    display += (gfx_noise(gl_FragCoord.xy + Params[3].y) - 0.5) / 255.0;
    display = clamp(display, 0.0, 1.0);
#ifdef HUD_COVERAGE
    fragColor = vec4(display, 0.0);
#else
    fragColor = vec4(display, Params[3].z > 0.5 ? gfx_luminance(display) : 1.0);
#endif
}
