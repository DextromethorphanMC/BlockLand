package com.blockland.client.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;

/**
 * How soaked entities are (rendering spec §15, soaking), a shared contract like {@link WaterRipples}: the water module
 * ({@code client/fluid/WaterInteractions}) records who is in water and how deep; whatever draws an entity's things — the
 * weapon in its hands — asks how wet they are. In water an entity is soaked through (1 under water, less wading); out
 * of it, it dries over {@link #DRY_TICKS} (half as fast while rain keeps falling on it). Client thread only.
 */
public final class Wetness {
    /** Ticks from soaked through to dry. */
    public static final int DRY_TICKS = 900;

    private static final Int2ObjectOpenHashMap<Soak> soaks = new Int2ObjectOpenHashMap<>();

    private static final class Soak {
        /** Wetness at {@link #since} (game time), drying from there. */
        float level;
        long since;
        /** Ticks it takes to dry from 1 now (longer in the rain). */
        int dry = DRY_TICKS;
    }

    private Wetness() {
    }

    /**
     * The entity is in water this tick, soaked to {@code level} (1 under water, about 0.4 wading to the waist); or out of
     * it with rain falling on it ({@code raining}: it dries half as fast).
     */
    public static void soak(Entity entity, float level, long gameTime, boolean raining) {
        Soak soak = soaks.get(entity.getId());
        if (soak == null) {
            if (level <= 0) return;
            soak = new Soak();
            soaks.put(entity.getId(), soak);
        }
        float now = current(soak, gameTime);
        soak.level = Math.max(now, Math.min(1, level));
        soak.since = gameTime;
        soak.dry = raining ? DRY_TICKS * 2 : DRY_TICKS;
    }

    /** How soaked the entity is now, 0 (dry) … 1 (just out of the water). */
    public static float of(Entity entity) {
        Soak soak = soaks.get(entity.getId());
        return soak == null ? 0 : current(soak, entity.level().getGameTime());
    }

    /** Forgets the dry ones (every few seconds) and anything no longer in the world. */
    public static void prune(long gameTime) {
        soaks.values().removeIf(s -> current(s, gameTime) <= 0);
    }

    public static void clear() {
        soaks.clear();
    }

    private static float current(Soak soak, long gameTime) {
        return Math.max(0, soak.level - (gameTime - soak.since) / (float) soak.dry);
    }
}
