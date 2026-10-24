#version 150

// BlockLand GPU particles (see gun_particle.vsh): a soft round glow with a hot core, or the emitter's sprite;
// premultiplied for additive blending.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 corner;
in vec4 particleColor;

out vec4 fragColor;

void main() {
    if (particleColor.a <= 0.0) {
        discard;
    }
    float shape;
    vec3 color = particleColor.rgb;
    if ((int(TextureMat[3].w + 0.5) & 4) != 0) {
        vec4 sprite = texture(Sampler0, corner * 0.5 + 0.5);
        shape = sprite.a;
        color *= sprite.rgb;
    } else {
        float d2 = dot(corner, corner);
        shape = exp(-d2 * 4.0) + 0.6 * exp(-d2 * 22.0);
        shape *= 1.0 - smoothstep(0.8, 1.0, d2);
    }
    float a = shape * particleColor.a;
    fragColor = vec4(color * a, a);
}
