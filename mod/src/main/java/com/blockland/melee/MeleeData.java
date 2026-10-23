package com.blockland.melee;

import com.blockland.common.Handling;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Knife definitions as written in {@code data/blockland/melee/knives.json} (read by Gson; field names = JSON keys).
 * Everything that makes one knife differ from another lives here: the two attacks, timing, model, sounds and its 工匠
 * (craft) options — skins and slots for universal attachments (a charm).
 */
public final class MeleeData {
    private MeleeData() {
    }

    /** One swing of an attack: the clip that plays and the direction the blade travels (for cut decals). */
    public static final class Swing {
        public String clip = "";
        /** Screen-space direction of the cut in degrees, counter-clockwise from "right" (0 = left to right). */
        public float slashAngle = 0;
    }

    /** The light (left click) or heavy (right click) attack. */
    public static final class Attack {
        public float damage = 4;
        /** Reach from the eye, in blocks. */
        public float range = 2.6f;
        /** Half-width of the swept hit volume (melee is forgiving, like in modern shooters). */
        public float radius = 0.4f;
        /** Seconds the attack animation lasts (the clip is stretched to it). */
        public float duration = 0.45f;
        /** Seconds into the attack at which the blade connects. */
        public float impact = 0.13f;
        /** Seconds from the start of this attack until the next attack may start. */
        public float cooldown = 0.4f;
        public float knockback = 0.25f;
        /** Damage multiplier when hitting a target from behind. */
        public float backstabMultiplier = 1;
        /** Camera punch at the moment of impact, in degrees. */
        public float punch = 1;
        /** Swings in combo order; consecutive attacks alternate through them. */
        public List<Swing> swings = List.of();
        /** Decal types (assets/blockland/decals.json) left on blocks and on bodies. */
        public String blockDecal = "knife_slash";
        public String bodyDecal = "knife_cut";
        /** Sound event id others hear when the swing starts. */
        public String swingSound = "blockland:knife.swing";

        public Swing swing(int combo) {
            if (swings.isEmpty()) return new Swing();
            return swings.get(Math.floorMod(combo, swings.size()));
        }
    }

    /** Animation names in the knife's GLB (see tools/assets/VIEWMODEL_SPEC.md §12). */
    public static final class Anims {
        public String idle = "idle";
        public String draw = "draw";
        public String inspect = "inspect";
        /** Optional loops: walk, the running carry and the tactical sprint. */
        public String walk = "walk";
        public String sprint = "sprint";
        public String tacSprint = "tac_sprint";
    }

    /** Sound event ids (any namespace). */
    public static final class Sounds {
        public String equip = "blockland:knife.equip";
        public String hitFlesh = "blockland:knife.hit_flesh";
        public String hitBlock = "blockland:knife.hit_wall";
        public String hitMarker = "blockland:gun.hitmarker";
        /** Hit marker sound of a backstab. */
        public String critical = "blockland:gun.headshot";
        public String kill = "blockland:gun.kill";
    }

    public static final class Knife implements Craftable {
        public String id;
        public String name = "";
        public String description = "";
        /** GLB path under {@code assets/blockland/}. */
        public String model = "";
        public float drawTime = 0.6f;
        /** A light attack started within this many seconds after the previous one continues the combo. */
        public float comboWindow = 0.7f;
        public Attack light = new Attack();
        public Attack heavy = new Attack();
        public Anims anims = new Anims();
        public Sounds sounds = new Sounds();
        /** First-person feel (weight, sway, sprint-out time…). */
        public Handling handling = new Handling();
        /** Cosmetic skins (per-material display colours / textures); {@code default} is added when missing. */
        public Map<String, CraftData.Skin> skins = new LinkedHashMap<>();
        /** Craft slots in display order (e.g. {@code charm} at {@code mount_charm}). */
        public Map<String, CraftData.SlotDef> slots = new LinkedHashMap<>();

        private transient List<Slot> resolvedSlots;

        public Attack attack(boolean heavy) {
            return heavy ? this.heavy : this.light;
        }

        /** Resolves the declared slots (called by the catalogue at load). */
        void resolveSlots() {
            resolvedSlots = CraftCatalog.resolveAll(slots);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Kind kind() {
            return Kind.KNIFE;
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
            return "knife";
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
