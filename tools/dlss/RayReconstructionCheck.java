import com.blockland.client.graphics.upscale.dlss.DlssBridge;
import com.blockland.client.graphics.upscale.dlss.DlssException;
import com.blockland.client.graphics.upscale.dlss.SlRuntime;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Locale;

import org.lwjgl.BufferUtils;

/**
 * Drives DLSS Ray Reconstruction through BlockLand's bridge outside the game (hidden window, no focus): the runtime with
 * Ray Reconstruction loaded, its three guides (diffuse albedo, specular albedo, normals with roughness) tagged with the
 * colour, depth and motion vectors, and a few dozen frames evaluated; a noisy flat input must come out smoother than it went
 * in (Ray Reconstruction denoises), and Super Resolution must still run on the same bridge afterwards.
 * <p>
 * Run through check_dlss.py --rr. Arguments: runtime folder (with sl.dlss_d.dll and nvngx_dlssd.dll), log folder.
 */
public final class RayReconstructionCheck {
    private static final int OUT_W = 1280, OUT_H = 720, RENDER_W = 853, RENDER_H = 480, FRAMES = 40;

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
        long window = GLFW.glfwCreateWindow(64, 64, "rr check", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GL: " + GL11.glGetString(GL11.GL_RENDERER));

        int exit = 1;
        SlRuntime sl = null;
        DlssBridge dlss = null;
        try {
            sl = SlRuntime.create(runtime, logs, "rr-check", false);
            System.out.println("runtime: " + sl.description());
            if (!sl.rayReconstruction()) {
                DlssException why = sl.rayReconstructionFailure();
                System.out.println("Ray Reconstruction not available: " + (why == null ? "?" : why.reason + " — " + why.detail));
                return;
            }
            dlss = DlssBridge.on(sl);
            dlss.resize(RENDER_W, RENDER_H, OUT_W, OUT_H);
            dlss.ensureGuides();
            int fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            float[] id = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            int noisy = noise(RENDER_W, RENDER_H);
            for (int i = 0; i < FRAMES; i++) {
                blit(fbo, noisy, dlss.colorTexture(), RENDER_W, RENDER_H);
                clear(fbo, dlss.depthTexture(), 0.5f, 0, 0, 1);
                clear(fbo, dlss.motionTexture(), 0, 0, 0, 0);
                clear(fbo, dlss.albedoTexture(), 0.5f, 0.5f, 0.5f, 1);
                clear(fbo, dlss.specularTexture(), 0.04f, 0.04f, 0.04f, 1);
                clear(fbo, dlss.normalTexture(), 0, 0, 1, 1);
                dlss.evaluate(DlssBridge.Mode.QUALITY, 0, frame(i == 0), id, id);
            }
            GL11.glFinish();
            float[] in = stats(noisy, RENDER_W, RENDER_H), out = stats(dlss.outputTexture(), OUT_W, OUT_H);
            System.out.printf(Locale.ROOT, "noisy input: mean %.3f, deviation %.3f; Ray Reconstruction output: mean %.3f, deviation %.3f%n",
                    in[0], in[1], out[0], out[1]);
            for (int i = 0; i < 4; i++) {
                blit(fbo, noisy, dlss.colorTexture(), RENDER_W, RENDER_H);
                dlss.evaluate(DlssBridge.Mode.QUALITY, 0, frame(i == 0));
            }
            GL11.glFinish();
            System.out.println("Super Resolution runs again after Ray Reconstruction");
            boolean smoother = out[1] < in[1] * 0.7f && Math.abs(out[0] - in[0]) < 0.15f;
            exit = smoother ? 0 : 1;
            System.out.println(smoother ? "RAY RECONSTRUCTION OK" : "RAY RECONSTRUCTION DID NOT DENOISE (see the numbers and sl.log)");
        } catch (DlssException e) {
            System.out.println("FAILED: " + e.reason + " — " + e.detail);
            e.printStackTrace();
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            if (dlss != null) dlss.close();
            if (sl != null) sl.close();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            System.exit(exit);
        }
    }

    private static DlssBridge.Frame frame(boolean reset) {
        float[] id = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new DlssBridge.Frame(0, 0, 1f / RENDER_W, 1f / RENDER_H, reset, id, id, id, id, new float[]{0, 0, 0},
                new float[]{0, 1, 0}, new float[]{1, 0, 0}, new float[]{0, 0, -1}, 0.05f, 256, 1.2f, 16 / 9f);
    }

    /** A flat grey with strong per-pixel noise (what one ray per pixel looks like), RGBA16F. */
    private static int noise(int w, int h) {
        FloatBuffer data = BufferUtils.createFloatBuffer(w * h * 4);
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < w * h; i++) {
            float v = Math.max(0, 0.4f + (random.nextFloat() - 0.5f) * 0.6f);
            data.put(v).put(v).put(v).put(1);
        }
        data.flip();
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, data);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return texture;
    }

    private static void blit(int fbo, int from, int to, int w, int h) {
        int read = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, from, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, to, 0);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glDeleteFramebuffers(read);
    }

    private static void clear(int fbo, int texture, float r, float g, float b, float a) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glClearColor(r, g, b, a);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    /** Mean and standard deviation of the red channel over the centre of a texture. */
    private static float[] stats(int texture, int w, int h) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(w * h * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        double sum = 0, squares = 0;
        int n = 0;
        for (int y = h / 4; y < 3 * h / 4; y++) {
            for (int x = w / 4; x < 3 * w / 4; x++) {
                float v = pixels.get((y * w + x) * 4);
                sum += v;
                squares += v * v;
                n++;
            }
        }
        double mean = sum / n;
        return new float[]{(float) mean, (float) Math.sqrt(Math.max(0, squares / n - mean * mean))};
    }
}
