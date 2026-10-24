#version 150

// Screen-space reflections (client/graphics/reflect, rendering spec §17) for the lighting pack's lit terrain program.
// Import after <blockland:scene.glsl>. The reflected ray is marched through last frame's world (BlPrevScene: its colour
// and linear depth, taken after the level, so the viewmodel is never in it), each point carried into last frame's view
// (BlPrevView, BlPrevProj); where it passes just behind what was drawn there, that is what it sees. Steps grow along the
// ray and the hit is refined by bisection. Rays leaving the picture, going back towards the eye or finding nothing return
// a confidence of 0, and the caller keeps its environment estimate there.

uniform sampler2D BlPrevScene;   // texture unit 14: rgb last frame's world (display), a its linear depth (0 = sky)

// Where a camera-relative point lay on last frame's picture (xy uv) and how far in front of last frame's eye (z).
vec3 bl_ssrProject(vec3 p) {
    vec4 v = BlPrevView * vec4(p, 1.0);
    float depth = -v.z;
    return vec3(vec2(BlPrevProj.x * v.x, BlPrevProj.y * v.y) / max(depth, 1e-3) * 0.5 + 0.5, depth);
}

// The reflection seen along r from pos (camera-relative), as (display colour, confidence 0..1).
vec4 bl_ssr(vec3 pos, vec3 r, vec2 pixel) {
    if (BlPrevProj.w < 0.5) {
        return vec4(0.0);
    }
    int steps = int(BlPrevProj.z + 0.5);
    float distance0 = length(pos);
    // a ray turned back towards the eye runs behind what the picture shows
    float facing = smoothstep(-0.2, 0.15, dot(r, pos / max(distance0, 1e-3)));
    if (facing <= 0.0) {
        return vec4(0.0);
    }
    float stepLength = max(0.08, distance0 * 0.012);
    float jitter = fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))) + BlFlags.w * 7.123);
    float t = stepLength * (0.5 + jitter);
    float previous = 0.0;
    for (int i = 0; i < 64; i++) {
        if (i >= steps) {
            break;
        }
        vec3 q = bl_ssrProject(pos + r * t);
        if (q.z <= 0.05 || any(lessThan(q.xy, vec2(0.0))) || any(greaterThan(q.xy, vec2(1.0)))) {
            return vec4(0.0);
        }
        float scene = textureLod(BlPrevScene, q.xy, 0.0).a;
        float behind = q.z - scene;
        if (scene > 0.0 && behind > 0.0 && behind < max(0.35, (t - previous) * 1.5)) {
            // refine between the last point in front and this one
            float lo = previous, hi = t;
            for (int k = 0; k < 5; k++) {
                float mid = 0.5 * (lo + hi);
                vec3 m = bl_ssrProject(pos + r * mid);
                if (m.z - textureLod(BlPrevScene, m.xy, 0.0).a > 0.0) hi = mid; else lo = mid;
            }
            vec3 hit = bl_ssrProject(pos + r * hi);
            vec2 border = min(hit.xy, 1.0 - hit.xy);
            float edge = smoothstep(0.0, 0.08, min(border.x, border.y));
            float far = 1.0 - smoothstep(0.6, 1.0, float(i) / float(steps));
            return vec4(textureLod(BlPrevScene, hit.xy, 0.0).rgb, edge * far * facing);
        }
        previous = t;
        t += stepLength;
        stepLength *= 1.15;
    }
    return vec4(0.0);
}
