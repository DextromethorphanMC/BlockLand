#version 150

// BlockLand lighting pack (tools/assets/RENDERING_SPEC.md §3): vanilla's terrain vertex shader plus the inputs of the lit
// path and the wind (§18). Every vanilla output is computed exactly as vanilla does; the rest is only read when the
// program is patched, and plants only move while it is patched and the world is lit.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>
#moj_import <blockland:bl_wind.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
// lit path: camera-relative position and normal, the vertex shade (face shading and smooth-lighting AO), the colour
// with block light only, and the light levels (block, sky) 0..1
out vec3 worldPos;
out vec3 worldNormal;
out vec3 vertexShade;
out vec3 blockColor;
out vec2 lightLevel;

vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

// The wind bits of this vertex's sprite (spec §18). FaceBakery shrinks every face's uvs a little towards the sprite's
// middle (TextureAtlasSprite.uvShrinkRatio), so the vertex's own uv lies inside its own sprite whichever way the face's u
// and v run: its atlas cell is the sprite's. (Looking past the uv, half a cell outwards, could land in the neighbouring
// sprite and set a still block such as sand waving whenever a plant sat next to it in the atlas.)
int windBits() {
    return bl_blockWind(UV0);
}

void main() {
    vec3 pos = Position + ModelOffset;
    vec3 lit = pos;
    if (BlRole != 0 && bl_worldLit() && BlWind.w > 0.0) {
        int corner = gl_VertexID & 3;
        int bits = windBits();
        if (bits != 0) pos = bl_wind(pos, Position, bits, corner, Normal);
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;

    // where it would stand without the wind: its shadow and light are looked up there, the shadow map holding still plants
    worldPos = lit;
    worldNormal = Normal;
    vertexShade = Color.rgb;
    blockColor = Color.rgb * minecraft_sample_lightmap(Sampler2, ivec2(UV2.x, 0)).rgb;
    lightLevel = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
}
