import com.blockland.client.graphics.upscale.dlss.DlssBridge;
import com.blockland.client.graphics.upscale.dlss.FrameGeneration;
import com.blockland.client.graphics.upscale.dlss.SlRuntime;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Drives the real DLSS Frame Generation path outside the game: a small window that never takes the focus, BlockLand's
 * runtime with Frame Generation, Reflex and PCL (and Super Resolution on the same runtime), the presenter's child window
 * and swapchain, then a few hundred frames at 2× and at the most the system allows, each with the HUD-less colour, UI,
 * depth and motion vectors tagged. Passes when Streamline accepts all of it with no error status (Streamline's log shows
 * any rejected input). DLSS-G itself only generates while a window of the process is in the foreground and visible, so
 * the frames-on-screen figure stays at 1 here unless the window is shown ("visible") and clicked.
 * <p>
 * Run through check_dlss.py --framegen. Arguments: Streamline runtime folder, log folder.
 */
public final class FrameGenCheck {
    private static final int OUT_W = 960, OUT_H = 540, RENDER_W = 640, RENDER_H = 360, FRAMES = 240;

    public static void main(String[] args) {
        Path runtime = Path.of(args[0]);
        Path logs = Path.of(args[1]);
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, args.length > 2 && args[2].equals("visible") ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(OUT_W, OUT_H, "frame generation check", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(0);
        GL.createCapabilities();
        System.out.println("GL: " + GL11.glGetString(GL11.GL_RENDERER));

        int exit = 1;
        SlRuntime sl = null;
        FrameGeneration fg = null;
        DlssBridge dlss = null;
        try {
            sl = SlRuntime.create(runtime, logs, "framegen-check", true);
            System.out.println("runtime: " + sl.description());
            // Super Resolution on the same runtime: both features share the device and the frame sequence
            dlss = DlssBridge.on(sl);
            dlss.resize(RENDER_W, RENDER_H, OUT_W, OUT_H);
            fg = FrameGeneration.create(sl, GLFWNativeWin32.glfwGetWin32Window(window), 1);
            fg.resize(OUT_W, OUT_H, RENDER_W, RENDER_H);
            int fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            float best = 0;
            int bad = 0;
            for (int pass = 0; pass < 2; pass++) {
                int multiplier = pass == 0 ? 2 : Math.max(2, fg.state().maxFrames() + 1);
                long start = System.nanoTime();
                for (int i = 0; i < FRAMES; i++) {
                    GLFW.glfwPollEvents();
                    fg.beginFrame();
                    float t = i / (float) FRAMES;
                    clear(fbo, dlss.colorTexture(), t, 0.3f, 1 - t, 1);
                    clear(fbo, dlss.depthTexture(), 0.5f, 0, 0, 1);
                    clear(fbo, dlss.motionTexture(), 2f, 0, 0, 0);
                    dlss.evaluate(DlssBridge.Mode.QUALITY, 0, frame(i, i == 0));
                    clear(fbo, fg.colorTexture(), t, 0.3f, 1 - t, 1);
                    clear(fbo, fg.hudlessTexture(), t, 0.3f, 1 - t, 1);
                    clear(fbo, fg.uiTexture(), 0, 0, 0, 0);
                    clear(fbo, fg.depthTexture(), 0.5f, 0, 0, 1);
                    clear(fbo, fg.motionTexture(), 2f, 0, 0, 0);
                    fg.present(multiplier, frame(i, i == 0));
                    GLFW.glfwSwapBuffers(window);
                    if (fg.state().status() != 0) bad++;
                }
                float seconds = (System.nanoTime() - start) / 1e9f;
                FrameGeneration.State state = fg.state();
                System.out.printf(Locale.ROOT, "%d×: status %d, max %d×, %.2f frames on screen per rendered frame, %.0f rendered fps%n",
                        multiplier, state.status(), state.maxFrames() + 1, state.presented(), FRAMES / seconds);
                best = Math.max(best, state.presented());
            }
            // a world change: the presenter goes and comes back on the same runtime (Streamline is never brought up twice)
            fg.close();
            fg = FrameGeneration.create(sl, GLFWNativeWin32.glfwGetWin32Window(window), 1);
            fg.resize(OUT_W, OUT_H, RENDER_W, RENDER_H);
            for (int i = 0; i < 30; i++) {
                fg.beginFrame();
                clear(fbo, fg.colorTexture(), 0.5f, 0.5f, 0.5f, 1);
                fg.present(2, frame(i, i == 0));
                if (fg.state().status() != 0) bad++;
            }
            System.out.println("presenter reopened on the same runtime: status " + fg.state().status());
            exit = bad == 0 ? 0 : 1;
            if (exit != 0) System.out.println("FRAME GENERATION FAILED (" + bad + " frames with a bad status; see the log)");
            else if (best > 1.5f) System.out.println("FRAME GENERATION OK (generated frames reached the screen)");
            else System.out.println("FRAME GENERATION OK (inputs accepted; nothing generated without the focus, see sl.log)");
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            if (fg != null) fg.close();
            if (dlss != null) dlss.close();
            if (sl != null) sl.close();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
        System.exit(exit);
    }

    private static void clear(int fbo, int texture, float r, float g, float b, float a) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glClearColor(r, g, b, a);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    /** A camera panning sideways (motion vectors of 2 render pixels per frame), unjittered. */
    private static DlssBridge.Frame frame(int i, boolean reset) {
        float near = 0.05f, far = 256, fov = 1.2f, aspect = OUT_W / (float) OUT_H;
        float f = (float) (1 / Math.tan(fov / 2));
        float[] proj = {f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) / (near - far), -1, 0, 0, 2 * far * near / (near - far), 0};
        float[] id = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new DlssBridge.Frame(0, 0, 1f / RENDER_W, 1f / RENDER_H, reset, proj, invert(proj), id, id,
                new float[]{i * 0.1f, 64, 0}, new float[]{0, 1, 0}, new float[]{1, 0, 0}, new float[]{0, 0, -1}, near, far, fov, aspect);
    }

    /** 4×4 inverse by Gauss-Jordan elimination. */
    private static float[] invert(float[] m) {
        double[][] a = new double[4][8];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) a[r][c] = m[c * 4 + r];
            a[r][4 + r] = 1;
        }
        for (int c = 0; c < 4; c++) {
            int pivot = c;
            for (int r = c + 1; r < 4; r++) if (Math.abs(a[r][c]) > Math.abs(a[pivot][c])) pivot = r;
            double[] t = a[c];
            a[c] = a[pivot];
            a[pivot] = t;
            double p = a[c][c];
            for (int k = 0; k < 8; k++) a[c][k] /= p;
            for (int r = 0; r < 4; r++) {
                if (r == c) continue;
                double f = a[r][c];
                for (int k = 0; k < 8; k++) a[r][k] -= f * a[c][k];
            }
        }
        float[] out = new float[16];
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) out[c * 4 + r] = (float) a[r][4 + c];
        return out;
    }
}
