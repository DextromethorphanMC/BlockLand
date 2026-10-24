#version 150

// BlockLand sun shadow map: clears one cascade's tile (the quad covers the tile, Position 0..1).

layout(std140) uniform ShadowCascade {
    mat4 ShadowProj;
    vec4 ShadowTile;    // xy: the tile's centre in the atlas (NDC), z: alpha cut-off, w: unused
};

in vec3 Position;

void main() {
    gl_Position = vec4(ShadowTile.xy + Position.xy - 0.5, 0.0, 1.0);
}
