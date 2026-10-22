package com.blockland.client.render;

import com.blockland.BlockLand;
import com.blockland.client.gltf.GlbReader;
import com.blockland.client.gltf.GltfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GLB models from resource packs, parsed and uploaded on first use and kept until the next resource reload
 * (so an artist's replacement GLB in a resource pack is picked up with F3+T).
 */
public final class ModelCache {
    private static final Map<ResourceLocation, Optional<GpuModel>> models = new HashMap<>();

    private ModelCache() {
    }

    /** @return the model, or null when it is missing or broken (logged once) */
    @Nullable
    public static GpuModel get(ResourceLocation location) {
        return models.computeIfAbsent(location, ModelCache::load).orElse(null);
    }

    private static Optional<GpuModel> load(ResourceLocation location) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing model {}", location);
            return Optional.empty();
        }
        try (InputStream in = resource.get().open()) {
            long start = System.nanoTime();
            GltfModel model = GlbReader.read(in.readAllBytes());
            GpuModel gpu = GpuModel.upload(location.toString(), model);
            BlockLand.LOGGER.info("[BlockLand] Loaded {}: {} nodes, {} meshes, {} animations in {} ms", location,
                    model.nodes.size(), model.meshes.size(), model.animations.size(), (System.nanoTime() - start) / 1_000_000);
            return Optional.of(gpu);
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot load model {}", location, e);
            return Optional.empty();
        }
    }

    public static void clear() {
        models.values().forEach(m -> m.ifPresent(GpuModel::close));
        models.clear();
    }
}
