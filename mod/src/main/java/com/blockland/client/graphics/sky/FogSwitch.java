package com.blockland.client.graphics.sky;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * The 迷雾 switch (rendering spec §14): with fog off, vanilla's distance fog in the open air goes (also the rain fog
 * and the thick fog of the Nether), leaving only a thin fade where the loaded world ends, so chunks never appear with a
 * hard edge. Fog that is part of the game stays: under water, in lava and powder snow, blindness and darkness.
 */
public final class FogSwitch {
    /** Share of the render distance the fade at its end still covers with fog off. */
    private static final float EDGE = 0.04f;

    private FogSwitch() {
    }

    public static void init() {
        ViewportEvent.RenderFog.BUS.addListener(FogSwitch::onFog);
    }

    private static boolean onFog(ViewportEvent.RenderFog event) {
        GraphicsSettings settings = Graphics.settings();
        if (!settings.enabled || settings.fog || event.getType() != FogType.ATMOSPHERIC) return false;
        if (event.getCamera().getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) return false;
        FogData data = event.getData();
        data.environmentalStart = Float.MAX_VALUE;
        data.environmentalEnd = Float.MAX_VALUE;
        data.renderDistanceStart = data.renderDistanceEnd * (1 - EDGE);
        return false;
    }
}
