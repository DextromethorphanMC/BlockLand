package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Knife messages: the attack request, the attacker's hit confirmation and the strike others see. */
public final class MeleePackets {
    private MeleePackets() {
    }

    /**
     * The client started an attack with the knife in its selected slot.
     *
     * @param heavy right-click attack (else the light left-click attack)
     * @param combo which swing of the attack's combo plays (cosmetic: cut direction)
     */
    public record C2SAttack(int slot, boolean heavy, int combo) {
        public static void encode(C2SAttack msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
            buf.writeBoolean(msg.heavy);
            buf.writeVarInt(msg.combo);
        }

        public static C2SAttack decode(FriendlyByteBuf buf) {
            return new C2SAttack(buf.readVarInt(), buf.readBoolean(), buf.readVarInt());
        }
    }

    public enum HitKind { BODY, BACKSTAB, KILL, BACKSTAB_KILL }

    /**
     * Hit confirmation for the attacker: the hit marker, and the cut's blood — {@code wounded} when the target really lost
     * health ({@code HurtCheck}), struck at {@code x, y, z}.
     */
    public record S2CHit(HitKind kind, float damage, int target, boolean wounded, double x, double y, double z) {
        public static void encode(S2CHit msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.kind);
            buf.writeFloat(msg.damage);
            buf.writeVarInt(msg.target);
            buf.writeBoolean(msg.wounded);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
        }

        public static S2CHit decode(FriendlyByteBuf buf) {
            return new S2CHit(buf.readEnum(HitKind.class), buf.readFloat(), buf.readVarInt(), buf.readBoolean(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble());
        }
    }

    /** What the blade struck. */
    public enum Target { BLOCK, ENTITY }

    /**
     * Someone else's knife connected: cut decal and impact sound for players tracking the attacker.
     *
     * @param targetId struck entity (when {@code target} is ENTITY)
     * @param face     struck block face ({@code Direction} ordinal) when {@code target} is BLOCK
     * @param wounded  the struck entity really lost health ({@code HurtCheck}): only then does a body bleed
     */
    public record S2CImpact(int attackerId, boolean heavy, int combo, Target target, int targetId,
                            double x, double y, double z, int face, boolean wounded) {
        public static void encode(S2CImpact msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.attackerId);
            buf.writeBoolean(msg.heavy);
            buf.writeVarInt(msg.combo);
            buf.writeEnum(msg.target);
            buf.writeVarInt(msg.targetId);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeByte(msg.face);
            buf.writeBoolean(msg.wounded);
        }

        public static S2CImpact decode(FriendlyByteBuf buf) {
            return new S2CImpact(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readEnum(Target.class), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readByte(), buf.readBoolean());
        }
    }
}
