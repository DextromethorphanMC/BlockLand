#version 150

// BlockLand stateless GPU particles (client/render/GpuParticles, asset spec §38). One draw = one emitter: a static mesh
// of quads whose vertices hold only seeds; every particle's birth, path, size, colour and fade come from them and the
// draw's parameters, so nothing is simulated or uploaded per frame. Per-draw parameters (DynamicTransforms):
//   ModelViewMat   emitter space (a node of the weapon) -> view space
//   ColorModulator display colour (rgb) and brightness (a: fade x intensity)
//   ModelOffset    gravity in view space (m/s^2, the world's down times the emitter's gravity)
//   TextureMat     [0] = (age s, life s, life jitter 0..1, spawn s)
//                  [1] = (speed m/s, speed jitter 0..1, drag 1/s, direction 0..1 towards -Z)
//                  [2] = (spawn radius m, orbit rad/s about Z, gather 0..1, turbulence m)
//                  [3] = (half-size at birth m, at death m, streak s, flags: 1 loop, 2 ring spawn, 4 textured)
// Vertex: Position = seeds 1..3, UV0 = the quad's corner, Normal = (seed 4, seed 5, index share) as -1..1 bytes.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Normal;

out vec2 corner;
out vec4 particleColor;

const float TAU = 6.2831853;

// The particle's position in emitter space at time t of its life (seconds).
vec3 path(float t, vec3 start, vec3 dir, float speed, float drag, float spin, float pull, float u, vec3 seeds) {
    float travel = drag > 1e-3 ? (1.0 - exp(-drag * t)) / drag : t;
    vec3 p = start + dir * speed * travel;
    float a = TextureMat[2].y * t * spin;
    float c = cos(a), s = sin(a);
    p.xy = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
    p *= 1.0 - pull * (u * u * (3.0 - 2.0 * u));
    float turbulence = TextureMat[2].w * u;
    p += turbulence * vec3(sin(t * 7.3 + seeds.x * 40.0), sin(t * 6.1 + seeds.y * 40.0), sin(t * 5.3 + seeds.z * 40.0));
    return p;
}

void main() {
    vec3 seeds = Position;
    float s4 = Normal.x, s5 = Normal.y, share = Normal.z;
    corner = UV0 * 2.0 - 1.0;
    int flags = int(TextureMat[3].w + 0.5);
    bool loop = (flags & 1) != 0;
    bool ring = (flags & 2) != 0;

    float life = TextureMat[0].y * (1.0 - TextureMat[0].z * s4);
    float t;
    if (loop) {
        // a stream: births spread over one life and repeat, the emitter's own start staggered by the share
        t = TextureMat[0].x - share * TextureMat[0].y;
        t = t < 0.0 ? -1.0 : mod(t, life);
    } else {
        t = TextureMat[0].x - share * TextureMat[0].w;
    }
    if (t < 0.0 || t > life) {
        // not born yet / gone: a degenerate quad outside the clip volume
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        particleColor = vec4(0.0);
        return;
    }
    float u = t / life;

    // spawn: a sphere (or a ring about Z), launch direction from the seeds
    float phi = TAU * seeds.x;
    float cosTheta = seeds.y * 2.0 - 1.0;
    float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
    vec3 sphere = vec3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
    float radius = TextureMat[2].x;
    vec3 start = ring ? vec3(cos(phi), sin(phi), 0.0) * radius * (0.85 + 0.3 * seeds.z)
                      : sphere * radius * pow(seeds.z, 0.3333);
    vec3 outward = ring ? normalize(vec3(cos(phi), sin(phi), cosTheta * 0.3)) : sphere;
    vec3 dir = normalize(mix(outward, vec3(0.0, 0.0, -1.0), TextureMat[1].w) + vec3(1e-4));
    float speed = TextureMat[1].x * (1.0 - TextureMat[1].y * s5);
    float spin = 0.7 + 0.6 * seeds.y;
    float pull = TextureMat[2].z;

    vec3 p = path(t, start, dir, speed, TextureMat[1].z, spin, pull, u, seeds);
    vec4 view = ModelViewMat * vec4(p, 1.0);
    view.xyz += 0.5 * ModelOffset * t * t;
    // screen-space velocity for streaks (a short step back along the path)
    float dt = min(t, 0.016);
    vec4 before = ModelViewMat * vec4(path(t - dt, start, dir, speed, TextureMat[1].z, spin, pull, (t - dt) / life, seeds), 1.0);
    before.xyz += 0.5 * ModelOffset * (t - dt) * (t - dt);
    vec2 velocity = dt > 1e-5 ? (view.xy - before.xy) / dt : vec2(0.0);

    float size = mix(TextureMat[3].x, TextureMat[3].y, u) * (0.6 + 0.8 * s4);
    float speedOnScreen = length(velocity);
    vec2 along = speedOnScreen > 1e-4 ? velocity / speedOnScreen : vec2(0.0, 1.0);
    vec2 side = vec2(-along.y, along.x);
    float streak = TextureMat[3].z * speedOnScreen;
    vec2 offset = side * corner.x * size + along * corner.y * (size + streak * 0.5);
    view.xy += offset;
    gl_Position = ProjMat * view;

    // hot at birth (towards white), cooling to the colour; quick fade in, long fade out
    float heat = pow(1.0 - u, 3.0);
    vec3 color = mix(ColorModulator.rgb, vec3(1.0, 0.95, 0.85), 0.6 * heat);
    float alpha = smoothstep(0.0, 0.06, u) * pow(1.0 - u, 1.4) * ColorModulator.a;
    // a streak spreads the same light over a longer sprite
    alpha *= size / max(size + streak * 0.5, 1e-5) * 0.6 + 0.4;
    particleColor = vec4(color, alpha);
}
