package com.blockland.mixin.core;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sections the level draws and the graph that finds them, for the benchmark's "every section is built" check. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccess {
    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> blockland$visibleSections();

    @Accessor("sectionOcclusionGraph")
    SectionOcclusionGraph blockland$occlusionGraph();
}
