#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3, §14): vanilla's cloud fragment shader, left out while
// BlockLand's volumetric clouds replace vanilla's (BlBlockLight.a). Unpatched (BlRole 0) or with the flag off, the
// result is exactly vanilla's.

#moj_import <minecraft:fog.glsl>
#moj_import <blockland:scene.glsl>

uniform int BlRole;

in float vertexDistance;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (BlRole != 0 && BlBlockLight.a > 0.5) {
        discard;
    }
    vec4 color = vertexColor;
    color.a *= 1.0f - linear_fog_value(vertexDistance, 0, FogCloudsEnd);
    fragColor = color;
}
