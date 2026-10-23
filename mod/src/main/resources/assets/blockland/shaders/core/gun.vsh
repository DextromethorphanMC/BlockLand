#version 150

// BlockLand retained-mode model shader (guns, knives, arms, attachments, effects): PBR with LabPBR texture sets
// (asset spec §20) and the skin effects of §21 / §30. Per-draw parameters ride in Minecraft's DynamicTransforms block,
// so no extra uniform buffers are needed (packed by DrawList.add):
//   ModelViewMat   model -> view space
//   ColorModulator display colour (rgb, a skin's tint applied) and alpha
//   ModelOffset    xy = lightmap texel (block, sky light 0..15)
//                  z  = flags: 1 fullbright (emissive parts, effects), 2 has a _n map, 4 has a _s map,
//                       8 premultiply alpha (additive blending ignores it)
//   TextureMat     [0] = (metallic, perceptual roughness, alpha cutoff, uv scale)
//                  [1] = (mesh origin in the model's rest pose xyz - an attachment's plus its mount's, so effects
//                        run in the weapon's space - and the time in seconds)
//                  [2] = effect params
//                  [3] = (effect colour rgb, effect: 0 none, 1 iridescent, 2 flow, 3 galaxy, 4 circuit)

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
// xyz = normal (0..1 = -1..1), w = baked ambient occlusion
in vec4 Normal;

uniform sampler2D Sampler2;

out vec3 viewPos;
out vec3 viewNormal;
out vec2 texCoord0;
out vec3 objectPos;
out float vertexAo;
out vec4 lightColor;

void main() {
    vec4 pos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * pos;
    viewPos = pos.xyz;
    viewNormal = mat3(ModelViewMat) * (Normal.xyz * 2.0 - 1.0);
    texCoord0 = UV0 * TextureMat[0][3];
    objectPos = Position + TextureMat[1].xyz;
    vertexAo = Normal.w;
    lightColor = texelFetch(Sampler2, ivec2(clamp(ModelOffset.xy, 0.0, 15.0)), 0);
}
