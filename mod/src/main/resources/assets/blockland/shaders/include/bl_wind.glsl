#version 150

// BlockLand wind (tools/assets/RENDERING_SPEC.md §18): plants bent by the steady wind and its travelling gusts, and pushed
// aside by entities, in the lighting pack's terrain vertex shader. Import after <blockland:scene.glsl> and
// <blockland:bl_lighting.glsl>. Every world-anchored wave has a period dividing 2048 blocks (BlCamera wraps there), so
// nothing jumps where the wrapped coordinates do.

// one turn per 2048 blocks: spatial frequencies are whole multiples of it
const float BL_WIND_K = 0.0030679616;
// one turn per hour: bl_time() wraps at 3600 s, so every frequency of time is a whole multiple of it (nothing jumps there)
const float BL_TIME_K = 0.0017453293;

// The wind's strength at a camera-relative position (world: the same, world-anchored): the steady wind broken up by slow
// turbulence carried along with it, plus every gust over it. `back` is how far a gust that just went by lets the plants
// swing back against the wind.
float bl_windAt(vec3 rel, vec3 world, float t, out float back) {
    // carried along with the wind: how far it has blown so far, integrated frame by frame on the CPU and wrapped at
    // 2048 blocks (a heading that wanders must not swing a far-travelled pattern round)
    vec2 q = world.xz - vec2(BlWindPush.w, BlWindSpring.w);
    float turbulence = 0.55 + 0.27 * sin(dot(q, vec2(32.0, 16.0) * BL_WIND_K))
            + 0.18 * sin(dot(q, vec2(-48.0, 64.0) * BL_WIND_K) + 1.7);
    float gust = 0.0;
    back = 0.0;
    int count = int(BlWindInfo.x + 0.5);
    for (int i = 0; i < BL_MAX_GUSTS; i++) {
        if (i >= count) break;
        vec4 a = BlGusts[2 * i];
        vec4 b = BlGusts[2 * i + 1];
        vec2 d = rel.xz - a.xy;
        float across = dot(d, vec2(-b.y, b.x));
        // along its heading, > 0 ahead of the centre; the front is ragged across its width
        float along = dot(d, b.xy) + sin(across * 0.19 + b.w) * a.z * 0.35 + sin(across * 0.53 + b.w * 2.3) * a.z * 0.12;
        float width = exp(-(across * across) / (b.z * b.z));
        gust += a.w * exp(-(along * along) / (a.z * a.z)) * width;
        float behind = along + a.z * 1.7;
        back += a.w * exp(-(behind * behind) / (a.z * a.z * 0.6)) * width;
    }
    return BlWind.z * turbulence + gust;
}

// How far a plant whose stem stands at `stem` (camera-relative, the bottom of the plant) is pushed over by the entities
// around it: xy the direction away from them × how far (1 = BlWindPush.x's full lean), z how much it is pressed flat
// under a foot (0..1). Entities near by push it over at once (the falloff over their radius is the arrival); where one
// has just been (BlSways: where it stood or walked, re-stamped while it stays), the plant springs back with a damped
// overshoot, e^(−decay τ)·cos(ω τ) of the lean it had (BlWindSpring). A small wobble along the walker's heading
// (BlWindSpring.z) is what is left of the old trail sway.
vec3 bl_windPush(vec3 stem, float top) {
    float core = BlWindPush.z;
    vec2 live = vec2(0.0);
    float press = 0.0;
    int pushers = int(BlWindInfo.y + 0.5);
    for (int i = 0; i < BL_MAX_PUSHERS; i++) {
        if (i >= pushers) break;
        vec4 p = BlPushers[2 * i];
        vec4 v = BlPushers[2 * i + 1];
        // from the feet a little below the plant's bottom up to the head over its top
        if (stem.y + top < p.y - 0.25 || stem.y > p.y + v.z) continue;
        vec2 d = stem.xz - p.xz;
        float dist = length(d);
        if (dist >= p.w) continue;
        float f = (1.0 - smoothstep(core * p.w, p.w, dist)) * v.w;
        float speed = length(v.xy);
        vec2 away = dist > 0.04 ? d / dist : (speed > 0.05 ? v.xy / speed : BlWind.xy);
        // right under the feet it goes down the way the body moves
        if (speed > 0.3) away = normalize(away + v.xy / speed * (1.0 - smoothstep(0.0, 0.5 * p.w, dist)) + 1e-4);
        live += away * f;
        press = max(press, (1.0 - smoothstep(0.0, 0.45 * p.w, dist)) * v.w);
    }
    float liveLength = length(live);
    if (liveLength > 1.0) live /= liveLength;

    vec2 spring = vec2(0.0), wobble = vec2(0.0);
    float weightSum = 0.0, weightMax = 0.0, springPress = 0.0;
    int sways = int(BlWindInfo.z + 0.5);
    for (int i = 0; i < BL_MAX_SWAYS; i++) {
        if (i >= sways) break;
        vec4 a = BlSways[2 * i];
        vec4 b = BlSways[2 * i + 1];
        if (stem.y + top < a.y - 0.25 || stem.y > a.y + 1.8) continue;
        vec2 d = stem.xz - a.xz;
        float dist = length(d);
        if (dist >= a.w) continue;
        float w = (1.0 - smoothstep(core * a.w, a.w, dist)) * b.w;
        float age = b.z;
        float k = exp(-BlWindSpring.y * age) * cos(BlWindSpring.x * age);
        vec2 away = dist > 0.04 ? d / dist : b.xy;
        spring += away * k * w;
        springPress += (1.0 - smoothstep(0.0, 0.45 * a.w, dist)) * k * w;
        weightSum += w;
        weightMax = max(weightMax, w);
        wobble += b.xy * w * exp(-age * 1.6) * sin(age * 9.5 - dist * 3.0);
    }
    if (weightSum > 1e-4) {
        // one release however many marks lie close together: their average, as strong as the nearest
        spring *= weightMax / weightSum;
        springPress *= weightMax / weightSum;
    }
    // while a body is there it holds the plant; the spring takes over as it goes
    float hold = min(liveLength, 1.0);
    vec2 lean = live + spring * (1.0 - hold) + wobble * BlWindSpring.z;
    press = max(press, max(springPress, 0.0) * (1.0 - hold));
    return vec3(lean, press);
}

// Plant columns around the camera (texture unit 15, client/graphics/wind/PlantColumns): per block column (world x, z
// modulo 128) the longest run of blocks growing up and of blocks hanging down; RG / BA = (bottom or top y + 65) | length << 9.
uniform sampler2D BlPlantColumns;

// The camera's world y (BlCamera.y is wrapped to 0..2048; the world is far lower than that).
float bl_windCameraY() {
    return BlCamera.y > 1024.0 ? BlCamera.y - 2048.0 : BlCamera.y;
}

// Height over the anchor of the column a stacked plant's block belongs to: > 0 over the base of a run growing up,
// < 0 under the top of a run hanging down (its magnitude the distance), 0 = not in a known column. block: the world
// block of the plant, inBlock: the vertex's height in it.
float bl_windColumn(ivec3 block, float inBlock) {
    if (BlWindStack.w < 0.5) return 0.0;
    ivec4 c = ivec4(texelFetch(BlPlantColumns, ivec2(block.x & 127, block.z & 127), 0) * 255.0 + 0.5);
    int up = c.r | (c.g << 8);
    int hang = c.b | (c.a << 8);
    if (up != 0) {
        int base = (up & 511) - 65;
        int count = up >> 9;
        if (block.y >= base && block.y < base + count) return float(block.y - base) + inBlock;
    }
    if (hang != 0) {
        int top = (hang & 511) - 65;
        int count = hang >> 9;
        if (block.y <= top && block.y > top - count) return -(float(top + 1 - block.y) - inBlock);
    }
    return 0.0;
}

// The wind's lean and flutter (blocks at one block of height) at a stem's top h blocks over its anchor: the top feels the
// wind from a little upwind (a gust reaches it later) and flutters a little behind the stem's foot.
vec2 bl_windBend(vec3 pos, float h, float lean, float flutterScale, float t, float seed) {
    vec2 dir = BlWind.xy;
    vec3 lagged = pos - vec3(dir.x, 0.0, dir.y) * (BlWindBend.w * h);
    float back;
    float w = bl_windAt(lagged, lagged + BlCamera.xyz, t, back);
    float delay = h * 0.9;
    float flutter = flutterScale * (0.6 + 1.4 * w) * (0.65 * sin(t * (4927.0 * BL_TIME_K) - delay + seed * 6.2831853)
            + 0.35 * sin(t * (7506.0 * BL_TIME_K) - delay * 1.5 + seed * 9.0));
    float sideways = flutterScale * (0.4 + 0.9 * w) * (0.6 * sin(t * (3495.0 * BL_TIME_K) - delay + seed * 11.0)
            + 0.4 * sin(t * (5901.0 * BL_TIME_K) - delay * 1.5 + seed * 4.0));
    return dir * (lean * w - 0.5 * lean * back + flutter) + vec2(-dir.y, dir.x) * sideways;
}

// A slow swaying current under water (at one block of height).
vec2 bl_windCurrent(vec3 world, float t, float seed) {
    vec2 dir = BlWind.xy;
    float s = 0.6 * sin(t * (716.0 * BL_TIME_K) + seed * 6.2831853) + 0.4 * sin(t * (407.0 * BL_TIME_K) + world.x * 64.0 * BL_WIND_K);
    return dir * (0.05 + 0.07 * s) * BlWindInfo.w + vec2(-dir.y, dir.x) * 0.03 * sin(t * (533.0 * BL_TIME_K) + seed * 4.0);
}

// A stem of height h tipped by d sideways keeps its length: its top sinks (or, hanging, rises) by `sink`.
vec2 bl_windLean(vec2 d, float h, out float sink) {
    float len = length(d);
    float most = 0.97 * h;
    if (len > most) d *= most / len;
    sink = h - sqrt(max(h * h - dot(d, d), 0.0));
    return d;
}

// Where a vertex of a plant goes. pos: camera-relative, local: the vertex's position in its chunk section (block-aligned),
// wind: bl_blockWind of its sprite (mode | upper half << 2 | water << 3 | flex level << 4), corner: the vertex's corner
// of its quad (rendering spec §18: 0 and 3 the upper edge of a side face, 0 and 1 at its +tangent end), normal: the face's.
vec3 bl_wind(vec3 pos, vec3 local, int wind, int corner, vec3 normal) {
    int mode = wind & 3;
    if (mode == 0 || BlWind.w <= 0.0) return pos;
    bool water = ((wind >> 3) & 1) == 1;
    float upper = float((wind >> 2) & 1);
    float flexBase = 0.5 * float(((wind >> 4) & 3) + 1);
    float flex = flexBase * BlWind.w;
    float t = bl_time();
    vec3 world = pos + BlCamera.xyz;
    vec2 dir = BlWind.xy;
    bool side = abs(normal.y) < 0.5;
    bool top = corner == 0 || corner == 3;

    if (mode == 3) {
        // leaves: the whole canopy rustles, a smooth field of the position so neighbouring blocks never part; a body
        // brushing through only parts them a little (each vertex away from it, which keeps the field whole)
        float back;
        float w = bl_windAt(pos, world, t, back);
        vec3 ph = world * (128.0 * BL_WIND_K);
        float shiver = 0.010 + 0.022 * w;
        vec3 d = vec3(sin(t * (1776.0 * BL_TIME_K) + ph.x + ph.y * 0.7), 0.6 * sin(t * (1318.0 * BL_TIME_K) + ph.z + ph.x * 0.5), sin(t * (1547.0 * BL_TIME_K) + ph.z + ph.y))
                * shiver + vec3(dir.x, 0.0, dir.y) * (0.018 * w - 0.01 * back);
        vec3 push = bl_windPush(pos - vec3(0.0, 0.5, 0.0), 1.0);
        d.xz += push.xy * 0.3 * BlWindKinds.z;
        d.y -= length(push.xy) * 0.08 * BlWindKinds.z;
        return pos + d * flex;
    }

    // the vertex's height in its block, and its plant's stem: the middle of its block (a side face's far end lies a
    // face width along its tangent, so the midpoint, and the block it is in, is the same for all four corners)
    float y = local.y;
    float inBlock = side ? (top ? y - floor(y - 0.1) : max(y - floor(y + 0.25), 0.0)) : y - floor(y - 0.1);
    vec3 stemLocal;
    if (side) {
        vec2 tangent = normalize(vec2(-normal.z, normal.x) + 1e-5);
        float halfWidth = abs(normal.x) > 0.3 && abs(normal.z) > 0.3 ? 0.7071 : 0.5;
        vec2 middle = local.xz - (corner < 2 ? 1.0 : -1.0) * tangent * halfWidth;
        stemLocal.xz = floor(middle) + 0.5;
    } else {
        stemLocal.xz = floor(local.xz + 0.001) + 0.5;
    }
    stemLocal.y = y - inBlock;
    // phases vary smoothly over the ground (a plant's corners stay nearly in step); frequencies are constants, or the
    // phase differences between neighbours would grow with time
    float seed = 0.5 + 0.5 * sin(dot(world.xz, vec2(128.0, 96.0) * BL_WIND_K));
    bool cross = side && abs(normal.x) > 0.3 && abs(normal.z) > 0.3;

    if (mode == 2) {
        // stacked and hanging plants (sugar cane, bamboo, kelp, vines): in a known column one cantilever from the
        // column's base (or, hanging, from its top), bending with height^exponent, smooth across its blocks
        vec3 origin = floor(pos - local + vec3(BlCamera.x, bl_windCameraY(), BlCamera.z) + 0.5);
        ivec3 block = ivec3(origin) + ivec3(int(floor(stemLocal.x)), int(floor(stemLocal.y + 0.5)), int(floor(stemLocal.z)));
        float column = bl_windColumn(block, inBlock);
        // pushed from the stem of a cross (cane, kelp), from the vertex itself on a flat face (a vine sheet stays whole)
        vec3 at = cross ? pos + (vec3(stemLocal.x, y - 0.5, stemLocal.z) - local) : pos - vec3(0.0, 0.5, 0.0);
        vec3 push = bl_windPush(at, 1.0);
        float pushKind = (water ? BlWindKinds.w : BlWindKinds.y) * min(flexBase, 1.0) * min(BlWind.w, 1.0);
        vec2 mask = cross ? vec2(1.0) : abs(normal.xz) * 0.8 + 0.2;
        if (column != 0.0) {
            float h = abs(column);
            vec2 bend = water ? bl_windCurrent(world, t, seed) * 0.35 : bl_windBend(pos, h, BlWindStack.x, BlWindStack.y, t, seed);
            vec2 d = bend * flex * pow(h, BlWindBend.z);
            d += push.xy * min(sin(min(length(push.xy), 1.0) * BlWindPush.x) * h, BlWindStack.z) * pushKind;
            float sink;
            d = bl_windLean(d * mask, h, sink);
            return pos + vec3(d.x, column > 0.0 ? -sink : sink, d.y);
        }
        // not in a known column (beyond the window, not scanned yet): a smooth field of the position, mostly across
        // their own plane, so blocks above one another still move together
        float back;
        float w = bl_windAt(pos, world, t, back);
        float ph = dot(world, vec3(96.0, 160.0, 64.0) * BL_WIND_K);
        vec2 bend = water ? bl_windCurrent(world, t, seed) : bl_windBend(pos, 0.0, BlWindBend.x, BlWindBend.y, t, seed);
        vec2 d = bend * 0.28 * flex + dir * 0.02 * sin(t * (1203.0 * BL_TIME_K) + ph) * (0.5 + w) * flex;
        d += push.xy * BlWindStack.z * pushKind;
        d *= mask;
        return pos + vec3(d.x, 0.0, d.y);
    }

    // bending plants: anchored at the bottom, the tops moving most; the upper half of a two-block plant bends on from
    // the lower's top. The wind bends the stem (with h^exponent, the top lagging); a body tips the whole stem over about
    // its foot (with h, as a lean of up to BlWindPush.x radians, pressed towards BlWindPush.y × 90° under a foot).
    float h = inBlock + upper;
    stemLocal.y -= upper;
    vec2 bend = water ? bl_windCurrent(world, t, seed) : bl_windBend(pos, h, BlWindBend.x, BlWindBend.y, t, seed);
    vec3 stem = pos + (stemLocal - local);
    vec3 push = bl_windPush(stem, h);
    float kind = (water ? BlWindKinds.w : BlWindKinds.x) * min(flexBase, 1.0) * min(BlWind.w, 1.0);
    float pushLength = length(push.xy);
    float angle = min(pushLength, 1.0) * BlWindPush.x * kind;
    angle = mix(angle, max(angle, BlWindPush.y * 1.5707963), clamp(push.z * kind, 0.0, 1.0));
    vec2 pushDir = pushLength > 1e-4 ? push.xy / pushLength : dir;
    vec2 d = bend * flex * pow(h, BlWindBend.z) + pushDir * sin(angle) * h;
    // lean, never stretch: the top stays a stem's length from the foot and sinks as it tips
    float sink;
    d = bl_windLean(d, h, sink);
    return pos + vec3(d.x, -sink, d.y);
}
