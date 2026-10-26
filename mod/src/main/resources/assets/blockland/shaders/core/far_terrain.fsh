#version 150

// Far terrain (client/graphics/far, tools/assets/RENDERING_SPEC.md §22): the lighting pack's terrain look without
// texture detail — vanilla's light (sky 15) shaded by the sun with the clouds' shadows, water reflecting the sky by Fresnel
// with the sun's glint — and BlockLand's fog. Without world lighting it is vanilla's: colour × lightmap, vanilla's fog.
// MASKED: fragments over chunks vanilla or the real far chunks have drawn are dropped (the coverage masks: one texel a
// chunk near the camera, one a cell of chunks farther out), so the far terrain fills only what they left open.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>
#moj_import <blockland:bl_fog.glsl>
#moj_import <blockland:far_view.glsl>

uniform sampler2D Sampler0;
#ifdef MASKED
uniform sampler2D FarMaskSampler;
uniform sampler2D FarMaskCoarseSampler;
#endif

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec3 worldPos;
flat in vec3 faceNormal;
flat in vec3 albedo;
flat in vec3 fullLight;
flat in vec3 blockLight;
flat in int water;
flat in uint sprite;

out vec4 fragColor;

void main() {
#ifdef MASKED
    if (FarMask.z > 0.5) {
        // a wall lies on its cell's edge, often a chunk's: test the chunk of the cell it belongs to (behind its face)
        vec2 chunk = floor((FarCamera.xz + worldPos.xz - faceNormal.xz * 0.5) / 16.0);
        bool covered = false;
        if (all(lessThan(abs(chunk), vec2(FarMask.w * 0.5)))) {
            covered = texelFetch(FarMaskSampler, ivec2(mod(FarMask.xy + chunk, FarMask.w)), 0).r > 0.5;
        }
        if (!covered) {
            vec2 wrapped = mod(FarMaskCoarse.xy + chunk, FarMaskCoarse.z * FarMaskCoarse.w);
            covered = texelFetch(FarMaskCoarseSampler, ivec2(floor(wrapped / FarMaskCoarse.z)), 0).r > 0.5;
        }
        if (covered) {
            discard;
        }
    }
#endif
    vec3 n = faceNormal;
    vec3 albedo = albedo;
    vec3 world = worldPos + BlCamera.xyz;
    if ((sprite & 0x10000000u) != 0u) {
        // the block's own texture, block by block as the real terrain shows it: the face's coordinate within its block,
        // the mip level from how many texels a pixel covers (far off the sprite's last level is its average colour)
        vec2 face = n.y > 0.5 ? world.xz : abs(n.x) > 0.5 ? vec2(world.z * n.x, -world.y) : vec2(-world.x * n.z, -world.y);
        float size = float(16u << ((sprite >> 24) & 15u));
        vec2 origin = vec2(float(sprite & 4095u), float((sprite >> 12) & 4095u)) * 16.0;
        vec2 inBlock = clamp(fract(face), 0.5 / size, 1.0 - 0.5 / size);
        vec2 dx = dFdx(face) * size, dy = dFdy(face) * size;
        float lod = clamp(0.5 * log2(max(max(dot(dx, dx), dot(dy, dy)), 1e-8)), 0.0, FarAtlas.z);
        vec4 texel = textureLod(Sampler0, (origin + inBlock * size) / FarAtlas.xy, lod);
        albedo *= texel.rgb;
    } else {
        // no sprite: block by block a little lighter or darker, faded out where a pixel covers more than a block or two
        vec3 block = floor(world - n * 0.01);
        float footprint = length(fwidth(worldPos));
        float grain = fract(sin(dot(block, vec3(12.9898, 78.233, 37.719))) * 43758.5453) * 2.0 - 1.0;
        albedo *= 1.0 + 0.08 * grain * (1.0 - smoothstep(0.6, 2.5, footprint));
    }
    vec4 color;
    if (bl_worldLit()) {
        float sun = bl_sunVisibility(worldPos, n, gl_FragCoord.xy);
        vec3 light = bl_skyShade(fullLight, blockLight, sun);
        color = vec4(albedo * min(light, vec3(1.0)), 1.0);
        if (water != 0) {
            // far water: the sky by Fresnel against a level surface (a little rough: waves too small to see from here)
            vec3 v = normalize(-worldPos);
            float outdoors = 1.0 - BlCamera.w;
            float fres = bl_fresnel(max(dot(n, v), 0.0), 0.02);
            vec3 r = reflect(-v, n);
            vec3 env = bl_reflection(r, outdoors, FogColor.rgb);
            color.rgb = mix(color.rgb, env, clamp(fres * BlWater.z * 0.85, 0.0, 0.85));
            float spec = bl_sunSpecular(n, v, 0.12) * BlWater.z * outdoors * step(0.0, BlSunDir.y) * bl_cloudShadow(worldPos);
            color.rgb += BlSunColor.rgb * spec * 0.5;
        }
    } else {
        color = vec4(albedo * fullLight, 1.0);
    }
    float fog;
    vec4 fogged = bl_fog(color, sphericalVertexDistance, cylindricalVertexDistance, worldPos, fog);
    fragColor = vec4(fogged.rgb, 1.0);
}
