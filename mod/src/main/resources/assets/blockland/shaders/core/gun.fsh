#version 150

#moj_import <minecraft:dynamictransforms.glsl>

// Uniform packing: see gun.vsh.
uniform sampler2D Sampler0;          // albedo (display colours)
uniform sampler2D NormalSampler;     // LabPBR _n: normal xy (DirectX: green = down), AO, height
uniform sampler2D SpecularSampler;   // LabPBR _s: smoothness, F0 / metal id, porosity, emission (255 = none)

in vec3 viewPos;
in vec3 viewNormal;
in vec2 texCoord0;
in vec3 objectPos;
in float vertexAo;
in vec4 lightColor;

out vec4 fragColor;

// A small studio light rig in view space (like a weapon viewmodel): key light above/right, cool fill from the left, a
// rim from behind and a sky-gradient environment that rough surfaces see blurred. Lit in linear light (GGX, Smith,
// Schlick), converted back to display space and scaled by Minecraft's lightmap like vanilla shading; emission is
// added after the lightmap.
const vec3 KEY = vec3(0.37968, 0.81930, 0.42963);
const vec3 FILL = vec3(-0.90886, 0.20449, 0.36354);
const vec3 RIM = vec3(0.0, 0.34894, -0.93715);
const float KEY_LIGHT = 0.72;
const float FILL_LIGHT = 0.20;
const float RIM_LIGHT = 0.18;
const vec3 AMBIENT_UP = vec3(0.23, 0.24, 0.26);
const vec3 AMBIENT_DOWN = vec3(0.13, 0.13, 0.14);
const vec3 SKY = vec3(0.50, 0.58, 0.72);
const vec3 GROUND = vec3(0.045, 0.045, 0.05);
const float PI = 3.14159265;
// LabPBR predefined metals (G 230..237): iron, gold, aluminium, chrome, copper, lead, platinum, silver
const vec3 METALS[8] = vec3[8](
    vec3(0.531, 0.512, 0.496), vec3(1.000, 0.782, 0.344), vec3(0.913, 0.922, 0.924), vec3(0.549, 0.556, 0.554),
    vec3(0.955, 0.638, 0.538), vec3(0.632, 0.626, 0.641), vec3(0.679, 0.642, 0.588), vec3(0.962, 0.949, 0.922));

vec3 toLinear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 toDisplay(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2));
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x), mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y);
    float b = mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x), mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y);
    return mix(a, b, f.z);
}

// Tangent frame from screen-space derivatives (no tangents in the vertex): columns = d(position)/du, d(position)/dv,
// normal. Works with mirrored and box-projected UVs. Returns false where the UVs do not change (no frame).
bool cotangentFrame(vec3 n, vec3 p, vec2 uv, out vec3 t, out vec3 b) {
    vec3 dp1 = dFdx(p);
    vec3 dp2 = dFdy(p);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, n);
    vec3 dp1perp = cross(n, dp1);
    t = dp2perp * duv1.x + dp1perp * duv2.x;
    b = dp2perp * duv1.y + dp1perp * duv2.y;
    float scale = max(dot(t, t), dot(b, b));
    if (scale < 1e-20) return false;
    float inv = inversesqrt(scale);
    t *= inv;
    b *= inv;
    return true;
}

float distributionGGX(float noh, float a) {
    float a2 = a * a;
    float d = noh * noh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

float visibilitySmith(float nov, float nol, float a) {
    float a2 = a * a;
    float v = nol * sqrt(nov * nov * (1.0 - a2) + a2);
    float l = nov * sqrt(nol * nol * (1.0 - a2) + a2);
    return 0.5 / max(v + l, 1e-5);
}

vec3 fresnel(vec3 f0, float voh) {
    return f0 + (1.0 - f0) * pow(1.0 - voh, 5.0);
}

// Split-sum environment response without a lookup texture (Karis, mobile approximation).
vec3 environmentBrdf(vec3 f0, float roughness, float nov) {
    vec4 r = roughness * vec4(-1.0, -0.0275, -0.572, 0.022) + vec4(1.0, 0.0425, 1.04, -0.04);
    float a004 = min(r.x * r.x, exp2(-9.28 * nov)) * r.x + r.y;
    vec2 ab = vec2(-1.04, 1.04) * a004 + r.zw;
    return f0 * ab.x + ab.y;
}

// The studio sky seen in a reflection: bright above, a dark floor below, blurred for rough surfaces.
vec3 environment(vec3 r, float roughness) {
    float sky = smoothstep(-0.35 - 0.45 * roughness, 0.8 + 0.2 * roughness, r.y);
    return mix(GROUND, SKY, sky) * (1.0 - 0.35 * roughness);
}

// Light reflected by one directional light: Lambert diffuse (the 1/pi is in the light) and GGX specular.
vec3 directLight(vec3 n, vec3 v, vec3 l, vec3 diffuse, vec3 f0, float a, float light, float diffuseShare) {
    float nol = dot(n, l);
    if (nol <= 0.0) return vec3(0.0);
    vec3 h = normalize(v + l);
    float nov = max(dot(n, v), 1e-4);
    float noh = max(dot(n, h), 0.0);
    float voh = max(dot(v, h), 0.0);
    vec3 f = fresnel(f0, voh);
    vec3 specular = distributionGGX(noh, a) * visibilitySmith(nov, nol, a) * f * PI;
    return (diffuse * (1.0 - f) * diffuseShare + specular) * nol * light;
}

// Interference colours of a thin coating (soap film, anodised titanium): cos of the viewing angle, thickness in um.
vec3 thinFilm(float cosTheta, float thickness) {
    float n = 1.45;
    float cosT = sqrt(max(0.0, 1.0 - (1.0 - cosTheta * cosTheta) / (n * n)));
    float path = 2.0 * n * thickness * cosT;
    return 0.5 + 0.5 * cos(6.2831853 * path / vec3(0.650, 0.532, 0.450));
}

// Rotates a colour about the grey axis (hue shift by `angle` radians, brightness kept).
vec3 hueShift(vec3 c, float angle) {
    const vec3 k = vec3(0.57735);
    float ca = cos(angle);
    return c * ca + cross(k, c) * sin(angle) + k * dot(k, c) * (1.0 - ca);
}

// Distance (in cells) from f to the spoke running from the cell's centre to the middle of one of its edges.
float spoke(vec2 f, vec2 edge) {
    vec2 ba = edge - vec2(0.5);
    vec2 pa = f - vec2(0.5);
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// One projection plane of the circuit effect (asset spec §30). p in cells, aa = cells per pixel, phase = position along
// the traces for the light pulses (in pulse periods). A trace crosses a cell edge where that edge's hash says so (both
// cells agree), and runs to the cell's centre, so the traces join up into Manhattan paths with bends and junctions;
// dead ends get a pad, junctions a solder dot, empty cells sometimes a chip with pins.
// Returns (trace core 0..1, halo 0..1, pulse on the traces 0..1, chip body 0..1).
vec4 circuitPlane(vec2 p, float aa, float phase) {
    vec2 cell = floor(p);
    vec2 f = p - cell;
    // more traces run along the first axis (the weapon's length on its sides and top) than across it
    bool right = hash12(cell + vec2(18.31, 3.17)) < 0.62;
    bool left = hash12(cell + vec2(17.31, 3.17)) < 0.62;
    bool up = hash12(cell + vec2(5.71, 42.93)) < 0.34;
    bool down = hash12(cell + vec2(5.71, 41.93)) < 0.34;
    float d = 1e3;
    float degree = 0.0;
    if (right) { d = min(d, spoke(f, vec2(1.0, 0.5))); degree += 1.0; }
    if (left) { d = min(d, spoke(f, vec2(0.0, 0.5))); degree += 1.0; }
    if (up) { d = min(d, spoke(f, vec2(0.5, 1.0))); degree += 1.0; }
    if (down) { d = min(d, spoke(f, vec2(0.5, 0.0))); degree += 1.0; }
    float soft = max(aa, 1e-4) * 0.8;
    const float width = 0.055;
    float core = 1.0 - smoothstep(width - soft, width + soft, d);
    float halo = degree > 0.5 ? exp(-max(d - width, 0.0) * 11.0) : 0.0;
    float body = 0.0;
    float r = length(f - vec2(0.5));
    if (degree > 0.5 && degree < 1.5) {
        // a dead end: a round pad with a drilled via
        float ring = 1.0 - smoothstep(0.045 - soft, 0.045 + soft, abs(r - 0.13));
        core = max(core, ring);
        core *= smoothstep(0.045 - soft, 0.045 + soft, r);
    } else if (degree > 2.5) {
        core = max(core, 1.0 - smoothstep(0.1 - soft, 0.1 + soft, r));
    } else if (degree < 0.5 && hash12(cell + vec2(3.37, 7.71)) > 0.55) {
        // a chip: a dark package with a lit outline and two rows of pins
        vec2 q = abs(f - vec2(0.5)) - vec2(0.27, 0.17);
        float box = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
        body = 1.0 - smoothstep(-soft, soft, box);
        float outline = 1.0 - smoothstep(0.025 - soft, 0.025 + soft, abs(box));
        float pinX = clamp(floor((f.x - 0.3) / 0.1333 + 0.5), 0.0, 3.0) * 0.1333 + 0.3;
        float pins = 1.0 - smoothstep(0.035 - soft, 0.035 + soft, length(vec2(f.x - pinX, min(abs(f.y - 0.24), abs(f.y - 0.76)))));
        core = max(core, max(outline, pins));
        halo = max(halo, outline * 0.6);
    }
    // a pulse: bright head, fading tail behind it, travelling along `phase`
    float pulse = pow(fract(phase), 7.0);
    // too fine to resolve (far away, grazing): fade to the network's average glow instead of shimmering
    float sharp = 1.0 - smoothstep(0.3, 0.75, aa);
    core = mix(0.16, core, sharp);
    halo = mix(0.12, halo, sharp);
    body = mix(0.1, body, sharp);
    return vec4(core, halo, pulse * core, body);
}

vec3 starLayer(vec2 p, float t) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    float h = hash12(cell);
    vec2 center = vec2(hash12(cell + 7.13), hash12(cell + 3.71)) * 0.7 + 0.15;
    float size = mix(0.035, 0.11, hash12(cell + 1.37)) + length(fwidth(p)) * 0.5;
    float star = smoothstep(size, 0.0, length(f - center)) * step(0.62, h);
    float twinkle = 0.55 + 0.45 * sin(t * (1.0 + 3.0 * h) + h * 40.0);
    vec3 tint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.85, 0.7), hash12(cell + 9.1));
    return tint * star * twinkle;
}

void main() {
    int flags = int(ModelOffset.z + 0.5);
    vec4 albedo = texture(Sampler0, texCoord0) * ColorModulator;
    // sprays (asset spec §29, effect 5): the vertex AO byte carries the decal's facing fade, not occlusion
    bool decal = int(TextureMat[3][3] + 0.5) == 5;
    if (decal) {
        albedo.a *= vertexAo;
    }
    if (albedo.a < TextureMat[0][2]) {
        discard;
    }
    if ((flags & 1) != 0) {
        // fullbright: emissive parts and effects keep their colour; additive blending ignores alpha
        fragColor = (flags & 8) != 0 ? vec4(albedo.rgb * albedo.a, albedo.a) : albedo;
        return;
    }
    vec3 n = normalize(viewNormal);
    if (!gl_FrontFacing) {
        n = -n;
    }
    vec3 geometric = n;
    vec3 v = normalize(-viewPos);
    vec3 t, b;
    bool frame = cotangentFrame(geometric, viewPos, texCoord0, t, b);

    // LabPBR normal map: tilt (DirectX: +y = down the texture = +v), material AO
    // (textures are sampled under per-draw conditions only, so mip selection always has its derivatives)
    float ao = decal ? 1.0 : vertexAo;
    if ((flags & 2) != 0) {
        vec4 nt = texture(NormalSampler, texCoord0);
        ao *= nt.b;
        if (frame) {
            vec2 xy = nt.rg * 2.0 - 1.0;
            vec3 ts = vec3(xy, sqrt(max(0.0, 1.0 - dot(xy, xy))));
            n = normalize(t * ts.x + b * ts.y + geometric * ts.z);
        }
    }

    // material: glTF factors, or the LabPBR specular map
    vec3 albedoLinear = toLinear(albedo.rgb);
    float metallic = TextureMat[0][0];
    float roughness = TextureMat[0][1];
    vec3 f0 = vec3(0.04);
    float emission = 0.0;
    if ((flags & 4) != 0) {
        vec4 st = texture(SpecularSampler, texCoord0);
        roughness = 1.0 - st.r;
        float id = st.g * 255.0;
        if (id < 229.5) {
            f0 = vec3(st.g);
            metallic = 0.0;
        } else if (id < 237.5) {
            f0 = METALS[int(clamp(id - 229.5, 0.0, 7.0))];
            metallic = 1.0;
        } else {
            f0 = albedoLinear;
            metallic = 1.0;
        }
        if (st.a < 0.999) emission = st.a * (255.0 / 254.0);
    } else {
        f0 = mix(f0, albedoLinear, metallic);
    }
    vec3 diffuse = albedoLinear * (1.0 - metallic);

    // skin effects
    int effect = int(TextureMat[3][3] + 0.5);
    vec4 params = TextureMat[2];
    vec3 effectColor = TextureMat[3].rgb;
    float time = TextureMat[1][3];
    float nov = max(dot(n, v), 1e-4);
    vec3 glow = vec3(0.0);
    // a clear coat over the material (circuit): a second, sharp dielectric reflection
    float coat = 0.0;
    if (effect == 1) {
        // iridescent: a thin coating whose colour follows the viewing angle and drifts across the surface
        float pattern = noise3(objectPos * max(params.w, 0.01) + vec3(time * params.z));
        vec3 film = thinFilm(nov, max(params.y, 0.05) * (0.30 + 0.50 * pattern));
        f0 = mix(f0, film * mix(0.55, 1.0, metallic), clamp(params.x, 0.0, 1.0));
        diffuse = mix(diffuse, diffuse * film * 1.6, clamp(params.x, 0.0, 1.0) * 0.5);
    } else if (effect == 2) {
        // flow: the emission mask glows, pulses run along the weapon towards the muzzle (-z)
        float pulseLength = max(params.y, 0.005);
        float period = max(pulseLength * 6.0, 0.05);
        float d = (fract((-objectPos.z - params.x * time) / period) - 0.5) * period;
        float pulse = exp(-(d * d) / (pulseLength * pulseLength) * 2.0);
        glow = toLinear(effectColor) * emission * (clamp(params.z, 0.0, 1.0) + params.w * pulse) * 1.5;
        emission = 0.0;
    } else if (effect == 3) {
        // galaxy: stars and nebula deep below the surface, shifted by the view (parallax)
        vec2 slope = vec2(0.0);
        if (frame) {
            vec3 tangentView = vec3(dot(v, normalize(t)), dot(v, normalize(b)), dot(v, geometric));
            slope = tangentView.xy / max(tangentView.z, 0.25);
        }
        float depth = params.x * TextureMat[0][3] / 0.05;
        vec3 stars = vec3(0.0);
        for (int i = 1; i <= 4; i++) {
            float layer = float(i);
            vec2 p = texCoord0 - slope * depth * layer * 0.25;
            stars += starLayer(p * max(params.y, 0.05) * (3.0 + layer * 1.7), time * params.z + layer * 17.0) * (1.25 - 0.2 * layer);
        }
        vec3 nebula = toLinear(texture(Sampler0, texCoord0 - slope * depth * 0.6).rgb * ColorModulator.rgb) * params.w;
        glow = stars * toLinear(effectColor) * 1.6 + nebula * 0.55;
        diffuse = albedoLinear * 0.12;
    } else if (effect == 4) {
        // circuit: neon traces laid out in the rest-pose space (seamless over the weapon and its attachments), each face
        // projected along its dominant axis; pulses run towards the muzzle (-z), the hue drifts in time and along the gun
        vec3 faceNormal = cross(dFdx(objectPos), dFdy(objectPos));
        vec3 axis = abs(faceNormal) / max(length(faceNormal), 1e-12);
        vec3 w = pow(axis, vec3(6.0));
        w /= max(w.x + w.y + w.z, 1e-5);
        float density = max(params.y, 1.0);
        vec3 cells = objectPos * density;
        vec3 fw = fwidth(objectPos) * density;
        const float PERIOD = 0.18;
        float shift = max(params.x, 0.0) * time / PERIOD;
        vec4 c = vec4(0.0);
        if (w.x > 0.02) c += w.x * circuitPlane(cells.zy, max(fw.z, fw.y), -(cells.z + cells.y) / density / PERIOD - shift);
        if (w.y > 0.02) c += w.y * circuitPlane(cells.zx, max(fw.z, fw.x), -(cells.z + cells.x) / density / PERIOD - shift);
        if (w.z > 0.02) c += w.z * circuitPlane(cells.xy, max(fw.x, fw.y), -(cells.x + cells.y) / density / PERIOD - shift);
        float strength = clamp(params.z, 0.0, 1.0);
        vec3 neon = toLinear(clamp(hueShift(effectColor, 6.2831853 * (params.w * time + objectPos.z * 0.35)), 0.0, 1.0));
        glow = neon * (c.x * (0.3 + 1.2 * strength) + c.y * 0.32 * strength + c.z * 2.6);
        // the traces sit under the lacquer: copper dark where they run, the chips' packages a touch lighter
        diffuse *= (1.0 - 0.6 * c.x) * (1.0 + 0.8 * c.w);
        emission = 0.0;
        coat = 1.0;
    }

    // geometric specular anti-aliasing: curved and bumpy surfaces widen their highlights instead of sparkling
    vec3 dndx = dFdx(n), dndy = dFdy(n);
    float variance = 0.25 * (dot(dndx, dndx) + dot(dndy, dndy));
    float alpha = roughness * roughness;
    alpha = sqrt(clamp(alpha * alpha + min(2.0 * variance, 0.18), 0.0, 1.0));
    float directAlpha = max(alpha, 0.045);

    vec3 ambient = mix(AMBIENT_DOWN, AMBIENT_UP, n.y * 0.5 + 0.5);
    float directAo = mix(1.0, ao, 0.35);
    vec3 color = diffuse * ambient * ao
            + (directLight(n, v, KEY, diffuse, f0, directAlpha, KEY_LIGHT, 1.0)
            + directLight(n, v, FILL, diffuse, f0, directAlpha, FILL_LIGHT, 1.0)
            + directLight(n, v, RIM, diffuse, f0, directAlpha, RIM_LIGHT, 0.0)) * directAo
            + environment(reflect(-v, n), sqrt(alpha)) * environmentBrdf(f0, sqrt(alpha), nov) * ao;
    if (coat > 0.0) {
        float coatNov = max(dot(geometric, v), 1e-4);
        color += coat * (environment(reflect(-v, geometric), 0.06) * environmentBrdf(vec3(0.04), 0.06, coatNov) * ao
                + directLight(geometric, v, KEY, vec3(0.0), vec3(0.04), 0.03, KEY_LIGHT, 0.0) * directAo);
    }

    vec3 display = toDisplay(color) * lightColor.rgb;
    // emission (LabPBR _s alpha, effects) is light of its own: not darkened by the lightmap
    display += albedo.rgb * emission + toDisplay(glow);
    fragColor = vec4(display, albedo.a);
}
