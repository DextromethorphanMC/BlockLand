package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Gun messages. Ammo and the build travel in the item component; these are the actions and their effects. */
public final class GunPackets {
    private GunPackets() {
    }

    /**
     * The client fired the gun in its selected slot.
     *
     * @param slot  hotbar slot the client fired from (must still be selected on the server)
     * @param yaw   view rotation when the trigger was pulled
     * @param aim   0 = hip, 1 = fully aimed down the sight
     * @param seed  spread seed; the server uses it so client tracers match server hits
     * @param shots rounds fired at once: 1, or 2 for a dual gun's volley (asset spec §26)
     * @param hand  which gun of a dual pair fired a single shot (0 = right, 1 = left; 0 for every other gun)
     */
    public record C2SFire(int slot, float yaw, float pitch, float aim, long seed, int shots, int hand) {
        public C2SFire(int slot, float yaw, float pitch, float aim, long seed) {
            this(slot, yaw, pitch, aim, seed, 1, 0);
        }

        public static void encode(C2SFire msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
            buf.writeFloat(msg.yaw);
            buf.writeFloat(msg.pitch);
            buf.writeFloat(msg.aim);
            buf.writeLong(msg.seed);
            buf.writeByte(msg.shots);
            buf.writeByte(msg.hand);
        }

        public static C2SFire decode(FriendlyByteBuf buf) {
            return new C2SFire(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readLong(), buf.readByte(),
                    buf.readByte());
        }
    }

    /** Start reloading the gun in the selected slot. */
    public record C2SReload(int slot) {
        public static void encode(C2SReload msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
        }

        public static C2SReload decode(FriendlyByteBuf buf) {
            return new C2SReload(buf.readVarInt());
        }
    }

    /**
     * The player raised ({@code aiming}) or lowered the sights of the gun in {@code slot}. The server times it, so a
     * shot only gets the aimed spread once the sights could really be up (the gun's ADS time), and tells the players
     * around (a raised magnified scope glints).
     */
    public record C2SAim(int slot, boolean aiming) {
        public static void encode(C2SAim msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
            buf.writeBoolean(msg.aiming);
        }

        public static C2SAim decode(FriendlyByteBuf buf) {
            return new C2SAim(buf.readVarInt(), buf.readBoolean());
        }
    }

    /** A player another client can see raised or lowered their sights (the receiver decides what a scope shows). */
    public record S2CAim(int playerId, boolean aiming) {
        public static void encode(S2CAim msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.playerId);
            buf.writeBoolean(msg.aiming);
        }

        public static S2CAim decode(FriendlyByteBuf buf) {
            return new S2CAim(buf.readVarInt(), buf.readBoolean());
        }
    }

    public enum HitKind { BODY, HEAD, KILL, HEAD_KILL }

    /** Hit confirmation for the shooter's hit marker. */
    public record S2CHit(HitKind kind, float damage) {
        public static void encode(S2CHit msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.kind);
            buf.writeFloat(msg.damage);
        }

        public static S2CHit decode(FriendlyByteBuf buf) {
            return new S2CHit(buf.readEnum(HitKind.class), buf.readFloat());
        }
    }

    /**
     * Someone else fired: muzzle flash, tracer and impact for players tracking the shooter.
     *
     * @param hitBlock   the projectile ended on a block (impact particles and a bullet hole at the end point)
     * @param face       the struck block face ({@code Direction} ordinal) when {@code hitBlock}
     * @param suppressed the gun wears a suppressor: no muzzle flash
     * @param hand       which gun of a dual pair fired (0 = right / the only one, 1 = left)
     */
    public record S2CShot(int shooterId, double endX, double endY, double endZ, boolean hitBlock, int face, boolean suppressed,
                          int hand) {
        public S2CShot(int shooterId, double endX, double endY, double endZ, boolean hitBlock, int face, boolean suppressed) {
            this(shooterId, endX, endY, endZ, hitBlock, face, suppressed, 0);
        }

        public static void encode(S2CShot msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.shooterId);
            buf.writeDouble(msg.endX);
            buf.writeDouble(msg.endY);
            buf.writeDouble(msg.endZ);
            buf.writeBoolean(msg.hitBlock);
            buf.writeByte(msg.face);
            buf.writeBoolean(msg.suppressed);
            buf.writeByte(msg.hand);
        }

        public static S2CShot decode(FriendlyByteBuf buf) {
            return new S2CShot(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readByte(),
                    buf.readBoolean(), buf.readByte());
        }
    }
}
