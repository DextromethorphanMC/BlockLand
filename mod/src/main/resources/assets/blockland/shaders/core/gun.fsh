#version 150

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec3 viewPos;
in vec3 viewNormal;
in vec2 texCoord0;
in vec4 lightColor;

out vec4 fragColor;

// A small studio light rig in view space (like a weapon viewmodel): key light above/right, cool fill from the
// left, rim from behind, plus a cheap sky-gradient "environment" reflection so dark metal still shows its edges.
// The environment brightness comes from Minecraft's lightmap.
const vec3 KEY = vec3(0.38, 0.82, 0.43);
const vec3 FILL = vec3(-0.80, 0.18, 0.32);
const vec3 RIM = vec3(0.0, 0.35, -0.94);

void main() {
    vec4 albedo = texture(Sampler0, texCoord0) * ColorModulator;
    if (albedo.a < TextureMat[0][2]) {
        discard;
    }
    if (ModelOffset.z > 0.5) {
        fragColor = albedo;
        return;
    }
    vec3 n = normalize(viewNormal);
    if (!gl_FrontFacing) {
        n = -n;
    }
    vec3 v = normalize(-viewPos);
    float specular = TextureMat[0][0];
    float shininess = max(TextureMat[0][1], 1.0);
    float metallic = TextureMat[0][3];
    float gloss = pow(clamp((shininess - 6.0) / 140.0, 0.0, 1.0), 0.333);

    vec3 key = normalize(KEY);
    float diffuse = 0.50 + 0.50 * max(dot(n, key), 0.0) + 0.18 * max(dot(n, normalize(FILL)), 0.0);
    float spec = pow(max(dot(n, normalize(key + v)), 0.0), shininess) * specular;
    float spec2 = pow(max(dot(n, normalize(normalize(FILL) + v)), 0.0), shininess * 0.5) * specular * 0.25;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * max(dot(n, normalize(RIM)) * 0.5 + 0.5, 0.0) * (0.12 + specular * 0.35);

    // environment: a bright sky above the horizon, dark ground below, seen in the reflection direction
    vec3 r = reflect(-v, n);
    float sky = smoothstep(-0.15, 0.75, r.y);
    vec3 environment = mix(vec3(0.06, 0.06, 0.07), vec3(0.80, 0.86, 0.95), sky);
    float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    float reflectivity = mix(fresnel * 0.6, 0.55, metallic) * (0.25 + 0.75 * gloss);

    vec3 specColor = mix(vec3(1.0), clamp(albedo.rgb * 2.5, 0.0, 1.0), metallic);
    vec3 color = albedo.rgb * diffuse * (1.0 - 0.45 * metallic)
            + specColor * (spec + spec2 + rim)
            + specColor * environment * reflectivity;
    fragColor = vec4(color * lightColor.rgb, albedo.a);
}
