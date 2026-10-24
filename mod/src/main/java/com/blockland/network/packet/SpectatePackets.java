package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Spectating after death (观察者画面). The client only asks to switch (next / previous player, free camera); the server
 * decides whom it may watch, keeps the free camera inside the match and tells it what to show ({@link S2CState}).
 * First or third person is the client's own choice (a camera around the watched player) and never travels.
 */
public final class SpectatePackets {
    private SpectatePackets() {
    }

    public enum Control { NEXT, PREVIOUS, FREE }

    /** The spectator pressed a control. */
    public record C2SControl(Control action) {
        public static void encode(C2SControl msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.action);
        }

        public static C2SControl decode(FriendlyByteBuf buf) {
            return new C2SControl(buf.readEnum(Control.class));
        }
    }

    /** NONE = not spectating (the HUD goes away), TARGET = watching a player, FREE = the free camera. */
    public enum Mode { NONE, TARGET, FREE }

    /**
     * What the spectator sees (sent on every change and once a second).
     *
     * @param targetId   entity id of the watched player (-1 in the free camera)
     * @param cardId     the watched player's name card ("" = default)
     * @param tier       their RankTier id, {@code position} their place on the ladder (0 = unplaced)
     * @param playtime   their total time played on the server, seconds
     * @param killer     who killed the spectator ("" = nobody / unknown)
     * @param ticksLeft  until the respawn (or the return to the lobby), -1 = no limit; {@code totalTicks} the whole wait
     * @param respawn    the wait ends in a respawn (otherwise in the return to the lobby)
     * @param canFree    the free camera is allowed
     * @param targets    how many players can be watched
     */
    public record S2CState(Mode mode, int targetId, String name, String cardId, String tier, int score, int position,
                           long playtime, float health, float maxHealth, String killer, int ticksLeft, int totalTicks,
                           boolean respawn, boolean canFree, int targets) {
        public static final S2CState NONE = new S2CState(Mode.NONE, -1, "", "", "", 0, 0, 0, 0, 0, "", -1, 1, false, false, 0);

        public static void encode(S2CState msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.mode);
            buf.writeVarInt(msg.targetId + 1);
            buf.writeUtf(msg.name, 64);
            buf.writeUtf(msg.cardId, 64);
            buf.writeUtf(msg.tier, 32);
            buf.writeVarInt(msg.score);
            buf.writeVarInt(msg.position);
            buf.writeVarLong(msg.playtime);
            buf.writeFloat(msg.health);
            buf.writeFloat(msg.maxHealth);
            buf.writeUtf(msg.killer, 64);
            buf.writeVarInt(msg.ticksLeft + 1);
            buf.writeVarInt(msg.totalTicks);
            buf.writeBoolean(msg.respawn);
            buf.writeBoolean(msg.canFree);
            buf.writeVarInt(msg.targets);
        }

        public static S2CState decode(FriendlyByteBuf buf) {
            return new S2CState(buf.readEnum(Mode.class), buf.readVarInt() - 1, buf.readUtf(64), buf.readUtf(64), buf.readUtf(32),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarLong(), buf.readFloat(), buf.readFloat(), buf.readUtf(64),
                    buf.readVarInt() - 1, Math.max(1, buf.readVarInt()), buf.readBoolean(), buf.readBoolean(), buf.readVarInt());
        }
    }
}
