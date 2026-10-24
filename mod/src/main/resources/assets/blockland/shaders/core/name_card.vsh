#version 330

// BlockLand name card effects (client/profile/NameCardRenderer): one quad over the card, UV0 0..1 across it, the vertex
// colour carrying the clock, the effect and the card's frame count (see name_card.fsh).

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 cardUv;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    cardUv = UV0;
    vertexColor = Color;
}
