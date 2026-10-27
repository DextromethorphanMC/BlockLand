// Rigid objects that move on their own (vehicles: client/render/ObjectMotion, rendering spec §4), for the frame's motion:
// a world pixel inside a mover's box is moved back to where that point of the object was a frame ago before it is
// reprojected, instead of by the camera alone. Filled once per frame by client/graphics/post/ObjectMotionBlock.
//   BlMotionInvCurrent      = current unjittered NDC -> current camera-relative world
//   BlMotionPrevFromCurrent = current camera-relative world -> previous clip (the camera's motion included)
//   BlMotionInfo.x          = movers
//   BlMoverLocal[i]         = current camera-relative world -> the mover's box space (the box: |p| <= BlMoverHalf[i])
//   BlMoverBack[i]          = the box space -> where that point was a frame ago (camera-relative to this frame's camera)

#define BL_MOVERS 8

layout(std140) uniform BlObjectMotion {
    mat4 BlMotionInvCurrent;
    mat4 BlMotionPrevFromCurrent;
    vec4 BlMotionInfo;
    mat4 BlMoverLocal[BL_MOVERS];
    mat4 BlMoverBack[BL_MOVERS];
    vec4 BlMoverHalf[BL_MOVERS];
};

// Where the camera-relative point pos was a frame ago if a mover holds it (moved = true), else pos.
vec3 bl_objectBack(vec3 pos, out bool moved) {
    moved = false;
    int count = int(BlMotionInfo.x + 0.5);
    for (int i = 0; i < BL_MOVERS; i++) {
        if (i >= count) break;
        vec3 local = (BlMoverLocal[i] * vec4(pos, 1.0)).xyz;
        if (all(lessThanEqual(abs(local), BlMoverHalf[i].xyz))) {
            moved = true;
            return (BlMoverBack[i] * vec4(local, 1.0)).xyz;
        }
    }
    return pos;
}

// The previous frame's uv of the world pixel at uv with depth (0..1), when a mover holds it; false = use the camera's
// reprojection (no mover, or behind the previous camera).
bool bl_objectPrevious(vec2 uv, float depth, out vec2 previous) {
    previous = uv;
    if (BlMotionInfo.x < 0.5 || depth >= 1.0) return false;
    vec4 world = BlMotionInvCurrent * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    bool moved;
    vec3 before = bl_objectBack(world.xyz / max(world.w, 1e-6), moved);
    if (!moved) return false;
    vec4 clip = BlMotionPrevFromCurrent * vec4(before, 1.0);
    if (clip.w <= 1e-5) return false;
    previous = clip.xy / clip.w * 0.5 + 0.5;
    return true;
}
