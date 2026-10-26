#version 150

// The sun and the moon (client/graphics/sky/CelestialBodies, tools/assets/RENDERING_SPEC.md §14): one quad each, uv −1..1
// across it (the moon's shifted by 4 on x), its parameters in the vertex colour.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
