package com.blockland.equipment.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The active smoke clouds and fire patches on the server, so they can act on each other (smoke puts fire out, a
 * molotov breaking inside smoke fizzles). Keyed by the thrown entity that carries the area; an entry goes when its
 * entity is removed or its time is up. Server thread only.
 */
final class Areas {
    record Smoke(Entity entity, Vec3 centre, float radius, float height, long until) {
        /** Whether the cloud (an upright ellipsoid on the ground) holds the point, with {@code margin} blocks to spare. */
        boolean covers(Vec3 p, float margin) {
            double dx = p.x - centre.x, dz = p.z - centre.z, dy = p.y - (centre.y + height * 0.35);
            double r = radius + margin, h = height * 0.65 + margin;
            return dx * dx / (r * r) + dz * dz / (r * r) + dy * dy / (h * h) <= 1;
        }
    }

    record Fire(Entity entity, List<FireSpread.Cell> cells, Vec3 centre, float radius, long startTick, float spreadSeconds) {
        /** Whether the cell burns yet (the fire spreads out from the impact). */
        boolean lit(FireSpread.Cell cell, long gameTime) {
            return (gameTime - startTick) / 20f >= cell.delay() * spreadSeconds;
        }
    }

    private static final List<Smoke> smokes = new ArrayList<>();
    private static final Map<Entity, Fire> fires = new IdentityHashMap<>();

    private Areas() {
    }

    static void addSmoke(Smoke smoke) {
        smokes.removeIf(s -> s.entity() == smoke.entity());
        smokes.add(smoke);
    }

    /** The smoke cloud covering the point (with margin), or null. */
    static Smoke smokeAt(ServerLevel level, Vec3 p, float margin) {
        long now = level.getGameTime();
        smokes.removeIf(s -> s.entity().isRemoved() || now > s.until());
        for (Smoke s : smokes) {
            if (s.entity().level() == level && s.covers(p, margin)) return s;
        }
        return null;
    }

    static void addFire(Fire fire) {
        fires.put(fire.entity(), fire);
    }

    static Fire fire(Entity entity) {
        return fires.get(entity);
    }

    static void removeFire(Entity entity) {
        fires.remove(entity);
        fires.keySet().removeIf(Entity::isRemoved);
    }

    /** Fire patches whose centre a new smoke cloud covers. */
    static List<Fire> firesUnder(Smoke smoke) {
        fires.keySet().removeIf(Entity::isRemoved);
        List<Fire> out = new ArrayList<>();
        for (Fire fire : fires.values()) {
            if (fire.entity().level() == smoke.entity().level() && smoke.covers(fire.centre(), fire.radius() * 0.5f)) out.add(fire);
        }
        return out;
    }
}
