package com.blockland.client.graffiti;

import com.blockland.BlockLand;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Particle art: a picture turned into points of light. The picture's opaque pixels are sampled once per graffiti (on
 * the client, from the texture file), each point keeping the colour it came from; a placement only adds its own random
 * order, phases and scatter. Cleared on a resource reload.
 */
final class GraffitiArt {
    /** One point: where in the square (−0.5..0.5, y up), its colour (0xRRGGBB) and a random phase 0..1. */
    record Point(float x, float y, int rgb, float phase) {
    }

    private static final Map<String, List<Point>> cache = new HashMap<>();

    private GraffitiArt() {
    }

    static void clear() {
        cache.clear();
    }

    /** {@code count} points of the texture (fewer when the picture has fewer opaque pixels), the same every time. */
    static List<Point> points(String texture, int count) {
        return cache.computeIfAbsent(texture + "#" + count, k -> sample(BlockLand.id(texture), count));
    }

    private static List<Point> sample(ResourceLocation location, int count) {
        List<Point> points = new ArrayList<>();
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return points;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int w = image.getWidth(), h = image.getHeight();
            List<int[]> opaque = new ArrayList<>();
            // every other pixel is enough to choose from
            for (int y = 0; y < h; y += 2) {
                for (int x = 0; x < w; x += 2) {
                    if (ARGB.alpha(image.getPixel(x, y)) > 128) opaque.add(new int[]{x, y});
                }
            }
            RandomSource random = RandomSource.create(location.hashCode());
            for (int i = 0; i < count && !opaque.isEmpty(); i++) {
                int[] p = opaque.remove(random.nextInt(opaque.size()));
                int argb = image.getPixel(p[0], p[1]);
                points.add(new Point(p[0] / (float) w - 0.5f, 0.5f - p[1] / (float) h, argb & 0xFFFFFF, random.nextFloat()));
            }
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not sample graffiti {}: {}", location, e.toString());
        }
        return points;
    }
}
