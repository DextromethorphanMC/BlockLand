#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:bl_object_motion.glsl>

// BlockLand motion blur, pass 1 (client/graphics/post/MotionBlur): each pixel's blur while the shutter is open — rg =
// half the smear in output pixels (clamped to the tile size), b = distance from the camera in blocks, a = 1 on the
// first-person weapon and arms. World pixels: their depth back to a camera-relative position, moved back by the motion
// of the vehicle that holds it (blockland:bl_object_motion.glsl: turning too) or else of the entity whose box holds it
// (if any), then into the previous frame through the camera's motion. Weapon pixels:
// the hand motion texture (client/render/HandMotion).
//   Params[0] = (output size, 1 / output size)
//   Params[1] = (jitter in uv xy, -, -)
//   Info      = (entities, world shutter: exposure / frame time, weapon shutter, longest blur radius in pixels)
//   Boxes     = per entity: box min, box max (camera-relative), motion since the last frame (blocks)

layout(std140) uniform MotionBlurData {
    mat4 InvCurrent;      // current unjittered NDC -> current camera-relative world
    mat4 PrevFromCurrent; // current camera-relative world -> previous clip
    vec4 Info;
    vec4 Boxes[96];
};

uniform sampler2D ColorSampler;
uniform sampler2D WorldDepthSampler;
uniform sampler2D HandVelocitySampler;

in vec2 texCoord;

out vec4 fragColor;

// The blur radius vector from a whole-frame screen motion in uv, scaled to the shutter and clamped.
vec2 radius(vec2 frameMotionUv, float shutter) {
    vec2 r = frameMotionUv * Params[0].xy * shutter * 0.5;
    float len = length(r);
    if (len > Info.w) r *= Info.w / len;
    return r;
}

void main() {
    if (texture(ColorSampler, texCoord).a > 0.5) {
        vec2 own = texture(HandVelocitySampler, texCoord + Params[1].xy).rg;
        // the weapon sits right in front of the eye
        fragColor = vec4(radius(own, Info.z), 0.02, 1.0);
        return;
    }
    float depth = texture(WorldDepthSampler, texCoord + Params[1].xy).r;
    vec4 world = InvCurrent * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 position = world.xyz / max(world.w, 1e-6);
    float distance = length(position);
    if (depth >= 1.0 || Info.y <= 0.0) {
        // the sky moves with the camera's turning only
        vec4 clip = PrevFromCurrent * vec4(position, 1.0);
        vec2 previous = clip.w > 1e-5 ? clip.xy / clip.w * 0.5 + 0.5 : texCoord;
        fragColor = vec4(Info.y > 0.0 ? radius(texCoord - previous, Info.y) : vec2(0.0), 1e4, 0.0);
        return;
    }
    bool carried;
    vec3 before = bl_objectBack(position, carried);
    int count = carried ? 0 : int(Info.x);
    for (int i = 0; i < 32; i++) {
        if (i >= count) break;
        vec3 lo = Boxes[i * 3].xyz, hi = Boxes[i * 3 + 1].xyz;
        if (all(greaterThanEqual(position, lo)) && all(lessThanEqual(position, hi))) {
            before = position - Boxes[i * 3 + 2].xyz;
            break;
        }
    }
    vec4 clip = PrevFromCurrent * vec4(before, 1.0);
    if (clip.w <= 1e-5) {
        fragColor = vec4(0.0, 0.0, distance, 0.0);
        return;
    }
    vec2 previous = clip.xy / clip.w * 0.5 + 0.5;
    fragColor = vec4(radius(texCoord - previous, Info.y), distance, 0.0);
}
