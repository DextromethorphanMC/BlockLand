package com.blockland.mixin.core;

import net.minecraft.client.renderer.chunk.CompileTaskDynamicQueue;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The dispatcher's compile queue, for the benchmark's "every section is built" check ({@code perf/PendingSections}). */
@Mixin(SectionRenderDispatcher.class)
public interface SectionDispatcherAccess {
    @Accessor("compileQueue")
    CompileTaskDynamicQueue blockland$compileQueue();
}
