#version 150

#moj_import <blockland:gfx_screen.glsl>

// Water on the lens (client/graphics/post/LensWater, rendering spec §27; the effect after Chocapic13's Bliss shaders'
// water on the camera), on the upscaled linear-HDR picture before bloom. Out of the water: for the first moment a film
// drains off downwards, then drops sit on the glass — each a small lens showing the picture behind it turned over, a dark
// rim, a glint — the bigger ones sliding down in fits, leaving a hair-thin trail and a few beads, all of them shrinking
// until the glass is clear (a few seconds). Under water: a gentle wavering of the picture.
//   Params[0] = (seconds since the camera left the water, 1 = under water now, time (s), aspect width / height)
//   Params[1] = (1 / width, 1 / height, seed of this exit 0..1, -)

uniform sampler2D ColorSampler;

in vec2 texCoord;

out vec4 fragColor;

uint lw_hash(uvec3 v) {
    uint h = v.x * 1597334677u ^ v.y * 3812015801u ^ v.z * 2798796415u;
    h = (h ^ (h >> 16u)) * 2246822519u;
    h = (h ^ (h >> 13u)) * 3266489917u;
    return h ^ (h >> 16u);
}

// Four random numbers 0..1 for a cell, a slot and a stream, different for every exit from the water.
vec4 lw_rand(int x, int y, int stream) {
    uint seed = uint(Params[1].z * 65535.0);
    uint h = lw_hash(uvec3(uint(x + 65536), uint(y + 65536), uint(stream) * 7919u + seed));
    uint g = lw_hash(uvec3(h, seed, 101u));
    return vec4(float(h & 0xFFFFu), float(h >> 16u), float(g & 0xFFFFu), float(g >> 16u)) / 65535.0;
}

// The lens over this pixel so far: (where the picture is seen through it (screen-height units), thickness for the
// nearest-on-top test, distance from its middle 0..1). Its coverage is kept apart (anti-aliased rim).
vec4 lens = vec4(0.0);
float lensCover = 0.0;
vec2 lensDir = vec2(0.0);

// A drop of half axes `radii` at `centre` on the glass, seen at `p`: a cap of water, thicker in the middle; through it
// the picture turned over and squeezed (`magnify` times the drop's own extent around its middle).
void drop(vec2 p, vec2 centre, vec2 radii, float magnify) {
    vec2 d = (p - centre) / radii;
    float q = dot(d, d);
    if (q >= 1.0) {
        return;
    }
    float r = sqrt(q);
    float thick = sqrt(1.0 - q) * max(radii.x, radii.y);
    if (thick <= lens.z) {
        return;
    }
    float pixel = Params[1].y / max(radii.y, 1e-4);
    lens = vec4(centre - (p - centre) * magnify, thick, r);
    lensCover = 1.0 - smoothstep(1.0 - 1.5 * pixel, 1.0, r);
    lensDir = d;
}

// How far a drop that set out at `tau` seconds has crept (0..), sticking and letting go.
float lurches(float x) {
    return floor(x) + smoothstep(0.55, 1.0, fract(x));
}

vec3 sampleAt(vec2 uv) {
    return texture(ColorSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

void main() {
    float age = Params[0].x;
    bool under = Params[0].y > 0.5;
    float time = Params[0].z;
    float aspect = Params[0].w;
    vec2 p = vec2(texCoord.x * aspect, texCoord.y);
    vec2 uv = texCoord;
    vec2 centred = texCoord * 2.0 - 1.0;
    float edge = clamp(dot(centred, centred) * 0.5, 0.0, 1.0);

    if (under) {
        // under water: the picture wavers a little, more towards the edges
        vec2 wobble = vec2(sin(p.y * 13.0 + time * 1.8 + sin(p.x * 7.0 + time)), cos(p.x * 9.0 - time * 1.4 + sin(p.y * 5.0)));
        fragColor = vec4(sampleAt(uv + wobble * 0.0018 * (0.4 + 0.6 * edge)), 1.0);
        return;
    }

    // the film draining off: below a line that sweeps down the screen, the water still running streams and bends
    float drain = 1.25 - age * 2.3;
    float film = (1.0 - smoothstep(drain - 0.25, drain, p.y)) * (1.0 - smoothstep(0.3, 0.8, age));
    if (film > 0.0) {
        float meniscus = 1.0 + 2.0 * exp(-abs(drain - p.y) * 30.0);
        vec2 flow = vec2(sin(p.y * 38.0 + p.x * 9.0 + age * 24.0) + 0.5 * sin(p.x * 61.0 - age * 9.0),
                sin(p.x * 27.0 + p.y * 6.0 - age * 17.0));
        uv += flow * 0.0035 * film * meniscus * vec2(1.0 / aspect, 1.0);
    }
    vec3 base = sampleAt(uv);

    // the drops that slide: lanes down the screen, a few drops each
    const float LANE = 0.05;
    int lane = int(floor(p.x / LANE));
    float trail = 0.0;
    for (int dl = -1; dl <= 1; dl++) {
        int l = lane + dl;
        for (int k = 0; k < 3; k++) {
            vec4 a = lw_rand(l, k, 1);
            if (a.x > 0.7) {
                continue;
            }
            vec4 b = lw_rand(l, k, 2);
            float r = mix(0.008, 0.024, a.y * a.y);
            float size = smoothstep(0.008, 0.024, r);
            float life = mix(2.2, 5.5, a.z) * (0.7 + 0.3 * size);
            float fade = 1.0 - age / life;
            if (fade <= 0.0) {
                continue;
            }
            float y0 = mix(0.05, 1.12, a.w);
            float stick = mix(0.2, 2.2, b.x) * (1.25 - size);
            float speed = mix(0.05, 0.42, size);
            float tau = max(age - stick, 0.0);
            float start = b.y * 3.0;
            float slid = speed * (0.35 * tau + 0.65 * (lurches(start + tau * 1.6) - lurches(start)) / 1.6);
            float x0 = (float(l) + 0.5 + (b.z - 0.5) * 0.5) * LANE;
            float phase = b.w * 6.2831853;
            float cx = x0 + sin(slid * 22.0 + phase) * LANE * 0.12;
            float cy = y0 - slid;
            float rr = r * sqrt(fade) * (1.0 - 0.35 * min(slid / 0.6, 1.0));
            float moving = smoothstep(0.0, 0.05, tau);
            drop(p, vec2(cx, cy), vec2(rr, rr * (1.0 + 0.3 * moving)), 1.6);

            // its trail, from where it set out to where it is now: a hair-thin film and beads left on the way
            if (slid > 0.002 && p.y > cy && p.y < y0) {
                float along = y0 - p.y;
                float px = x0 + sin(along * 22.0 + phase) * LANE * 0.12;
                float passed = (slid - along) / max(speed, 0.02);
                float left = clamp(1.0 - passed / 1.6, 0.0, 1.0) * fade;
                float w = rr * 0.28;
                trail = max(trail, (1.0 - smoothstep(w * 0.5, w, abs(p.x - px))) * left);
                const float BEAD = 0.018;
                int cell = int(floor(p.y / BEAD));
                vec4 c = lw_rand(l * 4 + k, cell, 3);
                if (c.x < 0.45 && left > 0.0) {
                    vec2 bc = vec2(px + (c.y - 0.5) * w * 1.5, (float(cell) + 0.4 + 0.2 * c.z) * BEAD);
                    float br = r * mix(0.12, 0.3, c.w) * sqrt(left);
                    if (bc.y > cy + rr) {
                        drop(p, bc, vec2(br), 1.3);
                    }
                }
            }
        }
    }

    // the drops that only sit there and dry
    const float CELL = 0.035;
    ivec2 home = ivec2(floor(p / CELL));
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            ivec2 c = home + ivec2(i, j);
            vec4 a = lw_rand(c.x, c.y, 4);
            if (a.x > 0.55) {
                continue;
            }
            float life = mix(1.2, 5.0, a.z);
            float fade = 1.0 - age / life;
            if (fade <= 0.0) {
                continue;
            }
            vec4 b = lw_rand(c.x, c.y, 5);
            float r = mix(0.002, 0.009, a.y * a.y * a.y) * sqrt(fade);
            vec2 centre = (vec2(c) + 0.25 + 0.5 * vec2(a.w, b.x)) * CELL;
            drop(p, centre, vec2(r), 1.4);
        }
    }

    vec3 color = base;
    if (trail > 0.0) {
        // the wet trail: a faint smear sideways, a touch darker
        vec3 smear = sampleAt(uv + vec2(0.0015 * trail / aspect, 0.0));
        color = mix(color, smear * 0.94, trail * 0.8);
    }
    if (lensCover > 0.0) {
        vec2 seen = vec2(lens.x / aspect, lens.y);
        vec2 px = Params[1].xy * 1.5;
        vec3 through = 0.25 * (sampleAt(seen + vec2(px.x, px.y)) + sampleAt(seen - vec2(px.x, px.y))
                + sampleAt(seen + vec2(-px.x, px.y)) + sampleAt(seen + vec2(px.x, -px.y)));
        // light leaves the rim at a steep angle: a dark ring; a glint of the light above on its upper side
        through *= mix(1.0, 0.45, smoothstep(0.55, 1.0, lens.w));
        vec2 g = lensDir - vec2(-0.32, 0.42);
        through += exp(-dot(g, g) * 45.0) * (0.12 + 0.35 * gfx_luminance(base));
        color = mix(color, through, lensCover);
    }
    fragColor = vec4(max(color, vec3(0.0)), 1.0);
}
