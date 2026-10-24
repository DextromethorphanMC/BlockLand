import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Checks BlockLand's voxel ray tracer (rt_common.glsl) against a CPU reference, outside the game (hidden window): an atlas is
 * filled the way client/graphics/raytrace/VoxelWorld fills it (section tiles, toroidal slots, a box corner at negative
 * section coordinates), random blocks are placed, and a few thousand rays are marched on the GPU with rt_march; every hit
 * cell must be the first solid cell a fine CPU march finds. Run through check_voxels.py. Argument: rt_common.glsl.
 */
public final class VoxelTraceCheck {
    private static final int NX = 6, NY = 8, NZ = 6, COLUMNS = 32, TILE = 64;
    private static final int CORNER_X = -3, CORNER_Y = -2, CORNER_Z = 5;
    private static final int RAYS = 4096;

    public static void main(String[] args) throws Exception {
        String common = Files.readString(Path.of(args[0])).replaceFirst("#version 150", "");
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(64, 64, "voxel trace check", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        // blocks: world cells inside the box, solid (kind 255) with a colour encoding their position
        int cellsX = NX * 16, cellsY = NY * 16, cellsZ = NZ * 16;
        boolean[] solid = new boolean[cellsX * cellsY * cellsZ];
        Random random = new Random(1234);
        for (int i = 0; i < solid.length; i++) solid[i] = random.nextFloat() < 0.02f;
        int rows = (NX * NY * NZ + COLUMNS - 1) / COLUMNS;
        int width = COLUMNS * TILE, height = rows * TILE;
        ByteBuffer atlas = BufferUtils.createByteBuffer(width * height * 4);
        for (int vy = 0; vy < cellsY; vy++) {
            for (int vz = 0; vz < cellsZ; vz++) {
                for (int vx = 0; vx < cellsX; vx++) {
                    if (!solid[index(vx, vy, vz)]) continue;
                    // VoxelWorld: world section → slot, block → texel in the slot's tile
                    int sx = CORNER_X + (vx >> 4), sy = CORNER_Y + (vy >> 4), sz = CORNER_Z + (vz >> 4);
                    int slot = Math.floorMod(sx, NX) + NX * (Math.floorMod(sz, NZ) + NZ * Math.floorMod(sy, NY));
                    int x = vx & 15, y = vy & 15, z = vz & 15;
                    int u = (slot % COLUMNS) * TILE + x + 16 * (y & 3), v = (slot / COLUMNS) * TILE + z + 16 * (y >> 2);
                    int at = (v * width + u) * 4;
                    atlas.put(at, (byte) 200).put(at + 1, (byte) 100).put(at + 2, (byte) 50).put(at + 3, (byte) 255);
                }
            }
        }
        int colors = texture(GL11.GL_RGBA8, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, atlas);
        int light = texture(GL11.GL_RGBA8, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(width * height * 4));

        // rays: origins in empty cells inside the box, random directions
        FloatBuffer rays = BufferUtils.createFloatBuffer(RAYS * 2 * 4);
        List<float[]> list = new ArrayList<>();
        while (list.size() < RAYS) {
            float ox = 20 + random.nextFloat() * (cellsX - 40), oy = 20 + random.nextFloat() * (cellsY - 40), oz = 20 + random.nextFloat() * (cellsZ - 40);
            if (solid[index((int) ox, (int) oy, (int) oz)]) continue;
            double a = random.nextDouble() * Math.PI * 2, c = random.nextDouble() * 2 - 1, s = Math.sqrt(1 - c * c);
            list.add(new float[]{ox, oy, oz, (float) (s * Math.cos(a)), (float) c, (float) (s * Math.sin(a))});
        }
        for (int i = 0; i < RAYS; i++) {
            float[] r = list.get(i);
            rays.put(i * 4, r[0]).put(i * 4 + 1, r[1]).put(i * 4 + 2, r[2]).put(i * 4 + 3, 0);
            rays.put((RAYS + i) * 4, r[3]).put((RAYS + i) * 4 + 1, r[4]).put((RAYS + i) * 4 + 2, r[5]).put((RAYS + i) * 4 + 3, 0);
        }
        int rayTexture = texture(GL30.GL_RGBA32F, RAYS, 2, GL11.GL_RGBA, GL11.GL_FLOAT, rays);
        int result = texture(GL30.GL_RGBA32F, RAYS, 1, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);

        String fragment = "#version 150\n" + common + """
                uniform sampler2D Rays;
                out vec4 fragColor;
                void main() {
                    int i = int(gl_FragCoord.x);
                    vec3 origin = texelFetch(Rays, ivec2(i, 0), 0).xyz;
                    vec3 dir = texelFetch(Rays, ivec2(i, 1), 0).xyz;
                    uint seed = 1u;
                    float t;
                    ivec3 cell, front;
                    vec3 n;
                    bool hit = rt_march(origin, dir, 40.0, 200, seed, t, cell, front, n);
                    fragColor = hit ? vec4(vec3(cell), 1.0) : vec4(0.0);
                }
                """;
        String vertex = """
                #version 150
                const vec2 corners[4] = vec2[4](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));
                void main() { gl_Position = vec4(corners[gl_VertexID], 0.0, 1.0); }
                """;
        int program = program(vertex, fragment);
        GL20.glUseProgram(program);
        // RtInfo: two mat4, then ten vec4 (std140)
        FloatBuffer info = BufferUtils.createFloatBuffer(32 + 40);
        for (int i = 0; i < 32; i++) info.put(i, i % 5 == 0 ? 1 : 0);
        float[] vecs = {0, 0, 0, 0, 0, 0, RAYS, 1, 0, 0, 0, 0,
                NX, NY, NZ, COLUMNS, Math.floorMod(CORNER_X, NX), Math.floorMod(CORNER_Y, NY), Math.floorMod(CORNER_Z, NZ), 0,
                1, 40, 200, 3, 1, 1, 1, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0};
        for (int i = 0; i < vecs.length; i++) info.put(32 + i, vecs[i]);
        int ubo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, info, GL15.GL_STATIC_DRAW);
        GL31.glUniformBlockBinding(program, GL31.glGetUniformBlockIndex(program, "RtInfo"), 0);
        org.lwjgl.opengl.GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, ubo);
        bind(program, "VoxelColor", 0, colors);
        bind(program, "VoxelLight", 1, light);
        bind(program, "Rays", 2, rayTexture);
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, result, 0);
        GL11.glViewport(0, 0, RAYS, 1);
        GL30.glBindVertexArray(GL30.glGenVertexArrays());
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        FloatBuffer out = BufferUtils.createFloatBuffer(RAYS * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, result);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, out);

        int hits = 0, wrong = 0;
        for (int i = 0; i < RAYS; i++) {
            float[] r = list.get(i);
            int[] expected = reference(solid, r, 40, cellsX, cellsY, cellsZ);
            boolean gpuHit = out.get(i * 4 + 3) > 0.5f;
            if (expected == null) {
                if (gpuHit) wrong++;
                continue;
            }
            hits++;
            if (!gpuHit || (int) out.get(i * 4) != expected[0] || (int) out.get(i * 4 + 1) != expected[1] || (int) out.get(i * 4 + 2) != expected[2]) {
                if (wrong < 5) {
                    System.out.printf("ray %d: expected %d %d %d, got %s%n", i, expected[0], expected[1], expected[2],
                            gpuHit ? (int) out.get(i * 4) + " " + (int) out.get(i * 4 + 1) + " " + (int) out.get(i * 4 + 2) : "a miss");
                }
                wrong++;
            }
        }
        System.out.printf("%d rays, %d hits by the reference, %d disagreements%n", RAYS, hits, wrong);
        // a ray that grazes exactly along a cell edge may go either way; allow a handful
        boolean ok = wrong <= RAYS / 500 && hits > RAYS / 4;
        System.out.println(ok ? "VOXEL TRACE OK" : "VOXEL TRACE WRONG");
        GLFW.glfwTerminate();
        System.exit(ok ? 0 : 1);
    }

    private static int index(int x, int y, int z) {
        return x + NX * 16 * (z + NZ * 16 * y);
    }

    /** First solid cell along the ray within the distance, by fine steps (volume space), or null. */
    private static int[] reference(boolean[] solid, float[] r, float longest, int cx, int cy, int cz) {
        int lx = (int) Math.floor(r[0]), ly = (int) Math.floor(r[1]), lz = (int) Math.floor(r[2]);
        for (double t = 0; t <= longest; t += 0.0005) {
            int x = (int) Math.floor(r[0] + r[3] * t), y = (int) Math.floor(r[1] + r[4] * t), z = (int) Math.floor(r[2] + r[5] * t);
            if (x == lx && y == ly && z == lz) continue;
            lx = x;
            ly = y;
            lz = z;
            if (x < 0 || y < 0 || z < 0 || x >= cx || y >= cy || z >= cz) return null;
            if (solid[index(x, y, z)]) return new int[]{x, y, z};
        }
        return null;
    }

    private static int texture(int internal, int w, int h, int format, int type, java.nio.Buffer data) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        if (data instanceof ByteBuffer b) GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, b);
        else GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (FloatBuffer) data);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return t;
    }

    private static void bind(int program, String name, int unit, int texture) {
        GL20.glUniform1i(GL20.glGetUniformLocation(program, name), unit);
        GL20.glActiveTexture(GL20.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static int program(String vertex, String fragment) {
        int program = GL20.glCreateProgram();
        for (int[] stage : new int[][]{{GL20.GL_VERTEX_SHADER, 0}, {GL20.GL_FRAGMENT_SHADER, 1}}) {
            int shader = GL20.glCreateShader(stage[0]);
            GL20.glShaderSource(shader, stage[1] == 0 ? vertex : fragment);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(program, shader);
        }
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
        return program;
    }
}
