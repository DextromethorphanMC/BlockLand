package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 枪战爆破 messages. The client only reports which button it holds; the server decides whether that plants or
 * defuses, times it and sends each player the match as they may see it ({@link S2CState}).
 */
public final class BombPackets {
    private BombPackets() {
    }

    /** The attack button went down ({@code holding}) or came up while the bomb is in hand. */
    public record C2SPlant(boolean holding) {
        public static void encode(C2SPlant msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.holding);
        }

        public static C2SPlant decode(FriendlyByteBuf buf) {
            return new C2SPlant(buf.readBoolean());
        }
    }

    /** The defuse key went down ({@code holding}) or came up. */
    public record C2SDefuse(boolean holding) {
        public static void encode(C2SDefuse msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.holding);
        }

        public static C2SDefuse decode(FriendlyByteBuf buf) {
            return new C2SDefuse(buf.readBoolean());
        }
    }

    public enum Side { NONE, ATTACKER, DEFENDER }

    /** Where the match stands: before the start, the bomb not yet down, ticking, and how it ended. */
    public enum Phase { WAITING, LIVE, PLANTED, DEFUSED, EXPLODED, ENDED }

    /** What this player is doing with the bomb right now (timed by the server). */
    public enum Action { NONE, PLANTING, DEFUSING }

    /** A bomb site as a box in world coordinates (block corners, max exclusive). */
    public record Site(String name, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public double centerX() {
            return (minX + maxX) / 2;
        }

        public double centerZ() {
            return (minZ + maxZ) / 2;
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }
    }

    /**
     * The bomb match as this player sees it (sent on every change and once a second; {@link Side#NONE} = not in a bomb
     * match, the HUD goes away). Defenders are not told who carries the bomb or where it was dropped.
     *
     * @param carrier       this player carries the bomb
     * @param carrierName   attackers: who carries it ("" = nobody / not told)
     * @param dropped       attackers: the bomb lies on the ground at (dropX, dropY, dropZ)
     * @param plantedSite   the site it was planted at ("" = not planted)
     * @param roundTicks    ticks left to plant (-1 = no limit or not running)
     * @param bombTicks     ticks until it goes off (-1 = not planted), {@code fuseTicks} the whole fuse
     * @param actionTicks   the whole plant / defuse, {@code actionDone} how much of it is done
     */
    public record S2CState(Side side, Phase phase, List<Site> sites, boolean carrier, String carrierName,
                           boolean dropped, double dropX, double dropY, double dropZ, String plantedSite,
                           int roundTicks, int bombTicks, int fuseTicks, Action action, int actionTicks, int actionDone) {
        public static final S2CState NONE = new S2CState(Side.NONE, Phase.ENDED, List.of(), false, "", false, 0, 0, 0, "",
                -1, -1, 1, Action.NONE, 1, 0);

        public S2CState {
            sites = List.copyOf(sites);
        }

        public static void encode(S2CState msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.side);
            buf.writeEnum(msg.phase);
            buf.writeVarInt(msg.sites.size());
            for (Site s : msg.sites) {
                buf.writeUtf(s.name, 32);
                buf.writeDouble(s.minX);
                buf.writeDouble(s.minY);
                buf.writeDouble(s.minZ);
                buf.writeDouble(s.maxX);
                buf.writeDouble(s.maxY);
                buf.writeDouble(s.maxZ);
            }
            buf.writeBoolean(msg.carrier);
            buf.writeUtf(msg.carrierName, 64);
            buf.writeBoolean(msg.dropped);
            buf.writeDouble(msg.dropX);
            buf.writeDouble(msg.dropY);
            buf.writeDouble(msg.dropZ);
            buf.writeUtf(msg.plantedSite, 32);
            buf.writeVarInt(msg.roundTicks + 1);
            buf.writeVarInt(msg.bombTicks + 1);
            buf.writeVarInt(msg.fuseTicks);
            buf.writeEnum(msg.action);
            buf.writeVarInt(msg.actionTicks);
            buf.writeVarInt(msg.actionDone);
        }

        public static S2CState decode(FriendlyByteBuf buf) {
            Side side = buf.readEnum(Side.class);
            Phase phase = buf.readEnum(Phase.class);
            int count = Math.min(16, buf.readVarInt());
            List<Site> sites = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                sites.add(new Site(buf.readUtf(32), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble()));
            }
            return new S2CState(side, phase, sites, buf.readBoolean(), buf.readUtf(64), buf.readBoolean(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readUtf(32), buf.readVarInt() - 1, buf.readVarInt() - 1,
                    Math.max(1, buf.readVarInt()), buf.readEnum(Action.class), Math.max(1, buf.readVarInt()), buf.readVarInt());
        }
    }

    /** The bomb went off at (x, y, z): the client's explosion (fire, smoke, sparks, light, shake). Breaks nothing. */
    public record S2CDetonate(double x, double y, double z, float radius, float shake) {
        public static void encode(S2CDetonate msg, FriendlyByteBuf buf) {
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeFloat(msg.radius);
            buf.writeFloat(msg.shake);
        }

        public static S2CDetonate decode(FriendlyByteBuf buf) {
            return new S2CDetonate(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat());
        }
    }
}
