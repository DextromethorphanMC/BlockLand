#version 150

// BlockLand viewmodel motion: uv per frame, current - previous (see gun_velocity.vsh).

in vec4 currentClip;
in vec4 previousClip;

out vec4 fragColor;

void main() {
    vec2 velocity = (currentClip.xy / currentClip.w - previousClip.xy / previousClip.w) * 0.5;
    fragColor = vec4(velocity, 0.0, 1.0);
}
