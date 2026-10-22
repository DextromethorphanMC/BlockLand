#version 150

// BlockLand retained-mode model shader (guns, arms, effects).
// Per-draw parameters ride in Minecraft's DynamicTransforms block so no extra uniform buffers are needed:
//   ModelViewMat   model -> view space
//   ColorModulator material colour (rgb) and alpha, already multiplied by the skin tint
//   ModelOffset    xy = lightmap texel (block, sky light 0..15), z = 1 for emissive/fullbright
//   TextureMat     [0] = (specular, shininess, alpha cutoff, metallic)

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec3 Normal;

uniform sampler2D Sampler2;

out vec3 viewPos;
out vec3 viewNormal;
out vec2 texCoord0;
out vec4 lightColor;

void main() {
    vec4 pos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * pos;
    viewPos = pos.xyz;
    viewNormal = mat3(ModelViewMat) * Normal;
    texCoord0 = UV0;
    lightColor = texelFetch(Sampler2, ivec2(clamp(ModelOffset.xy, 0.0, 15.0)), 0);
}
