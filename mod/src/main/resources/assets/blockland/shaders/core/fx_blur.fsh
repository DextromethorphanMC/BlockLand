#version 150

// BlockLand world effects: one direction of a depth-aware Gaussian blur (9 taps) at the effect's resolution, so
// occlusion and light shafts are smoothed within a surface but never across a depth edge.

#moj_import <blockland:world_fx.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    const float weights[5] = float[5](0.2270270, 0.1945946, 0.1216216, 0.0540541, 0.0162162);
    float z0 = fx_viewZ(fx_depth(texCoord));
    vec4 sum = texture(InSampler, texCoord) * weights[0];
    float total = weights[0];
    for (int i = 1; i < 5; i++) {
        for (int side = -1; side <= 1; side += 2) {
            vec2 uv = texCoord + FxBlur.xy * float(i * side);
            float z = fx_viewZ(fx_depth(uv));
            float w = weights[i] * exp(-abs(z - z0) / max(abs(z0), 0.05) * FxBlur.z);
            sum += texture(InSampler, uv) * w;
            total += w;
        }
    }
    fragColor = sum / total;
}
