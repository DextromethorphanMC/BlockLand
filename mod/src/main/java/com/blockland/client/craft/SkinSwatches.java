package com.blockland.client.craft;

import com.blockland.BlockLand;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A skin's swatch for the in-game 工匠's skin cards: a small mosaic of colours sampled from the albedo of the texture set
 * it puts on the weapon's main region (so camo reads as camo), tinted like the skin tints it; a skin without a texture
 * set shows its tint or its effect colour. Sampled once per skin and cached until a resource reload.
 */
final class SkinSwatches {
    /** Cells of a swatch, row by row. */
    static final int COLUMNS = 4, ROWS = 2;
    private static final int PLAIN = 0xFF5A6474;

    private static final Map<String, int[]> cache = new HashMap<>();

    private SkinSwatches() {
    }

    static void clear() {
        cache.clear();
    }

    /** {@link #COLUMNS} × {@link #ROWS} ARGB colours. */
    static int[] of(Craftable weapon, CraftData.Skin skin) {
        return cache.computeIfAbsent(weapon.id() + "/" + skin.id, k -> sample(skin));
    }

    private static int[] sample(CraftData.Skin skin) {
        int[] cells = new int[COLUMNS * ROWS];
        CraftData.Layer layer = mainLayer(skin);
        float[] tint = layer != null && layer.tint != null && layer.tint.length >= 3 ? layer.tint : firstTint(skin);
        int fallback = fallback(skin, layer, tint);
        if (layer == null || layer.pbr.isEmpty() || !read(layer.pbr, cells)) {
            java.util.Arrays.fill(cells, fallback);
            return cells;
        }
        if (tint != null) {
            for (int i = 0; i < cells.length; i++) cells[i] = multiply(cells[i], tint);
        }
        return cells;
    }

    /** The layer of the region a skin is most seen on: the weapon's body (or every region), else the first with a texture. */
    @Nullable
    private static CraftData.Layer mainLayer(CraftData.Skin skin) {
        for (String region : new String[]{"body", CraftData.Skin.ANY_REGION, "receiver", "blade", "handguard"}) {
            CraftData.Layer layer = skin.regions.get(region);
            if (layer != null && !layer.pbr.isEmpty()) return layer;
        }
        for (CraftData.Layer layer : skin.regions.values()) {
            if (!layer.pbr.isEmpty()) return layer;
        }
        return skin.regions.isEmpty() ? null : skin.regions.values().iterator().next();
    }

    @Nullable
    private static float[] firstTint(CraftData.Skin skin) {
        for (float[] tint : skin.tints.values()) {
            if (tint != null && tint.length >= 3) return tint;
        }
        return null;
    }

    private static int fallback(CraftData.Skin skin, @Nullable CraftData.Layer layer, @Nullable float[] tint) {
        if (layer != null && !layer.effectColor.isEmpty()) {
            try {
                return 0xFF000000 | Integer.parseInt(layer.effectColor.replace("#", ""), 16);
            } catch (NumberFormatException ignored) {
                // a broken colour shows the plain swatch
            }
        }
        return tint != null ? multiply(0xFFFFFFFF, tint) : PLAIN;
    }

    /** Averages the albedo over a {@link #COLUMNS} × {@link #ROWS} grid; false when the file cannot be read. */
    private static boolean read(String base, int[] cells) {
        ResourceLocation location = BlockLand.id(base + ".png");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return false;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int w = image.getWidth(), h = image.getHeight();
            if (w <= 0 || h <= 0) return false;
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLUMNS; col++) {
                    long r = 0, g = 0, b = 0;
                    int n = 0;
                    int x0 = col * w / COLUMNS, x1 = Math.max(x0 + 1, (col + 1) * w / COLUMNS);
                    int y0 = row * h / ROWS, y1 = Math.max(y0 + 1, (row + 1) * h / ROWS);
                    int step = Math.max(1, Math.min(x1 - x0, y1 - y0) / 6);
                    for (int y = y0; y < y1; y += step) {
                        for (int x = x0; x < x1; x += step) {
                            int argb = image.getPixel(x, y);
                            r += (argb >> 16) & 0xFF;
                            g += (argb >> 8) & 0xFF;
                            b += argb & 0xFF;
                            n++;
                        }
                    }
                    cells[row * COLUMNS + col] = n == 0 ? PLAIN : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
                }
            }
            return true;
        } catch (Exception e) {
            BlockLand.LOGGER.debug("[BlockLand] No swatch for {}: {}", location, e.toString());
            return false;
        }
    }

    private static int multiply(int argb, float[] tint) {
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * tint[0]));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * tint[1]));
        int b = Math.min(255, Math.round((argb & 0xFF) * tint[2]));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
