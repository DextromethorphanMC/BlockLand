package com.blockland.mixin.core;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;

/** Whether the graph of visible sections is still being worked out (a full update runs on a background thread). */
@Mixin(SectionOcclusionGraph.class)
public interface OcclusionGraphAccess {
    @Accessor("needsFullUpdate")
    boolean blockland$needsFullUpdate();

    @Accessor("fullUpdateTask")
    Future<?> blockland$fullUpdateTask();
}
