package com.blockland.gun;

import java.util.Optional;

/**
 * A gun as configured on one item: the definition combined with its fitted magazine, sight, laser and skin.
 * Every effective number (capacity, ADS time, spread, damage) is derived here so client and server agree.
 */
public record GunStats(GunData.Gun gun, GunData.Ammo ammo, GunData.Magazine magazine, GunData.Sight sight,
                       GunData.Laser laser, GunData.Skin skin) {

    /** Resolves a state, replacing anything that no longer fits the gun with its defaults. */
    public static Optional<GunStats> of(String gunId, GunState state) {
        GunCatalog catalog = GunCatalog.get();
        return catalog.gun(gunId).flatMap(gun -> {
            GunData.Ammo ammo = catalog.ammo(gun.ammo);
            String magazineId = catalog.fits(gun, state.magazine()) ? state.magazine() : gun.defaultMagazine();
            GunData.Magazine magazine = catalog.magazine(magazineId);
            GunData.Sight sight = catalog.sight(gun.sights.contains(state.sight()) ? state.sight() : gun.defaultSight());
            GunData.Laser laser = catalog.laser(gun.lasers.contains(state.laser()) ? state.laser() : gun.defaultLaser());
            GunData.Skin skin = gun.skins.getOrDefault(state.skin(), gun.skins.get("default"));
            if (ammo == null || magazine == null || sight == null || laser == null) return Optional.empty();
            return Optional.of(new GunStats(gun, ammo, magazine, sight, laser, skin));
        });
    }

    public int capacity() {
        return magazine.capacity;
    }

    public float adsTime() {
        return gun.adsTime * sight.adsTimeScale * magazine.adsTimeScale;
    }

    public float reloadTime(boolean empty) {
        return (empty ? gun.reload.empty : gun.reload.tactical) * magazine.reloadTimeScale;
    }

    /** Cone half-angle (degrees) before movement and bloom. */
    public float baseSpread(boolean aiming) {
        return aiming ? gun.spread.ads : gun.spread.hip * laser.hipSpreadScale;
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

    /** Damage of one projectile at {@code distance} blocks, before the headshot multiplier. */
    public float damageAt(double distance) {
        GunData.Falloff f = gun.falloff;
        float scale = 1;
        if (distance > f.start) {
            double t = Math.min(1, (distance - f.start) / Math.max(0.001, f.end - f.start));
            scale = (float) (1 - t * (1 - f.min));
        }
        return ammo.damage * gun.damageScale * scale;
    }
}
