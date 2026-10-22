package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Gun messages. Ammo and attachments travel in the item component; these are the actions and their effects. */
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
     */
    public record C2SFire(int slot, float yaw, float pitch, float aim, long seed) {
        public static void encode(C2SFire msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
            buf.writeFloat(msg.yaw);
            buf.writeFloat(msg.pitch);
            buf.writeFloat(msg.aim);
            buf.writeLong(msg.seed);
        }

        public static C2SFire decode(FriendlyByteBuf buf) {
            return new C2SFire(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readLong());
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

    /** Gunsmith: fit these attachments and skin to the gun in {@code slot} (any inventory slot). */
    public record C2SGunsmith(int slot, String skin, String magazine, String sight, String laser) {
        public static void encode(C2SGunsmith msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
            buf.writeUtf(msg.skin, 64);
            buf.writeUtf(msg.magazine, 64);
            buf.writeUtf(msg.sight, 64);
            buf.writeUtf(msg.laser, 64);
        }

        public static C2SGunsmith decode(FriendlyByteBuf buf) {
            return new C2SGunsmith(buf.readVarInt(), buf.readUtf(64), buf.readUtf(64), buf.readUtf(64), buf.readUtf(64));
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
     * @param hitBlock the projectile ended on a block (impact particles and a bullet hole at the end point)
     * @param face     the struck block face ({@code Direction} ordinal) when {@code hitBlock}
     */
    public record S2CShot(int shooterId, double endX, double endY, double endZ, boolean hitBlock, int face) {
        public static void encode(S2CShot msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.shooterId);
            buf.writeDouble(msg.endX);
            buf.writeDouble(msg.endY);
            buf.writeDouble(msg.endZ);
            buf.writeBoolean(msg.hitBlock);
            buf.writeByte(msg.face);
        }

        public static S2CShot decode(FriendlyByteBuf buf) {
            return new S2CShot(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readByte());
        }
    }
}
