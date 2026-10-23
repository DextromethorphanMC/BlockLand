package com.blockland.client.gun;

/**
 * The sight of a bolt-action gun between shots (asset spec §23): it stays up for a moment so the shot is seen through
 * it, comes down while the fire clip works the bolt, and may go up again in time to be steady when the next round can
 * be fired. Pure timing, checked offline; the fire rate itself stays with {@code rpm} (client and server).
 */
final class BoltCycle {
    /** The sight stays up this long after the shot (s). */
    static final float SHOW_SHOT = 0.12f;
    /** The sight starts rising this share of the aim time before the next shot is possible (1 = fully up by then). */
    static final float RAISE_LEAD = 1.0f;

    private BoltCycle() {
    }

    /**
     * Whether the sight must be down {@code sinceShot} seconds after a shot.
     *
     * @param fireInterval seconds between shots (60 / rpm)
     * @param adsTime      seconds to raise the sight
     */
    static boolean sightDown(float sinceShot, float fireInterval, float adsTime) {
        float raise = Math.max(SHOW_SHOT, fireInterval - adsTime * RAISE_LEAD);
        return sinceShot >= SHOW_SHOT && sinceShot < raise;
    }
}
