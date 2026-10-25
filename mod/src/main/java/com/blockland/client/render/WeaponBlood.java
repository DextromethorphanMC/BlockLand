package com.blockland.client.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Blood splashed back onto the weapons (and hands) entities hold (rendering spec §19, the weapon), a shared contract like
 * {@link Wetness}: the impact module records each splash — the way it flew, how much, how long it stays wet and at all —
 * and whatever draws an entity's weapon takes the splashes it has not laid yet ({@link #take}) and lays them on the
 * surfaces they reach, where they then stay (the first-person viewmodel: client/viewmodel/ViewmodelBlood). Client thread
 * only.
 */
public final class WeaponBlood {
    /** Splashes nobody laid within this many ticks are dropped (the weapon was not drawn). */
    private static final int STALE_TICKS = 40;

    /**
     * One splash on a holder's weapon.
     *
     * @param direction the way the blood flies, in the world (unit; from the wound towards the holder)
     * @param amount    how much (0..1; a blade's hit about 0.6, a round's close up less)
     * @param at        game time of the splash
     * @param fadeTicks ticks until it is gone
     * @param dryTicks  ticks until it is dry
     * @param seed      a different number for every splash (its drops' layout)
     */
    public record Splash(Vec3 direction, float amount, long at, int fadeTicks, int dryTicks, int seed) {
        /** 1 fresh and wet … 0 dry. */
        public float wetness(long gameTime) {
            return 1 - Math.min(1, Math.max(0, (gameTime - at) / (float) Math.max(1, dryTicks)));
        }

        /** 1 while it lasts, going over its last fifth (flaking off, wiped on the sleeve); 0 when gone. */
        public float opacity(long gameTime) {
            float t = (gameTime - at) / (float) Math.max(1, fadeTicks);
            return t >= 1 ? 0 : t <= 0.8f ? 1 : 1 - (t - 0.8f) / 0.2f;
        }

        public boolean gone(long gameTime) {
            return gameTime - at >= fadeTicks;
        }
    }

    private static final Int2ObjectOpenHashMap<List<Splash>> pending = new Int2ObjectOpenHashMap<>();
    private static int sequence;

    private WeaponBlood() {
    }

    /**
     * Blood splashed back onto {@code holder}'s weapon.
     *
     * @param direction the way it flies (world; normalised here)
     * @param amount    how much (0..1)
     * @param fadeTicks ticks until it is gone
     * @param dryTicks  ticks until it is dry
     */
    public static void splash(Entity holder, Vec3 direction, float amount, long gameTime, int fadeTicks, int dryTicks) {
        if (amount <= 0 || direction.lengthSqr() < 1e-8) return;
        pending.computeIfAbsent(holder.getId(), k -> new ArrayList<>())
                .add(new Splash(direction.normalize(), Math.min(1, amount), gameTime, Math.max(20, fadeTicks), Math.max(1, dryTicks),
                        ++sequence * 7919 + holder.getId() * 104729));
    }

    /** The splashes on {@code holder}'s weapon not laid yet (taken: the next call returns only newer ones). */
    public static List<Splash> take(Entity holder) {
        List<Splash> list = pending.remove(holder.getId());
        return list == null ? List.of() : list;
    }

    /** Wipes everything (the world left). */
    public static void clear() {
        pending.clear();
    }

    /** Forgets splashes nobody laid (the weapon was not on screen). */
    public static void prune(long gameTime) {
        pending.values().forEach(list -> list.removeIf(s -> gameTime - s.at() > STALE_TICKS));
        pending.int2ObjectEntrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
