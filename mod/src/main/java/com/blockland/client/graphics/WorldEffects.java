package com.blockland.client.graphics;

import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;

/**
 * Screen-space effects of the world that the post chain folds into the picture (rendering spec §1): produced in
 * {@code afterLevel} from the world depth, at the internal resolution or lower (sampled with linear filtering), so the
 * hand and the viewmodel drawn afterwards are never touched by them.
 */
public interface WorldEffects {
    WorldEffects NONE = new WorldEffects() {
    };

    /** Ambient visibility of the world (red channel, 0 occluded … 1 open), or null. */
    @Nullable
    default GpuTextureView ambientOcclusion() {
        return null;
    }

    /**
     * Ray traced lighting (client/graphics/raytrace): RGB light to add (linear, albedo applied), A ambient visibility that
     * replaces {@link #ambientOcclusion()}; or null.
     */
    @Nullable
    default GpuTextureView rayTraced() {
        return null;
    }

    /**
     * Volumetric clouds (client/graphics/sky): RGB light they scatter towards the eye (linear), A the transmittance the
     * picture behind them is multiplied by; or null.
     */
    @Nullable
    default GpuTextureView clouds() {
        return null;
    }

    /**
     * The volumetric fog and light: RGB light it scatters towards the eye (linear, added before tone mapping), A the
     * transmittance the picture behind it is multiplied by; or null.
     */
    @Nullable
    default GpuTextureView volumetricLight() {
        return null;
    }
}
