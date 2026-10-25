package com.blockland.mixin.core;

import net.minecraft.client.renderer.chunk.CompileTaskDynamicQueue;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The queue's tasks (its {@code size()} never counts cancelled tasks it dropped, so it can stay above zero for good). */
@Mixin(CompileTaskDynamicQueue.class)
public interface CompileQueueAccess {
    @Accessor("tasks")
    List<SectionRenderDispatcher.RenderSection.CompileTask> blockland$tasks();
}
