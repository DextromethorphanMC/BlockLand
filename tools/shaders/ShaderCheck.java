import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles and links shader programs the way Blaze3D does (moj_import, injected defines, attribute locations) against
 * the real OpenGL driver in a hidden 3.2 core context, so a broken shader is caught before the game loads it. A vanilla
 * core shader override that fails to compile would leave the terrain invisible, so every override is checked here.
 * <p>
 * Run through {@code check_shaders.py}. Arguments: the programs file, then the shader roots in lookup order (an
 * override root first, the mod's assets, the extracted vanilla assets). Programs file lines:
 * {@code <name> | <vertex id> | <fragment id> | <attr,attr,...> | <DEFINE[=value] ...>}; '#' starts a comment.
 */
public final class ShaderCheck {
    private static final Pattern IMPORT = Pattern.compile("#\\s*moj_import\\s*(?:\"(.*)\"|<(.*)>)");
    private static final Pattern VERSION = Pattern.compile("#\\s*version\\s+(\\d+)");

    private static List<Path> roots = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path programs = Path.of(args[0]);
        for (int i = 1; i < args.length; i++) roots.add(Path.of(args[i]));

        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(64, 64, "shader check", 0, 0);
        if (window == 0) throw new IllegalStateException("no GL context");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GL: " + GL11C.glGetString(GL11C.GL_RENDERER) + " / " + GL11C.glGetString(GL11C.GL_VERSION));

        int failed = 0, checked = 0;
        for (String raw : Files.readAllLines(programs, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", -1);
            if (f.length < 4) {
                System.out.println("BAD LINE: " + line);
                failed++;
                continue;
            }
            String name = f[0].strip();
            Map<String, String> defines = new LinkedHashMap<>();
            if (f.length > 4) {
                for (String d : f[4].strip().split("\\s+")) {
                    if (d.isEmpty()) continue;
                    int eq = d.indexOf('=');
                    defines.put(eq < 0 ? d : d.substring(0, eq), eq < 0 ? "" : d.substring(eq + 1));
                }
            }
            String[] attributes = f[3].strip().isEmpty() ? new String[0] : f[3].strip().split("\\s*,\\s*");
            checked++;
            String error = check(f[1].strip(), f[2].strip(), attributes, defines);
            if (error == null) {
                System.out.println("ok    " + name);
            } else {
                failed++;
                System.out.println("FAIL  " + name + "\n" + error.indent(6));
            }
        }
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.out.println(failed == 0 ? "ALL " + checked + " PROGRAMS OK" : failed + " OF " + checked + " PROGRAMS FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String check(String vertexId, String fragmentId, String[] attributes, Map<String, String> defines) {
        String vs, fs;
        try {
            vs = source(vertexId, ".vsh", defines);
            fs = source(fragmentId, ".fsh", defines);
        } catch (Exception e) {
            return e.getMessage();
        }
        int v = compile(GL20C.GL_VERTEX_SHADER, vs);
        int fr = compile(GL20C.GL_FRAGMENT_SHADER, fs);
        StringBuilder errors = new StringBuilder();
        if (GL20C.glGetShaderi(v, GL20C.GL_COMPILE_STATUS) == 0) errors.append("vertex ").append(vertexId).append(":\n").append(GL20C.glGetShaderInfoLog(v));
        if (GL20C.glGetShaderi(fr, GL20C.GL_COMPILE_STATUS) == 0) errors.append("fragment ").append(fragmentId).append(":\n").append(GL20C.glGetShaderInfoLog(fr));
        if (errors.length() > 0) return errors.toString();
        int program = GL20C.glCreateProgram();
        for (int i = 0; i < attributes.length; i++) GL20C.glBindAttribLocation(program, i, attributes[i]);
        GL20C.glAttachShader(program, v);
        GL20C.glAttachShader(program, fr);
        GL20C.glLinkProgram(program);
        String result = null;
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            result = "link: " + GL20C.glGetProgramInfoLog(program);
        } else {
            // uniform blocks must fit the smallest guaranteed size (16 KiB)
            int blocks = GL20C.glGetProgrami(program, GL31C.GL_ACTIVE_UNIFORM_BLOCKS);
            for (int b = 0; b < blocks; b++) {
                int size = GL31C.glGetActiveUniformBlocki(program, b, GL31C.GL_UNIFORM_BLOCK_DATA_SIZE);
                if (size > 16384) result = "uniform block " + GL31C.glGetActiveUniformBlockName(program, b) + " is " + size + " bytes (> 16384)";
            }
        }
        GL20C.glDeleteProgram(program);
        GL20C.glDeleteShader(v);
        GL20C.glDeleteShader(fr);
        return result;
    }

    private static int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        return shader;
    }

    /** "ns:core/name" + extension → preprocessed source with defines, like GlDevice + GlslPreprocessor. */
    private static String source(String id, String extension, Map<String, String> defines) throws Exception {
        String[] parts = id.split(":", 2);
        String text = read(parts[0], "shaders/" + parts[1] + extension);
        int[] version = {150};
        String processed = imports(text, version, true);
        Matcher m = VERSION.matcher(processed);
        if (m.find()) processed = processed.substring(0, m.start(1)) + Math.max(version[0], Integer.parseInt(m.group(1))) + processed.substring(m.end(1));
        if (!defines.isEmpty()) {
            StringBuilder d = new StringBuilder();
            defines.forEach((k, val) -> d.append("#define ").append(k).append(val.isEmpty() ? "" : " " + val).append('\n'));
            int nl = processed.indexOf('\n') + 1;
            processed = processed.substring(0, nl) + d + "#line 1 0\n" + processed.substring(nl);
        }
        return processed;
    }

    private static String imports(String text, int[] version, boolean top) throws Exception {
        if (!top) {
            Matcher v = VERSION.matcher(text);
            if (v.find()) {
                version[0] = Math.max(version[0], Integer.parseInt(v.group(1)));
                text = text.substring(0, v.start()) + "/*" + v.group() + "*/" + text.substring(v.end());
            }
        }
        StringBuilder out = new StringBuilder();
        Matcher m = IMPORT.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String ref = m.group(2) != null ? m.group(2) : m.group(1);
            String[] p = ref.contains(":") ? ref.split(":", 2) : new String[]{"minecraft", ref};
            out.append(imports(read(p[0], "shaders/include/" + p[1]), version, false)).append('\n');
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String read(String namespace, String path) throws Exception {
        for (Path root : roots) {
            Path file = root.resolve("assets").resolve(namespace).resolve(path);
            if (Files.isRegularFile(file)) return Files.readString(file, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("not found: " + namespace + ":" + path);
    }
}
