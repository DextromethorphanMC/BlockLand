#version 150

// Far plane everywhere in the tile (depth test GREATER: every stored depth below 1 is replaced).

out vec4 fragColor;

void main() {
    gl_FragDepth = 1.0;
    fragColor = vec4(1.0);
}
