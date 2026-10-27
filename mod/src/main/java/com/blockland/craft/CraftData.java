package com.blockland.craft;

import com.google.gson.annotations.SerializedName;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data of the 工匠 (craft) system as written in the JSON files (read by Gson; field names = JSON keys): universal
 * attachments ({@code data/blockland/craft/attachments.json}), the slots a weapon offers and cosmetic skins (inside
 * guns.json / knives.json, and shared ones in {@code data/blockland/craft/skins.json}).
 */
public final class CraftData {
    /** Option id meaning "nothing fitted" in a slot whose default allows it. */
    public static final String NONE = "none";

    private CraftData() {
    }

    /**
     * One universal attachment: any weapon with a slot of the same kind can fit it (a sight is not tied to the gun it
     * was first made for). Its model is drawn at the weapon's mount node (asset spec §19).
     */
    public static final class Attachment {
        public String id;
        /** Slot kind: {@code optic}, {@code laser}, {@code barrel}, {@code stock}, {@code charm}. */
        public String slot = "";
        public String name = "";
        public String description = "";
        /** GLB under {@code assets/blockland/} with an {@code attachment} root; empty = nothing to draw (iron sights). */
        public String model = "";
        /** "common" or "legendary" (UI colour only). */
        public String rarity = "common";

        // optics
        /** Eye-point node in the attachment model; without a model the weapon's own {@code aim_iron} is used. */
        public String aimNode = "aim";
        /** FOV divisor while aiming (1.25 = 25% zoom). */
        public float zoom = 1.1f;
        /**
         * A reflex sight (red dot, holographic): its glass shows a reticle projected at infinity along the sight line;
         * null for other optics (asset spec §22).
         */
        public Reflex reflex;
        /** Magnified optics only (asset spec §22): the picture in the eyepiece; null for every other attachment. */
        public Scope scope;
        /**
         * Aim focus (client-side feel): how strongly the world outside the focus circle blurs while aimed (0–1, at the
         * screen's corners); negative = by kind (iron sights, reflex sights; a scope's comes from its {@code scope}).
         */
        public float focusBlur = -1;
        /** Radius of the sharp circle around the aim point, as a share of half the screen height; negative = by kind. */
        public float focusRadius = -1;

        // lasers
        /** Beam colour {@code #RRGGBB}; empty = no beam. The beam leaves the model's {@code emitter} node. */
        public String color = "";

        // barrels
        /** Replaces the weapon's fire sound (a {@code blockland:} sound path), empty = keep it. */
        public String fireSound = "";
        /** Quiet shots without a muzzle flash (others see no flash, the shot carries less far). */
        public boolean suppressed = false;

        // underbarrel
        /**
         * How the first-person support hand holds this attachment (asset spec §35); null = the weapon's clips keep the
         * hand where they put it.
         */
        public Grip grip;

        // ammo (slot "ammo")
        /** What an ammo attachment's rounds do besides its stat multipliers; null for every other attachment. */
        public Round round;

        // stat multipliers (1 = no change)
        public float damageScale = 1;
        public float rangeScale = 1;
        public float recoilScale = 1;
        public float hipSpreadScale = 1;
        public float adsTimeScale = 1;

        public boolean hasModel() {
            return model != null && !model.isEmpty();
        }

        public boolean hasBeam() {
            return color != null && !color.isEmpty();
        }

        /** A magnified optic: shows its own picture of the world in the eyepiece. */
        public boolean isScope() {
            return scope != null && scope.magnification > 1.01f;
        }

        /** A reflex sight (red dot, holographic) with a model: its glass shows a reticle at infinity. */
        public boolean isReflex() {
            return reflex != null && hasModel() && !isScope();
        }

        public int argb() {
            return hasBeam() ? 0xFF000000 | Integer.parseInt(color.substring(1), 16) : 0;
        }

        /** How strongly the world outside the aim focus blurs (the data's, else by kind: scopes by power). */
        public float aimFocusBlur() {
            if (focusBlur >= 0) return Math.min(1, focusBlur);
            if (isScope()) return scope.peripheryBlur();
            return isReflex() ? 0.42f : 0.36f;
        }

        /** Radius of the sharp aim focus circle in half screen heights (scopes use their eyepiece instead). */
        public float aimFocusRadius() {
            if (focusRadius > 0) return focusRadius;
            return isReflex() ? 0.34f : 0.28f;
        }
    }

    /**
     * The optics of a magnified scope (asset spec §22). Purely visual: the server never reads it, the fire rate and
     * spread stay with the gun's data.
     */
    /**
     * The support hand's hold on an underbarrel attachment, in the attachment's frame (origin = the mount point, +Y up,
     * -Z down the bore; asset spec §35). Authored with the arm rigs' hand helpers ({@code underbarrel_models.py grips}).
     */
    public static final class Grip {
        /** Wrist position (metres). */
        public float[] wrist = {0, 0, 0};
        /** Palm orientation (quaternion x, y, z, w; the hand joint frame of spec §16). */
        public float[] palm = {0, 0, 0, 1};
        /** Finger → curls of its first and second segment in degrees (negative closes); missing = the clip's. */
        public Map<String, float[]> fingers = new LinkedHashMap<>();
    }

    public static final class Scope {
        /** The picture in the eyepiece is this many times the unzoomed view (the default power of a variable scope). */
        public float magnification = 2;
        /**
         * Powers of a variable scope, low to high (the mouse wheel steps through them while aiming); null or one entry =
         * fixed power {@link #magnification}.
         */
        public float[] zoomLevels;
        /** Index into {@link #zoomLevels} the scope starts at. */
        public int defaultZoom = 0;
        /**
         * {@code second}: the reticle keeps its size whatever the power (most hunting scopes); {@code first}: it grows
         * with the power, so its marks always span the same angle in the world.
         */
        public String focalPlane = "second";
        /** Brightness of the illuminated reticle (0 = etched only, 1 = daylight-bright). */
        public float illumination = 1;
        /** Half-amplitude of the aimed rifle's breathing sway seen through this scope, in degrees (0 = steady). */
        public float sway = 0;
        /** Seconds the shooter can hold their breath to steady this scope (sprint key while aiming; 0 = cannot). */
        public float holdBreath = 0;
        /** How much the world around the eyepiece blurs while aimed (0–1; negative = by magnification). */
        public float blurOutside = -1;
        /** Strength of sun glare and lens ghosts when the sun is near the field of view (0–1). */
        public float flare = 1;
        /** Smudges on the glass, seen when bright light falls through it (0–1). */
        public float dirt = 0.3f;
        /** How visible the raised scope's glint is to other players (0–1; negative = by magnification). */
        public float glint = -1;
        /** Eyepiece glass node in the attachment model (a flat disc facing the eye). */
        public String lensNode = "lens_ocular";
        /** RGBA reticle texture under {@code assets/blockland/}, centred in the eyepiece; empty = none. */
        public String reticle = "";
        /** Width of the reticle texture as a share of the eyepiece diameter. */
        public float reticleSize = 1;
        /** Tint of the illuminated (coloured) reticle pixels, {@code #RRGGBB}. */
        public String reticleColor = "#FF3A2E";
        /** How far the eye may sit off the optical axis (m) before the picture shrinks into a dark ring. */
        public float eyeBox = 0.012f;
        /** How far the reticle drifts against the picture when the eye is off axis (0–1). */
        public float parallax = 0.4f;
        /** Tint of reflections on the coated glass, {@code #RRGGBB}. */
        public String coating = "#7A5CFF";
        /** Darkening towards the rim of the picture (0–1). */
        public float vignette = 0.35f;
        /**
         * Diameter of the eyepiece on screen when fully aimed, as a share of the screen height: the eye is right at the
         * eyepiece, so the magnified picture fills the middle of the view (the model's own eyepiece looks far smaller).
         */
        public float apparentSize = 0.6f;

        /** The scope's powers, low to high (one entry for a fixed-power scope). */
        public float[] levels() {
            if (zoomLevels == null || zoomLevels.length == 0) return new float[]{magnification};
            float[] levels = zoomLevels.clone();
            for (int i = 0; i < levels.length; i++) levels[i] = Math.max(1.01f, levels[i]);
            java.util.Arrays.sort(levels);
            return levels;
        }

        /** Index of the power the scope starts at. */
        public int startLevel() {
            float[] levels = levels();
            return Math.max(0, Math.min(levels.length - 1, defaultZoom));
        }

        public boolean firstFocalPlane() {
            return "first".equalsIgnoreCase(focalPlane);
        }

        /** Strength of the blur around the eyepiece: the data's, or by magnification (none at 1.5×, full at 6×). */
        public float peripheryBlur() {
            if (blurOutside >= 0) return Math.min(1, blurOutside);
            return Math.max(0, Math.min(1, (magnification - 1.5f) / 4.5f));
        }

        /** Strength of the glint other players see: the data's, or by magnification (faint on low powers). */
        public float glintStrength() {
            if (glint >= 0) return Math.min(1, glint);
            return Math.max(0, Math.min(1, (magnification - 1.5f) / 4.5f));
        }

        public int reticleRgb() {
            return rgb(reticleColor, 0xFF3A2E);
        }

        public int coatingRgb() {
            return rgb(coating, 0x7A5CFF);
        }

        static int rgb(String hex, int fallback) {
            try {
                return hex != null && hex.length() == 7 && hex.charAt(0) == '#' ? Integer.parseInt(hex.substring(1), 16) : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /**
     * The reticle of a reflex sight: drawn in the optic's {@code glass} as light at infinity, so it sits on the sight
     * line wherever the eye is behind the window (no parallax) and is only seen through the glass.
     */
    public static final class Reflex {
        /** {@code dot} (red dot) or {@code holo} (holographic: a ring around a dot). */
        public String reticle = "dot";
        /** Diameter of the dot in MOA (game scale: a few pixels at 1080p). */
        public float dot = 12;
        /** Holographic ring: outer diameter and line width in MOA. */
        public float ring = 160;
        public float ringWidth = 9;
        /** Reticle colour {@code #RRGGBB} and brightness (1 = daylight). */
        public String color = "#FF2A1A";
        public float brightness = 1;
        /** Tint of the coated glass {@code #RRGGBB} and how much it shows (0–1). */
        public String coating = "#5A7DFF";
        public float tint = 0.12f;

        public boolean holographic() {
            return "holo".equals(reticle);
        }

        public int colorRgb() {
            return Scope.rgb(color, 0xFF2A1A);
        }

        public int coatingRgb() {
            return Scope.rgb(coating, 0x5A7DFF);
        }
    }

    /** A slot as a weapon declares it. */
    /**
     * The rounds an ammo attachment loads (slot {@code ammo}: normal, incendiary, armour-piercing, tracer, water). What a
     * struck target makes of them goes through {@code combat/RoundTraits} while the round hurts it.
     */
    public static final class Round {
        /** Multiplier on the durability armour (plates, vehicle hulls) loses to a hit (1 = an ordinary round). */
        public float armorDamage = 1;
        /** Seconds a struck target burns (0 = none). */
        public float fireSeconds = 0;
        /** Every round leaves a bright tracer that everyone sees ({@link #tracerColor}, {@link #tracerWidth}). */
        public boolean tracer = false;
        /** Tint of the shot's streak (#RRGGBB, empty = the gun's / skin's). */
        public String tracerColor = "";
        /** Width of the streak (1 = an ordinary shot's). */
        public float tracerWidth = 1;
        /** Water does not slow it and it never skips off a surface: in water it flies as in air ({@code combat/Ballistics}). */
        public boolean waterproof = false;
        /**
         * The impact modifier ({@code impacts.json} ammo profile, e.g. {@code incendiary}) applied over the caliber's own
         * profile; empty = none.
         */
        public String impact = "";

        public boolean hasTracerColor() {
            return tracerColor != null && tracerColor.length() == 7 && tracerColor.startsWith("#");
        }

        public int tracerRgb() {
            return hasTracerColor() ? Integer.parseInt(tracerColor.substring(1), 16) : 0xFFFFFF;
        }
    }

    public static final class SlotDef {
        /** Mount node in the weapon model where model attachments sit. */
        public String node = "";
        /** The option a fresh weapon has ({@link #NONE} = empty slot). */
        @SerializedName("default")
        public String defaultOption = NONE;
        /** Allowed options; null = every attachment of this slot kind (plus "none" when that is the default). */
        public List<String> options;
        /** Weapon nodes hidden while the slot holds anything but its default (e.g. the AK's wooden stock). */
        public List<String> hides = List.of();
        /** Display name of "none" in this slot (e.g. 木质枪托 for the AK's built-in stock); empty = 无. */
        public String noneName = "";
        /**
         * Slots this one takes over while it holds anything but its default: they fall back to theirs (a pistol's one
         * short rail holds a grip or a laser, not both). The last one chosen in the 工匠 wins.
         */
        public List<String> excludes = List.of();
    }

    /**
     * A cosmetic variant (asset spec §21): re-textures regions of the weapon and its attachments with LabPBR texture
     * sets and shader effects, and/or — the older way — sets per-material display colours and texture replacements
     * (materials: asset spec §6). Shared skins ({@code craft/skins.json}) say which weapons they join in {@code for}.
     */
    public static final class Skin {
        /** Region id meaning "every skinnable region the skin does not list". */
        public static final String ANY_REGION = "*";
        /** Region of parts no skin touches (glass, reticles, brass, charms). */
        public static final String NO_REGION = "none";

        public String id;
        public String name = "";
        public String rarity = "common";
        /** Weapon kinds ({@code gun}, {@code knife}), weapon ids or {@code *} a shared skin joins; empty for a weapon's own. */
        @SerializedName("for")
        public List<String> forWeapons = List.of();
        /** region id (or {@code *}) → how the skin re-textures it */
        public Map<String, Layer> regions = new LinkedHashMap<>();
        /** material name → display RGB (0..1) */
        public Map<String, float[]> tints = new LinkedHashMap<>();
        /** material name → texture path under {@code assets/blockland/} */
        public Map<String, String> textures = new LinkedHashMap<>();
        /**
         * Special skins (asset spec §30): a model of their own (GLB under {@code assets/blockland/}) following the weapon's
         * node contract (same nodes, mounts and clips), drawn instead of the weapon's; empty = the weapon's model.
         */
        public String model = "";
        /** Special skins: what the shots look and sound like (muzzle flash, tracer, fire sound); null = the weapon's. */
        @Nullable
        public SkinFx fx;
        /**
         * Special skins: an inspect of their own (asset spec §38) — its clip, what its events set off (particles, light,
         * a surge of the skin's shader effects) and how the skin's look changes over it; null = the weapon's inspect.
         */
        @Nullable
        public SkinInspect inspect;
        /**
         * Whether every player may use the skin (asset spec §30); otherwise it is an inventory item a player must own
         * ({@link SkinKeys}). Null = the default rule: special skins are owned items, all others are free.
         */
        @Nullable
        public Boolean free;

        /** A special skin: it changes the model, the shots or the inspect, not only the materials (the craft list says so). */
        public boolean isSpecial() {
            return fx != null || inspect != null || model != null && !model.isBlank();
        }

        /** Usable by everyone; the {@code default} skin always is. */
        public boolean isFree() {
            if ("default".equals(id)) return true;
            return free != null ? free : !isSpecial();
        }

        public boolean hasModel() {
            return model != null && !model.isBlank();
        }

        /** The layer for a region: its own, else the {@code *} layer; null for "none", "" and unlisted regions. */
        @Nullable
        public Layer layer(String region) {
            if (regions == null || region == null || region.isEmpty() || region.equals(NO_REGION)) return null;
            Layer own = regions.get(region);
            return own != null ? own : regions.get(ANY_REGION);
        }

        /** Whether any layer runs a shader effect (the craft screen marks such skins). */
        public boolean hasEffects() {
            if (regions == null) return false;
            for (Layer layer : regions.values()) if (layer != null && layer.hasEffect()) return true;
            return false;
        }

        /** Whether this shared skin joins a weapon of this kind and id. */
        public boolean isFor(Craftable.Kind kind, String weaponId) {
            if (forWeapons == null) return false;
            for (String target : forWeapons) {
                if (target == null) continue;
                if (target.equals("*") || target.equalsIgnoreCase(kind.name()) || target.equals(weaponId)) return true;
            }
            return false;
        }

        public boolean isEmpty() {
            return (tints == null || tints.isEmpty()) && (textures == null || textures.isEmpty()) && (regions == null || regions.isEmpty());
        }
    }

    /**
     * How a special skin changes the shots (asset spec §30). Client and server read the same data: the server only uses
     * {@link #fireSound} (everyone hears it), the rest is presentation — the shooter's viewmodel, and other players'
     * flashes and tracers (their clients read the skin from the shooter's held item).
     */
    public static final class SkinFx {
        /** Muzzle flash colour {@code #RRGGBB} (multiplies the flash textures); empty = the usual warm white. */
        public String flashColor = "";
        /** Front-on flash texture under {@code assets/blockland/} (spec §9 layout); empty = the shared one. */
        public String flashTexture = "";
        /** Side-on flash cone texture; empty = the shared one. */
        public String flashSideTexture = "";
        /** Size multiplier of the muzzle flash (first and third person). */
        public float flashScale = 1;
        /** Tracer colour {@code #RRGGBB}; empty = the usual pale yellow. */
        public String tracerColor = "";
        /** Width multiplier of the tracer. */
        public float tracerWidth = 1;
        /** Replaces the gun's fire sound ({@code blockland:} sound path); empty = keep it. A suppressor still wins. */
        public String fireSound = "";
        /** Colour of the hit marker's kill marks {@code #RRGGBB} while the skin is held (asset spec §38); empty = the player's. */
        public String hitColor = "";
        /** What a confirmed kill sets off on the shooter's weapon (asset spec §38); null = nothing of the skin's own. */
        @Nullable
        public SkinCue kill;
        /** The skin's own kill icon on the shooter's HUD at a confirmed kill (asset spec §38); null = none. */
        @Nullable
        public SkinKillIcon killIcon;

        public boolean hasHitColor() {
            return hitColor != null && !hitColor.isBlank();
        }

        public int hitRgb() {
            return Scope.rgb(hitColor, 0xFF3B3B);
        }

        public int flashRgb() {
            return Scope.rgb(flashColor, 0xFFE2A0);
        }

        public int tracerRgb() {
            return Scope.rgb(tracerColor, 0xFFF4D0);
        }

        public boolean hasFlashColor() {
            return flashColor != null && !flashColor.isBlank();
        }

        public boolean hasTracerColor() {
            return tracerColor != null && !tracerColor.isBlank();
        }
    }

    /**
     * A special skin's own inspect (asset spec §38). Times are seconds of the clip as authored. The skin's model (or the
     * weapon's) carries the clip and its events; the data says what the events set off and how the skin's look changes.
     */
    public static final class SkinInspect {
        /** Clip of the drawn model played instead of the weapon's inspect; empty or missing = the weapon's inspect. */
        public String clip = "";
        /**
         * The surge of the skin's shader effects over the clip, keys {@code [seconds, 0..1]} (linear between keys, 0
         * before the first and after the last): 0 is the skin at rest, 1 its effects at full blaze. The dynamic material.
         */
        public float[][] surge = new float[0][];
        /** Clip event name → what it sets off (besides its sound and the rig's shove, which every event has). */
        public Map<String, SkinCue> cues = new LinkedHashMap<>();
        /** Particle streams that run during part of the clip. */
        public List<SkinEmitter> emitters = List.of();
        /** A light that follows the surge (its intensity × the surge), or null. */
        @Nullable
        public SkinLight glow;

        /** The surge at a time of the clip. */
        public float surgeAt(float seconds) {
            if (surge == null || surge.length == 0) return 0;
            float[] first = surge[0];
            if (first == null || first.length < 2 || seconds <= first[0]) return 0;
            for (int i = 1; i < surge.length; i++) {
                float[] a = surge[i - 1], b = surge[i];
                if (b == null || b.length < 2 || a == null || a.length < 2) return 0;
                if (seconds <= b[0]) {
                    float u = b[0] > a[0] ? (seconds - a[0]) / (b[0] - a[0]) : 1;
                    return Math.max(0, Math.min(1, a[1] + (b[1] - a[1]) * u));
                }
            }
            return 0;
        }
    }

    /** What a clip event (or a kill) of a special skin sets off on the first-person weapon (asset spec §38). */
    public static final class SkinCue {
        /** Particle presets ({@code assets/blockland/viewmodel/particles.json}) burst at {@link #node}. */
        public List<String> particles = List.of();
        /** Node of the drawn model the particles and the light start from. */
        public String node = "gun";
        /** A dual weapon: also at the node's left-gun twin. */
        public boolean twin = true;
        /** A flash of light there, or null. */
        @Nullable
        public SkinLight light;
        /** A sound ({@code blockland:} sound event) besides the event's own; empty = none. */
        public String sound = "";
        /** A surge pulse added to the skin's effects (0..1), fading over {@link #surgeFade} seconds. */
        public float surge;
        public float surgeFade = 1;
        /** A shove of the rig and camera by an {@code impulses.json} entry (kills: clip events shove by their own name). */
        public String impulse = "";
        /** A punch of the field of view, percent (negative = narrower). */
        public float fov;
    }

    /**
     * A special skin's kill icon (asset spec §38): an artwork that burns in on the shooter's HUD at a confirmed kill,
     * glows and sheds embers, and burns away. Its textures come from {@code tools/assets/gen_kill_icons.py}.
     */
    public static final class SkinKillIcon {
        /** Texture base under {@code assets/blockland/}: {@code <base>.png}, {@code <base>_glow.png}, {@code <base>_burn.png}. */
        public String texture = "";
        /** Size on screen, GUI pixels. */
        public float size = 64;
        /** Seconds on screen, the burn-in and the burn-away included. */
        public float seconds = 2.2f;
        /** Colour of the glow and the embers {@code #RRGGBB}. */
        public String color = "#FF3A14";
        /** Embers thrown off as it burns in and away (and a trickle while it glows). */
        public int embers = 24;

        public int rgb() {
            return Scope.rgb(color, 0xFF3A14);
        }
    }

    /** A particle stream of a special skin's inspect: a preset at a node between two times of the clip. */
    public static final class SkinEmitter {
        public String particles = "";
        public String node = "gun";
        public boolean twin = true;
        public float from;
        public float to;
        /** Seconds the stream fades in and out over. */
        public float fade = 0.2f;
    }

    /** A light of a special skin's effects (rendering spec §5). */
    public static final class SkinLight {
        public String color = "#FFFFFF";
        /** Brightness (1 ≈ a torch at its own block). */
        public float intensity = 1;
        /** Reach in blocks. */
        public float radius = 4;
        /** How long a flash lasts (cues); a glow lasts as long as the surge. */
        public float seconds = 0.3f;
        /** A glow's node (the light sits there). */
        public String node = "gun";

        public int rgb() {
            return Scope.rgb(color, 0xFFFFFF);
        }
    }

    /** How a skin re-textures one region (asset spec §21). */
    public static final class Layer {
        /** LabPBR texture set under {@code assets/blockland/} without {@code .png} (§20); empty keeps the material's. */
        public String pbr = "";
        /** Tiling of the texture set; 0 keeps the material's. */
        public float uvScale = 0;
        /** RGB multiplier on the albedo, or null. */
        public float[] tint;
        /** {@code iridescent}, {@code flow}, {@code galaxy}, {@code circuit}, {@code inferno}, or empty. */
        public String effect = "";
        /** Colour of the effect's light {@code #RRGGBB}, or empty for white. */
        public String effectColor = "";
        /** The effect's four numbers (see the spec table); missing ones take the effect's defaults. */
        public float[] params;

        public boolean hasEffect() {
            return effect != null && !effect.isBlank() && !effect.equalsIgnoreCase("none");
        }
    }
}
