package com.blockland.client.graphics.upscale;

import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Native resolution without a temporal pass: the colour goes on as it is. FXAA (the other spatial choice) runs on the
 * tone-mapped picture at the end of the post chain, so here it only names itself.
 */
public final class NativeAa implements Upscaler {
    private final boolean fxaa;

    public NativeAa(boolean fxaa) {
        this.fxaa = fxaa;
    }

    @Override
    public String id() {
        return fxaa ? "fxaa" : "none";
    }

    @Override
    public boolean temporal() {
        return false;
    }

    public boolean fxaa() {
        return fxaa;
    }

    @Override
    public GpuTextureView resolve(Frame frame) {
        return frame.color();
    }
}
