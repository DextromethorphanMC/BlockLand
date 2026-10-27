package com.blockland.vehicle;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The vehicles' data ({@code data/blockland/vehicles/vehicles.json}, vehicle spec §2), read by Gson. Positions are model
 * space: metres, +Y up, front −Z, origin on the ground under the middle of the wheelbase — the model's nodes sit at the
 * same points. Speeds blocks/s, accelerations blocks/s², angles degrees, times seconds.
 */
public final class VehicleData {
    private VehicleData() {
    }

    public enum Kind { BIKE, WHEELED, TRACKED, BOAT }

    public static final class Vehicle {
        public transient String id;
        public String name = "";
        public String model = "";
        /** Width and height of the entity box. */
        public float[] size = {2, 1.5f};
        public float mass = 1000;
        public float health = 100;
        /** Share of incoming damage the hull ignores (before a round's {@code armorDamage}). */
        public float armor;
        public Movement movement = new Movement();
        public List<Seat> seats = new ArrayList<>();
        public List<HitBox> hitBoxes = new ArrayList<>();
        public List<Weapon> weapons = new ArrayList<>();
        public Effects effects = new Effects();
        /** Sound keys; each is the sound event {@code vehicle.<id>.<key>}. */
        public List<String> sounds = new ArrayList<>();

        public Kind kind() {
            return movement.kind();
        }

        /** The weapon fired from seat {@code seat}, or null. */
        @Nullable
        public Weapon weaponOf(int seat) {
            for (Weapon w : weapons) if (w.seat == seat) return w;
            return null;
        }

        /** The weapon's index in {@link #weapons}, -1 for none. */
        public int weaponIndexOf(int seat) {
            for (int i = 0; i < weapons.size(); i++) if (weapons.get(i).seat == seat) return i;
            return -1;
        }

        public String sound(String key) {
            return "vehicle." + id + "." + key;
        }
    }

    public static final class Movement {
        public String kind = "wheeled";
        public float maxSpeed = 20;
        public float reverseSpeed = 6;
        public float accel = 6;
        public float brake = 14;
        /** Degrees per second at the steer's full lock (tracked: on the spot). */
        public float turnRate = 60;
        /** Share of the sideways slide removed per tick (0 = ice, 1 = rails). */
        public float grip = 0.85f;
        public float stepHeight = 1.0f;
        public float wheelRadius = 0.35f;
        /** Water deeper than this over the hull's bottom stalls a land vehicle. */
        public float wadeDepth = 1.0f;
        /** Boats: the hull's depth under the surface at rest. */
        public float draft = 0.3f;
        /** Boats: the speed above which the hull planes (less drag, rides higher). */
        public float planing = 9;
        /** Bikes: the most the bike leans into a turn. */
        public float lean = 20;
        /** Tracks: how far the track material's v runs per metre of track (its uvs before uv scale; VEH-MODELS' models). */
        public float trackUvPerMetre = 20;

        public Kind kind() {
            return switch (kind) {
                case "bike" -> Kind.BIKE;
                case "tracked" -> Kind.TRACKED;
                case "boat" -> Kind.BOAT;
                default -> Kind.WHEELED;
            };
        }
    }

    public static final class Seat {
        public String node = "";
        public String role = "passenger";
        /** Exposed riders can be hit by rounds and may use their own weapons; the others sit inside the hull. */
        public boolean exposed = true;
        /** {@code first} or {@code third}: the camera the seat starts with. */
        public String camera = "first";
        /** {@code turret}: the seat turns with weapon 0's yaw ({@link #pos} relative to its yaw pivot); else the hull's. */
        public String mount = "hull";
        /** The rider's hips (hull space, or relative to the turret's yaw pivot on a turret). */
        public float[] pos = {0, 0.6f, 0};
        /** How far the rider may look away from the vehicle's front (degrees each side). */
        public float yawLimit = 150;

        public boolean driver() {
            return "driver".equals(role);
        }

        public boolean thirdPerson() {
            return "third".equals(camera);
        }

        public boolean onTurret() {
            return "turret".equals(mount);
        }

        public Vec3 hips() {
            return new Vec3(pos[0], pos[1], pos[2]);
        }
    }

    /** A hull box, yawed with the vehicle (hit registration: {@code combat/HitShapes}). */
    public static final class HitBox {
        public float[] center = {0, 0.5f, 0};
        public float[] half = {1, 0.5f, 1};
    }

    public static final class Weapon {
        public String id = "";
        /** The seat that aims and fires it. */
        public int seat;
        /** {@code turret}: the pivot is in the space of weapon 0's yaw pivot (a gun on the turret); else the hull's. */
        public String mount = "hull";
        public String pivotYaw = "";
        public String pivotPitch = "";
        public String muzzle = "";
        /** The yaw pivot (hull space, or turret space when mounted on the turret). */
        public float[] pivot = {0, 1, 0};
        /** The pitch pivot relative to the yaw pivot. */
        public float[] pitchPivot = {0, 0, 0};
        /** The muzzle relative to the pitch pivot (front −Z). */
        public float[] muzzleOffset = {0, 0, -1};
        /** Yaw limits relative to its mount [min, max] (Minecraft sense: + = right), null = all round. */
        @Nullable
        public float[] yawLimit;
        /** Elevation limits [min, max] (+ = up). */
        public float[] pitchLimit = {-10, 20};
        public float turnRate = 40;
        /** {@code shell} (a projectile that explodes) or {@code hitscan}. */
        public String kind = "hitscan";
        public float damage = 8;
        public float fireInterval = 0.1f;
        public float range = 150;
        /** Hitscan cone half-angle. */
        public float spread = 0.5f;
        /** impacts.json ammo profile of what it fires. */
        public String ammo = "rifle";
        public int magazine = 100;
        public float reload = 4;
        public float shellSpeed = 120;
        /** Blocks/s² the shell falls. */
        public float shellGravity = 6;
        /** Sight magnification (FOV divisor) while the gunner looks through the sight. */
        public float zoom = 1.5f;
        @Nullable
        public Explosion explosion;

        public boolean shell() {
            return "shell".equals(kind);
        }

        public boolean onTurret() {
            return "turret".equals(mount);
        }

        public int fireTicks() {
            return Math.max(1, Math.round(fireInterval * 20));
        }

        public int reloadTicks() {
            return Math.max(1, Math.round(reload * 20));
        }
    }

    public static final class Explosion {
        public float radius = 4;
        public float innerRadius = 1.2f;
        public float damage = 60;
        public float minDamage = 8;
        public float knockback = 1.2f;
        /** Damage to vehicle hulls within the radius (falls off like {@code damage}). */
        public float hullDamage = 80;
    }

    /** Effect anchors (the model's {@code exhaust_<i>}, {@code headlight_<i>}, {@code wake} nodes sit here too). */
    public static final class Effects {
        public List<float[]> exhaust = new ArrayList<>();
        public List<float[]> headlights = new ArrayList<>();
        @Nullable
        public float[] wake;
    }

    /** JSON layout of the file. */
    static final class File {
        Map<String, Vehicle> vehicles = new LinkedHashMap<>();
    }
}
