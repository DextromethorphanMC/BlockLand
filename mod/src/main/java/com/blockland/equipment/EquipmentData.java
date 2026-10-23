package com.blockland.equipment;

import com.blockland.common.Handling;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Equipment (装备) definitions as written in {@code data/blockland/equipment/equipment.json} (read by Gson; field names
 * = JSON keys, except {@code throw}): grenades, the molotov, throwing knives and injectors. How an item is held, thrown
 * or used and how it flies lives here; what it does when it goes off is the {@code effect} object, read only by the
 * effects module.
 */
public final class EquipmentData {
    private EquipmentData() {
    }

    /** Kinds the equipment module knows how to handle (the {@code kind} key). */
    public static final String FRAG = "frag", FLASH = "flash", SMOKE = "smoke", MOLOTOV = "molotov", KNIFE = "knife",
            ADRENALINE = "adrenaline";

    /** One way to throw: launch speed (blocks per tick) and how far above the view direction it leaves (degrees). */
    public static final class Launch {
        public float speed = 1.4f;
        public float pitchUp = 4;
    }

    /** How the item leaves the hand and flies. */
    public static final class Throw {
        /** Left click: the full overhand throw. */
        public Launch overhand = new Launch();
        /** Right click: the short underhand toss (null = the item has no lob). */
        public Launch lob;
        /** Blocks per tick² pulling down. */
        public float gravity = 0.05f;
        /** Velocity kept per tick in flight. */
        public float drag = 0.99f;
        /** Share of the speed into a surface that bounces back (0 = stops dead). */
        public float bounce = 0.38f;
        /** Share of the speed along a surface kept on a bounce. */
        public float friction = 0.62f;
        /** Seconds into the throw clip at which the item leaves the hand (the clip's {@code release} event). */
        public float releaseAt = 0.22f;
        /** Seconds from the release until the hand is free again (the rest of the throw clip). */
        public float recover = 0.42f;
    }

    /** Animation names in the item's GLB (asset spec §36). */
    public static final class Anims {
        public String idle = "idle";
        public String draw = "draw";
        public String inspect = "inspect";
        public String walk = "walk";
        public String sprint = "sprint";
        public String tacSprint = "tac_sprint";
        /** Grenades: the pin pulled (the molotov's rag lit), then held cooking. */
        public String pull = "pull";
        public String hold = "hold";
        /** JSON key {@code throw}. */
        public String throwClip = "throw";
        public String lob = "lob";
        /** Injectors. */
        public String use = "use";
    }

    public static final class Equipment implements Craftable {
        public String id;
        public String name = "";
        public String description = "";
        public String kind = FRAG;
        /** GLB path under {@code assets/blockland/}. */
        public String model = "";
        public int stack = 1;
        public float drawTime = 0.45f;
        /** Seconds after the hand is free again before the next item may be readied. */
        public float cooldown = 0.5f;
        /** Seconds from the pin coming out until it goes off (0 = only on impact, or never). */
        public float fuse = 0;
        /** The fuse runs while the item is still held (frag, flash): held too long it goes off in the hand. */
        public boolean cookable = false;
        /** Seconds of the {@code pull} clip: the pin is out (the rag lit) at its end. */
        public float pullTime = 0.35f;
        /** JSON key {@code throw}. */
        public Throw throwing = new Throw();
        /** Injectors: seconds of the {@code use} clip, and when in it the effect takes hold. */
        public float useTime = 1.4f;
        public float injectAt = 0.8f;
        /** Parameters of what the item does when it goes off (the effects module reads them). */
        public JsonObject effect = new JsonObject();
        public Anims anims = new Anims();
        /** First-person feel (weight, sway, sprint-out time…). */
        public Handling handling = new Handling();
        public Map<String, CraftData.Skin> skins = new LinkedHashMap<>();
        public Map<String, CraftData.SlotDef> slots = new LinkedHashMap<>();

        private transient List<Slot> resolvedSlots;

        /** Thrown (grenades, molotov, knives) rather than used on oneself (injectors). */
        public boolean thrown() {
            return !ADRENALINE.equals(kind);
        }

        /** Readied by holding the button (pin, rag), released to throw. */
        public boolean held() {
            return thrown() && !KNIFE.equals(kind);
        }

        /** Goes off on its first impact (the molotov's bottle breaks). */
        public boolean shatters() {
            return MOLOTOV.equals(kind);
        }

        public Launch launch(boolean lob) {
            return lob && throwing.lob != null ? throwing.lob : throwing.overhand;
        }

        /** The effect parameter {@code key} as a number (the module itself reads only a few, e.g. the knife damage). */
        public float effectFloat(String key, float fallback) {
            return effect != null && effect.has(key) && effect.get(key).isJsonPrimitive() ? effect.get(key).getAsFloat() : fallback;
        }

        void resolveSlots() {
            resolvedSlots = CraftCatalog.resolveAll(slots);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Kind kind() {
            return Kind.EQUIPMENT;
        }

        @Override
        public Component displayName() {
            return CraftCatalog.text("item.blockland." + id, name);
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public String modelRoot() {
            return "equipment";
        }

        @Override
        public List<Slot> slots() {
            return resolvedSlots == null ? List.of() : resolvedSlots;
        }

        @Override
        public Map<String, CraftData.Skin> skins() {
            return skins;
        }

        @Override
        public OptionInfo describe(String slot, String option) {
            return slot(slot).map(s -> CraftCatalog.describeAttachment(this, s, option))
                    .orElseGet(() -> new OptionInfo(Component.literal(option), Component.empty(), "common"));
        }
    }
}
