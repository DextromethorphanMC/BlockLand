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
     * @param seen  per projectile (rounds × pellets, in the spread's numbering), the entity the client's own trace
     *              struck ({@link #NONE} for none): the server checks the shot against where that entity stood when the
     *              shooter saw it (lag compensation), never against a claim alone
     */
    public record C2SFire(int slot, float yaw, float pitch, float aim, long seed, int shots, int hand, int[] seen) {
        public static final int NONE = -1;
        /** At most this many projectiles report what they struck (a volley of the widest shotgun). */
        public static final int MAX_SEEN = 32;

        public C2SFire(int slot, float yaw, float pitch, float aim, long seed) {
            this(slot, yaw, pitch, aim, seed, 1, 0, new int[0]);
        }

        /** The entity projectile {@code index} struck on the client, or {@link #NONE}. */
        public int seenAt(int index) {
            return index >= 0 && index < seen.length ? seen[index] : NONE;
        }

        public static void encode(C2SFire msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
            buf.writeFloat(msg.yaw);
            buf.writeFloat(msg.pitch);
            buf.writeFloat(msg.aim);
            buf.writeLong(msg.seed);
            buf.writeByte(msg.shots);
            buf.writeByte(msg.hand);
            int count = Math.min(MAX_SEEN, msg.seen.length);
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) buf.writeVarInt(msg.seen[i] + 1);
        }

        public static C2SFire decode(FriendlyByteBuf buf) {
            int slot = buf.readVarInt();
            float yaw = buf.readFloat(), pitch = buf.readFloat(), aim = buf.readFloat();
            long seed = buf.readLong();
            int shots = buf.readByte(), hand = buf.readByte();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_SEEN) throw new IllegalArgumentException("too many projectiles: " + count);
            int[] seen = new int[count];
            for (int i = 0; i < count; i++) seen[i] = buf.readVarInt() - 1;
            return new C2SFire(slot, yaw, pitch, aim, seed, shots, hand, seen);
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

    /**
     * Hit confirmation for the shooter: the hit marker, and the round's blood — {@code wounded} when the target really lost
     * health ({@code HurtCheck}; armour can soak a round whole), struck at {@code x, y, z} (as the shooter saw it).
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

    /**
     * Someone else fired: muzzle flash, tracer and impact for players tracking the shooter.
     *
     * @param hitBlock   the projectile ended on a block (impact particles and a bullet hole at the end point)
     * @param face       the struck block face ({@code Direction} ordinal) when {@code hitBlock}; otherwise {@link #SKIPPED}
     *                   (it skipped off a water surface at the end point), {@link #SPENT} (water or lava spent it there) or
     *                   -1 (it struck an entity there, or flew its full range)
     * @param suppressed the gun wears a suppressor: no muzzle flash
     * @param hand       which gun of a dual pair fired (0 = right / the only one, 1 = left)
     * @param wounded    it struck an entity and really hurt it ({@code HurtCheck}): only then does a body bleed
     */
    public record S2CShot(int shooterId, double endX, double endY, double endZ, boolean hitBlock, int face, boolean suppressed,
                          int hand, boolean wounded) {
        /** {@link #face} of a round that skipped off water, or that a fluid spent, at the end point (no block, no entity). */
        public static final int SKIPPED = -2, SPENT = -3;
        /** {@link #face} of a launched round (a grenade, flying on as an entity): the shot alone, no tracer or impact. */
        public static final int LAUNCHED = -4;

        public S2CShot(int shooterId, double endX, double endY, double endZ, boolean hitBlock, int face, boolean suppressed) {
            this(shooterId, endX, endY, endZ, hitBlock, face, suppressed, 0, false);
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
            buf.writeBoolean(msg.wounded);
        }

        public static S2CShot decode(FriendlyByteBuf buf) {
            return new S2CShot(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readByte(),
                    buf.readBoolean(), buf.readByte(), buf.readBoolean());
        }
    }
}
