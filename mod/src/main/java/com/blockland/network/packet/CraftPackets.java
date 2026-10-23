package com.blockland.network.packet;

import com.blockland.craft.Build;
import net.minecraft.network.FriendlyByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工匠 (craft) messages. They work in every phase — the lobby runs without a world — so they only carry plain data:
 * weapon ids and builds. What a build does to an item is decided by the server.
 */
public final class CraftPackets {
    /** Most builds one player may keep (also caps what a client decodes). */
    public static final int MAX_BUILDS = 64;

    private CraftPackets() {
    }

    /** Asks for the player's saved builds. */
    public record C2SRequest() {
        public static final C2SRequest INSTANCE = new C2SRequest();

        public static void encode(C2SRequest msg, FriendlyByteBuf buf) {
        }

        public static C2SRequest decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }
    }

    /**
     * Saves the player's build of one weapon.
     *
     * @param fitSlot inventory slot of a weapon of this kind to fit the build to right away (in game), -1 = only save
     */
    public record C2SSave(String weaponId, Build build, int fitSlot) {
        public static void encode(C2SSave msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.weaponId, Build.MAX_ID);
            msg.build.write(buf);
            buf.writeVarInt(msg.fitSlot);
        }

        public static C2SSave decode(FriendlyByteBuf buf) {
            return new C2SSave(buf.readUtf(Build.MAX_ID), Build.read(buf), buf.readVarInt());
        }
    }

    /**
     * The player's saved builds, weapon id → build (weapons without an entry use their defaults).
     *
     * @param error translation key of why the last save was refused, "" when it was not
     */
    public record S2CBuilds(Map<String, Build> builds, String error) {
        public static void encode(S2CBuilds msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.builds.size());
            msg.builds.forEach((weapon, build) -> {
                buf.writeUtf(weapon, Build.MAX_ID);
                build.write(buf);
            });
            buf.writeUtf(msg.error, 128);
        }

        public static S2CBuilds decode(FriendlyByteBuf buf) {
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_BUILDS) throw new IllegalArgumentException("too many builds: " + size);
            Map<String, Build> builds = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) builds.put(buf.readUtf(Build.MAX_ID), Build.read(buf));
            return new S2CBuilds(builds, buf.readUtf(128));
        }
    }
}
