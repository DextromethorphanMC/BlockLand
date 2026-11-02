package com.blockland.client.graphics.gpu;

import com.blockland.BlockLand;
import com.mojang.blaze3d.opengl.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A compute shader program ({@code assets/blockland/shaders/core/<name>.csh}) compiled with raw GL (Blaze3D knows no
 * compute stage). Defines are injected after the version line, as Blaze3D injects a pipeline's; imports are not
 * supported (a compute shader here is self-contained). Needs GL 4.3. {@link #bind} binds the program and remembers the
 * one bound before, {@link #unbind} puts it back, so Blaze3D's own program cache stays right; uniforms are set by
 * location ({@link #uniform}) while bound; storage buffers, images and texture units are the caller's.
 */
public final class ComputeProgram implements AutoCloseable {
    private final int program;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private int previous;

    private ComputeProgram(int program) {
        this.program = program;
    }

    /** Compiles and links the program; throws with the driver's log when it does not compile. */
    public static ComputeProgram compile(String name, Map<String, String> defines) throws IOException {
        ResourceLocation id = BlockLand.id("shaders/core/" + name + ".csh");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) throw new IOException("compute shader not found: " + id);
        String source;
        try (BufferedReader reader = resource.get().openAsReader()) {
            source = reader.lines().collect(Collectors.joining("\n", "", "\n"));
        }
        if (!defines.isEmpty()) {
            StringBuilder d = new StringBuilder();
            defines.forEach((k, v) -> d.append("#define ").append(k).append(v.isEmpty() ? "" : " " + v).append('\n'));
            int nl = source.indexOf('\n') + 1;
            source = source.substring(0, nl) + d + "#line 1 0\n" + source.substring(nl);
        }
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IOException("compute shader " + name + " " + defines + " does not compile:\n" + log);
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, shader);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IOException("compute program " + name + " does not link:\n" + log);
        }
        return new ComputeProgram(program);
    }

    /** The uniform's location (-1 when the compiler left it out). */
    public int uniform(String name) {
        return uniforms.computeIfAbsent(name, n -> GL20.glGetUniformLocation(program, n));
    }

    /** Binds the program (remembering the one bound before) so uniforms can be set and dispatches made. */
    public void bind() {
        previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GlStateManager._glUseProgram(program);
    }

    public void dispatch(int x, int y, int z) {
        GL43.glDispatchCompute(x, y, z);
    }

    /** Puts back the program that was bound before {@link #bind}. */
    public void unbind() {
        GlStateManager._glUseProgram(previous);
    }

    @Override
    public void close() {
        GL20.glDeleteProgram(program);
    }
}
