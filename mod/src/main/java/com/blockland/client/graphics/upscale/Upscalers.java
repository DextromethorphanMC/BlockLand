package com.blockland.client.graphics.upscale;

import com.blockland.client.graphics.GraphicsSettings;

/**
 * Picks this frame's upscaler / anti-aliasing from the settings: the render scale, whether projections jitter, whether
 * FXAA runs at the end. DLSS falls back to the temporal upscaler while it is unavailable (FrameHooks reports why).
 */
public final class Upscalers implements AutoCloseable {
    /** This frame's choice. */
    public record Choice(Upscaler upscaler, float scale, boolean fxaa) {
    }

    private final TemporalUpscaler temporal = new TemporalUpscaler();
    private final DlssUpscaler dlss = new DlssUpscaler(temporal);
    private final NativeAa none = new NativeAa(false);
    private final NativeAa fxaa = new NativeAa(true);
    private Upscaler last = none;

    public Choice choose(GraphicsSettings settings) {
        GraphicsSettings.Upscaler mode = settings.upscalerMode();
        float quality = settings.upscaleQualityMode().scale;
        DlssUpscaler.Probe probe = DlssUpscaler.probe();
        Choice choice;
        boolean dlaa = mode == GraphicsSettings.Upscaler.DLAA
                || mode == GraphicsSettings.Upscaler.DLSS && settings.upscaleQualityMode() == GraphicsSettings.UpscaleQuality.NATIVE;
        if (settings.dlssChosen() && probe.available()) {
            choice = new Choice(dlss, dlaa ? 1 : quality, false);
        } else if (mode == GraphicsSettings.Upscaler.TAAU || settings.dlssChosen()) {
            // DLSS unavailable: the built-in upscaler at the same scale (DLAA becomes TAA)
            choice = new Choice(temporal, dlaa ? 1 : quality, false);
        } else {
            choice = switch (settings.antialiasingMode()) {
                case TAA -> new Choice(temporal, 1, false);
                case FXAA -> new Choice(fxaa, 1, true);
                case OFF -> new Choice(none, 1, false);
            };
        }
        if (choice.upscaler() != last) {
            // switching away and back must not resurrect an old history
            temporal.invalidate();
            dlss.invalidate();
            last = choice.upscaler();
        }
        return choice;
    }

    public void invalidate() {
        temporal.invalidate();
        dlss.invalidate();
    }

    @Override
    public void close() {
        temporal.close();
        dlss.close();
    }
}
