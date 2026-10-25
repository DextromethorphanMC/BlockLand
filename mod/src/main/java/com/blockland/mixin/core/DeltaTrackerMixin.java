package com.blockland.mixin.core;

import com.blockland.client.perf.PerfHooks;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the benchmark holds the client's ticks (frame comparison), no game time passes between frames: the partial
 * tick stays at the held tick's end, so everything interpolated (entities, clouds, the camera) is drawn where it is.
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerMixin {
    @Inject(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), cancellable = true)
    private void blockland$heldPartialTick(boolean runsNormally, CallbackInfoReturnable<Float> cir) {
        if (PerfHooks.ticksHeld()) cir.setReturnValue(1.0f);
    }

    @Inject(method = "getGameTimeDeltaTicks", at = @At("HEAD"), cancellable = true)
    private void blockland$heldDeltaTicks(CallbackInfoReturnable<Float> cir) {
        if (PerfHooks.ticksHeld()) cir.setReturnValue(0.0f);
    }

    @Inject(method = "getRealtimeDeltaTicks", at = @At("HEAD"), cancellable = true)
    private void blockland$heldRealtimeTicks(CallbackInfoReturnable<Float> cir) {
        if (PerfHooks.ticksHeld()) cir.setReturnValue(0.0f);
    }
}
