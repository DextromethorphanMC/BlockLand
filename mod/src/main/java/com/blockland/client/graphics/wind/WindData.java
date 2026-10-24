package com.blockland.client.graphics.wind;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The wind's tunables ({@code assets/blockland/graphics/wind.json}, rendering spec §18; Gson, field names = JSON keys).
 * Which sprites move and how is the block materials' {@code wind}; this file says how hard the wind and bodies move them
 * and which blocks stack into plant columns.
 */
final class WindData {
    private static final ResourceLocation FILE = BlockLand.id("graphics/wind.json");

    /** Single plants (and the halves of two-block ones). */
    static final class Bend {
        /** Lean per unit of wind at the top of a one-block plant (blocks). */
        float lean = 0.18f;
        /** Flutter per unit of wind (blocks), on top of the lean. */
        float flutter = 0.035f;
        /** Displacement ∝ height over the plant's base to this power (a cantilever bends more the taller it is). */
        float exponent = 1.75f;
        /** How far upwind (blocks per block of height) a stem's top feels the wind: tall stems lag a gust at the top. */
        float lag = 0.6f;
    }

    /** Stacked and hanging plants (sugar cane, bamboo, kelp, vines): a column bending from its base or its top. */
    static final class Stack {
        float lean = 0.05f;
        float flutter = 0.012f;
        /** At most this far does a body push a column (blocks). */
        float pushCap = 0.35f;
    }

    /** Bodies pushing plants aside and the plants springing back behind them. */
    static final class Push {
        /** The push fades to nothing this far beyond the body's half width (blocks). */
        float radiusPad = 0.6f;
        /** Share of the radius within which the push is full. */
        float core = 0.15f;
        /** Degrees a plant leans right beside a body. */
        float lean = 70;
        /** How far a plant under a foot is pressed down (share of 90°). */
        float flatten = 0.85f;
        /** The spring back after a body leaves: period (s) and damping ratio. */
        float springPeriod = 0.45f;
        float springDamping = 0.35f;
        /** The small wobble left along a walker's heading (× the old trail sway). */
        float trail = 0.08f;
        /** Push per wind mode. */
        Kinds kinds = new Kinds();
    }

    static final class Kinds {
        float bend = 1, sway = 0.6f, leaves = 0.12f, water = 0.8f;
    }

    /** Blocks forming plant columns: growing up from their base, or hanging from their top. */
    static final class Columns {
        List<String> up = List.of();
        List<String> hang = List.of();
    }

    Bend bend = new Bend();
    Stack stack = new Stack();
    Push push = new Push();
    Columns columns = new Columns();

    static WindData load(ResourceManager manager) {
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing {}", FILE);
            return new WindData();
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            WindData data = new Gson().fromJson(reader, WindData.class);
            if (data == null) return new WindData();
            if (data.bend == null) data.bend = new Bend();
            if (data.stack == null) data.stack = new Stack();
            if (data.push == null) data.push = new Push();
            if (data.push.kinds == null) data.push.kinds = new Kinds();
            if (data.columns == null) data.columns = new Columns();
            return data;
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot read {}", FILE, e);
            return new WindData();
        }
    }

    static Set<Block> blocks(List<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String name : ids == null ? List.<String>of() : ids) {
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
        }
        return blocks;
    }
}
