#version 150

// BlockLand sun shadow map: entities captured in camera-relative world space (client/graphics/shadow/EntityCasters).

layout(std140) uniform ShadowCascade {
    mat4 ShadowProj;
    vec4 ShadowTile;    // xy: the tile's centre in the atlas (NDC), z: alpha cut-off, w: unused
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec2 texCoord0;

void main() {
    gl_Position = ShadowProj * vec4(Position, 1.0);
    texCoord0 = UV0;
}
