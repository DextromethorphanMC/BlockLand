#version 330

// BlockLand name card effects (client/profile/NameCardRenderer), drawn over the card's own texture (Sampler0, frames
// stacked top to bottom). GUI elements have no uniforms of their own, so the vertex colour carries the parameters:
//   red, green   the clock: 16 bits over 64 seconds
//   blue         low 4 bits the effect (1 aurora, 2 holo, 3 neon, 4 inferno), high 4 bits the frame count - 1
//   alpha        opacity
// The output is blended over the card (translucent); the card's art stays visible underneath.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 cardUv;
in vec4 vertexColor;

out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i), b = hash(i + vec2(1.0, 0.0)), c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float sum = 0.0, amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * noise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        amp *= 0.5;
    }
    return sum;
}

vec3 hue(float h) {
    return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
}

// The card's own texel (its first frame), for effects that follow the art.
vec3 art(vec2 uv, float frames) {
    return texture(Sampler0, vec2(uv.x, uv.y / frames)).rgb;
}

// Curtains of light waving over the night sky, strongest high up, fading towards the hills.
vec4 aurora(vec2 uv, float t) {
    vec2 aspect = vec2(uv.x * 3.2, uv.y);
    float curtain = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float wave = 0.32 + 0.13 * sin(aspect.x * (1.1 + fi * 0.37) + t * (0.35 + fi * 0.11) + fi * 2.1)
                + 0.06 * fbm(vec2(aspect.x * 1.6 + t * 0.12, fi * 3.0));
        float band = exp(-pow((uv.y - wave) / (0.09 + 0.03 * fi), 2.0));
        float rays = 0.55 + 0.45 * fbm(vec2(aspect.x * 7.0 + fi * 11.0, t * 0.4));
        curtain += band * rays * (0.9 - fi * 0.22);
    }
    float sky = smoothstep(0.82, 0.55, uv.y);
    vec3 color = mix(vec3(0.15, 1.0, 0.55), vec3(0.45, 0.35, 1.0), smoothstep(0.15, 0.55, uv.y + 0.08 * sin(t * 0.3 + uv.x * 4.0)));
    float a = clamp(curtain * sky, 0.0, 1.0) * 0.85;
    return vec4(color, a);
}

// An iridescent foil: rainbow bands shifting with the angle of a light sweeping across, plus glints.
vec4 holo(vec2 uv, float t) {
    float sweep = fract(t * 0.18);
    float d = uv.x + uv.y * 0.35 - (sweep * 1.8 - 0.4);
    float sheen = exp(-d * d * 40.0);
    vec3 rainbow = hue(uv.x * 0.9 + uv.y * 0.6 + t * 0.07 + fbm(uv * vec2(6.0, 2.0)) * 0.4);
    float glint = step(0.985, hash(floor(uv * vec2(120.0, 38.0)) + floor(t * 6.0))) * (0.5 + 0.5 * sin(t * 9.0));
    vec3 color = rainbow * 0.9 + vec3(1.0) * (sheen * 0.8 + glint);
    float a = 0.28 + sheen * 0.45 + glint * 0.6;
    return vec4(color, clamp(a, 0.0, 1.0));
}

// A neon city: windows flicker in shifting colours, a scan line of light passes over the skyline.
vec4 neon(vec2 uv, float t, float frames) {
    vec3 base = art(uv, frames);
    float window = smoothstep(0.18, 0.3, max(base.r, base.b));
    vec2 cell = floor(uv * vec2(160.0, 50.0));
    float flicker = step(0.35, hash(cell + floor(t * 2.0 + hash(cell) * 4.0)));
    vec3 tint = hue(hash(cell * 0.37) * 0.25 + 0.78 + 0.1 * sin(t * 0.5));
    float scan = exp(-pow((uv.x - fract(t * 0.11) * 1.3 + 0.15) * 7.0, 2.0)) * smoothstep(1.0, 0.3, uv.y);
    float sky = smoothstep(0.75, 0.0, uv.y) * 0.25 * (0.6 + 0.4 * sin(t * 0.7 + uv.x * 3.0));
    vec3 color = tint * window * flicker * 1.4 + vec3(1.0, 0.25, 0.9) * scan + vec3(0.3, 0.1, 0.6) * sky;
    float a = clamp(window * flicker * 0.9 + scan * 0.55 + sky, 0.0, 1.0);
    return vec4(color, a);
}

// Flames rolling up from the bottom edge.
vec4 inferno(vec2 uv, float t) {
    vec2 p = vec2(uv.x * 3.2 * 2.2, uv.y * 2.2);
    float n = fbm(p + vec2(0.0, t * 1.4)) * 0.65 + fbm(p * 2.1 + vec2(t * 0.3, t * 2.3)) * 0.35;
    float heat = clamp((uv.y - 0.25) * 1.6 + (n - 0.5) * 1.1, 0.0, 1.0);
    vec3 color = mix(vec3(0.6, 0.05, 0.0), vec3(1.0, 0.45, 0.05), smoothstep(0.2, 0.6, heat));
    color = mix(color, vec3(1.0, 0.9, 0.55), smoothstep(0.65, 1.0, heat));
    float a = smoothstep(0.15, 0.7, heat) * 0.75;
    return vec4(color, a);
}

void main() {
    float t = (vertexColor.r * 255.0 * 256.0 + vertexColor.g * 255.0) / 65535.0 * 64.0;
    int code = int(vertexColor.b * 255.0 + 0.5);
    int effect = code & 15;
    float frames = float((code >> 4) + 1);
    vec4 fx;
    if (effect == 1) {
        fx = aurora(cardUv, t);
    } else if (effect == 2) {
        fx = holo(cardUv, t);
    } else if (effect == 3) {
        fx = neon(cardUv, t, frames);
    } else if (effect == 4) {
        fx = inferno(cardUv, t);
    } else {
        discard;
    }
    float alpha = fx.a * vertexColor.a;
    if (alpha < 0.004) {
        discard;
    }
    fragColor = vec4(fx.rgb, alpha) * ColorModulator;
}
