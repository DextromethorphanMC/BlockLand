#version 150

// BlockLand sun shadow map: alpha-tested casters (leaves, glass panes' frames, plants, entity skins' outer layers).

layout(std140) uniform ShadowCascade {
    mat4 ShadowProj;
    vec4 ShadowTile;    // xy: the tile's centre in the atlas (NDC), z: alpha cut-off, w: unused
};

uniform sampler2D Sampler0;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    if (texture(Sampler0, texCoord0).a < ShadowTile.z) {
        discard;
    }
    fragColor = vec4(1.0);
}
