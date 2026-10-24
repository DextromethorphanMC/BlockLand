#version 150

// BlockLand screen-space ambient occlusion (half resolution): along a few screen directions, rotated per pixel and per
// frame, points of the depth buffer within a world radius are tested against the surface's tangent plane (scalable
// ambient obscurance with a horizon bias). Normals come from the depth itself, from the closer neighbour on each axis,
// so silhouettes do not smear. Sky and the far plane stay open.

#moj_import <blockland:world_fx.glsl>

in vec2 texCoord;

out vec4 fragColor;

const int DIRECTIONS = 4;
const int STEPS = 4;

vec3 neighbour(vec2 uv) {
    return fx_viewPosition(uv, fx_depth(uv));
}

void main() {
    float depth = fx_depth(texCoord);
    if (depth >= 1.0) {
        fragColor = vec4(1.0);
        return;
    }
    vec3 p = fx_viewPosition(texCoord, depth);
    vec2 texel = 1.0 / FxSize.xy;
    vec3 left = neighbour(texCoord - vec2(texel.x, 0.0));
    vec3 right = neighbour(texCoord + vec2(texel.x, 0.0));
    vec3 down = neighbour(texCoord - vec2(0.0, texel.y));
    vec3 up = neighbour(texCoord + vec2(0.0, texel.y));
    vec3 dx = abs(right.z - p.z) < abs(p.z - left.z) ? right - p : p - left;
    vec3 dy = abs(up.z - p.z) < abs(p.z - down.z) ? up - p : p - down;
    vec3 n = normalize(cross(dx, dy));
    if (dot(n, p) > 0.0) {
        n = -n;
    }

    float radius = FxAo.x;
    // the radius on screen (uv of the screen height), capped so close-ups do not sample half the screen
    float screenRadius = min(radius * FxProj.y / -p.z * 0.5, FxAo.w);
    if (screenRadius < texel.y * 1.5) {
        fragColor = vec4(1.0);
        return;
    }
    float aspect = FxSize.y / FxSize.x;
    float noise = fx_noise(gl_FragCoord.xy, FxAo.z);
    float jitter = fx_noise(gl_FragCoord.yx + 17.0, FxAo.z);
    float occlusion = 0.0;
    float r2 = radius * radius;
    for (int d = 0; d < DIRECTIONS; d++) {
        float angle = (float(d) + noise) * (6.2831853 / float(DIRECTIONS));
        vec2 dir = vec2(cos(angle) * aspect, sin(angle)) * screenRadius;
        for (int s = 0; s < STEPS; s++) {
            float t = (float(s) + jitter) / float(STEPS);
            vec2 uv = texCoord + dir * (t * t * 0.85 + 0.15 * t);
            if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
                continue;
            }
            float sampleDepth = fx_depth(uv);
            if (sampleDepth >= 1.0) {
                continue;
            }
            vec3 v = fx_viewPosition(uv, sampleDepth) - p;
            float dist2 = dot(v, v);
            float horizon = dot(v, n) * inversesqrt(max(dist2, 1e-6));
            float falloff = clamp(1.0 - dist2 / r2, 0.0, 1.0);
            occlusion += max(horizon - 0.12, 0.0) * falloff;
        }
    }
    occlusion /= float(DIRECTIONS * STEPS);
    float ao = clamp(1.0 - occlusion * 2.2 * FxAo.y, 0.0, 1.0);
    fragColor = vec4(ao, ao, ao, 1.0);
}
