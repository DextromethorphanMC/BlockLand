#version 150

#moj_import <blockland:gfx_screen.glsl>

// BlockLand motion blur, pass 4 (client/graphics/post/MotionBlur): McGuire et al. 2012's reconstruction filter with
// Jimenez 2014's refinements. Taps run along the longest blur of the pixel's neighbourhood (every other tap along the
// pixel's own blur direction, so pixels moving differently from their neighbourhood still smear their own way). Each
// tap counts when it lies in front and its own blur reaches the pixel, or lies behind and the pixel's blur reaches it,
// or both blur alike — so what moves smears over what is behind it, the first-person weapon over the world and never the
// world into the weapon, and silhouettes keep no halo.
//   Params[0] = (output size, 1 / output size)
//   Params[1] = (taps, tile size in pixels, frame index, -)

uniform sampler2D ColorSampler;
uniform sampler2D VelocitySampler;
uniform sampler2D NeighborSampler;

in vec2 texCoord;

out vec4 fragColor;

// 1 where b is in front of (or level with) a, fading out over a depth difference that grows with the distance.
float inFront(float a, float b) {
    float soft = 0.05 + 0.02 * min(a, b);
    return clamp(1.0 - (b - a) / soft, 0.0, 1.0);
}

float cone(float distance, float blur) {
    return clamp(1.0 - distance / max(blur, 1e-3), 0.0, 1.0);
}

float cylinder(float distance, float blur) {
    return 1.0 - smoothstep(0.95 * blur, 1.05 * blur, distance);
}

void main() {
    vec2 size = Params[0].xy;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 center = texture(ColorSampler, texCoord);
    vec2 neighbourhood = texelFetch(NeighborSampler, pixel / int(Params[1].y), 0).rg;
    float reach = length(neighbourhood);
    if (reach < 0.5) {
        fragColor = center;
        return;
    }
    vec4 own = texelFetch(VelocitySampler, pixel, 0);
    float ownBlur = max(length(own.rg), 0.5);
    float depth = own.b;
    vec2 ownDirection = length(own.rg) > 0.5 ? own.rg / length(own.rg) * reach : neighbourhood;

    int taps = int(Params[1].x);
    float noise = gfx_noise(gl_FragCoord.xy + Params[1].z * vec2(7.13, 3.71)) - 0.5;
    float weight = 1.0 / ownBlur;
    vec3 sum = center.rgb * weight;
    for (int i = 0; i < 24; i++) {
        if (i >= taps) break;
        float t = mix(-1.0, 1.0, (float(i) + 0.5 + noise) / float(taps));
        vec2 along = (i & 1) == 0 ? neighbourhood : ownDirection;
        vec2 offset = along * t;
        ivec2 at = clamp(pixel + ivec2(round(offset)), ivec2(0), ivec2(size) - 1);
        vec4 tap = texelFetch(VelocitySampler, at, 0);
        float tapBlur = length(tap.rg);
        float distance = length(offset);
        float front = inFront(depth, tap.b);
        float back = inFront(tap.b, depth);
        float w = front * cone(distance, tapBlur) + back * cone(distance, ownBlur)
                + cylinder(distance, tapBlur) * cylinder(distance, ownBlur) * 2.0;
        weight += w;
        sum += texelFetch(ColorSampler, at, 0).rgb * w;
    }
    fragColor = vec4(sum / weight, center.a);
}
