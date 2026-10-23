#version 150

// BlockLand scope eyepiece: the glass disc of a magnified scope showing the scope's own picture of the world.
// The optics are documented in com.blockland.client.scope.ScopeOptics (keep both in step).
//   ModelViewMat  eyepiece disc (unit disc in its node's XY plane) -> view space, drawn with the viewmodel projection

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

layout(std140) uniform ScopeInfo {
    mat4 ScopeView;     // camera space -> scope frame (the optical axis is -Z)
    vec4 WorldTan;      // x: tan(world half fov) * aspect, y: tan(world half fov), z: field stop tangent, w: 1 / picture margin
    vec4 Eye;           // xy: eye offset from the optical axis in eye boxes, z: eye relief error, w: picture shown (0..1)
    vec4 Reticle;       // x: 1 / reticle size, y: parallax, zw: cos / sin of the scope's cant
    vec4 ReticleColor;  // rgb, a: illumination
    vec4 Glass;         // rgb: what the glass reflects (sky over ground, lit like the player), a: ambient light 0..1
    vec4 Optics;        // x: rim vignette, y: colour fringe, z: barrel distortion, w: has a reticle
    vec4 Sun;           // xyz: direction of the sun in the scope frame, w: how much sunlight reaches the glass (0..1)
    vec4 Lens;          // x: smudges, y: flare, z: one picture texel (uv), w: sharpening
    vec4 Coating;       // rgb: tint of the coating's reflections, a: -
};

in vec3 Position;
in vec2 UV0;

// the eye's ray through this point as the world view around the scope draws it, in the scope's frame; linear in
// screen space, so it is interpolated without perspective
noperspective out vec3 scopeRay;
out vec2 lensPos;
out vec3 viewPos;
out vec3 viewNormal;

void main() {
    vec4 pos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * pos;
    vec2 ndc = gl_Position.xy / gl_Position.w;
    scopeRay = mat3(ScopeView) * vec3(ndc.x * WorldTan.x, ndc.y * WorldTan.y, -1.0);
    lensPos = UV0 * 2.0 - 1.0;
    viewPos = pos.xyz;
    viewNormal = mat3(ModelViewMat) * vec3(0.0, 0.0, 1.0);
}
