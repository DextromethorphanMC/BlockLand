#version 150

#moj_import <blockland:gfx_screen.glsl>
#moj_import <blockland:scene.glsl>
#moj_import <blockland:bl_lighting.glsl>
#moj_import <blockland:bliss_water.glsl>

// Caustics under water (client/graphics/water/UnderwaterCaustics, rendering spec §15), right after the level while the
// camera is in water. The caustics are Bliss's — ported from Bliss (Chocapic13' Shaders edit) by Xonk, based on
// Chocapic13's shaders; see assets/blockland/shaders/include/bliss/license.md: dimensions/composite1.fsh's
//   DirectLightColor *= mix(1.0, waterCaustics(feetPlayerPos + cameraPosition, WsunVec)*WATER_CAUSTICS_BRIGHTNESS + 0.25,
//                           clamp(estimatedDepth,0,1));
// BlockLand: applied after the fact to the lit display colour (in linear light), the sun's share weighed by its shadow at
// the point and how squarely the surface faces it; estimatedDepth is the depth under the water's surface (its height over
// the camera found on the CPU; Bliss estimates it from the light map). The alpha (emission, spec §3) passes through.
//   Params[0] = (surface height over the camera (blocks), -, -, -)

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    float depth = texture(DepthSampler, texCoord).r;
    vec3 feetPlayerPos = bl_worldFromView(bl_viewPosition(texCoord, depth));
    // the surface's normal from the screen derivatives, in uniform control flow
    vec3 n = normalize(cross(dFdx(feetPlayerPos), dFdy(feetPlayerPos)) + vec3(0.0, 1e-9, 0.0));
    if (dot(n, feetPlayerPos) > 0.0) {
        n = -n;
    }
    fragColor = scene;
    float estimatedDepth = Params[0].x - feetPlayerPos.y;
    if (depth >= 1.0 || !bl_worldLit() || estimatedDepth <= 0.0 || BlSunDir.y <= 0.0 || bliss_lightSign() < 0.0) {
        return;
    }
    float facing = clamp(dot(n, BlSunDir.xyz) * 2.0 + 0.2, 0.0, 1.0);
    float sunShare = bl_shadow(feetPlayerPos, n, gl_FragCoord.xy) * clamp(BlSunColor.a, 0.0, 1.0) * facing;
    if (sunShare <= 0.001) {
        sunShare = 0.0;
    }
    // BlockLand: the light that reached this deep came down through the water (absorbed per channel, as the composite
    // has it from above), and the waves focus it most a block or two down, not at all deep down (bl_causticStrength)
    float sunY = max(BlSunDir.y, 0.25);
    vec3 lin = bl_linear(scene.rgb) * exp(-bl_waterAbsorb() * estimatedDepth * (0.5 + 0.5 / sunY));
    float strength = bl_causticStrength(estimatedDepth) * sunShare;
    if (strength > 0.001) {
        float caustics = waterCaustics(feetPlayerPos + BlCamera.xyz, BlSunDir.xyz)*WATER_CAUSTICS_BRIGHTNESS + 0.25;
        lin *= mix(1.0, caustics, strength);
    }
    fragColor = vec4(bl_display(lin), scene.a);
}
