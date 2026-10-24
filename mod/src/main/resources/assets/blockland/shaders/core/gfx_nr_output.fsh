#version 150

#moj_import <blockland:gfx_screen.glsl>

// DLSS 5 neural rendering result back to linear HDR (rows flipped back, gfx_sdr undone, exposure removed). Highlight
// recovery (RTX Remix's): where the original was bright enough to sit in the SDR shoulder, the original HDR comes back,
// since the model's SDR processing compresses it. The alpha (hand / viewmodel mask) stays the original's.
//   Params[0] = (exposure multiplier, highlight recovery on, -, -)

uniform sampler2D OutSampler;
uniform sampler2D OriginalSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(OutSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 original = texelFetch(OriginalSampler, texel, 0);
    vec3 sdr = texelFetch(OutSampler, ivec2(texel.x, size.y - 1 - texel.y), 0).rgb;
    float exposure = max(Params[0].x, 1e-4);
    vec3 relit = gfx_sdr_inverse(clamp(sdr, 0.0, 1.0)) / exposure;
    if (Params[0].y > 0.5) {
        vec3 exposed = max(original.rgb, vec3(0.0)) * exposure;
        float bright = smoothstep(1.0, 8.0, max(exposed.r, max(exposed.g, exposed.b)));
        float shoulder = smoothstep(0.75, 0.99, max(sdr.r, max(sdr.g, sdr.b)));
        relit = mix(relit, original.rgb, bright * shoulder);
    }
    fragColor = vec4(relit, original.a);
}
