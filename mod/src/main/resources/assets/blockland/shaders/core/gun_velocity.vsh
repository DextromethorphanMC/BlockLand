#version 150

// BlockLand viewmodel motion (client/render/DrawList#motion, rendering spec §4): the screen motion of each part of the
// first-person weapon and arms since the last frame, from this frame's and last frame's node matrices.
//   ModelViewMat = this frame's model -> view, TextureMat = last frame's (DynamicTransforms)
//   ProjMat      = the viewmodel projection with this frame's jitter (rasterises exactly like the colour pass)

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

layout(std140) uniform HandMotionInfo {
    mat4 Unjittered;    // the viewmodel projection without jitter
};

in vec3 Position;
in vec2 UV0;
in vec4 Normal;

out vec4 currentClip;
out vec4 previousClip;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    currentClip = Unjittered * view;
    previousClip = Unjittered * (TextureMat * vec4(Position, 1.0));
}
