#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:bl_object_motion.glsl>

// DLSS input: motion vectors in render pixels of DLSS's own (top-to-bottom) texel space, previous position minus current
// position (the convention tools/dlss/DlssCheck measured), from the world depth and the camera's motion. The hand and
// the viewmodel move with the camera plus their own motion (client/render/HandMotion); world pixels on a moving vehicle
// with the vehicle's own (blockland:bl_object_motion.glsl).
//   Matrix    = current unjittered NDC -> previous clip (camera-relative, the camera's translation included)
//   Params[0] = (render size, 1 / render size)

uniform sampler2D ColorSampler;
uniform sampler2D WorldDepthSampler;
uniform sampler2D HandVelocitySampler;

out vec4 fragColor;

void main() {
    vec2 size = Params[0].xy;
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 source = ivec2(texel.x, int(size.y) - 1 - texel.y);
    // the viewmodel: its own motion (current - previous, uv), turned into previous - current pixels
    vec2 own = -texelFetch(HandVelocitySampler, source, 0).rg * size;
    vec2 motion = vec2(own.x, -own.y);
    if (texelFetch(ColorSampler, source, 0).a < 0.5) {
        vec2 uv = (vec2(source) + 0.5) * Params[0].zw;
        float depth = texelFetch(WorldDepthSampler, source, 0).r;
        vec4 clip = Matrix * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        vec2 carried;
        bool object = bl_objectPrevious(uv, depth, carried);
        if (clip.w > 1e-5 || object) {
            vec2 previous = object ? carried : clip.xy / clip.w * 0.5 + 0.5;
            vec2 screen = (previous - uv) * size;
            // OpenGL's y runs up the screen, DLSS's rows run down
            motion = vec2(screen.x, -screen.y);
        }
    }
    fragColor = vec4(motion, 0.0, 1.0);
}
