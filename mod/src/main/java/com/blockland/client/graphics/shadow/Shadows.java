package com.blockland.client.graphics.shadow;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.blockland.client.graphics.ShadowInfo;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.frame.FrameStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * The sun's cascaded shadow map and the screen-space effects of the world (ambient occlusion, volumetric light)
 * (rendering spec §1, §6). Provides {@code Graphics.shadows()} and {@code Graphics.worldEffects()}. Its stage is added
 * before the light module's, so in every phase it runs first: the cascades are drawn before the scene uniforms take
 * their matrices, and the world effects are made while the world's version of the scene uniforms is still the current
 * one (the light module switches to the hand version in its own after-level hook).
 */
public final class Shadows {
    private static final ShadowMap map = new ShadowMap();
    private static final WorldFx effects = new WorldFx();

    private Shadows() {
    }

    public static void init(BusGroup modBus) {
        TerrainCache.init();
        Graphics.addStage(new Stage());
        Graphics.setShadows(map::info);
        Graphics.setWorldEffects(effects);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            map.release();
            effects.release();
        });
        // switched off: the atlas and the effect targets go at once (the stage is not called while the pipeline is off)
        Graphics.onSettingsChanged(() -> {
            GraphicsSettings settings = Graphics.settings();
            if (!settings.enabled || settings.shadowQuality() == GraphicsSettings.ShadowQuality.OFF) map.release();
            if (!settings.enabled) effects.release();
        });
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && Graphics.settings().showStats
                    && Graphics.status().running) {
                ShadowInfo info = map.info();
                event.getText().add("[BlockLand] sun shadows: " + (info == null ? "off" : info.count() + " cascades, "
                        + info.atlasSize() + "² atlas, " + map.stats()));
                event.getText().add("[BlockLand] world effects: " + effects.stats());
            }
            return false;
        });
    }

    private static final class Stage implements FrameStage {
        @Override
        public void restart() {
            map.restart();
        }

        @Override
        public void beginFrame(FrameContext frame) {
            effects.reset();
            // decided before the scene uniforms are written: the lit programs then leave the haze to the fog pass
            Graphics.status().volumetricFogActive = effects.wantsFog(Graphics.settings());
        }

        @Override
        public void cameraReady(FrameContext frame) {
            ClientLevel level = Minecraft.getInstance().level;
            GraphicsStatus status = Graphics.status();
            if (level == null) {
                status.shadowCascades = 0;
                return;
            }
            GraphicsSettings settings = Graphics.settings();
            // what reads the map: the lit world anywhere in view; with the world unlit (the scene block then carries no
            // cascades, so every bl_shadow lookup returns lit without sampling) only the volumetric light, out to its
            // march range — or nothing
            float readRange = settings.worldLighting ? Float.MAX_VALUE : effects.wantsFog(settings) ? WorldFx.volumetricRange(settings) : 0;
            map.render(level, settings, frame, readRange);
            ShadowInfo info = map.info();
            status.shadowCascades = info == null ? 0 : info.count();
            status.shadowReason = map.reason();
        }

        @Override
        public void afterLevel(FrameContext frame) {
            effects.render(frame, Graphics.settings(), map.info());
        }
    }
}
