#version 150

// BlockLand reflex sight glass (com.blockland.client.scope.ReflexSights): the optic's own glass mesh, in view space.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
// xyz = normal (0..1 = -1..1), w = baked ambient occlusion (see gun.vsh)
in vec4 Normal;

out vec3 viewPos;
out vec3 viewNormal;

void main() {
    vec4 pos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * pos;
    viewPos = pos.xyz;
    viewNormal = mat3(ModelViewMat) * (Normal.xyz * 2.0 - 1.0);
}
