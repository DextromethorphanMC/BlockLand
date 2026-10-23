#version 150

// BlockLand flashbang (client/equipment/effect/FlashBlind): the white-out over the frame, and the afterimage — the
// picture burnt in at the moment of the flash, frozen on the retina while the view moves — fading out under it,
// slightly blurred and bleached. Blended TRANSLUCENT: alpha a = 1 - (1 - after)(1 - white).

layout(std140) uniform EffectInfo {
    vec4 Amount;  // x: white 0..1, y: afterimage 0..1, z: aspect, w: seconds since the flash
    vec4 Extra;   // unused
};

uniform sampler2D InSampler;  // the afterimage (the frame at the flash)

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float white = Amount.x, after = Amount.y;
    // a soft 5-tap smear: a burnt-in picture is never sharp
    vec2 px = vec2(0.004 / Amount.z, 0.004);
    vec3 pic = texture(InSampler, texCoord).rgb * 0.4
        + (texture(InSampler, texCoord + vec2(px.x, 0.0)).rgb + texture(InSampler, texCoord - vec2(px.x, 0.0)).rgb
         + texture(InSampler, texCoord + vec2(0.0, px.y)).rgb + texture(InSampler, texCoord - vec2(0.0, px.y)).rgb) * 0.15;
    // bleached: bright parts burn in white, the rest goes pale
    float luma = dot(pic, vec3(0.299, 0.587, 0.114));
    pic = mix(vec3(luma), pic, 0.35) * 0.55 + 0.45;
    float alpha = 1.0 - (1.0 - after) * (1.0 - white);
    vec3 color = alpha > 1e-4 ? (pic * after * (1.0 - white) + vec3(1.0) * white) / alpha : vec3(1.0);
    fragColor = vec4(color, alpha);
}
