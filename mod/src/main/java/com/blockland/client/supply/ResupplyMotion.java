package com.blockland.client.supply;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The first-person resupply's motion as written in {@code assets/blockland/supply/resupply.json} (read by Gson; field
 * names = JSON keys; asset spec §46): keyed poses of the gun (lowered and canted) and of the left arm (swung down into
 * the crate and back up beside the gun with the magazine), when the magazine shows in the hand, and the sounds on the
 * way. Two timelines: {@code reach} (the gun's reload follows) and {@code stow} (the gun was full: the magazine goes
 * into a pouch). Resource packs can override it; reloaded with F3+T. tools/assets/ammo_crate_preview.py renders it
 * from the same file.
 */
final class ResupplyMotion {
    private static final ResourceLocation FILE = BlockLand.id("supply/resupply.json");
    @Nullable
    private static ResupplyMotion loaded;

    float reach = 0.95f, stow = 1.45f;
    float[] pivot = {0.12f, -0.16f, -0.30f};
    Part gun = new Part();
    Arm arm = new Arm();
    Magazine magazine = new Magazine();
    Look look = new Look();
    Hand hand = new Hand();
    /** [seconds, sound event id] pairs, played once each. */
    List<List<Object>> events = List.of();

    /**
     * A moved part's keyed poses per timeline: {@code [seconds, pitch, yaw, roll, x, y, z]} (degrees, metres), eased
     * from key to key.
     */
    static final class Part {
        float[][] reach = {{0, 0, 0, 0, 0, 0, 0}}, stow = {{0, 0, 0, 0, 0, 0, 0}};

        /** The pose at t into {@code into} (pitch, yaw, roll, x, y, z); false when it is the rest pose. */
        boolean pose(boolean reload, float t, float[] into) {
            float[][] keys = reload ? reach : stow;
            boolean moved = false;
            for (int c = 0; c < 6; c++) {
                into[c] = ResupplyMotion.eased(keys, t, c + 1);
                moved |= into[c] != 0;
            }
            return moved;
        }
    }

    /**
     * The left arm, moved rigidly about its shoulder: {@code down} swings it into the crate ([pitch, yaw, roll, x, y,
     * z]); {@code up} turns it so the wrist comes to this offset from the gun's magazine node (the new magazine held
     * under the well); keys {@code [seconds, down, up]} blend rest -> down -> up.
     */
    static final class Arm {
        float[] down = new float[6];
        float[] up = new float[3];
        float[][] reach = {{0, 0, 0}}, stow = {{0, 0, 0}};

        /** The down and up weights at t into {@code into}; false when the arm is at rest. */
        boolean weights(boolean reload, float t, float[] into) {
            float[][] keys = reload ? reach : stow;
            into[0] = ResupplyMotion.eased(keys, t, 1);
            into[1] = ResupplyMotion.eased(keys, t, 2);
            return into[0] > 0 || into[1] > 0;
        }
    }

    /** The magazine in the left hand: where in the hand's frame, and from when to when it shows. */
    static final class Magazine {
        float[] offset = new float[3];
        float[] rotation = new float[3];
        float[] reach = {0, 0}, stow = {0, 0};

        boolean shows(boolean reload, float t) {
            float[] span = reload ? reach : stow;
            return span.length >= 2 && t >= span[0] && t < span[1];
        }
    }

    /**
     * The head: it turns to look into the crate's open top from where it leans over it — the lower the crate stands
     * under the eye, the more it bends over and down — and the hand's rummaging and the grab shake the view.
     */
    static final class Look {
        /** Keys {@code [seconds, weight]} per timeline: how far the head has turned and leaned towards the crate. */
        float[][] reach = {{0, 0}}, stow = {{0, 0}};
        /** Share of the angle to the crate's inside the head turns (the eyes do the rest), and the most it turns. */
        float share = 0.85f, maxPitch = 55, maxYaw = 30;
        /** The eyes aim this far below the crate's top, at its middle (into it). */
        float inside = 0.12f;
        /**
         * The lean towards the crate [forward, down] in metres: {@code lean} with the crate's top level with the eye, plus
         * {@code leanPerMetre} per metre it lies below the eye, at most {@code leanMax}.
         */
        float[] lean = {0.05f, 0}, leanPerMetre = {0.06f, 0.22f}, leanMax = {0.18f, 0.32f};
        /** Share of the head's turn the lowered gun holds still against (the hands hold it at the chest, not the eyes). */
        float gunStays = 0.4f;
        /** The hand rummaging in the crate: {@code [from, to, degrees, hertz]}. */
        float[] rummage = {0, 0, 0, 0};
        /** Jolts {@code [seconds, pitch, yaw, roll]} (the hand hitting the contents, the grab), ringing out. */
        float[][] jolts = {};
        float joltHz = 7, joltDecay = 7;

        float weight(boolean reload, float t) {
            return ResupplyMotion.eased(reload ? reach : stow, t, 1);
        }
    }

    /** The left hand in the crate: this far below its top and towards the player from its middle, at most reachMax from the shoulder. */
    static final class Hand {
        float inside = 0.08f, toward = 0.08f, reachMax = 0.66f;
    }

    float duration(boolean reload) {
        return reload ? reach : stow;
    }

    /** Component {@code c} of the keys at t: piecewise between the keys, each segment eased in and out. */
    static float eased(float[][] keys, float t, int c) {
        if (keys.length == 0 || keys[0].length <= c) return 0;
        if (t <= keys[0][0]) return keys[0][c];
        for (int i = 1; i < keys.length; i++) {
            if (t < keys[i][0]) {
                float u = (t - keys[i - 1][0]) / Math.max(1e-4f, keys[i][0] - keys[i - 1][0]);
                u = u * u * (3 - 2 * u);
                return keys[i - 1][c] + (keys[i][c] - keys[i - 1][c]) * u;
            }
        }
        return keys[keys.length - 1][c];
    }

    static ResupplyMotion get() {
        if (loaded == null) loaded = load();
        return loaded;
    }

    /** A resource reload: read the file again when next needed. */
    static void reset() {
        loaded = null;
    }

    private static ResupplyMotion load() {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing {}", FILE);
            return new ResupplyMotion();
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            ResupplyMotion motion = new Gson().fromJson(reader, ResupplyMotion.class);
            return motion == null ? new ResupplyMotion() : motion;
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot read {}", FILE, e);
            return new ResupplyMotion();
        }
    }
}
