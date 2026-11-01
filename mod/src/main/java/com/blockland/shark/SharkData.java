package com.blockland.shark;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import net.minecraft.world.Difficulty;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * The shark's tunables ({@code /data/blockland/shark/shark.json} on the classpath, the same on client and server; shark
 * spec §2), read once — attributes are created from it during mod construction.
 */
public final class SharkData {
    private static final String RESOURCE = "/data/blockland/shark/shark.json";
    private static SharkData instance;

    public float health = 30;
    public float armor = 2;
    public float speed = 1.3f;
    public Damage damage = new Damage();
    public float knockback = 0.9f;
    public int xp = 8;
    /** How far it notices a swimmer, and a bleeding one (below {@link #bloodBelowHealth} of their health, or just hurt). */
    public float aggroRange = 12, bloodRange = 28;
    public float bloodBelowHealth = 0.5f;
    /** Seconds it circles its prey before it charges [min, max]. */
    public float[] circleSeconds = {3, 6};
    public float circleRadius = 5.5f;
    /** Speed modifier of the charge. */
    public float chargeSpeed = 1.9f;
    public float biteCooldown = 1.6f;
    public float retreatSeconds = 2;
    /** Riders (boats, vehicles) are left alone: only those in the water are prey. */
    public boolean ignoreRiders = true;
    /** Now and then it hunts fish. */
    public boolean huntFish = true;
    public Spawn spawn = new Spawn();
    public Animation animation = new Animation();

    public static final class Damage {
        public float easy = 4, normal = 6, hard = 9;

        public float of(Difficulty difficulty) {
            return switch (difficulty) {
                case PEACEFUL, EASY -> easy;
                case NORMAL -> normal;
                case HARD -> hard;
            };
        }
    }

    public static final class Spawn {
        /** Water at least this deep under the spawn point. */
        public int minDepth = 6;
        /** Spawns between these many blocks below sea level [min, max]. */
        public int[] belowSeaLevel = {2, 30};
        /** At most this many sharks within {@link #nearbyRadius} blocks (natural spawns). */
        public int maxNearby = 2;
        public int nearbyRadius = 64;
    }

    /** Client: the body's swimming wave (model space metres). */
    public static final class Animation {
        /** Length of one wave along the body. */
        public float waveLength = 3.2f;
        /** Sideways sway of the tail at rest and how much more at full speed. */
        public float amplitude = 0.16f, speedAmplitude = 0.10f;
        /** Tail beats per block swum, and per second while hardly moving. */
        public float beatsPerBlock = 0.55f, idleBeatsPerSecond = 0.9f;
        /** How far the body bends into a turn (metres at the tail per radian turned per tick, before easing). */
        public float turnBend = 0.35f;
        /** Degrees the jaw drops in a bite. */
        public float jawOpen = 32;
    }

    public float circleTicksMin() {
        return Math.max(0, circleSeconds.length > 0 ? circleSeconds[0] : 3) * 20;
    }

    public float circleTicksMax() {
        return Math.max(circleTicksMin(), (circleSeconds.length > 1 ? circleSeconds[1] : 6) * 20);
    }

    public static synchronized SharkData get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static SharkData load() {
        try (InputStream in = SharkData.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                SharkData read = new Gson().fromJson(reader, SharkData.class);
                return read != null ? read : new SharkData();
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the shark's data", e);
            return new SharkData();
        }
    }
}
