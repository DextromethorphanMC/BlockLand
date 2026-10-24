package com.blockland.server.combat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Where every living entity stood over the last second, tick by tick, so a shot can be checked against the world as
 * the shooter saw it (lag compensation): a client draws the others a round trip plus its interpolation behind the
 * server, and a target that was under the crosshair there has moved on here by the time the shot arrives.
 */
public final class HitHistory {
    /** Ticks kept (one second). */
    public static final int TICKS = 20;

    private static final class Track {
        final Vec3[] positions = new Vec3[TICKS];
        int filled;
        int head;
        int lastTick;

        void add(Vec3 position, int tick) {
            head = (head + 1) % TICKS;
            positions[head] = position;
            filled = Math.min(TICKS, filled + 1);
            lastTick = tick;
        }

        Vec3 ago(int ticks) {
            int k = Math.min(ticks, filled - 1);
            return positions[(head - k + TICKS) % TICKS];
        }
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    private int tick;

    /** Records this tick's positions (call once per server tick, after the entities moved). */
    public void tick(MinecraftServer server) {
        tick++;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity) || !entity.isAlive()) continue;
                tracks.computeIfAbsent(entity.getId(), id -> new Track()).add(entity.position(), tick);
            }
        }
        tracks.values().removeIf(track -> tick - track.lastTick > TICKS);
    }

    /** Where the entity stood {@code ticks} ticks ago (as far back as recorded; its position now when unknown). */
    public Vec3 positionAgo(Entity entity, int ticks) {
        Track track = tracks.get(entity.getId());
        if (track == null || track.filled == 0 || ticks <= 0) return entity.position();
        return track.ago(Math.min(ticks, TICKS - 1));
    }

    public void clear() {
        tracks.clear();
    }
}
