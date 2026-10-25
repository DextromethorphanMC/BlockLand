package com.blockland.mixin.core;

import com.blockland.client.perf.PerfHooks;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The perf module's background frame rate (rendering spec §20) goes through vanilla's own frame limiter. */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void blockland$backgroundLimit(CallbackInfoReturnable<Integer> cir) {
        int vanilla = cir.getReturnValueI();
        int limit = PerfHooks.frameLimit(vanilla);
        if (limit != vanilla) cir.setReturnValue(limit);
    }
}
