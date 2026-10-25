package com.blockland.mixin.core;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

/** A queued compile task's cancellation (cancelled tasks are never built). */
@Mixin(SectionRenderDispatcher.RenderSection.CompileTask.class)
public interface CompileTaskStateAccess {
    @Accessor("isCancelled")
    AtomicBoolean blockland$isCancelled();
}
