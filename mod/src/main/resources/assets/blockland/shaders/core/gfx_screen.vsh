#version 150

// BlockLand full-screen passes (client/graphics/gpu/Fullscreen): a quad over the whole target, Position 0..1.

in vec3 Position;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
    texCoord = Position.xy;
}
