package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 枪战感染 messages: choosing a monster, using its skill, and what the client shows about its own role. */
public final class InfectionPackets {
    private InfectionPackets() {
    }

    /** The infected player picked a monster in the selection panel. */
    public record C2SSelectMonster(String monsterId) {
        public static void encode(C2SSelectMonster msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.monsterId, 64);
        }

        public static C2SSelectMonster decode(FriendlyByteBuf buf) {
            return new C2SSelectMonster(buf.readUtf(64));
        }
    }

    /** The monster player pressed the skill key. */
    public record C2SUseSkill() {
        public static final C2SUseSkill INSTANCE = new C2SUseSkill();

        public static void encode(C2SUseSkill msg, FriendlyByteBuf buf) {
        }

        public static C2SUseSkill decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }
    }

    /** One monster in the selection panel. {@code entity} is the vanilla entity type drawn as its preview. */
    public record MonsterOption(String id, String name, String description, String entity, float health,
                                String skillName, String skillDescription, float skillCooldown) {
    }

    /**
     * Open the monster selection panel; the first option is chosen automatically after {@code seconds}.
     *
     * @param mother the player is a mother monster (more health)
     */
    public record S2CSelect(int seconds, boolean mother, float healthMultiplier, List<MonsterOption> options) {
        public static void encode(S2CSelect msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.seconds);
            buf.writeBoolean(msg.mother);
            buf.writeFloat(msg.healthMultiplier);
            buf.writeVarInt(msg.options.size());
            for (MonsterOption o : msg.options) {
                buf.writeUtf(o.id, 64);
                buf.writeUtf(o.name, 64);
                buf.writeUtf(o.description, 256);
                buf.writeUtf(o.entity, 128);
                buf.writeFloat(o.health);
                buf.writeUtf(o.skillName, 64);
                buf.writeUtf(o.skillDescription, 256);
                buf.writeFloat(o.skillCooldown);
            }
        }

        public static S2CSelect decode(FriendlyByteBuf buf) {
            int seconds = buf.readVarInt();
            boolean mother = buf.readBoolean();
            float multiplier = buf.readFloat();
            int count = Math.min(32, buf.readVarInt());
            List<MonsterOption> options = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                options.add(new MonsterOption(buf.readUtf(64), buf.readUtf(64), buf.readUtf(256), buf.readUtf(128), buf.readFloat(),
                        buf.readUtf(64), buf.readUtf(256), buf.readFloat()));
            }
            return new S2CSelect(seconds, mother, multiplier, options);
        }
    }

    public enum Role { NONE, HUMAN, MONSTER }

    /**
     * This player's side in the match (NONE = not in an infection match). For monsters: the monster and its skill
     * ({@code monsterId} lets the client show them in its own language).
     */
    public record S2CRole(Role role, boolean mother, String monster, String skillName, float skillCooldown, String monsterId) {
        public static final S2CRole NONE = new S2CRole(Role.NONE, false, "", "", 0, "");

        public static void encode(S2CRole msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.role);
            buf.writeBoolean(msg.mother);
            buf.writeUtf(msg.monster, 64);
            buf.writeUtf(msg.skillName, 64);
            buf.writeFloat(msg.skillCooldown);
            buf.writeUtf(msg.monsterId, 64);
        }

        public static S2CRole decode(FriendlyByteBuf buf) {
            return new S2CRole(buf.readEnum(Role.class), buf.readBoolean(), buf.readUtf(64), buf.readUtf(64), buf.readFloat(), buf.readUtf(64));
        }
    }

    /** The skill was used: ready again in {@code cooldown} seconds, active for {@code active} seconds. */
    public record S2CSkill(float cooldown, float active) {
        public static void encode(S2CSkill msg, FriendlyByteBuf buf) {
            buf.writeFloat(msg.cooldown);
            buf.writeFloat(msg.active);
        }

        public static S2CSkill decode(FriendlyByteBuf buf) {
            return new S2CSkill(buf.readFloat(), buf.readFloat());
        }
    }
}
