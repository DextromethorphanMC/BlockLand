#version 150

// BlockLand reflex sight glass: the reticle is light at infinity — it depends only on the direction of the view ray
// against the sight line, never on where on the glass the ray passes — so it sits on the sight line wherever the eye
// is behind the window and is only seen through the glass. Plus the glass itself: looking straight through it barely
// changes the view; towards the frame it reflects what is around the player, the coating's colour only in that grazing
// reflection.

layout(std140) uniform ReflexInfo {
    mat4 ViewToSight; // rotation from view space into the sight's frame (the sight line is -Z)
    vec4 Reticle;     // dot radius, ring radius, ring width (tangents in the viewmodel's projection), 1 = holographic
    vec4 Color;       // reticle colour (rgb), brightness
    vec4 Coating;     // glass tint (rgb), how much of it shows
    vec4 Environment; // rgb: what the glass reflects (the player's sky, lit like them), a: light around the player 0..1
};

in vec3 viewPos;
in vec3 viewNormal;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 ray = mat3(ViewToSight) * normalize(viewPos);
    float ahead = -ray.z;
    vec2 t = ray.xy / max(ahead, 1e-4);
    float r = length(t);
    // one screen pixel in these tangent units: edges stay crisp at any size, a tiny dot never vanishes
    float px = max(fwidth(r), 1e-6);

    // the dot: a crisp LED core with a little bloom through the coating
    float dotRadius = max(Reticle.x, px * 1.25);
    float core = 1.0 - smoothstep(dotRadius - px, dotRadius + px, r);
    float bloom = exp(-pow(r / (dotRadius * 2.4), 2.0)) * 0.3;
    float mark = max(core, bloom);
    if (Reticle.w > 0.5) {
        // holographic: a ring around the dot, its laser-written lines slightly grainy
        float halfWidth = max(Reticle.z * 0.5, px * 0.75);
        float ring = 1.0 - smoothstep(halfWidth - px, halfWidth + px, abs(r - (Reticle.y - halfWidth)));
        float grain = 0.82 + 0.18 * hash(floor(gl_FragCoord.xy * 0.75));
        mark = max(mark, ring * grain * 0.95);
    }
    mark *= step(0.0, ahead);
#ifdef RETICLE_DEPTH
    // depth-only pass: the reticle's own pixels join the viewmodel's, so the post chain neither smears them with the
    // camera's motion nor reprojects the world's history under them (the glass around it stays "world")
    if (mark < 0.35) {
        discard;
    }
    fragColor = vec4(0.0);
    return;
#endif
    vec3 light = Color.rgb * Color.a * mark;

    // the glass: a fresnel reflection of the surroundings, faintly coloured by the coating towards the frame, and a
    // little light lost through it (more at a slant); blended as scene * (1 - absorb) + reflection + reticle light
    vec3 n = normalize(viewNormal);
    vec3 v = normalize(-viewPos);
    if (dot(n, v) < 0.0) {
        n = -n;
    }
    float facing = max(dot(n, v), 0.0);
    float rim = pow(1.0 - facing, 3.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
    vec3 reflected = reflect(-v, n);
    vec3 surroundings = mix(Environment.rgb * 0.16, Environment.rgb, smoothstep(-0.3, 0.7, reflected.y));
    vec3 reflection = surroundings * fresnel * mix(vec3(1.0), Coating.rgb, 0.35 * smoothstep(0.0, 0.5, rim));
    float absorb = clamp(Coating.a * 0.3 + 0.4 * rim, 0.0, 0.6);
    vec3 add = reflection + light;
    float alpha = clamp(max(absorb, max(add.r, max(add.g, add.b))), 0.0, 1.0);
    fragColor = vec4(add / max(alpha, 1e-3), alpha);
}
