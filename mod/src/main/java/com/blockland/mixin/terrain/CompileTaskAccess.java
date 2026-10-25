package com.blockland.mixin.terrain;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

/** A compile task's cancellation flag, for the queue sweep ({@code COMPILE_QUEUE}). */
@Mixin(SectionRenderDispatcher.RenderSection.CompileTask.class)
public interface CompileTaskAccess {
    @Accessor("isCancelled")
    AtomicBoolean blockland$cancelled();
}
