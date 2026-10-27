package com.blockland.gun;

import com.blockland.combat.Ballistics;
import com.blockland.combat.RoundTraits;
import com.blockland.craft.AttachmentCatalog;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.craft.CraftData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A gun as configured on one item: the definition combined with its fitted build (magazine, universal attachments,
 * skin). Every effective number (capacity, ADS time, spread, recoil, range, damage) is derived here so client and
 * server agree.
 *
 * @param build       the item's build with every slot filled in (invalid options replaced by defaults)
 * @param sight       the fitted optic (iron sights when nothing else is fitted)
 * @param laser       the fitted laser, or {@link #NO_LASER}
 * @param attachments every fitted attachment (their stat multipliers apply)
 */
public record GunStats(GunData.Gun gun, GunData.Ammo ammo, GunData.Magazine magazine, Build build, CraftData.Skin skin,
                       CraftData.Attachment sight, CraftData.Attachment laser, List<CraftData.Attachment> attachments) {
    /** "No laser" (no beam, no model). */
    public static final CraftData.Attachment NO_LASER = synthetic(CraftData.NONE, "laser", "无");
    /** Fallback when the attachment file has no {@code iron} entry. */
    private static final CraftData.Attachment IRON = synthetic("iron", "optic", "机械瞄具");

    private static CraftData.Attachment synthetic(String id, String slot, String name) {
        CraftData.Attachment attachment = new CraftData.Attachment();
        attachment.id = id;
        attachment.slot = slot;
        attachment.name = name;
        attachment.zoom = 1.12f;
        return attachment;
    }

    /** Resolves a state, replacing anything that no longer fits the gun with its defaults. */
    public static Optional<GunStats> of(String gunId, GunState state) {
        GunCatalog catalog = GunCatalog.get();
        return catalog.gun(gunId).flatMap(gun -> {
            GunData.Ammo ammo = catalog.ammo(gun.ammo);
            Build build = CraftCatalog.complete(gun, state.build());
            String magazineId = build.option(GunData.Gun.MAGAZINE_SLOT, "");
            GunData.Magazine magazine = catalog.magazine(catalog.fits(gun, magazineId) ? magazineId : gun.defaultMagazine());
            if (ammo == null || magazine == null) return Optional.empty();
            List<CraftData.Attachment> fitted = new ArrayList<>();
            CraftData.Attachment sight = null, laser = null;
            for (String option : build.slots().values()) {
                CraftData.Attachment attachment = AttachmentCatalog.get().get(option);
                if (attachment == null) continue;
                fitted.add(attachment);
                if ("optic".equals(attachment.slot)) sight = attachment;
                if ("laser".equals(attachment.slot)) laser = attachment;
            }
            if (sight == null) {
                CraftData.Attachment iron = AttachmentCatalog.get().get("iron");
                sight = iron != null ? iron : IRON;
            }
            CraftData.Skin skin = gun.skins.getOrDefault(build.skin(), gun.skins.get("default"));
            return Optional.of(new GunStats(gun, ammo, magazine, build, skin, sight, laser == null ? NO_LASER : laser, List.copyOf(fitted)));
        });
    }

    public int capacity() {
        return magazine.capacity;
    }

    public float adsTime() {
        float scale = magazine.adsTimeScale;
        for (CraftData.Attachment a : attachments) scale *= a.adsTimeScale;
        return gun.adsTime * scale;
    }

    /**
     * The reload clip for the fitted magazine: its own variant ({@link GunData.Magazine#clipSuffix}) when the model has
     * one ({@code has}), else the gun's.
     */
    public String reloadClip(boolean empty, java.util.function.Predicate<String> has) {
        String clip = empty ? gun.anims.reloadEmpty : gun.anims.reload;
        String own = magazine.clipSuffix == null || magazine.clipSuffix.isEmpty() ? null : clip + magazine.clipSuffix;
        return own != null && has.test(own) ? own : clip;
    }

    public float reloadTime(boolean empty) {
        return (empty ? gun.reload.empty : gun.reload.tactical) * magazine.reloadTimeScale;
    }

    /** Cone half-angle (degrees) before movement and bloom. */
    public float baseSpread(boolean aiming) {
        if (aiming) return gun.spread.ads;
        float scale = 1;
        for (CraftData.Attachment a : attachments) scale *= a.hipSpreadScale;
        return gun.spread.hip * scale;
    }

    /**
     * Current cone half-angle in degrees.
     *
     * @param aim    0 = hip, 1 = fully aimed (blended while raising the sight)
     * @param moving horizontal speed in blocks per tick
     * @param bloom  accumulated per-shot bloom in degrees
     */
    public float spread(float aim, double moving, boolean airborne, float bloom) {
        GunData.Spread s = gun.spread;
        float base = baseSpread(false) + (baseSpread(true) - baseSpread(false)) * aim;
        float movement = (float) Math.min(1, moving / 0.22) * s.move * (1 - 0.6f * aim);
        float air = airborne ? s.air * (1 - 0.5f * aim) : 0;
        return Math.min(s.max + base, base + movement + air + bloom);
    }

    /** Multiplier on camera recoil and the viewmodel kick (stocks, suppressors). */
    public float recoilScale() {
        float scale = 1;
        for (CraftData.Attachment a : attachments) scale *= a.recoilScale;
        return scale;
    }

    public float recoilVertical() {
        return gun.recoil.vertical * recoilScale();
    }

    public float recoilHorizontal() {
        return gun.recoil.horizontal * recoilScale();
    }

    public float kick() {
        return gun.recoil.kick * recoilScale();
    }

    private float rangeScale() {
        float scale = 1;
        for (CraftData.Attachment a : attachments) scale *= a.rangeScale;
        return scale;
    }

    /** How far a shot travels, in blocks. */
    public float range() {
        return gun.range * rangeScale();
    }

    /** Damage of one projectile at {@code distance} blocks, before the headshot multiplier. */
    public float damageAt(double distance) {
        GunData.Falloff f = gun.falloff;
        float rangeScale = rangeScale();
        float start = f.start * rangeScale, end = f.end * rangeScale;
        float scale = 1;
        if (distance > start) {
            double t = Math.min(1, (distance - start) / Math.max(0.001, end - start));
            scale = (float) (1 - t * (1 - f.min));
        }
        float damageScale = 1;
        for (CraftData.Attachment a : attachments) damageScale *= a.damageScale;
        return ammo.damage * gun.damageScale * damageScale * scale;
    }

    /** Quiet shots without a muzzle flash (a suppressor is fitted). */
    public boolean suppressed() {
        for (CraftData.Attachment a : attachments) if (a.suppressed) return true;
        return false;
    }

    /**
     * Fire sound path in the {@code blockland} namespace: an attachment's override (suppressor), a special skin's
     * (asset spec §30) or the gun's own.
     */
    public String fireSound() {
        for (CraftData.Attachment a : attachments) if (a.fireSound != null && !a.fireSound.isEmpty()) return a.fireSound;
        CraftData.SkinFx fx = skinFx();
        if (fx != null && fx.fireSound != null && !fx.fireSound.isBlank()) return fx.fireSound;
        return gun.sounds.fire;
    }

    /** What a special skin does to the shots (flash, tracer, sound), or null for an ordinary skin. */
    @Nullable
    public CraftData.SkinFx skinFx() {
        return skin == null ? null : skin.fx;
    }

    // ---- the rounds (ammo attachments, slot "ammo") ---------------------------------------------------

    /** The ammo attachment's slot id in builds. */
    public static final String AMMO_SLOT = "ammo";

    /** What the fitted ammo attachment's rounds do, or null for ordinary rounds (no ammo slot, or nothing special). */
    @Nullable
    public CraftData.Round round() {
        CraftData.Attachment ammoAttachment = fitted(AMMO_SLOT);
        return ammoAttachment == null ? null : ammoAttachment.round;
    }

    /** What a hit by these rounds carries into the target's hurt ({@code combat/RoundTraits}). */
    public RoundTraits.Traits traits() {
        CraftData.Attachment ammoAttachment = fitted(AMMO_SLOT);
        CraftData.Round round = ammoAttachment == null ? null : ammoAttachment.round;
        if (round == null) return RoundTraits.NONE;
        return new RoundTraits.Traits(ammoAttachment.id, round.armorDamage, round.fireSeconds);
    }

    /** How the rounds fare in fluids: the caliber's, or no slowing and no skipping in water for waterproof rounds. */
    public Ballistics.Medium medium() {
        Ballistics.Medium medium = ammo.medium();
        CraftData.Round round = round();
        return round != null && round.waterproof ? new Ballistics.Medium(0, medium.lavaRange(), 0) : medium;
    }

    /** The impact profile id ({@code impacts.json} ammo) of a hit: the caliber's, with the round's modifier ({@code rifle+incendiary}). */
    public String impactProfile() {
        CraftData.Round round = round();
        return round == null || round.impact == null || round.impact.isEmpty() ? ammo.id : ammo.id + "+" + round.impact;
    }

    /** The attachment in one slot, null when it is empty (or holds a gun-specific part). */
    @Nullable
    public CraftData.Attachment fitted(String slot) {
        return CraftCatalog.fitted(build, slot);
    }
}
