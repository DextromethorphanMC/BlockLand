package com.blockland.client.perf.gl;

import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.util.Arrays;

/**
 * {@code GL_STATE} (rendering spec §20): GL state that Blaze3D sends again for every draw although the context already
 * holds it. Each cache mirrors one piece of context (or object) state exactly: it is updated by every call that goes
 * through it, whether the switch is on or off, and a call is only skipped while the switch is on and the cached value is
 * the one being set — so flipping the switch at any moment is safe, and off sends exactly vanilla's calls.
 * <ul>
 * <li>uniform buffer bindings ({@code glBindBufferRange}) per binding point, as buffer name, offset and length;</li>
 * <li>sampler units ({@code glUniform1i}): a program's sampler uniform always gets the same unit (its index in the
 *     program), so once set it holds until the program is deleted;</li>
 * <li>a texture's base and max mip level, kept on the texture object ({@link Levels});</li>
 * <li>a texture's wrap and filter parameters are only re-sent when {@code setUseMipmaps} really changes the flag (the
 *     render types set it before every draw);</li>
 * <li>polygon mode, viewport and scissor box;</li>
 * <li>the framebuffer binding: a render pass leaves its framebuffer bound instead of binding 0, so the next pass into
 *     the same target binds nothing (Blaze3D binds explicitly before everything that reads the binding — passes, clears,
 *     copies, read-backs — and the deletion of a bound framebuffer resets Blaze3D's own binding cache, see
 *     {@code GlStateManagerMixin}).</li>
 * </ul>
 * Only Blaze3D changes these pieces of state: BlockLand's raw GL (lighting slots, texture filtering, shadow compare
 * mode, the DLSS bridge) touches other binding points, units 12–15 and other texture parameters, and restores what it
 * binds. Deleted buffers drop out of the binding caches ({@link #bufferDeleted}); GL reuses names. Batched draws bind
 * their per-draw uniform blocks themselves: those binds go through {@link #bindUniformBuffer} ({@code TerrainDraws}) or
 * the cache forgets every binding after the batch ({@link #forgetUniformBuffers}).
 */
public final class GlStateCache {
    /** A texture's mip range as last sent (value + 1; 0 = unknown), kept on the texture object itself. */
    public interface Levels {
        int blockland$baseLevel();

        void blockland$setBaseLevel(int level);

        int blockland$maxLevel();

        void blockland$setMaxLevel(int level);
    }

    /** The sampler units already set on a program (bit per unit). */
    public interface Units {
        long blockland$units();

        void blockland$setUnits(long units);
    }

    /** A Blaze3D buffer's GL name. */
    public interface Handle {
        int blockland$handle();
    }

    /** The GL name of a Blaze3D buffer (a {@code GlBuffer}). */
    public static int handle(Object glBuffer) {
        return ((Handle) glBuffer).blockland$handle();
    }

    /**
     * Binding points cached: 0..14. BlockLand's scene block sits on 15 ({@code SceneLighting.VANILLA_UBO_BINDING},
     * bound by raw GL), so it and everything above are always sent.
     */
    private static final int UBO_POINTS = 15;
    private static final int GL_TEXTURE_BASE_LEVEL = GL12.GL_TEXTURE_BASE_LEVEL;
    private static final int GL_TEXTURE_MAX_LEVEL = GL12.GL_TEXTURE_MAX_LEVEL;
    private static final int FRONT_AND_BACK = GL11.GL_FRONT_AND_BACK;

    /** Buffer name per binding point (0 = unknown; no buffer is named 0). */
    private static final int[] uboBuffer = new int[UBO_POINTS];
    private static final long[] uboOffset = new long[UBO_POINTS];
    private static final long[] uboLength = new long[UBO_POINTS];

    private static int polygonMode = -1;
    private static boolean viewportKnown;
    private static int viewportX, viewportY, viewportWidth, viewportHeight;
    private static boolean scissorKnown;
    private static int scissorX, scissorY, scissorWidth, scissorHeight;

    /** The mip range trySetup is about to send for the sampler it binds (sent with that texture's mode flush). */
    private static int pendingBaseTarget, pendingBase = -1, pendingMaxTarget, pendingMax = -1;

    private GlStateCache() {
    }

    private static boolean on() {
        return Optimization.GL_STATE.on();
    }

    // ---- uniform buffers ---------------------------------------------------------------------------------------------

    /** {@code glBindBufferRange}, skipped when the binding point already holds exactly this range. */
    public static void bindUniformBuffer(int target, int index, int buffer, long offset, long length) {
        if (target == GL31.GL_UNIFORM_BUFFER && index >= 0 && index < UBO_POINTS && buffer != 0) {
            if (uboBuffer[index] == buffer && uboOffset[index] == offset && uboLength[index] == length && on()) return;
            GL30.glBindBufferRange(target, index, buffer, offset, length);
            uboBuffer[index] = buffer;
            uboOffset[index] = offset;
            uboLength[index] = length;
            return;
        }
        GL30.glBindBufferRange(target, index, buffer, offset, length);
        if (target == GL31.GL_UNIFORM_BUFFER && index >= 0 && index < UBO_POINTS) uboBuffer[index] = 0;
    }

    /** Some binding points were bound behind the cache (a batch's per-draw uniform blocks): send all of them again. */
    public static void forgetUniformBuffers() {
        Arrays.fill(uboBuffer, 0);
    }

    /** A buffer is being deleted: its name may come back for another buffer, and GL unbinds it from every point. */
    public static void bufferDeleted(int buffer) {
        for (int i = 0; i < UBO_POINTS; i++) {
            if (uboBuffer[i] == buffer) uboBuffer[i] = 0;
        }
    }

    // ---- samplers ----------------------------------------------------------------------------------------------------

    /** trySetup's {@code glUniform1i(location, unit)} on the program it just made current. */
    public static void samplerUnit(GlProgram program, int location, int unit) {
        Units units = (Units) program;
        long bit = unit >= 0 && unit < 64 ? 1L << unit : 0L;
        if (bit != 0L && (units.blockland$units() & bit) != 0L && on()) return;
        GlStateManager._glUniform1i(location, unit);
        if (bit != 0L) units.blockland$setUnits(units.blockland$units() | bit);
    }

    /** trySetup's base / max level for the sampler being bound: held until {@link #flushSampler} knows the texture. */
    public static void deferMipRange(int target, int pname, int value) {
        if (pname == GL_TEXTURE_BASE_LEVEL) {
            pendingBaseTarget = target;
            pendingBase = value;
        } else if (pname == GL_TEXTURE_MAX_LEVEL) {
            pendingMaxTarget = target;
            pendingMax = value;
        } else {
            GlStateManager._texParameter(target, pname, value);
        }
    }

    /**
     * trySetup's {@code texture.flushModeChanges(target)}, right after the two mip level calls: sends the levels the
     * texture does not hold yet, in vanilla's order (base, max, then the mode flush).
     */
    public static void flushSampler(GlTexture texture, int target) {
        Levels levels = (Levels) texture;
        boolean skip = on();
        if (pendingBase >= 0) {
            if (!skip || levels.blockland$baseLevel() != pendingBase + 1) {
                GlStateManager._texParameter(pendingBaseTarget, GL_TEXTURE_BASE_LEVEL, pendingBase);
            }
            levels.blockland$setBaseLevel(pendingBase + 1);
            pendingBase = -1;
        }
        if (pendingMax >= 0) {
            if (!skip || levels.blockland$maxLevel() != pendingMax + 1) {
                GlStateManager._texParameter(pendingMaxTarget, GL_TEXTURE_MAX_LEVEL, pendingMax);
            }
            levels.blockland$setMaxLevel(pendingMax + 1);
            pendingMax = -1;
        }
        texture.flushModeChanges(target);
    }

    /**
     * {@code GlTexture.setUseMipmaps} with the flag it already has: vanilla marks the modes dirty anyway, which re-sends
     * the wrap and filter parameters the texture already holds (every field change still marks them dirty).
     */
    public static boolean skipUnchangedMipmaps() {
        return on();
    }

    // ---- fixed-function state ----------------------------------------------------------------------------------------

    public static boolean samePolygonMode(int face, int mode) {
        if (face != FRONT_AND_BACK) {
            polygonMode = -1;
            return false;
        }
        if (polygonMode == mode && on()) return true;
        polygonMode = mode;
        return false;
    }

    public static boolean sameViewport(int x, int y, int width, int height) {
        if (viewportKnown && viewportX == x && viewportY == y && viewportWidth == width && viewportHeight == height && on()) return true;
        viewportKnown = true;
        viewportX = x;
        viewportY = y;
        viewportWidth = width;
        viewportHeight = height;
        return false;
    }

    public static boolean sameScissor(int x, int y, int width, int height) {
        if (scissorKnown && scissorX == x && scissorY == y && scissorWidth == width && scissorHeight == height && on()) return true;
        scissorKnown = true;
        scissorX = x;
        scissorY = y;
        scissorWidth = width;
        scissorHeight = height;
        return false;
    }

    /** A render pass ends: true leaves its framebuffer bound (the next pass into it binds nothing). */
    public static boolean keepFramebuffer() {
        return on();
    }

    /** Once per frame (defensive): the context-wide caches start over, so their first use in a frame is sent again. */
    public static void endFrame() {
        forgetUniformBuffers();
        polygonMode = -1;
        viewportKnown = false;
        scissorKnown = false;
    }
}
