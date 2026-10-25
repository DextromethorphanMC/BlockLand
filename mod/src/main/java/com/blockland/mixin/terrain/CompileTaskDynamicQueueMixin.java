package com.blockland.mixin.terrain;

import com.blockland.client.perf.Optimization;
import com.blockland.client.perf.terrain.CompileQueue;
import net.minecraft.client.renderer.chunk.CompileTaskDynamicQueue;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** The compile queue drops its cancelled tasks in one sweep before vanilla's scan ({@code COMPILE_QUEUE}). */
@Mixin(CompileTaskDynamicQueue.class)
public abstract class CompileTaskDynamicQueueMixin {
    @Shadow
    @Final
    private List<SectionRenderDispatcher.RenderSection.CompileTask> tasks;

    @Inject(method = "poll", at = @At("HEAD"))
    private void blockland$sweep(Vec3 camera, CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.CompileTask> cir) {
        if (Optimization.COMPILE_QUEUE.on()) CompileQueue.sweep(tasks, task -> ((CompileTaskAccess) task).blockland$cancelled().get());
    }
}
