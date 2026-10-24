package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wall graffiti (喷漆) messages. The client only asks to spray a slot; the server decides whether and where, and tells
 * everyone in that world what is up where ({@link Placement}).
 */
public final class GraffitiPackets {
    private GraffitiPackets() {
    }

    /** The player sprays the graffiti in slot {@code slot} (1-based). */
    public record C2SSpray(int slot) {
        public static void encode(C2SSpray msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
        }

        public static C2SSpray decode(FriendlyByteBuf buf) {
            return new C2SSpray(buf.readVarInt());
        }
    }

    /**
     * One piece of graffiti in the world.
     *
     * @param x,y,z     its centre: on the surface (normal pieces), or in the air (floating ones)
     * @param nx,ny,nz  the surface's outward normal (axis-aligned); zero for floating pieces
     * @param ux,uy,uz  "up" across the surface (the picture's top); floating pieces: unused
     * @param placedAt  level game time it was sprayed (the client plays the spraying from it)
     */
    public record Placement(int id, String graffiti, UUID owner, double x, double y, double z, float nx, float ny, float nz,
                            float ux, float uy, float uz, float size, long placedAt, boolean floating) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeUtf(graffiti, 64);
            buf.writeUUID(owner);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(nx);
            buf.writeFloat(ny);
            buf.writeFloat(nz);
            buf.writeFloat(ux);
            buf.writeFloat(uy);
            buf.writeFloat(uz);
            buf.writeFloat(size);
            buf.writeLong(placedAt);
            buf.writeBoolean(floating);
        }

        static Placement decode(FriendlyByteBuf buf) {
            return new Placement(buf.readVarInt(), buf.readUtf(64), buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readLong(), buf.readBoolean());
        }
    }

    /** A new piece went up. */
    public record S2CPlace(Placement placement) {
        public static void encode(S2CPlace msg, FriendlyByteBuf buf) {
            msg.placement.encode(buf);
        }

        public static S2CPlace decode(FriendlyByteBuf buf) {
            return new S2CPlace(Placement.decode(buf));
        }
    }

    /** Pieces that are gone (replaced by newer ones, the arena cleared). */
    public record S2CRemove(List<Integer> ids) {
        public static void encode(S2CRemove msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.ids.size());
            for (int id : msg.ids) buf.writeVarInt(id);
        }

        public static S2CRemove decode(FriendlyByteBuf buf) {
            int n = Math.min(4096, buf.readVarInt());
            List<Integer> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) ids.add(buf.readVarInt());
            return new S2CRemove(ids);
        }
    }

    /** Everything up in the player's world right now (on arriving in it); replaces what the client had. */
    public record S2CSync(List<Placement> placements) {
        public static void encode(S2CSync msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.placements.size());
            for (Placement p : msg.placements) p.encode(buf);
        }

        public static S2CSync decode(FriendlyByteBuf buf) {
            int n = Math.min(1024, buf.readVarInt());
            List<Placement> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(Placement.decode(buf));
            return new S2CSync(list);
        }
    }

    /** The graffiti in the player's four slots (ids, "" = empty), for the selection wheel. */
    public record S2CSlots(List<String> graffiti) {
        public static void encode(S2CSlots msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.graffiti.size());
            for (String id : msg.graffiti) buf.writeUtf(id, 64);
        }

        public static S2CSlots decode(FriendlyByteBuf buf) {
            int n = Math.min(8, buf.readVarInt());
            List<String> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) ids.add(buf.readUtf(64));
            return new S2CSlots(ids);
        }
    }
}
