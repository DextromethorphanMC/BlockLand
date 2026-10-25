package com.blockland.client.graphics.post;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * Where entities were drawn in one frame, by entity id, without an object per entity ({@code FRAME_GARBAGE}): the ids
 * map to slots of one array of positions, both reused frame after frame. The positions are the very doubles stored.
 */
final class DrawnPositions {
    private final Int2IntOpenHashMap slots = new Int2IntOpenHashMap();
    private double[] xyz = new double[3 * 64];
    private int size;

    DrawnPositions() {
        slots.defaultReturnValue(-1);
    }

    void clear() {
        slots.clear();
        size = 0;
    }

    /** Sets the entity's position (a second put for the same id replaces it, like a map's). */
    void put(int id, double x, double y, double z) {
        int slot = slots.get(id);
        if (slot < 0) {
            slot = size++;
            if (slot * 3 + 3 > xyz.length) xyz = Arrays.copyOf(xyz, xyz.length * 2);
            slots.put(id, slot);
        }
        xyz[slot * 3] = x;
        xyz[slot * 3 + 1] = y;
        xyz[slot * 3 + 2] = z;
    }

    /** The entity's slot, or -1 when it has no position here. */
    int slot(int id) {
        return slots.get(id);
    }

    double x(int slot) {
        return xyz[slot * 3];
    }

    double y(int slot) {
        return xyz[slot * 3 + 1];
    }

    double z(int slot) {
        return xyz[slot * 3 + 2];
    }

    /** Becomes a copy of {@code map} (the same positions). */
    void copyFrom(Int2ObjectOpenHashMap<Vec3> map) {
        clear();
        for (Int2ObjectMap.Entry<Vec3> e : Int2ObjectMaps.fastIterable(map)) {
            Vec3 at = e.getValue();
            put(e.getIntKey(), at.x, at.y, at.z);
        }
    }

    /** Makes {@code map} a copy of this (the same positions). */
    void copyTo(Int2ObjectOpenHashMap<Vec3> map) {
        map.clear();
        for (Int2IntMap.Entry e : Int2IntMaps.fastIterable(slots)) {
            int slot = e.getIntValue();
            map.put(e.getIntKey(), new Vec3(xyz[slot * 3], xyz[slot * 3 + 1], xyz[slot * 3 + 2]));
        }
    }
}
