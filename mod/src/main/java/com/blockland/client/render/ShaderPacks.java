package com.blockland.client.render;

import com.blockland.BlockLand;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * Whether OptiFine is installed and a shader pack is running. With a shader pack, OptiFine renders the world into its
 * own buffers and composites them later: draws of our own pipelines into the main target can be overwritten or drawn
 * without the pack's lighting, and a second level render (scope picture-in-picture) is not safe. Renderers check
 * {@link #active()} each frame and switch to their vanilla-render-type fallbacks. Read by reflection, so nothing
 * depends on OptiFine being present.
 */
public final class ShaderPacks {
    private static final boolean OPTIFINE;
    /** {@code net.optifine.shaders.Shaders.shaderPackLoaded}; null without OptiFine. */
    @Nullable
    private static final Field LOADED;
    /** {@code net.optifine.shaders.Shaders.isShadowPass}: OptiFine is rendering its shadow map. */
    @Nullable
    private static final Field SHADOW_PASS;

    static {
        Field loaded = null, shadow = null;
        boolean present = false;
        try {
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders", false, ShaderPacks.class.getClassLoader());
            present = true;
            loaded = shaders.getField("shaderPackLoaded");
            shadow = shaders.getField("isShadowPass");
        } catch (ClassNotFoundException e) {
            // no OptiFine
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] OptiFine found but its shader state is not readable ({}); assuming a shader pack", e.toString());
        }
        OPTIFINE = present;
        LOADED = loaded;
        SHADOW_PASS = shadow;
    }

    private ShaderPacks() {
    }

    /** OptiFine is installed (with or without a shader pack). */
    public static boolean optifine() {
        return OPTIFINE;
    }

    /** An OptiFine shader pack is loaded: use the compatible render paths. */
    public static boolean active() {
        if (!OPTIFINE) return false;
        if (LOADED == null) return true;
        try {
            return LOADED.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** OptiFine is rendering the shadow map right now (draw nothing of the first-person view). */
    public static boolean shadowPass() {
        if (SHADOW_PASS == null) return false;
        try {
            return SHADOW_PASS.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
