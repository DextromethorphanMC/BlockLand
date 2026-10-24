#version 150

// BlockLand world effects: joint bilateral upsample from the effect's resolution to the world's. Each output pixel
// blends the four nearest low-resolution texels by their bilinear weight and by how close their depth is to its own,
// so occlusion and light shafts stay sharp along silhouettes.

#moj_import <blockland:world_fx.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 lowSize = max(floor(FxSize.xy * FxBlur.w + 0.5), vec2(1.0));
    float z0 = fx_viewZ(fx_depth(texCoord));
    vec2 lp = texCoord * lowSize - 0.5;
    vec2 base = floor(lp);
    vec2 f = lp - base;
    vec4 sum = vec4(0.0);
    float total = 0.0;
    vec4 nearest = vec4(1.0);
    float nearestDiff = 1e9;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            vec2 cell = clamp(base + vec2(float(i), float(j)), vec2(0.0), lowSize - 1.0);
            vec2 uv = (cell + 0.5) / lowSize;
            float z = fx_viewZ(fx_depth(uv));
            float diff = abs(z - z0) / max(abs(z0), 0.05);
            vec4 value = texture(InSampler, uv);
            float bilinear = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
            float w = bilinear / (1e-3 + diff * 40.0);
            sum += value * w;
            total += w;
            if (diff < nearestDiff) {
                nearestDiff = diff;
                nearest = value;
            }
        }
    }
    fragColor = total > 1e-4 ? sum / total : nearest;
}
