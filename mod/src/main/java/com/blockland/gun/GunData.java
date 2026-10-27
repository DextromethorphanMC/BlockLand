package com.blockland.gun;

import com.blockland.combat.Ballistics;
import com.blockland.common.Handling;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gun definitions as written in {@code data/blockland/guns/guns.json} (read by Gson; field names = JSON keys).
 * Everything that makes one gun differ from another lives here: ammo, magazines, slots, skins, handling. The
 * attachments that go into the slots are universal (craft/attachments.json, {@link CraftData.Attachment}).
 */
public final class GunData {
    private GunData() {
    }

    /** A kind of ammunition: how hard it hits and which magazines take it. */
    public static final class Ammo {
        public String id;
        public String name = "";
        /** Item path in the {@code blockland} namespace, e.g. {@code pistol_ammo}. */
        public String item = "";
        public float damage = 4;
        public float headshotMultiplier = 2;
        /** Projectiles per shot (shotgun pellets). */
        public int pellets = 1;
        /** Decal type (assets/blockland/decals.json) a projectile leaves on the block it hits. */
        public String decal = "bullet_hole";
        /** Decal type a projectile leaves on the body it hits (the entry wound). */
        public String bodyDecal = "bullet_wound";
        public List<String> magazines = List.of();
        /**
         * Rounds in fluids ({@code combat/Ballistics}): blocks of water and of lava that spend a round (its energy and
         * damage falling linearly on the way; 0 = that fluid does not slow it), and the angle (degrees) to a level water
         * surface below which it skips off instead of going in (0 = never).
         */
        public float waterRange = 2.5f;
        public float lavaRange = 0.3f;
        public float skipAngle = 7;
        /**
         * A launched round (a grenade launcher's) instead of a bullet traced at once: it flies, falls and goes off where
         * it strikes ({@link GunGrenade}); {@link #damage} is then what it does striking someone before it is armed.
         * Null = a bullet.
         */
        @Nullable
        public Projectile projectile;

        /** How this round fares in fluids. */
        public Ballistics.Medium medium() {
            return new Ballistics.Medium(waterRange, lavaRange, skipAngle);
        }
    }

    /** A launched round: its flight and the blast it ends in. */
    public static final class Projectile {
        /** Speed at the muzzle (blocks per second) and how fast it falls (blocks per second squared). */
        public float speed = 50;
        public float gravity = 9.8f;
        /** Blocks from the muzzle before the fuze is armed: nearer, it only strikes (and clunks to the ground). */
        public float armDistance = 6;
        /** Seconds before it goes off wherever it is. */
        public float life = 8;
        /** The blast: the frag grenade's parameters (equipment.json {@code effect}: radius, innerRadius, damage …). */
        public com.google.gson.JsonObject blast = new com.google.gson.JsonObject();
    }

    /** A magazine attachment: capacity and which guns accept it. */
    public static final class Magazine {
        public String id;
        public String name = "";
        public String description = "";
        public String ammo = "";
        public int capacity = 10;
        public List<String> guns = List.of();
        /** Viewmodel node shown when this magazine is fitted. */
        public String node = "";
        public float reloadTimeScale = 1;
        public float adsTimeScale = 1;
        /**
         * Suffix of this magazine's own reload clips ({@code _drum}: {@code reload_drum}, {@code reload_empty_drum}), for
         * a magazine the hands must hold differently (a drum); the gun's clips play where the model has none.
         */
        public String clipSuffix = "";
    }

    public static final class Falloff {
        /** Full damage up to this distance (blocks)… */
        public float start = 20;
        /** …then linearly down to {@link #min} at this distance. */
        public float end = 50;
        public float min = 0.6f;
    }

    /** Bullet spread as a cone half-angle in degrees. */
    public static final class Spread {
        public float hip = 3;
        public float ads = 0.3f;
        /** Added while moving on the ground. */
        public float move = 1.5f;
        /** Added while airborne. */
        public float air = 4;
        /** Bloom added per shot, decays at {@link #recovery} degrees per second. */
        public float perShot = 0.8f;
        public float max = 6;
        public float recovery = 9;
    }

    /** Camera recoil per shot in degrees; {@code kick} scales the viewmodel kick; {@code ads} scales both when aiming. */
    public static final class Recoil {
        public float vertical = 1.2f;
        public float horizontal = 0.4f;
        public float kick = 1;
        public float ads = 0.7f;
    }

    public static final class Reload {
        /** Seconds for a reload with rounds left / with an empty magazine. */
        public float tactical = 1.8f;
        public float empty = 2.2f;
        /**
         * {@code magazine} (default: a magazine swap, every round at once) or {@code shell} (tube magazines, asset spec
         * §25: rounds go in one at a time, the reload can be interrupted by firing once a round is in).
         */
        public String mode = "magazine";
        /** Shell mode: seconds to get ready (clip {@code reload_start}) … */
        public float start = 0.45f;
        /** … per round loaded ({@code reload_shell}, the round counts at its end) … */
        public float shell = 0.5f;
        /** … and to finish ({@code reload_end}, or {@code reload_end_empty} — which also chambers a round — after an empty start). */
        public float end = 0.4f;
        public float endEmpty = 0.75f;

        public boolean shellMode() {
            return "shell".equalsIgnoreCase(mode);
        }
    }

    /**
     * A pair of guns, one in each hand (asset spec §26). The model repeats every node of the right gun ({@code gun},
     * {@code slide}, {@code muzzle}, {@code mount_barrel} …) for the left one with {@link #suffix}; magazines, the slide
     * lock and attachments at mounts apply to both. No sights: the use key fires both guns at once ({@link #volley}).
     * Rounds are counted for the pair (the magazine's capacity is both magazines).
     */
    public static final class Dual {
        public String suffix = "_L";
        /** The use key fires both guns at once (two rounds) instead of aiming. */
        public boolean volley = true;
        /** Spread multiplier of a volley's two shots. */
        public float volleySpread = 1.35f;
        /** Fire-rate credits a volley costs (the server's limit; one shot costs 1). */
        public float volleyCost = 2;
        /** Clips of the left gun's shot (and its last round) and of a volley. */
        public String fireLeft = "fire_L";
        public String fireLastLeft = "fire_last_L";
        public String fireBoth = "fire_both";
    }

    /** Animation names in the gun's GLB (see tools/assets/VIEWMODEL_SPEC.md). */
    public static final class Anims {
        public String idle = "idle";
        public String draw = "draw";
        public String fire = "fire";
        public String fireLast = "fire_last";
        public String reload = "reload";
        public String reloadEmpty = "reload_empty";
        public String inspect = "inspect";
        /**
         * Aimed variants (spec §31.5): a fire clip plays blended with {@code <clip><aimedSuffix>} by the aim amount when the
         * model has it ({@code fire_ads}, {@code fire_last_ads}); without one the hip clip plays alone.
         */
        public String aimedSuffix = "_ads";
        /** Optional loops (spec §17): walk, the lowered running carry and the raised tactical sprint. */
        public String walk = "walk";
        public String sprint = "sprint";
        public String tacSprint = "tac_sprint";
        /** Shell-mode reloads (spec §25): start, one round, finish, finish after an empty start (chambers a round). */
        public String reloadStart = "reload_start";
        public String reloadShell = "reload_shell";
        public String reloadEnd = "reload_end";
        public String reloadEndEmpty = "reload_end_empty";
    }

    public static final class Sounds {
        /** Sound event paths in the {@code blockland} namespace. */
        public String fire = "gun.p9.fire";
        public String dry = "gun.dry_fire";
    }

    /** The slide stays back by this offset while the gun is empty. */
    public static final class SlideLock {
        public String node = "slide";
        public float[] offset = {0, 0, 0.034f};
    }

    public enum FireMode {
        SEMI("半自动"), AUTO("全自动");

        public final String label;

        FireMode(String label) {
            this.label = label;
        }

        static FireMode parse(String id) {
            return "auto".equalsIgnoreCase(id) ? AUTO : SEMI;
        }
    }

    public static final class Gun implements Craftable {
        public String id;
        public String name = "";
        public String description = "";
        public String category = "pistol";
        /** GLB path under {@code assets/blockland/}. */
        public String model = "";
        public String ammo = "";
        public List<String> magazines = List.of();
        /**
         * Craft slots in display order (optic, barrel, laser, magazine, stock, charm …): mount node, default option,
         * optional option list (default: every attachment of the slot's kind) and nodes hidden while filled. A
         * {@code magazine} entry only places the magazine slot; its options are {@link #magazines}.
         */
        public Map<String, CraftData.SlotDef> slots = new LinkedHashMap<>();
        /** Supported fire modes, the first is the default ("semi", "auto"); several = select fire. */
        public List<String> fireModes = List.of("semi");
        /**
         * Bolt action (asset spec §23): after every shot the sight comes down while the fire clip cycles the bolt and
         * goes up again if aim is still held. Presentation only; {@link #rpm} stays the server's fire-rate limit.
         */
        public boolean boltAction = false;
        /**
         * Clip event that throws out the empty case (a bolt gun ejects when its bolt comes back, in the fire clip and the
         * empty reload); empty = at the shot.
         */
        public String ejectEvent = "";
        /** Two guns, one per hand (asset spec §26); null = a single gun. */
        @Nullable
        public Dual dual;
        public float rpm = 400;
        public float damageScale = 1;
        public float range = 80;
        public Falloff falloff = new Falloff();
        public Spread spread = new Spread();
        public Recoil recoil = new Recoil();
        public float adsTime = 0.18f;
        public float drawTime = 0.45f;
        public Reload reload = new Reload();
        public Anims anims = new Anims();
        public Sounds sounds = new Sounds();
        public SlideLock slideLock = new SlideLock();
        public Map<String, CraftData.Skin> skins = new LinkedHashMap<>();
        /** First-person feel (weight, sway, sprint-out time…). */
        public Handling handling = new Handling();

        public FireMode mode() {
            return fireModes.isEmpty() ? FireMode.SEMI : FireMode.parse(fireModes.get(0));
        }

        public List<FireMode> modes() {
            return fireModes.isEmpty() ? List.of(FireMode.SEMI) : fireModes.stream().map(FireMode::parse).distinct().toList();
        }

        /** Seconds between shots. */
        public float fireInterval() {
            return 60f / Math.max(1, rpm);
        }

        public boolean isDual() {
            return dual != null;
        }

        /** Whether the use key raises the sights (dual guns fire a volley with it instead). */
        public boolean canAim() {
            return dual == null;
        }

        public String defaultMagazine() {
            return magazines.isEmpty() ? "" : magazines.get(0);
        }

        // ---- craft ------------------------------------------------------------------------------------

        /** The magazine slot's id in builds. */
        public static final String MAGAZINE_SLOT = "magazine";

        private transient List<Slot> resolvedSlots;

        /** Resolves the declared slots (called by the catalogue once the magazines are known). */
        void resolveSlots(Map<String, Magazine> catalogMagazines) {
            List<Slot> list = new ArrayList<>();
            boolean magazinePlaced = false;
            for (Map.Entry<String, CraftData.SlotDef> entry : slots.entrySet()) {
                if (entry.getKey().equals(MAGAZINE_SLOT)) {
                    list.add(magazineSlot(catalogMagazines));
                    magazinePlaced = true;
                } else {
                    list.add(CraftCatalog.resolve(entry.getKey(), entry.getValue()));
                }
            }
            if (!magazinePlaced && !magazines.isEmpty()) list.add(magazineSlot(catalogMagazines));
            resolvedSlots = List.copyOf(list);
        }

        private Slot magazineSlot(Map<String, Magazine> catalogMagazines) {
            Map<String, String> nodes = new LinkedHashMap<>();
            for (String id : magazines) {
                Magazine magazine = catalogMagazines.get(id);
                if (magazine != null && !magazine.node.isEmpty()) nodes.put(id, magazine.node);
            }
            return new Slot(MAGAZINE_SLOT, "", defaultMagazine(), magazines, List.of(), nodes, "");
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Kind kind() {
            return Kind.GUN;
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
            return "gun";
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
            if (slot.equals(MAGAZINE_SLOT)) {
                Magazine magazine = GunCatalog.get().magazine(option);
                if (magazine == null) return new OptionInfo(Component.literal(option), Component.empty(), "common");
                return new OptionInfo(Component.translatable("blockland.craft.magazine_name",
                        CraftCatalog.text("blockland.magazine." + option, magazine.name), magazine.capacity),
                        CraftCatalog.text("blockland.magazine." + option + ".desc", magazine.description), "common");
            }
            return slot(slot).map(s -> CraftCatalog.describeAttachment(this, s, option))
                    .orElseGet(() -> new OptionInfo(Component.literal(option), Component.empty(), "common"));
        }
    }
}
