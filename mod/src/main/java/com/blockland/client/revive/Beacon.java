package com.blockland.client.revive;

import com.blockland.client.render.LightSources;
import com.blockland.client.render.RenderClock;
import com.blockland.revive.ReviveAntennaBlock;
import com.blockland.revive.ReviveAntennaBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The red beacon on top of a revive antenna (asset spec §51): a double flash every {@link #PERIOD} seconds, each antenna
 * at its own phase; the renderer draws the lit or the dark beacon node by it, and a red dynamic light
 * ({@link LightSources}) shines at the beacon while it flashes.
 */
final class Beacon {
    static final float PERIOD = 1.8f;
    /** (start, end) of each flash in the period, seconds. */
    private static final float[][] FLASHES = {{0f, 0.11f}, {0.24f, 0.35f}};
    /** The beacon's centre in the block frame with the front north (revive_antenna_model.BEACON_CENTRE). */
    static final float X = -0.032f, Y = 1.495f, Z = 0.055f;
    private static final int RGB = 0xFF2A18;
    private static final float INTENSITY = 1.5f, RADIUS = 7f;

    private static final Map<ReviveAntennaBlockEntity, Supplier<LightSources.Light>> lights = new WeakHashMap<>();

    private Beacon() {
    }

    /** How bright an antenna's beacon is now, 0..1 (edges 20 ms soft, so the light never pops). */
    static float level(BlockPos pos) {
        float t = (float) ((RenderClock.nanos() / 1e9 + phase(pos)) % PERIOD);
        float on = 0;
        for (float[] f : FLASHES) on = Math.max(on, Mth.clamp(Math.min(t - f[0], f[1] - t) / 0.02f + 0.5f, 0, 1));
        return on;
    }

    private static float phase(BlockPos pos) {
        long h = Mth.getSeed(pos.getX(), pos.getY(), pos.getZ());
        return (float) ((h >>> 16) & 0xFFFF) / 0xFFFF * PERIOD;
    }

    /** Attaches the beacon's light once per antenna (again after the light list was cleared, e.g. a new world). */
    static void attach(ReviveAntennaBlockEntity antenna) {
        Supplier<LightSources.Light> light = lights.get(antenna);
        if (light != null && LightSources.isAttached(light)) return;
        light = () -> lightOf(antenna);
        lights.put(antenna, light);
        LightSources.attach(light);
    }

    @Nullable
    private static LightSources.Light lightOf(ReviveAntennaBlockEntity antenna) {
        Minecraft mc = Minecraft.getInstance();
        if (antenna.isRemoved() || antenna.getLevel() == null || antenna.getLevel() != mc.level) return null;
        BlockPos pos = antenna.getBlockPos();
        Direction facing = antenna.getBlockState().getOptionalValue(ReviveAntennaBlock.FACING).orElse(Direction.NORTH);
        // the model's frame turned as the renderer turns it (JOML rotateY)
        double yaw = Math.toRadians(180 - facing.toYRot());
        double c = Math.cos(yaw), s = Math.sin(yaw);
        double x = X * c + Z * s, z = -X * s + Z * c;
        float k = level(pos);
        return new LightSources.Light(pos.getX() + 0.5 + x, pos.getY() + Y, pos.getZ() + 0.5 + z, (RGB >> 16 & 0xFF) / 255f,
                (RGB >> 8 & 0xFF) / 255f, (RGB & 0xFF) / 255f, INTENSITY * k, RADIUS);
    }
}
