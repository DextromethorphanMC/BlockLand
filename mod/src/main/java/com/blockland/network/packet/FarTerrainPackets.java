package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Far terrain messages (rendering spec §22), play phase only: the server's offer in the level a player just entered, the
 * client's tile requests, and the server's answers. Tiles travel as {@code FarTile.encode} bytes. A {@code session}
 * numbers one stay in one level; anything of another session is ignored on both sides.
 */
public final class FarTerrainPackets {
    /** Most tiles in one request. */
    public static final int MAX_KEYS = 64;
    /** Largest encoded tile accepted (a full tile deflates to far less). */
    public static final int MAX_TILE_BYTES = 96 * 1024;
    /** Most answers in one {@link S2CTiles}. */
    public static final int MAX_ANSWERS = 256;

    /** Answer kinds. */
    public static final byte DATA = 0, UNCHANGED = 1, EMPTY = 2, DENIED = 3;

    private FarTerrainPackets() {
    }

    /**
     * What the server offers in the level the player just entered: whether far terrain is available there, how far it
     * reaches (blocks), an id for the client's disk cache (the world and the player's view limit), the dimension and its
     * height range.
     */
    public record S2CHello(int session, boolean enabled, int maxDistance, long worldId, String dimension, int minY, int maxY) {
        public static void encode(S2CHello msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.session);
            buf.writeBoolean(msg.enabled);
            buf.writeVarInt(msg.maxDistance);
            buf.writeLong(msg.worldId);
            buf.writeUtf(msg.dimension, 256);
            buf.writeVarInt(msg.minY);
            buf.writeVarInt(msg.maxY);
        }

        public static S2CHello decode(FriendlyByteBuf buf) {
            return new S2CHello(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readLong(), buf.readUtf(256),
                    buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * Tiles the client wants ({@code FarTile.key}), each with the version it holds (0 = none). No keys = forget everything
     * this player asked for and has not been answered yet.
     */
    public record C2SRequest(int session, long[] keys, long[] versions) {
        public static void encode(C2SRequest msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.session);
            int n = Math.min(MAX_KEYS, msg.keys.length);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                buf.writeLong(msg.keys[i]);
                buf.writeLong(msg.versions[i]);
            }
        }

        public static C2SRequest decode(FriendlyByteBuf buf) {
            int session = buf.readVarInt();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_KEYS) throw new IllegalArgumentException("far terrain request of " + n + " tiles");
            long[] keys = new long[n], versions = new long[n];
            for (int i = 0; i < n; i++) {
                keys[i] = buf.readLong();
                versions[i] = buf.readLong();
            }
            return new C2SRequest(session, keys, versions);
        }
    }

    /** One answer: its kind ({@link #DATA}, {@link #UNCHANGED}, {@link #EMPTY}, {@link #DENIED}), the version, the tile. */
    public record Answer(long key, byte kind, long version, byte[] data) {
        public static Answer data(long key, long version, byte[] data) {
            return new Answer(key, DATA, version, data);
        }

        public static Answer of(long key, byte kind, long version) {
            return new Answer(key, kind, version, new byte[0]);
        }
    }

    /** Answers to earlier requests, in no particular order. */
    public record S2CTiles(int session, List<Answer> answers) {
        public static void encode(S2CTiles msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.session);
            int n = Math.min(MAX_ANSWERS, msg.answers.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Answer a = msg.answers.get(i);
                buf.writeLong(a.key);
                buf.writeByte(a.kind);
                buf.writeLong(a.version);
                buf.writeByteArray(a.kind == DATA ? a.data : new byte[0]);
            }
        }

        public static S2CTiles decode(FriendlyByteBuf buf) {
            int session = buf.readVarInt();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_ANSWERS) throw new IllegalArgumentException("far terrain answer of " + n + " tiles");
            List<Answer> answers = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long key = buf.readLong();
                byte kind = buf.readByte();
                long version = buf.readLong();
                byte[] data = buf.readByteArray(MAX_TILE_BYTES);
                answers.add(new Answer(key, kind, version, data));
            }
            return new S2CTiles(session, answers);
        }

        /** Bytes this answer adds to a packet (for packing them under a size). */
        public static int size(Answer answer) {
            return 8 + 1 + 8 + 5 + (answer.kind == DATA ? answer.data.length : 0);
        }
    }
}
