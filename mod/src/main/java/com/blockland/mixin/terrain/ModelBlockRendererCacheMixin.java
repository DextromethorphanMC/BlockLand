package com.blockland.mixin.terrain;

import com.blockland.client.perf.terrain.SectionLight;
import it.unimi.dsi.fastutil.longs.Long2FloatLinkedOpenHashMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Section meshing ({@code SECTION_MESHING}): the per-thread light and shade cache vanilla enables for a section compile
 * keeps the whole section and its border ({@link SectionLight}) instead of the last 100 positions.
 */
@Mixin(targets = "net.minecraft.client.renderer.block.ModelBlockRenderer$Cache")
public abstract class ModelBlockRendererCacheMixin {
    @Shadow
    private boolean enabled;
    @Shadow
    @Final
    private LevelRenderer.BrightnessGetter cachedBrightnessGetter;
    @Unique
    @Nullable
    private SectionLight blockland$section;

    @Inject(method = "enable", at = @At("TAIL"))
    private void blockland$enable(CallbackInfo ci) {
        blockland$section = SectionLight.attach(cachedBrightnessGetter);
    }

    @Inject(method = "disable", at = @At("HEAD"))
    private void blockland$disable(CallbackInfo ci) {
        blockland$section = null;
    }

    @Redirect(method = "getLightColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/client/renderer/LevelRenderer$BrightnessGetter;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I"))
    private int blockland$lightColor(LevelRenderer.BrightnessGetter getter, BlockAndTintGetter level, BlockState state, BlockPos pos) {
        SectionLight section = blockland$section;
        return LevelRenderer.getLightColor(section != null && enabled ? section : getter, level, state, pos);
    }

    /** Reached only while vanilla's caching is enabled; a value here is returned as vanilla returns a cached one. */
    @Redirect(method = "getShadeBrightness", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2FloatLinkedOpenHashMap;get(J)F"))
    private float blockland$cachedShade(Long2FloatLinkedOpenHashMap cache, long key, BlockState state, BlockAndTintGetter level, BlockPos pos) {
        SectionLight section = blockland$section;
        if (section != null) {
            int i = section.index(level, pos);
            if (i >= 0) return section.shade(i, state, level, pos);
        }
        return cache.get(key);
    }
}
