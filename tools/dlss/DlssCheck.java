import com.blockland.client.graphics.upscale.dlss.DlssBridge;
import com.blockland.client.graphics.upscale.dlss.DlssException;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Locale;

import org.lwjgl.BufferUtils;

/**
 * Drives the real DLSS bridge on this machine's GPU outside the game (hidden window, no focus): a panning, aliasing-heavy
 * procedural scene is rendered at the render resolution with sub-pixel jitter and exact motion vectors, upscaled by DLSS
 * for a few dozen frames, and compared with a 16× supersampled reference at the output resolution. Every combination of
 * jitter and motion-vector sign is run; the right conventions give a clearly lower error than the wrong ones and than
 * plain single-sample rendering at full resolution, which both proves the bridge works end to end and pins the
 * conventions the game's input pass must use. All values are in the textures' texel space (x right, y = row index).
 * <p>
 * Run through check_dlss.py. Arguments: Streamline runtime folder, log folder.
 */
public final class DlssCheck {
    private static final int OUT_W = 1280, OUT_H = 720, FRAMES = 48;
    /** World units across the output width (the pattern's scale). */
    private static final float SPAN = 64f;

    private static final String VERTEX = """
            #version 330 core
            const vec2 corners[4] = vec2[4](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));
            void main() { gl_Position = vec4(corners[gl_VertexID], 0.0, 1.0); }
            """;

    // world position of texel (x, y) + offset: x right, y = row index (down in DLSS's view, which is all that matters here)
    private static final String SCENE = """
            #version 330 core
            uniform vec2 Size;      // target size in texels
            uniform vec2 Camera;    // world position of the target's corner
            uniform vec2 Jitter;    // sample offset inside the texel (texels)
            uniform vec2 Motion;    // motion vector to write (texels)
            uniform int Samples;    // samples per axis (reference: 4)
            uniform int Flip;       // game convention: the picture is upright in screen space (y up) and flipped into rows
            layout(location = 0) out vec4 color;
            layout(location = 1) out float depth;
            layout(location = 2) out vec2 motion;
            vec3 scene(vec2 w) {
                // a zone plate (every frequency, every direction) over a coloured checker with thin lines
                float r2 = dot(w - vec2(40.0, 25.0), w - vec2(40.0, 25.0));
                float plate = 0.5 + 0.5 * cos(r2 * 0.9);
                vec2 cell = floor(w * 0.25);
                vec3 checker = mod(cell.x + cell.y, 2.0) < 1.0 ? vec3(0.85, 0.3, 0.2) : vec3(0.15, 0.4, 0.9);
                float line = step(abs(fract(w.x * 0.5 + w.y * 0.17) - 0.5), 0.04);
                return mix(checker, vec3(plate), 0.55) * (1.0 - 0.8 * line);
            }
            void main() {
                vec2 texel = floor(gl_FragCoord.xy);
                vec3 sum = vec3(0.0);
                for (int i = 0; i < Samples; i++) {
                    for (int j = 0; j < Samples; j++) {
                        vec2 offset = Samples == 1 ? Jitter + 0.5 : (vec2(i, j) + 0.5) / float(Samples);
                        vec2 p = texel + offset;
                        if (Flip == 1) {
                            // screen pixel of this row (y up); Jitter = the projection's shift of the content (pixels)
                            vec2 screen = vec2(texel.x + 0.5, Size.y - texel.y - 0.5);
                            p = Samples == 1 ? screen - Jitter : screen + offset - 0.5;
                        }
                        sum += scene(Camera + p * (64.0 / 1280.0) * (1280.0 / Size.x));
                    }
                }
                color = vec4(sum / float(Samples * Samples), 1.0);
                depth = 0.5;
                motion = Motion;
            }
            """;

    public static void main(String[] args) {
        Path runtime = Path.of(args[0]);
        Path logs = Path.of(args[1]);
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(64, 64, "dlss check", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GL: " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));

        int exit = 0;
        DlssBridge bridge = null;
        try {
            bridge = DlssBridge.create(runtime, logs, "dlss-check");
            System.out.println("DLSS: " + bridge.description());
            int program = program(VERTEX, SCENE);
            int vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            int fbo = GL30.glGenFramebuffers();

            // the reference and plain native rendering at the output resolution
            int reference = texture(OUT_W, OUT_H);
            int native1x = texture(OUT_W, OUT_H);
            draw(program, fbo, OUT_W, OUT_H, new int[]{reference}, endCamera(), 0, 0, 0, 0, 4);
            draw(program, fbo, OUT_W, OUT_H, new int[]{native1x}, endCamera(), 0, 0, 0, 0, 1);
            float[] ref = read(reference, OUT_W, OUT_H);
            System.out.printf(Locale.ROOT, "native 1 sample/pixel: error %.4f%n", rmse(read(native1x, OUT_W, OUT_H), ref));
            draw(program, fbo, OUT_W, OUT_H, new int[]{native1x}, camera(FRAMES - 2), 0, 0, 0, 0, 4);
            lagged = read(native1x, OUT_W, OUT_H);
            float px = SPAN / OUT_W * 0.5f;
            float[][] shifts = {{px, 0}, {-px, 0}, {0, px}, {0, -px}};
            for (int i = 0; i < 4; i++) {
                float[] c = endCamera();
                draw(program, fbo, OUT_W, OUT_H, new int[]{native1x}, new float[]{c[0] + shifts[i][0], c[1] + shifts[i][1]}, 0, 0, 0, 0, 4);
                shiftedRef[i] = read(native1x, OUT_W, OUT_H);
            }
            diagnose = true;

            for (DlssBridge.Mode mode : new DlssBridge.Mode[]{DlssBridge.Mode.QUALITY, DlssBridge.Mode.PERFORMANCE}) {
                int[] size = bridge.optimalRenderSize(mode, OUT_W, OUT_H);
                bridge.resize(size[0], size[1], OUT_W, OUT_H);
                System.out.printf(Locale.ROOT, "%s: render %dx%d -> %dx%d%n", mode, size[0], size[1], OUT_W, OUT_H);
                System.out.printf(Locale.ROOT, "  game convention (DlssUpscaler's formulas): error %.4f%n",
                        game(bridge, mode, 0, program, fbo, size[0], size[1]));
                if (mode == DlssBridge.Mode.QUALITY) {
                    // every model preset the settings offer (J, K, L, M) must be accepted and work
                    String[] names = {"J", "K", "L", "M"};
                    for (int i = 0; i < 4; i++) {
                        System.out.printf(Locale.ROOT, "  preset %s: error %.4f%n", names[i], game(bridge, mode, 10 + i, program, fbo, size[0], size[1]));
                    }
                }
                for (int jitterSign : new int[]{1, -1}) {
                    for (int motionSign : new int[]{1, -1}) {
                        float error = run(bridge, mode, program, fbo, size[0], size[1], jitterSign, motionSign, ref);
                        System.out.printf(Locale.ROOT, "  jitter %+d, motion %+d: error %.4f%n", jitterSign, motionSign, error);
                    }
                }
            }
        } catch (DlssException e) {
            System.out.println("DLSS UNAVAILABLE: " + e.reason + " — " + e.detail);
            exit = 2;
        } finally {
            if (bridge != null) bridge.close();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
        System.exit(exit);
    }

    /** Camera (world corner) of frame {@code f}: a steady diagonal pan. */
    private static float[] camera(int f) {
        return new float[]{f * 0.37f, f * 0.21f};
    }

    private static float[] endCamera() {
        return camera(FRAMES - 1);
    }

    private static float run(DlssBridge bridge, DlssBridge.Mode mode, int program, int fbo, int rw, int rh, int jitterSign,
                             int motionSign, float[] reference) {
        int preset = 0;
        float worldPerTexel = SPAN / rw;
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        for (int f = 0; f < FRAMES; f++) {
            float[] cam = camera(f), prev = camera(Math.max(0, f - 1));
            float jx = halton(f + 1, 2) - 0.5f, jy = halton(f + 1, 3) - 0.5f;
            // a surface point at world w sits at texel (w - cam) / worldPerTexel: it moved by -(cam - prev) / worldPerTexel
            float mx = motionSign * (cam[0] - prev[0]) / worldPerTexel;
            float my = motionSign * (cam[1] - prev[1]) / worldPerTexel;
            draw(program, fbo, rw, rh, new int[]{bridge.colorTexture(), bridge.depthTexture(), bridge.motionTexture()}, cam, jx, jy, mx, my, 1);
            DlssBridge.Frame frame = new DlssBridge.Frame(jitterSign * jx, jitterSign * jy, 1f / rw, 1f / rh, f == 0,
                    identity, identity, identity, identity, new float[3], new float[]{0, 1, 0}, new float[]{1, 0, 0},
                    new float[]{0, 0, -1}, 0.1f, 100f, 1f, OUT_W / (float) OUT_H);
            bridge.evaluate(mode, preset, frame);
        }
        float[] out = read(bridge.outputTexture(), OUT_W, OUT_H);
        if (diagnose) {
            // brightness (exposure handling), a one-frame lag, and a sub-pixel misregistration would each show here
            System.out.printf(Locale.ROOT, "    mean out %.4f ref %.4f; vs previous frame %.4f; shifted ±0.5 px x %.4f/%.4f y %.4f/%.4f%n",
                    mean(out), mean(reference), rmse(out, lagged), rmse(out, shiftedRef[0]), rmse(out, shiftedRef[1]),
                    rmse(out, shiftedRef[2]), rmse(out, shiftedRef[3]));
        }
        return rmse(out, reference);
    }

    static boolean diagnose;
    static float[] lagged;
    static float[][] shiftedRef = new float[4][];

    private static double mean(float[] a) {
        double sum = 0;
        for (int i = 0; i < a.length; i += 4) sum += a[i] + a[i + 1] + a[i + 2];
        return sum / (a.length / 4 * 3.0);
    }

    static int flip;

    /**
     * The game's path: content drawn upright (screen y up) with the projection shifted by the jitter δ (pixels), flipped
     * into DLSS's rows; DLSS gets jitter (δx, −δy) and motion (screen motion x, −y), as DlssUpscaler computes them.
     */
    private static float game(DlssBridge bridge, DlssBridge.Mode mode, int preset, int program, int fbo, int rw, int rh) {
        float worldPerTexel = SPAN / rw;
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        flip = 1;
        int referenceTex = texture(OUT_W, OUT_H);
        draw(program, fbo, OUT_W, OUT_H, new int[]{referenceTex}, endCamera(), 0, 0, 0, 0, 4);
        float[] reference = read(referenceTex, OUT_W, OUT_H);
        GL11.glDeleteTextures(referenceTex);
        for (int f = 0; f < FRAMES; f++) {
            float[] cam = camera(f), prev = camera(Math.max(0, f - 1));
            float dx = halton(f + 1, 2) - 0.5f, dy = halton(f + 1, 3) - 0.5f;
            // a world point sits at screen (w - cam) / worldPerTexel (y up): previous minus current = (cam - prev) / wpt
            float sx = (cam[0] - prev[0]) / worldPerTexel, sy = (cam[1] - prev[1]) / worldPerTexel;
            draw(program, fbo, rw, rh, new int[]{bridge.colorTexture(), bridge.depthTexture(), bridge.motionTexture()}, cam, dx, dy, sx, -sy, 1);
            DlssBridge.Frame frame = new DlssBridge.Frame(dx, -dy, 1f / rw, 1f / rh, f == 0,
                    identity, identity, identity, identity, new float[3], new float[]{0, 1, 0}, new float[]{1, 0, 0},
                    new float[]{0, 0, -1}, 0.1f, 100f, 1f, OUT_W / (float) OUT_H);
            bridge.evaluate(mode, preset, frame);
        }
        flip = 0;
        return rmse(read(bridge.outputTexture(), OUT_W, OUT_H), reference);
    }

    private static void draw(int program, int fbo, int w, int h, int[] targets, float[] cam, float jx, float jy, float mx, float my, int samples) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        int[] buffers = new int[targets.length];
        for (int i = 0; i < 3; i++) {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, i < targets.length ? targets[i] : 0, 0);
        }
        for (int i = 0; i < targets.length; i++) buffers[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
        GL20.glDrawBuffers(buffers);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("framebuffer incomplete: " + status);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "Size"), w, h);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "Camera"), cam[0], cam[1]);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "Jitter"), jx, jy);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "Motion"), mx, my);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "Samples"), samples);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "Flip"), flip);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static int texture(int w, int h) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return id;
    }

    private static float[] read(int texture, int w, int h) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(w * h * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        float[] out = new float[w * h * 4];
        pixels.get(out);
        return out;
    }

    /** Root mean square error over RGB, ignoring a margin (edges have no history to come from). */
    private static float rmse(float[] a, float[] b) {
        double sum = 0;
        long n = 0;
        int margin = 32;
        for (int y = margin; y < OUT_H - margin; y++) {
            for (int x = margin; x < OUT_W - margin; x++) {
                int i = (y * OUT_W + x) * 4;
                for (int c = 0; c < 3; c++) {
                    double d = a[i + c] - b[i + c];
                    if (Double.isNaN(d)) return Float.NaN;
                    sum += d * d;
                    n++;
                }
            }
        }
        return (float) Math.sqrt(sum / n);
    }

    private static float halton(int index, int base) {
        float result = 0, f = 1;
        while (index > 0) {
            f /= base;
            result += f * (index % base);
            index /= base;
        }
        return result;
    }

    private static int program(String vertex, String fragment) {
        int vs = shader(GL20.GL_VERTEX_SHADER, vertex), fs = shader(GL20.GL_FRAGMENT_SHADER, fragment);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
        return program;
    }

    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        return shader;
    }
}
