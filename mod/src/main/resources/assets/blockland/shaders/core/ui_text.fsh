#version 330

// BlockLand UI text: glyphs are signed distance fields (FreeType, 0.5 on the outline, larger inside), so text stays
// crisp at any GUI scale, position and animated size. The edge is anti-aliased over one screen pixel, measured with
// fwidth; small text is thickened a little, as anti-aliased strokes read thinner than they are.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const float EDGE = 128.0 / 255.0;

void main() {
    float distance = texture(Sampler0, texCoord0).r;
    float width = clamp(fwidth(distance) * 0.72, 0.004, 0.3);
    float edge = EDGE - min(width * 0.3, 0.04);
    float coverage = smoothstep(edge - width, edge + width, distance);
    float alpha = vertexColor.a * coverage;
    if (alpha < 0.004) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, alpha) * ColorModulator;
}
