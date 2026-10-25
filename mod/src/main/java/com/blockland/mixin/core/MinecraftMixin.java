package com.blockland.mixin.core;

import com.blockland.client.perf.PerfHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.SingleTickProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/** The perf module's two hooks into the client's loop (rendering spec §20): vanilla's profiler sections and held ticks. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /** While timings are recorded, vanilla's own profiler sections are measured too (on top of whatever vanilla wants). */
    @Inject(method = "constructProfiler", at = @At("RETURN"), cancellable = true)
    private void blockland$timedProfiler(boolean fpsPie, @Nullable SingleTickProfiler single,
                                         CallbackInfoReturnable<ProfilerFiller> cir) {
        ProfilerFiller tapped = PerfHooks.profiler(cir.getReturnValue());
        if (tapped != cir.getReturnValue()) cir.setReturnValue(tapped);
    }

    /** The benchmark's frame comparison holds the client's ticks, so the world on screen stays exactly as it is. */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void blockland$holdTick(CallbackInfo ci) {
        if (PerfHooks.ticksHeld()) ci.cancel();
    }
}
