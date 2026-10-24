#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:rt_common.glsl>

// Ray tracing guide (internal resolution): the surface normal in world orientation, from the world depth (each axis
// differentiated on the side with the smaller depth step, so edges keep their own surface's normal), and the linear
// depth (blocks from the camera). Sky: alpha 0.

uniform sampler2D DepthSampler;

out vec4 fragColor;

vec3 viewAt(ivec2 p) {
    ivec2 size = ivec2(RtSize.xy);
    p = clamp(p, ivec2(0), size - 1);
    return rt_viewPosition((vec2(p) + 0.5) / RtSize.xy, texelFetch(DepthSampler, p, 0).r);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(DepthSampler, p, 0).r;
    if (depth >= 1.0) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 c = viewAt(p);
    vec3 l = viewAt(p - ivec2(1, 0)), r = viewAt(p + ivec2(1, 0));
    vec3 d = viewAt(p - ivec2(0, 1)), u = viewAt(p + ivec2(0, 1));
    vec3 dx = abs(r.z - c.z) < abs(c.z - l.z) ? r - c : c - l;
    vec3 dy = abs(u.z - c.z) < abs(c.z - d.z) ? u - c : c - d;
    vec3 n = normalize(cross(dx, dy));
    if (dot(n, c) > 0.0) n = -n;
    fragColor = vec4(normalize(mat3(RtViewToWorld) * n), -c.z);
}
