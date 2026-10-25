package com.blockland.client.perf;

import com.blockland.mixin.core.CompileQueueAccess;
import com.blockland.mixin.core.CompileTaskStateAccess;
import com.blockland.mixin.core.LevelRendererAccess;
import com.blockland.mixin.core.OcclusionGraphAccess;
import com.blockland.mixin.core.SectionDispatcherAccess;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompileTaskDynamicQueue;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import java.util.concurrent.Future;

/**
 * Whether a rebuild of the sections has finished. Vanilla has no such question: its compile queue counter is not lowered
 * when the queue drops a cancelled task (so it may never reach zero), and right after a rebuild the queue is empty
 * because nothing is scheduled yet — the graph of visible sections is worked out on a background thread first, and the
 * sections are queued as they turn up in it.
 */
final class PendingSections {
    private PendingSections() {
    }

    /**
     * The graph of visible sections is complete and every visible section that can be built (all its neighbours are
     * loaded) has a mesh and nothing new to build. Visible sections counted into {@code visible[0]}.
     */
    static boolean visibleBuilt(LevelRenderer renderer, int[] visible) {
        LevelRendererAccess access = (LevelRendererAccess) renderer;
        OcclusionGraphAccess graph = (OcclusionGraphAccess) access.blockland$occlusionGraph();
        Future<?> update = graph.blockland$fullUpdateTask();
        if (graph.blockland$needsFullUpdate() || update != null && !update.isDone()) return false;
        var sections = access.blockland$visibleSections();
        visible[0] = sections.size();
        if (sections.isEmpty()) return false;
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (!section.hasAllNeighbors()) continue;
            if (section.isDirty() || section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) return false;
        }
        return true;
    }

    static int count(SectionRenderDispatcher dispatcher) {
        CompileTaskDynamicQueue queue = ((SectionDispatcherAccess) dispatcher).blockland$compileQueue();
        // the queue's own monitor: its methods are synchronized on it
        synchronized (queue) {
            int n = 0;
            for (SectionRenderDispatcher.RenderSection.CompileTask task : ((CompileQueueAccess) queue).blockland$tasks()) {
                if (!((CompileTaskStateAccess) task).blockland$isCancelled().get()) n++;
            }
            return n;
        }
    }
}
