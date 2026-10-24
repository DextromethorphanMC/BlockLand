package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 大逃杀 messages. The client only asks to jump out of the plane; the server answers with the match as the player sees
 * it ({@link S2CState}): the phase, the map's bounds for the intro and the minimap, the zone and who is still alive.
 */
public final class BattleRoyalePackets {
    private BattleRoyalePackets() {
    }

    /** The jump key was pressed while riding the plane. */
    public record C2SJump() {
        public static final C2SJump INSTANCE = new C2SJump();

        public static void encode(C2SJump msg, FriendlyByteBuf buf) {
        }

        public static C2SJump decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }
    }

    /** NONE = not in a 大逃杀 match (the HUD and the camera go back to normal). */
    public enum Phase { NONE, WAITING, INTRO, FLIGHT, FIGHT, ENDED }

    /**
     * A circle of the zone: centre and radius in world blocks.
     */
    public record Circle(double x, double z, double radius) {
        public Circle lerp(Circle to, double t) {
            return new Circle(x + (to.x - x) * t, z + (to.z - z) * t, radius + (to.radius - radius) * t);
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeDouble(x);
            buf.writeDouble(z);
            buf.writeDouble(radius);
        }

        static Circle decode(FriendlyByteBuf buf) {
            return new Circle(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
    }

    /**
     * The match as this player sees it (on every change and once a second).
     *
     * @param phaseTicks  ticks left of the countdown / intro (0 otherwise)
     * @param minX,minZ,maxX,maxZ,topY the map's box in the world (intro camera, minimap)
     * @param aboard      the player is still in the plane
     * @param zoneFrom    the zone before the current shrink, {@code zoneTo} after it; it shrinks between
     *                    {@code shrinkStartIn} and {@code shrinkEndIn} ticks from now (both ≤ 0 once done)
     */
    public record S2CState(Phase phase, int phaseTicks, String mapName, double minX, double minZ, double maxX, double maxZ, double topY,
                           boolean aboard, int alive, int total, int kills, boolean zone, Circle zoneFrom, Circle zoneTo,
                           int shrinkStartIn, int shrinkEndIn) {
        public static final S2CState NONE = new S2CState(Phase.NONE, 0, "", 0, 0, 0, 0, 0, false, 0, 0, 0, false,
                new Circle(0, 0, 0), new Circle(0, 0, 0), 0, 0);

        /** The zone {@code elapsed} ticks after this state arrived. */
        public Circle zoneAt(float elapsed) {
            float start = shrinkStartIn - elapsed, end = shrinkEndIn - elapsed;
            if (start >= 0) return zoneFrom;
            if (end <= 0) return zoneTo;
            float length = Math.max(1, shrinkEndIn - shrinkStartIn);
            return zoneFrom.lerp(zoneTo, (-start) / length);
        }

        public static void encode(S2CState msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.phase);
            buf.writeVarInt(msg.phaseTicks);
            buf.writeUtf(msg.mapName, 128);
            buf.writeDouble(msg.minX);
            buf.writeDouble(msg.minZ);
            buf.writeDouble(msg.maxX);
            buf.writeDouble(msg.maxZ);
            buf.writeDouble(msg.topY);
            buf.writeBoolean(msg.aboard);
            buf.writeVarInt(msg.alive);
            buf.writeVarInt(msg.total);
            buf.writeVarInt(msg.kills);
            buf.writeBoolean(msg.zone);
            msg.zoneFrom.encode(buf);
            msg.zoneTo.encode(buf);
            buf.writeInt(msg.shrinkStartIn);
            buf.writeInt(msg.shrinkEndIn);
        }

        public static S2CState decode(FriendlyByteBuf buf) {
            return new S2CState(buf.readEnum(Phase.class), buf.readVarInt(), buf.readUtf(128), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean(), Circle.decode(buf), Circle.decode(buf), buf.readInt(), buf.readInt());
        }
    }
}
