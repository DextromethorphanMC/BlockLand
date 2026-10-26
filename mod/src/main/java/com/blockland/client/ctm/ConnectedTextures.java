package com.blockland.client.ctm;

import com.blockland.BlockLand;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import java.io.Reader;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Connected textures (连接贴图, rendering spec §25): blocks named by resource-pack data ({@code assets/<ns>/ctm/*.json},
 * vanilla blocks included) pick each face's tile from their neighbours, so a wall of them reads as one surface with a
 * frame round its outside. Blocks never know they are connected.
 * <p>
 * The rules are read with every model bake (a resource reload: the new packs are already in place when the models
 * bake) and the listed blocks' models are wrapped in the bake's result ({@link ModelEvent.ModifyBakingResult}), so
 * the block-state dispatch, the section compile and everything downstream use the wrapped models.
 */
public final class ConnectedTextures {
    private static final String DIRECTORY = "ctm";

    private ConnectedTextures() {
    }

    public static void init(BusGroup modBus) {
        ModelEvent.ModifyBakingResult.getBus(modBus).addListener(event -> wrap(event.getResults().blockStateModels()));
        ConnectedPack.init(modBus);
    }

    /** Wraps the models of every block a rule names (runs on the model bake's worker thread). */
    private static void wrap(Map<BlockState, BlockStateModel> models) {
        Map<Block, List<CtmRule>> byBlock = load(Minecraft.getInstance().getResourceManager());
        if (byBlock.isEmpty()) return;
        int wrapped = 0;
        for (Map.Entry<Block, List<CtmRule>> e : byBlock.entrySet()) {
            List<CtmRule> rules = List.copyOf(e.getValue());
            // the states of a block usually share one baked model: one wrapper (and one quad cache) per model
            Map<BlockStateModel, ConnectedModel> wrappers = new IdentityHashMap<>();
            for (BlockState state : e.getKey().getStateDefinition().getPossibleStates()) {
                BlockStateModel model = models.get(state);
                if (model == null) continue;
                models.put(state, wrappers.computeIfAbsent(model, m -> new ConnectedModel(m, rules)));
                wrapped++;
            }
        }
        BlockLand.LOGGER.info("[BlockLand] Connected textures: {} blocks, {} block states", byBlock.size(), wrapped);
    }

    /** Every rule of the current packs by the block it wraps, in file order (the first rule matching a quad wins). */
    static Map<Block, List<CtmRule>> load(ResourceManager resources) {
        Map<Block, List<CtmRule>> byBlock = new LinkedHashMap<>();
        // sorted, so the rule order (and with it the output) never depends on the map's iteration order
        Map<ResourceLocation, Resource> files = new TreeMap<>(resources.listResources(DIRECTORY, id -> id.getPath().endsWith(".json")));
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            List<CtmRule> rules = new ArrayList<>();
            try (Reader reader = file.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                // one rule, or several under "rules" (e.g. a block's sides and its top)
                if (json.has("rules")) {
                    for (JsonElement e : json.getAsJsonArray("rules")) rules.add(CtmRule.parse(file.getKey(), e.getAsJsonObject()));
                } else {
                    rules.add(CtmRule.parse(file.getKey(), json));
                }
            } catch (Exception ex) {
                BlockLand.LOGGER.warn("[BlockLand] Connected textures {}: {}", file.getKey(), ex.getMessage());
                continue;
            }
            for (CtmRule rule : rules) add(byBlock, file.getKey(), rule);
        }
        return byBlock;
    }

    private static void add(Map<Block, List<CtmRule>> byBlock, ResourceLocation file, CtmRule rule) {
        for (ResourceLocation id : rule.blocks) {
            Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block == null) {
                BlockLand.LOGGER.warn("[BlockLand] Connected textures {}: unknown block {}", file, id);
                continue;
            }
            byBlock.computeIfAbsent(block, b -> new ArrayList<>()).add(rule);
        }
    }
}
