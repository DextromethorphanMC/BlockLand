package com.blockland.client.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rigid things in the world that move on their own (vehicles), for the frame's motion vectors (rendering spec §4, §11):
 * whatever draws one reports, each frame it draws it in the main view, its pose (model → world) and a box in its model
 * space that holds it (and whatever rides on it). The graphics module pairs each with its pose a frame before; TAA, the
 * DLSS inputs and the motion blur move a world pixel that lies in such a box back to where that point of the object was,
 * instead of reprojecting it by the camera alone — so a vehicle passing at speed neither smears nor ghosts, and one the
 * camera rides along with stays sharp. Render thread only.
 */
public final class ObjectMotion {
    /** Movers the frame carries (nearest first). */
    public static final int MAX = 8;
    /** Farther than this (blocks) an object's motion is left to the camera reprojection. */
    private static final double RANGE = 160;

    private record Posed(double x, double y, double z, Quaternionf rotation, Vector3f center, Vector3f half) {
    }

    /**
     * A mover of this frame, relative to the current camera.
     *
     * @param local camera-relative world (this frame) → the box's space (the box is |p| ≤ half)
     * @param back  the box's space → where that point was a frame ago, camera-relative to this frame's camera
     */
    public record Mover(Matrix4f local, Matrix4f back, Vector3f half, double distance) {
    }

    private static Long2ObjectOpenHashMap<Posed> current = new Long2ObjectOpenHashMap<>(), previous = new Long2ObjectOpenHashMap<>();

    private ObjectMotion() {
    }

    /**
     * An object drawn this frame (the main view; a second report in the same frame replaces the first).
     *
     * @param key      stable per object (an entity id)
     * @param rotation model space → world directions
     * @param center   the box's centre in model space
     * @param half     the box's half extents in model space
     */
    public static void drawn(long key, double x, double y, double z, Quaternionfc rotation, Vector3f center, Vector3f half) {
        current.put(key, new Posed(x, y, z, new Quaternionf(rotation), new Vector3f(center), new Vector3f(half)));
    }

    /**
     * Graphics module, once per composited frame: the objects drawn in it that moved since the frame before, nearest
     * first (at most {@link #MAX}), relative to the camera at ({@code camX}, {@code camY}, {@code camZ}); then this frame
     * becomes the one before. An object not drawn in the frame before has no motion yet.
     */
    public static List<Mover> collect(double camX, double camY, double camZ) {
        List<Mover> movers = new ArrayList<>();
        for (Long2ObjectMap.Entry<Posed> e : current.long2ObjectEntrySet()) {
            Posed now = e.getValue(), before = previous.get(e.getLongKey());
            if (before == null) continue;
            double dx = now.x - before.x, dy = now.y - before.y, dz = now.z - before.z;
            double moved = dx * dx + dy * dy + dz * dz;
            boolean turned = Math.abs(now.rotation.dot(before.rotation)) < 0.9999995f;
            // a teleport is a cut, not motion
            if (moved > 64 || moved < 1e-8 && !turned) continue;
            double rx = now.x - camX, ry = now.y - camY, rz = now.z - camZ;
            double distance = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (distance > RANGE) continue;
            Matrix4f model = new Matrix4f().translate((float) rx, (float) ry, (float) rz).rotate(now.rotation).translate(now.center);
            Matrix4f back = new Matrix4f().translate((float) (before.x - camX), (float) (before.y - camY), (float) (before.z - camZ))
                    .rotate(before.rotation).translate(before.center);
            movers.add(new Mover(model.invert(), back, new Vector3f(now.half), distance));
        }
        movers.sort(Comparator.comparingDouble(Mover::distance));
        Long2ObjectOpenHashMap<Posed> swap = previous;
        previous = current;
        current = swap;
        current.clear();
        return movers.size() > MAX ? movers.subList(0, MAX) : movers;
    }

    /** Leaving a world: nothing carries over. */
    public static void clear() {
        current.clear();
        previous.clear();
    }
}
