#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's entity vertex shader plus the inputs of the lit
// path. Every vanilla output is computed exactly as vanilla does.

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
// lit path: camera-relative position and normal (world phase only), the lightmap with block light only
out vec3 worldPos;
out vec3 worldNormal;
out vec4 blockLightColor;
// sky light level 0..1 (how much rain reaches the entity)
out float skyExposure;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
#ifdef NO_CARDINAL_LIGHTING
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif
#ifndef EMISSIVE
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    blockLightColor = texelFetch(Sampler2, ivec2(UV2.x / 16, 0), 0);
#else
    lightMapColor = vec4(1.0);
    blockLightColor = vec4(1.0);
#endif
    overlayColor = texelFetch(Sampler1, UV1, 0);

    texCoord0 = UV0;
#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
    worldPos = Position;
    worldNormal = Normal;
    skyExposure = clamp(float(UV2.y) / 240.0, 0.0, 1.0);
}
