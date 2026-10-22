package com.blockland.gun;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gun definitions as written in {@code data/blockland/guns/guns.json} (read by Gson; field names = JSON keys).
 * Everything that makes one gun differ from another lives here: ammo, magazines, attachments, skins, handling.
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
        public List<String> magazines = List.of();
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
    }

    public static final class Sight {
        public String id;
        public String name = "";
        public String description = "";
        /** Eye-point node used when aiming down this sight. */
        public String aimNode = "aim_iron";
        /** FOV divisor while aiming (1.25 = 25% zoom). */
        public float zoom = 1.1f;
        public float adsTimeScale = 1;
        /** Viewmodel nodes shown with this sight. */
        public List<String> nodes = List.of();
    }

    public static final class Laser {
        public String id;
        public String name = "";
        public String description = "";
        /** Beam colour, {@code #RRGGBB}; empty = no beam. */
        public String color = "";
        public float hipSpreadScale = 1;
        public List<String> nodes = List.of();

        public boolean hasBeam() {
            return !color.isEmpty();
        }

        public int argb() {
            return color.isEmpty() ? 0 : 0xFF000000 | Integer.parseInt(color.substring(1), 16);
        }
    }

    /** A cosmetic variant: per-material colour multipliers and/or texture replacements. */
    public static final class Skin {
        public String id;
        public String name = "";
        public String rarity = "common";
        /** material name → linear RGB multiplier */
        public Map<String, float[]> tints = new LinkedHashMap<>();
        /** material name → texture path under {@code assets/blockland/} */
        public Map<String, String> textures = new LinkedHashMap<>();
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
        /** Optional looping sprint carry; the runtime poses one procedurally when the model has none. */
        public String sprint = "sprint";
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

    public static final class Gun {
        public String id;
        public String name = "";
        public String description = "";
        public String category = "pistol";
        /** GLB path under {@code assets/blockland/}. */
        public String model = "";
        public String ammo = "";
        public List<String> magazines = List.of();
        public List<String> sights = List.of("iron");
        public List<String> lasers = List.of("none");
        /** Supported fire modes, the first is the default ("semi", "auto"); several = select fire. */
        public List<String> fireModes = List.of("semi");
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
        public Map<String, Skin> skins = new LinkedHashMap<>();

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

        public String defaultMagazine() {
            return magazines.isEmpty() ? "" : magazines.get(0);
        }

        public String defaultSight() {
            return sights.isEmpty() ? "iron" : sights.get(0);
        }

        public String defaultLaser() {
            return lasers.isEmpty() ? "none" : lasers.get(0);
        }
    }
}
