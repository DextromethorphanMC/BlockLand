import com.blockland.client.graphics.upscale.dlss.DlssBridge;
import com.blockland.client.graphics.upscale.dlss.DlssException;
import com.blockland.client.graphics.upscale.dlss.NeuralRendering;
import com.blockland.client.graphics.upscale.dlss.SlRuntime;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.file.Path;

/**
 * Drives DLSS 5 neural rendering (DLSS-NR) outside the game (hidden window, no focus): NGX brought up directly on the
 * Streamline runtime's device, the NGX parameter block's vtable layout recognised by typed round trips, DLSS-NR's
 * capability as the driver reports it, and Super Resolution still running on the same device afterwards. When the driver
 * and GPU support DLSS-NR, the feature is created and evaluated for a few dozen frames as well.
 * <p>
 * Run through check_dlss.py --neural. Arguments: runtime folder (with nvngx_dlssnr.dll), log folder.
 */
public final class NeuralCheck {
    private static final int OUT_W = 1280, OUT_H = 720, RENDER_W = 853, RENDER_H = 480;

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
        long window = GLFW.glfwCreateWindow(64, 64, "neural check", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GL: " + GL11.glGetString(GL11.GL_RENDERER));

        int exit = 1;
        SlRuntime sl = null;
        DlssBridge dlss = null;
        NeuralRendering nr = null;
        try {
            sl = SlRuntime.create(runtime, logs, "neural-check", false);
            System.out.println("runtime: " + sl.description());
            dlss = DlssBridge.on(sl);
            dlss.resize(RENDER_W, RENDER_H, OUT_W, OUT_H);
            int fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            superResolution(fbo, dlss, 4);
            System.out.println("Super Resolution ran before NGX");

            NeuralRendering.Capability capability = NeuralRendering.capability(sl);
            System.out.println("NGX up on the shared device, parameter block layout: " + NeuralRendering.parameterLayout(sl));
            System.out.println("DLSS-NR: available " + capability.available() + ", needs a newer driver " + capability.needsDriver()
                    + (capability.needsDriver() ? " (" + capability.minMajor() + "." + capability.minMinor() + "+)" : "")
                    + ", init result " + Integer.toHexString(capability.initResult()));
            superResolution(fbo, dlss, 4);
            System.out.println("Super Resolution still runs with NGX on the device");

            if (capability.available()) {
                nr = NeuralRendering.on(sl);
                nr.resize(OUT_W, OUT_H, RENDER_W, RENDER_H);
                // a lit scene with structure (blocks of colour, a sloping floor in depth) so the model has something to relight
                int scene = pattern(OUT_W, OUT_H, false), slope = pattern(RENDER_W, RENDER_H, true);
                float none = relight(fbo, nr, scene, slope, new NeuralRendering.Settings(0f, 0.3f, 0.7f, 0, true, 0.5f))[0];
                float full = 0;
                // which network and mask combination actually relights: change (mean |out - in|) and detail kept (sharpness ratio)
                for (int preset : new int[]{NeuralRendering.PRESET_DEFAULT, NeuralRendering.PRESET_B}) {
                    nr.preset(preset);
                    for (boolean autoMask : new boolean[]{true, false}) {
                        for (float strength : new float[]{0.5f, 1f}) {
                            float[] r = relight(fbo, nr, scene, slope, new NeuralRendering.Settings(1f, 0.3f * strength / 0.5f * 0.5f + (strength - 0.5f) * 1.4f,
                                    strength == 1f ? 1f : 0.7f, 0, autoMask, 0.5f));
                            if (preset == NeuralRendering.PRESET_DEFAULT && autoMask && strength == 0.5f) full = r[0];
                            System.out.printf(java.util.Locale.ROOT, "preset %s, auto mask %s, %s: change %.4f, sharpness %.2f%n",
                                    preset == 0 ? "default" : "B", autoMask ? "on " : "off", strength == 1f ? "tone/structure 100%" : "defaults           ",
                                    r[0], r[1]);
                        }
                    }
                }
                System.out.printf(java.util.Locale.ROOT, "intensity 0: change %.4f%n", none);
                if (!(full > none + 0.0005f)) throw new DlssException("blockland.graphics.status.nr.failed", "the settings do not reach the model");
            } else {
                System.out.println("DLSS-NR itself not run here (see above); the integration up to the driver check is verified");
            }
            exit = 0;
            System.out.println("NEURAL RENDERING CHECK OK");
        } catch (DlssException e) {
            System.out.println("FAILED: " + e.reason + " — " + e.detail);
            e.printStackTrace();
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            if (nr != null) nr.close();
            if (dlss != null) dlss.close();
            if (sl != null) sl.close();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
        System.exit(exit);
    }

    /**
     * 30 frames of the model on the pattern: {mean absolute change of the output against the input (SDR), the output's fine
     * detail over the input's (mean |Laplacian|, 1 = as sharp)}.
     */
    private static float[] relight(int fbo, NeuralRendering nr, int scene, int slope, NeuralRendering.Settings settings) {
        for (int i = 0; i < 30; i++) {
            copy(fbo, scene, nr.colorTexture(), OUT_W, OUT_H);
            copy(fbo, slope, nr.depthTexture(), RENDER_W, RENDER_H);
            clear(fbo, nr.motionTexture(), 0, 0, 0, 0);
            clear(fbo, nr.maskTexture(), 1, 1, 1, 1);
            nr.evaluate(settings, 0, 0, i == 0);
        }
        GL11.glFinish();
        float[] in = read(scene, OUT_W, OUT_H), out = read(nr.outputTexture(), OUT_W, OUT_H);
        double sum = 0;
        for (int i = 0; i < in.length; i += 4) {
            for (int c = 0; c < 3; c++) sum += Math.abs(out[i + c] - in[i + c]);
        }
        return new float[]{(float) (sum / (in.length / 4 * 3)), (float) (detail(out) / Math.max(1e-9, detail(in)))};
    }

    private static double detail(float[] image) {
        double sum = 0;
        for (int y = 1; y < OUT_H - 1; y++) {
            for (int x = 1; x < OUT_W - 1; x++) {
                int i = (y * OUT_W + x) * 4;
                double l = 4 * image[i + 1] - image[i + 1 - 4] - image[i + 1 + 4] - image[i + 1 - OUT_W * 4] - image[i + 1 + OUT_W * 4];
                sum += Math.abs(l);
            }
        }
        return sum;
    }

    /** Colour: coloured blocks under light from the top left (RGBA16F, linear SDR); depth: a floor rising to the horizon (R32F). */
    private static int pattern(int w, int h, boolean depth) {
        java.nio.FloatBuffer data = org.lwjgl.BufferUtils.createFloatBuffer(w * h * 4);
        float[][] palette = {{0.45f, 0.32f, 0.2f}, {0.25f, 0.5f, 0.2f}, {0.5f, 0.5f, 0.52f}, {0.6f, 0.55f, 0.4f}};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                if (depth) {
                    data.put(i, 0.98f - 0.3f * y / h);
                    continue;
                }
                float[] c = palette[(x / 64 + y / 64 * 3) % palette.length];
                // a little texture grain like Minecraft's, so lost detail shows
                float grain = 0.85f + 0.15f * (((x * 7 + y * 13) ^ (x * y)) & 7) / 7f;
                float light = (0.4f + 0.6f * (1 - (x + (h - y)) / (float) (w + h))) * grain;
                data.put(i, c[0] * light).put(i + 1, c[1] * light).put(i + 2, c[2] * light).put(i + 3, 1);
            }
        }
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depth ? GL30.GL_R32F : GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, data);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return texture;
    }

    private static void copy(int fbo, int from, int to, int w, int h) {
        int read = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, from, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, to, 0);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glDeleteFramebuffers(read);
    }

    private static float[] read(int texture, int w, int h) {
        java.nio.FloatBuffer pixels = org.lwjgl.BufferUtils.createFloatBuffer(w * h * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        float[] values = new float[w * h * 4];
        pixels.get(values);
        return values;
    }

    private static void superResolution(int fbo, DlssBridge dlss, int frames) {
        for (int i = 0; i < frames; i++) {
            clear(fbo, dlss.colorTexture(), 0.2f, 0.5f, 0.8f, 1);
            clear(fbo, dlss.depthTexture(), 0.5f, 0, 0, 1);
            clear(fbo, dlss.motionTexture(), 0, 0, 0, 0);
            float[] id = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            dlss.evaluate(DlssBridge.Mode.QUALITY, 0, new DlssBridge.Frame(0, 0, 1f / RENDER_W, 1f / RENDER_H, i == 0, id, id, id, id,
                    new float[]{0, 0, 0}, new float[]{0, 1, 0}, new float[]{1, 0, 0}, new float[]{0, 0, -1}, 0.05f, 256, 1.2f, 16 / 9f));
        }
        GL11.glFinish();
    }

    private static void clear(int fbo, int texture, float r, float g, float b, float a) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glClearColor(r, g, b, a);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }
}
