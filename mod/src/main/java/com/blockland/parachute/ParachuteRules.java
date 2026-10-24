package com.blockland.parachute;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Parachute tuning as written in {@code data/blockland/parachute/parachute.json} (read by Gson; field names = JSON
 * keys), read once from the classpath — the same file on client and server, so the jumper's prediction and the
 * server's checks agree. Speeds are blocks per tick, lengths blocks, angles degrees. Also the rules both sides check
 * before a canopy may open.
 */
public final class ParachuteRules {
    private static final String RESOURCE = "/data/blockland/parachute/parachute.json";
    private static ParachuteRules instance;

    /** Steady sinking speed under a full canopy. */
    public float descentSpeed = 0.13f;
    /** Ticks the canopy takes to fill: the fall is braked from free fall to {@link #descentSpeed} over them. */
    public int deployTicks = 12;
    /** Fastest fall left right after the canopy starts to open (the jolt), eased down to the descent speed. */
    public float deployFallSpeed = 0.55f;
    /** Horizontal speed the movement keys steer towards, and the drift forward without keys. */
    public float steerSpeed = 0.22f;
    public float forwardGlide = 0.08f;
    /** Share of the difference to the steered velocity taken each tick. */
    public float steerResponse = 0.14f;
    /** The canopy only opens this high above the ground (or more). */
    public float minOpenHeight = 3;
    /** Ticks between two toggles. */
    public int cooldownTicks = 6;
    public Camera camera = new Camera();
    public Canopy canopy = new Canopy();

    /** First-person feel under the canopy. */
    public static final class Camera {
        /** Degrees of the slow swing under the canopy. */
        public float sway = 1.4f;
        /** Degrees of roll when steering sideways. */
        public float bank = 5;
        /** Field of view widening while falling fast (0.03 = 3 %). */
        public float fovKick = 0.03f;
    }

    /** The drawn canopy (a ram-air wing): its size and how far above the harness it flies. */
    public static final class Canopy {
        public float span = 4.4f;
        public float chord = 1.6f;
        public float height = 3.0f;
        /** Degrees the wing curves down over its span. */
        public float arc = 100;
        public int cells = 9;
        public float thickness = 0.24f;
    }

    public static synchronized ParachuteRules get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static ParachuteRules load() {
        try (InputStream in = ParachuteRules.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                ParachuteRules rules = new Gson().fromJson(reader, ParachuteRules.class);
                return rules == null ? new ParachuteRules() : rules;
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the parachute rules", e);
            return new ParachuteRules();
        }
    }

    /** A parachute in the chest slot. */
    public static boolean wears(Player player) {
        return player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ParachuteItem;
    }

    /**
     * Whether a canopy may be open on this player at all: wearing one, in the open air (not on the ground, swimming,
     * riding, gliding with an elytra or flying), alive and playing.
     */
    public static boolean mayBeOpen(Player player) {
        return player.isAlive() && !player.isSpectator() && wears(player) && !player.onGround() && !player.isInWater()
                && !player.isInLava() && !player.isPassenger() && !player.isFallFlying() && !player.getAbilities().flying;
    }

    /** Whether the canopy may open now: {@link #mayBeOpen}, and at least {@code minOpenHeight} − {@code leeway} up. */
    public static boolean mayOpen(Player player, float leeway) {
        return mayBeOpen(player) && heightAboveGround(player, get().minOpenHeight + 1) >= get().minOpenHeight - leeway;
    }

    /** Blocks between the feet and the first solid block below, capped at {@code max}. */
    public static float heightAboveGround(Player player, float max) {
        Level level = player.level();
        BlockPos.MutableBlockPos pos = BlockPos.containing(player.getX(), player.getY(), player.getZ()).mutable();
        double feet = player.getY();
        for (int i = 0; i <= Math.ceil(max) + 1; i++) {
            VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double top = pos.getY() + shape.max(Direction.Axis.Y);
                return (float) Math.max(0, Math.min(max, feet - top));
            }
            if (pos.getY() <= level.getMinY()) break;
            pos.move(0, -1, 0);
        }
        return max;
    }
}
