#version 150
#extension GL_ARB_gpu_shader5 : require

// BlockLand sun shadow map: a BlockLand model's triangles from its own buffers (client/graphics/shadow/EntityCasters,
// SHADOW_GPU_CASTERS, rendering spec §20). Every vertex lands exactly where the CPU path (client/render/VanillaDraws)
// puts it: JOML's Matrix4f.transformPosition without fused multiply-adds, x = m00*x + (m10*y + (m20*z + m30)), each
// product and sum rounded on its own — `precise` keeps the compiler from fusing or reordering them — and the texture
// coordinates are scaled the same way; from there on it is shadow_entity.vsh.

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform ShadowCascade {
    mat4 ShadowProj;
    vec4 ShadowTile;    // xy: the tile's centre in the atlas (NDC), z: alpha cut-off, w: unused
};

in vec3 Position;
in vec2 UV0;

out vec2 texCoord0;

void main() {
    // ModelViewMat: the draw's placing (camera-relative world); ModelOffset.x: its texture coordinate scale
    precise float x = ModelViewMat[0][0] * Position.x + (ModelViewMat[1][0] * Position.y + (ModelViewMat[2][0] * Position.z + ModelViewMat[3][0]));
    precise float y = ModelViewMat[0][1] * Position.x + (ModelViewMat[1][1] * Position.y + (ModelViewMat[2][1] * Position.z + ModelViewMat[3][1]));
    precise float z = ModelViewMat[0][2] * Position.x + (ModelViewMat[1][2] * Position.y + (ModelViewMat[2][2] * Position.z + ModelViewMat[3][2]));
    vec3 placed = vec3(x, y, z);
    gl_Position = ShadowProj * vec4(placed, 1.0);
    precise vec2 uv = UV0 * ModelOffset.x;
    texCoord0 = uv;
}
