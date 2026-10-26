package com.blockland.client.ctm;

import com.blockland.BlockLand;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One connected-texture rule ({@code assets/<ns>/ctm/<name>.json}, rendering spec §25): which blocks it wraps, which of
 * their textures and faces it replaces, how the tile is chosen and what counts as connected.
 */
final class CtmRule {
    enum Method {
        CTM, HORIZONTAL, VERTICAL, RANDOM
    }

    enum Connect {
        /** The same block (any state). */
        BLOCK,
        /** The very same block state. */
        STATE
    }

    final ResourceLocation source;
    final List<ResourceLocation> blocks;
    /** Source sprites replaced (empty: every quad of the wrapped blocks on the rule's faces). */
    final Set<ResourceLocation> textures;
    /** Bit per {@link Direction#ordinal()}. */
    final int faces;
    final Method method;
    final Connect connect;
    final List<Block> connectBlocks;
    final List<TagKey<Block>> connectTags;
    /** A neighbour whose own face here is covered by a connected block does not connect. */
    final boolean exposedOnly;
    final ResourceLocation[] tiles;
    /** Cumulative weights (random). */
    final int[] weights;
    final int totalWeight;

    /** The tiles in the block atlas, looked up on first use (after the atlas upload); empty when one is missing. */
    @Nullable
    private volatile TextureAtlasSprite[] sprites;

    private CtmRule(ResourceLocation source, List<ResourceLocation> blocks, Set<ResourceLocation> textures, int faces, Method method,
                    Connect connect, List<Block> connectBlocks, List<TagKey<Block>> connectTags, boolean exposedOnly,
                    ResourceLocation[] tiles, int[] weights) {
        this.source = source;
        this.blocks = blocks;
        this.textures = textures;
        this.faces = faces;
        this.method = method;
        this.connect = connect;
        this.connectBlocks = connectBlocks;
        this.connectTags = connectTags;
        this.exposedOnly = exposedOnly;
        this.tiles = tiles;
        this.weights = weights;
        this.totalWeight = weights.length == 0 ? 0 : weights[weights.length - 1];
    }

    boolean appliesTo(Direction face, ResourceLocation sprite) {
        return (faces & 1 << face.ordinal()) != 0 && (textures.isEmpty() || textures.contains(sprite));
    }

    /** Whether {@code other} joins {@code self} under this rule. */
    boolean connects(BlockState self, BlockState other) {
        if (connect == Connect.STATE ? other == self : other.getBlock() == self.getBlock()) return true;
        for (Block b : connectBlocks) if (other.getBlock() == b) return true;
        for (TagKey<Block> tag : connectTags) if (other.is(tag)) return true;
        return false;
    }

    /** The tile sprites, or null when the rule cannot be drawn (a tile missing from the atlas). */
    @Nullable
    TextureAtlasSprite[] sprites() {
        TextureAtlasSprite[] s = sprites;
        if (s == null) {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            s = new TextureAtlasSprite[tiles.length];
            for (int i = 0; i < tiles.length; i++) {
                try {
                    s[i] = atlas.getSprite(tiles[i]);
                } catch (IllegalStateException notYet) {
                    // the atlas is not uploaded yet: draw the block as it is this time and look again next time
                    return null;
                }
                if (s[i] == null || s[i].contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                    BlockLand.LOGGER.warn("[BlockLand] Connected textures {}: tile {} is not in the block atlas", source, tiles[i]);
                    s = new TextureAtlasSprite[0];
                    break;
                }
            }
            sprites = s;
        }
        return s.length == 0 ? null : s;
    }

    /** The tile for a mask in the texture's frame ({@code seed}: the block and face, for {@code random}). */
    int tile(int textureMask, long seed) {
        return switch (method) {
            case CTM -> CtmTiles.ctm(textureMask);
            case HORIZONTAL -> CtmTiles.horizontal(textureMask);
            case VERTICAL -> CtmTiles.vertical(textureMask);
            case RANDOM -> {
                int r = (int) Long.remainderUnsigned(seed, totalWeight);
                int i = 0;
                while (r >= weights[i]) i++;
                yield i;
            }
        };
    }

    // ---- parsing --------------------------------------------------------------------------------------

    /** Parses a rule file; throws {@link IllegalArgumentException} with what is wrong. */
    static CtmRule parse(ResourceLocation source, JsonObject json) {
        List<ResourceLocation> blocks = new ArrayList<>();
        for (String id : strings(json.get("blocks"))) {
            if (id.startsWith("#")) throw new IllegalArgumentException("blocks: tags are not allowed (models are wrapped before tags exist): " + id);
            blocks.add(ResourceLocation.parse(id));
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("blocks: none given");
        Set<ResourceLocation> textures = new HashSet<>();
        for (String id : strings(json.get("textures"))) textures.add(ResourceLocation.parse(id));
        int faces = json.has("faces") ? 0 : 0x3F;
        for (String f : strings(json.get("faces"))) faces |= faces(f);
        String m = json.has("method") ? json.get("method").getAsString() : "ctm";
        Method method = switch (m) {
            case "ctm" -> Method.CTM;
            case "horizontal" -> Method.HORIZONTAL;
            case "vertical" -> Method.VERTICAL;
            case "random" -> Method.RANDOM;
            default -> throw new IllegalArgumentException("method: unknown " + m);
        };
        String c = json.has("connect") ? json.get("connect").getAsString() : "block";
        Connect connect = switch (c) {
            case "block" -> Connect.BLOCK;
            case "state" -> Connect.STATE;
            default -> throw new IllegalArgumentException("connect: unknown " + c);
        };
        List<Block> connectBlocks = new ArrayList<>();
        List<TagKey<Block>> connectTags = new ArrayList<>();
        for (String id : strings(json.get("connectTo"))) {
            if (id.startsWith("#")) {
                connectTags.add(TagKey.create(Registries.BLOCK, ResourceLocation.parse(id.substring(1))));
            } else {
                Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).orElse(null);
                if (b == null) BlockLand.LOGGER.warn("[BlockLand] Connected textures {}: unknown block {} in connectTo", source, id);
                else connectBlocks.add(b);
            }
        }
        boolean exposedOnly = !json.has("exposedOnly") || json.get("exposedOnly").getAsBoolean();

        int count = switch (method) {
            case CTM -> CtmTiles.CTM_TILES;
            case HORIZONTAL, VERTICAL -> 4;
            case RANDOM -> json.has("count") ? json.get("count").getAsInt() : -1;
        };
        JsonElement t = json.get("tiles");
        if (t == null) throw new IllegalArgumentException("tiles: none given");
        ResourceLocation[] tiles;
        if (t.isJsonArray()) {
            List<String> list = strings(t);
            tiles = new ResourceLocation[list.size()];
            for (int i = 0; i < tiles.length; i++) tiles[i] = ResourceLocation.parse(list.get(i));
        } else {
            if (count <= 0) throw new IllegalArgumentException("tiles: a random rule with a base path needs \"count\"");
            ResourceLocation base = ResourceLocation.parse(t.getAsString());
            tiles = new ResourceLocation[count];
            for (int i = 0; i < count; i++) tiles[i] = base.withSuffix("/" + i);
        }
        if (count > 0 && tiles.length != count) {
            throw new IllegalArgumentException("tiles: method " + m + " needs " + count + " tiles, got " + tiles.length);
        }
        if (tiles.length == 0) throw new IllegalArgumentException("tiles: none given");
        int[] weights = new int[0];
        if (method == Method.RANDOM) {
            weights = new int[tiles.length];
            JsonArray w = json.has("weights") ? json.getAsJsonArray("weights") : null;
            if (w != null && w.size() != tiles.length) throw new IllegalArgumentException("weights: one per tile");
            int sum = 0;
            for (int i = 0; i < tiles.length; i++) {
                int wi = w == null ? 1 : w.get(i).getAsInt();
                if (wi < 0) throw new IllegalArgumentException("weights: negative");
                weights[i] = sum += wi;
            }
            if (sum <= 0) throw new IllegalArgumentException("weights: all zero");
        }
        return new CtmRule(source, List.copyOf(blocks), Set.copyOf(textures), faces, method, connect, List.copyOf(connectBlocks),
                List.copyOf(connectTags), exposedOnly, tiles, weights);
    }

    private static int faces(String name) {
        return switch (name) {
            case "all" -> 0x3F;
            case "sides" -> 1 << Direction.NORTH.ordinal() | 1 << Direction.SOUTH.ordinal() | 1 << Direction.WEST.ordinal()
                    | 1 << Direction.EAST.ordinal();
            case "top" -> 1 << Direction.UP.ordinal();
            case "bottom" -> 1 << Direction.DOWN.ordinal();
            default -> {
                Direction d = Direction.byName(name);
                if (d == null) throw new IllegalArgumentException("faces: unknown " + name);
                yield 1 << d.ordinal();
            }
        };
    }

    private static List<String> strings(@Nullable JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null) return out;
        if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        else out.add(e.getAsString());
        return out;
    }
}
