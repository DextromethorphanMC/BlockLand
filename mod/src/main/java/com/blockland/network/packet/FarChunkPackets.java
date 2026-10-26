package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Real far chunks (rendering spec §23), play phase only: the client asks for chunk columns beyond the server's view
 * distance ({@code FarColumn.key}, with the version it holds), the server answers with {@code FarColumn.encode} bytes.
 * They share the far terrain's session ({@code FarTerrainPackets.S2CHello}); the answer kinds are {@link FarTerrainPackets}'.
 */
public final class FarChunkPackets {
    /** Most columns in one request. */
    public static final int MAX_KEYS = 128;
    /** Largest encoded column accepted. */
    public static final int MAX_COLUMN_BYTES = 512 * 1024;
    /** Most answers in one {@link S2CColumns}. */
    public static final int MAX_ANSWERS = 256;

    private FarChunkPackets() {
    }

    /** Columns wanted, each with the version held (0 = none); no keys = forget everything asked and not answered yet. */
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
            if (n < 0 || n > MAX_KEYS) throw new IllegalArgumentException("far chunk request of " + n + " columns");
            long[] keys = new long[n], versions = new long[n];
            for (int i = 0; i < n; i++) {
                keys[i] = buf.readLong();
                versions[i] = buf.readLong();
            }
            return new C2SRequest(session, keys, versions);
        }
    }

    /** Answers ({@code FarTerrainPackets.Answer}: DATA with the column, UNCHANGED, EMPTY = nothing generated there, DENIED). */
    public record S2CColumns(int session, List<FarTerrainPackets.Answer> answers) {
        public static void encode(S2CColumns msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.session);
            int n = Math.min(MAX_ANSWERS, msg.answers.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                FarTerrainPackets.Answer a = msg.answers.get(i);
                buf.writeLong(a.key());
                buf.writeByte(a.kind());
                buf.writeLong(a.version());
                buf.writeByteArray(a.kind() == FarTerrainPackets.DATA ? a.data() : new byte[0]);
            }
        }

        public static S2CColumns decode(FriendlyByteBuf buf) {
            int session = buf.readVarInt();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_ANSWERS) throw new IllegalArgumentException("far chunk answer of " + n + " columns");
            List<FarTerrainPackets.Answer> answers = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long key = buf.readLong();
                byte kind = buf.readByte();
                long version = buf.readLong();
                answers.add(new FarTerrainPackets.Answer(key, kind, version, buf.readByteArray(MAX_COLUMN_BYTES)));
            }
            return new S2CColumns(session, answers);
        }

        public static int size(FarTerrainPackets.Answer answer) {
            return 8 + 1 + 8 + 5 + (answer.kind() == FarTerrainPackets.DATA ? answer.data().length : 0);
        }
    }
}
