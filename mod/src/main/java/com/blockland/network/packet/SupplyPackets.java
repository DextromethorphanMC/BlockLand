package com.blockland.network.packet;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Resupply messages (asset spec §46): a use of an ammo crate with a gun in hand, and the server's answer. */
public final class SupplyPackets {
    private SupplyPackets() {
    }

    /**
     * The client used the ammo crate at {@code pos} with the gun in hotbar slot {@code slot} (a held gun keeps vanilla's
     * block interaction to itself, so the use comes here).
     */
    public record C2SUse(BlockPos pos, int slot) {
        public static void encode(C2SUse msg, FriendlyByteBuf buf) {
            buf.writeBlockPos(msg.pos);
            buf.writeVarInt(msg.slot);
        }

        public static C2SUse decode(FriendlyByteBuf buf) {
            return new C2SUse(buf.readBlockPos(), buf.readVarInt());
        }
    }

    /**
     * The server resupplied the gun in {@code slot} from the crate at {@code pos}: {@code rounds} went into the
     * inventory; {@code reload} = the gun's magazine is not full (the first-person resupply ends in its reload).
     */
    public record S2CResupplied(BlockPos pos, int slot, int rounds, boolean reload) {
        public static void encode(S2CResupplied msg, FriendlyByteBuf buf) {
            buf.writeBlockPos(msg.pos);
            buf.writeVarInt(msg.slot);
            buf.writeVarInt(msg.rounds);
            buf.writeBoolean(msg.reload);
        }

        public static S2CResupplied decode(FriendlyByteBuf buf) {
            return new S2CResupplied(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }
    }
}
