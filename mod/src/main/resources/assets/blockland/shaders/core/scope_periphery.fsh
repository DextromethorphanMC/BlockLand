#version 150

// BlockLand aim focus (com.blockland.client.scope.AimFocus): lays the blurred world over the sharp one around the focus
// circle (blended: TRANSLUCENT). Nothing inside the circle; outside, the blur grows from none at its edge to the
// strongest at the farthest screen corner, light blur first, then the heavy one. With the setting it also darkens.
// Result = (1 - dim) * mix(sharp, blurred, cover), as one blend: alpha a = 1 - (1 - dim)(1 - cover),
// colour = (1 - dim) * cover * blurred / a.

layout(std140) uniform BlurInfo {
    vec4 Dir;     // unused here
    vec4 Window;  // xy: focus centre (normalised device coordinates), z: its radius (half screen heights), w: aspect
    vec4 Amount;  // x: blur 0..1 (how far towards the heavy blur the corners go), y: darkening 0..1
};

uniform sampler2D InSampler;    // light blur (a quarter of the screen)
uniform sampler2D HeavySampler; // heavy blur (an eighth of the screen)

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec2 offset = vec2((ndc.x - Window.x) * Window.w, ndc.y - Window.y);
    float dist = length(offset);
    float radius = max(Window.z, 1e-3);
    // the farthest screen corner from the focus centre (half screen heights)
    vec2 far = vec2(Window.w + abs(Window.x) * Window.w, 1.0 + abs(Window.y));
    float t = clamp((dist - radius) / max(length(far) - radius, 1e-3), 0.0, 1.0);
    float k = Amount.x * t;
    float cover = smoothstep(0.0, 0.3, k);
    vec3 blurred = mix(texture(InSampler, texCoord).rgb, texture(HeavySampler, texCoord).rgb, smoothstep(0.3, 1.0, k));
    float dim = Amount.y * smoothstep(radius * 1.05, radius * 2.2, dist);
    float alpha = 1.0 - (1.0 - dim) * (1.0 - cover);
    vec3 color = alpha > 1e-4 ? (1.0 - dim) * cover * blurred / alpha : vec3(0.0);
    fragColor = vec4(color, alpha);
}
