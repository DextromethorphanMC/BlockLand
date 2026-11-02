package com.blockland.client.graphics.light;

import com.blockland.BlockLand;
import com.blockland.client.render.SceneLighting;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL41;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Patches the GL programs of vanilla's pipelines that run the lighting pack's shaders (rendering spec §3): the scene block
 * goes to binding point 15, {@code BlShadowMap} to unit 12, {@code BlMaterialMap} to unit 13, and {@code BlRole} says
 * what the program draws (derived from the pipeline — its shaders and blend — never from the shader source). Uniform
 * values live in the program object, so each program is patched once; Blaze3D recompiles everything on a shader reload,
 * which brings new program objects, found again by the per-frame scan of the device's pipeline cache.
 * <p>
 * The Fabulous transparency composite is a post chain pipeline that Blaze3D compiles lazily on its first draw: it is
 * compiled here ahead of time (exactly as the draw would) so it is never drawn unpatched.
 */
final class VanillaPrograms {
    static final int ROLE_OPAQUE = 1, ROLE_BLENDED = 2, ROLE_TRANSLUCENT_TERRAIN = 3, ROLE_COMPOSITE = 4, ROLE_CLOUDS = 5;

    private static final ResourceLocation TERRAIN = ResourceLocation.withDefaultNamespace("core/terrain");
    /** TERRAIN_PAGES: the same terrain vertex shader with the storage buffer path (client/perf/gl/PagedTerrain). */
    private static final ResourceLocation TERRAIN_PAGES = ResourceLocation.fromNamespaceAndPath("blockland", "core/terrain_pages");
    private static final ResourceLocation ENTITY = ResourceLocation.withDefaultNamespace("core/entity");
    private static final ResourceLocation TRANSPARENCY = ResourceLocation.withDefaultNamespace("post/transparency");
    private static final ResourceLocation CLOUDS = ResourceLocation.withDefaultNamespace("core/rendertype_clouds");
    private static final ResourceLocation TRANSPARENCY_CHAIN = ResourceLocation.withDefaultNamespace("transparency");

    @Nullable
    private static final Field PIPELINE_CACHE = field(GlDevice.class, "pipelineCache");
    @Nullable
    private static final Field CHAIN_PASSES = field(PostChain.class, "passes");
    @Nullable
    private static final Field PASS_PIPELINE = field(PostPass.class, "pipeline");

    /** Compiled pipelines already looked at (patched or not ours), by identity: a reload brings new objects. */
    private static final Map<GlRenderPipeline, Boolean> seen = new IdentityHashMap<>();
    /** Programs carrying the lighting pack's role uniform (the pack's shaders are the ones loaded). */
    private static int lit;
    private static boolean warned;

    private VanillaPrograms() {
    }

    static boolean supported() {
        return PIPELINE_CACHE != null && RenderSystem.getDevice() instanceof GlDevice;
    }

    /** Programs of the current shader set that carry the lighting pack's role uniform. */
    static int litPrograms() {
        return lit;
    }

    /** A shader reload replaced every program. */
    static void reset() {
        seen.clear();
        lit = 0;
    }

    /** Patches every compiled pipeline not seen yet (cheap when nothing is new). */
    @SuppressWarnings("unchecked")
    static void update() {
        if (!supported()) return;
        Map<RenderPipeline, GlRenderPipeline> cache;
        try {
            cache = (Map<RenderPipeline, GlRenderPipeline>) PIPELINE_CACHE.get(RenderSystem.getDevice());
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
            return;
        }
        if (seen.size() > cache.size() * 2 + 64) reset();
        for (GlRenderPipeline compiled : cache.values()) {
            if (seen.containsKey(compiled)) continue;
            int role = role(compiled.info());
            seen.put(compiled, role != 0);
            if (role != 0 && compiled.isValid()) patch(compiled.program().getProgramId(), role);
            // BlockLand's own pipelines that evaluate scene.glsl's cloud shadows (Bliss's clouds, spec §14) read Bliss's
            // noise from the same unit; a pipeline that declares the sampler binds it itself
            else if (compiled.isValid()) {
                int program = compiled.program().getProgramId();
                var samplers = compiled.info().getSamplers();
                if (!samplers.contains("noisetex")) {
                    setInt(program, GL20.glGetUniformLocation(program, "noisetex"), GlSlots.noiseUnit());
                }
                // and every one that evaluates scene.glsl's sun shadow reads the raw shadow depth (soft shadows, spec §6)
                // from the unit it stays bound on beside the vanilla programs' atlas
                if (!samplers.contains("BlShadowDepth")) {
                    setInt(program, GL20.glGetUniformLocation(program, "BlShadowDepth"), GlSlots.shadowDepthUnit());
                }
            }
        }
    }

    /** With Fabulous graphics: compiles and patches the transparency composite before the frame draws it. */
    @SuppressWarnings("unchecked")
    static void prepareTransparency() {
        Minecraft mc = Minecraft.getInstance();
        // only in a world (before the first resource load there are no post chains to ask for)
        if (!supported() || CHAIN_PASSES == null || PASS_PIPELINE == null || mc.level == null || mc.getOverlay() != null
                || !Minecraft.useShaderTransparency()) return;
        PostChain chain = mc.getShaderManager().getPostChain(TRANSPARENCY_CHAIN, LevelTargetBundle.SORTING_TARGETS);
        if (chain == null) return;
        try {
            for (PostPass pass : (List<PostPass>) CHAIN_PASSES.get(chain)) {
                RenderPipeline pipeline = (RenderPipeline) PASS_PIPELINE.get(pass);
                if (role(pipeline) == 0) continue;
                // the same compile the draw would trigger (the device's default shader source)
                if (RenderSystem.getDevice().precompilePipeline(pipeline) instanceof GlRenderPipeline compiled && !seen.containsKey(compiled)) {
                    seen.put(compiled, true);
                    if (compiled.isValid()) patch(compiled.program().getProgramId(), ROLE_COMPOSITE);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
        }
    }

    /** What a pipeline draws, for the shader's BlRole; 0 = not a lighting pack shader. */
    static int role(RenderPipeline pipeline) {
        ResourceLocation vertex = pipeline.getVertexShader();
        boolean blended = pipeline.getBlendFunction().isPresent();
        if (vertex.equals(TERRAIN) || vertex.equals(TERRAIN_PAGES)) {
            if (!blended) return ROLE_OPAQUE;
            // translucent blocks (water, glass, ice) have no cut-out; tripwire does
            return pipeline.getShaderDefines().values().containsKey("ALPHA_CUTOUT") ? ROLE_BLENDED : ROLE_TRANSLUCENT_TERRAIN;
        }
        if (vertex.equals(ENTITY)) return blended ? ROLE_BLENDED : ROLE_OPAQUE;
        if (pipeline.getFragmentShader().equals(TRANSPARENCY)) return ROLE_COMPOSITE;
        // vanilla's clouds: hidden while BlockLand's volumetric clouds replace them
        if (pipeline.getFragmentShader().equals(CLOUDS)) return ROLE_CLOUDS;
        return 0;
    }

    private static void patch(int program, int role) {
        int block = GL31.glGetUniformBlockIndex(program, "BlockLandScene");
        int roleLocation = GL20.glGetUniformLocation(program, "BlRole");
        if (block == GL31.GL_INVALID_INDEX || roleLocation < 0) return; // another pack's shader: leave it alone
        GL31.glUniformBlockBinding(program, block, SceneLighting.VANILLA_UBO_BINDING);
        setInt(program, GL20.glGetUniformLocation(program, "BlShadowMap"), SceneLighting.VANILLA_SHADOW_UNIT);
        setInt(program, GL20.glGetUniformLocation(program, "BlMaterialMap"), GlSlots.MATERIAL_UNIT);
        setInt(program, GL20.glGetUniformLocation(program, "BlPrevScene"), GlSlots.REFLECTION_UNIT);
        setInt(program, GL20.glGetUniformLocation(program, "BlPlantColumns"), GlSlots.COLUMNS_UNIT);
        setInt(program, GL20.glGetUniformLocation(program, "noisetex"), GlSlots.noiseUnit());
        setInt(program, GL20.glGetUniformLocation(program, "BlShadowDepth"), GlSlots.shadowDepthUnit());
        setInt(program, roleLocation, role);
        lit++;
    }

    /** Sets an int uniform of a program that may not be the current one (the current one is put back). */
    private static void setInt(int program, int location, int value) {
        if (location < 0) return;
        if (GL.getCapabilities().glProgramUniform1i != 0L) {
            GL41.glProgramUniform1i(program, location, value);
            return;
        }
        int current = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        GL20.glUniform1i(location, value);
        GL20.glUseProgram(current);
    }

    private static void warnOnce(Exception e) {
        if (warned) return;
        warned = true;
        BlockLand.LOGGER.warn("[BlockLand] Cannot reach vanilla's shader programs ({}); world lighting stays off", e.toString());
    }

    @Nullable
    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] {}.{} not found; world lighting cannot patch vanilla programs", owner.getSimpleName(), name);
            return null;
        }
    }
}
