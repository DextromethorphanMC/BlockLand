#version 150

// BlockLand screen effects (client/equipment/effect/ScreenPasses): a full-screen quad, Position 0..1.

in vec3 Position;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
    texCoord = Position.xy;
}
