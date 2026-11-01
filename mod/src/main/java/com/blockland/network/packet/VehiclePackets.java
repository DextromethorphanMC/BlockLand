package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Vehicle messages (vehicle spec §4): the driver's input, riders' actions, the driver's reconciliation, gun shots. */
public final class VehiclePackets {
    private VehiclePackets() {
    }

    /** The driver's input for one tick (throttle and steer as -100..100; the view an aircraft flies towards). */
    public record C2SControl(int vehicle, int seq, byte throttle, byte steer, boolean brake, boolean boost, float lookYaw, float lookPitch) {
        public static void encode(C2SControl msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.vehicle);
            buf.writeVarInt(msg.seq);
            buf.writeByte(msg.throttle);
            buf.writeByte(msg.steer);
            buf.writeByte((msg.brake ? 1 : 0) | (msg.boost ? 2 : 0));
            buf.writeFloat(msg.lookYaw);
            buf.writeFloat(msg.lookPitch);
        }

        public static C2SControl decode(FriendlyByteBuf buf) {
            int vehicle = buf.readVarInt(), seq = buf.readVarInt();
            byte throttle = buf.readByte(), steer = buf.readByte();
            int flags = buf.readByte();
            return new C2SControl(vehicle, seq, throttle, steer, (flags & 1) != 0, (flags & 2) != 0, buf.readFloat(), buf.readFloat());
        }
    }

    /** What a rider does besides driving. */
    public enum Action { FIRE, RELOAD, SEAT, HORN }

    /** @param arg the weapon for FIRE / RELOAD, the seat for SEAT */
    public record C2SAction(int vehicle, Action action, int arg) {
        public static void encode(C2SAction msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.vehicle);
            buf.writeEnum(msg.action);
            buf.writeVarInt(msg.arg);
        }

        public static C2SAction decode(FriendlyByteBuf buf) {
            return new C2SAction(buf.readVarInt(), buf.readEnum(Action.class), buf.readVarInt());
        }
    }

    /** To the driver: the vehicle's state after its input {@code seq} (prediction is matched to it). */
    public record S2CState(int vehicle, int seq, double x, double y, double z, float yaw, float vx, float vy, float vz,
                           float pitch, float roll) {
        public static void encode(S2CState msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.vehicle);
            buf.writeVarInt(msg.seq);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeFloat(msg.yaw);
            buf.writeFloat(msg.vx);
            buf.writeFloat(msg.vy);
            buf.writeFloat(msg.vz);
            buf.writeFloat(msg.pitch);
            buf.writeFloat(msg.roll);
        }

        public static S2CState decode(FriendlyByteBuf buf) {
            return new S2CState(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    /**
     * A vehicle gun's round, as the server traced it, to everyone tracking the vehicle: tracer and impact.
     *
     * @param face    the struck block face ({@code Direction} ordinal), -1 when it struck an entity or nothing
     * @param target  the struck entity, -1 for none
     * @param wounded the struck entity really lost health ({@code HurtCheck}): only then does a body bleed
     */
    public record S2CShot(int vehicle, int weapon, double x, double y, double z, int face, int target, boolean wounded) {
        public static void encode(S2CShot msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.vehicle);
            buf.writeVarInt(msg.weapon);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeByte(msg.face);
            buf.writeVarInt(msg.target + 1);
            buf.writeBoolean(msg.wounded);
        }

        public static S2CShot decode(FriendlyByteBuf buf) {
            return new S2CShot(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readByte(),
                    buf.readVarInt() - 1, buf.readBoolean());
        }
    }
}
