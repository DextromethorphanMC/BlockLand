package com.blockland.client.hud.minimap;

import java.util.Arrays;

/**
 * The minimap's columns (UI spec §3.4), without any game classes: a wrapping {@value #SIZE} × {@value #SIZE} cache where
 * world column (x, z) lives at texel (x & MASK, z & MASK); which column each texel holds now, whether it is sampled (or
 * its chunk was missing) and when. A pass visits the columns round the player in square rings, nearest first, and
 * samples what is new or due again — near columns often, far ones rarely — under per-tick limits of columns visited,
 * columns sampled and time. A texel taken over by a new column is cleared at once, so nothing from 512 blocks away
 * ever shows while it waits for its turn.
 */
final class MinimapGrid {
    static final int SIZE = 512, MASK = SIZE - 1;
    static final byte EMPTY = 0, DONE = 1, MISSING = 2;
    /** What a {@link Sampler} returns for a column whose chunk is not loaded (tried again later). */
    static final int NO_CHUNK = 0;
    /** Ticks before a missing chunk is looked for again. */
    private static final int RETRY_MISSING = 20;
    /** Ticks between passes when the last one found everything done (nothing is due sooner than this). */
    private static final int REST = 10;

    /** The colour of world column (x, z), ARGB, or {@link #NO_CHUNK}. */
    interface Sampler {
        int sample(int x, int z);
    }

    /** Writes texel (tx, tz) of the cache. */
    interface Painter {
        void paint(int tx, int tz, int argb);
    }

    private final int[] owner = new int[SIZE * SIZE];
    private final byte[] state = new byte[SIZE * SIZE];
    private final int[] stamp = new int[SIZE * SIZE];
    private int passX, passZ, ring, step, passStart;
    private boolean passing;
    private int tick;
    /** The last tick's work: columns visited, columns sampled, nanoseconds spent sampling. */
    int lastChecks, lastSamples;
    long lastNanos;

    MinimapGrid() {
        clear();
    }

    /** Forgets every column (another level, another layer): all are sampled again. */
    void clear() {
        Arrays.fill(owner, Integer.MIN_VALUE);
        Arrays.fill(state, EMPTY);
        passing = false;
        passStart = Integer.MIN_VALUE / 2;
    }

    /** Ticks before a sampled column at ring {@code distance} from the player is sampled again (blocks change). */
    static int refreshAge(int distance) {
        return distance <= 16 ? 40 : distance <= 64 ? 200 : 600;
    }

    private static int key(int x, int z) {
        return ((x >> 9) & 0xFFFF) << 16 | ((z >> 9) & 0xFFFF);
    }

    /**
     * One tick: the pass goes on from where it stopped, or starts again round the player once the player is 8 blocks
     * (an eighth of the window on a big map) from where it started, or a little while after it ended (standing still,
     * nothing is due sooner); it visits the columns within {@code half} of ({@code px}, {@code pz}). Visits and samples
     * stop at their limits and when {@code maxNanos} have gone by.
     */
    void tick(int px, int pz, int half, Sampler sampler, Painter painter, int maxChecks, int maxSamples, long maxNanos) {
        tick++;
        // a big map's pass is long: it keeps going while the player is not too far from where it began
        int lag = Math.max(8, half / 8);
        boolean moved = Math.abs(px - passX) > lag || Math.abs(pz - passZ) > lag;
        if (!passing && !moved && tick - passStart < REST) {
            lastChecks = 0;
            lastSamples = 0;
            lastNanos = 0;
            return;
        }
        if (!passing || moved) {
            passing = true;
            passStart = tick;
            passX = px;
            passZ = pz;
            ring = 0;
            step = 0;
        }
        int reach = half + lag;
        int checks = 0, samples = 0;
        long start = System.nanoTime();
        while (checks < maxChecks && samples < maxSamples) {
            if ((checks & 1023) == 1023 && System.nanoTime() - start > maxNanos) break;
            int count = ring == 0 ? 1 : 8 * ring;
            if (step >= count) {
                ring++;
                step = 0;
                if (ring > reach) {
                    passing = false;
                    break;
                }
                continue;
            }
            int dx, dz;
            if (ring == 0) {
                dx = 0;
                dz = 0;
            } else {
                int r = ring, k = step;
                if (k < 2 * r) {
                    dx = -r + k;
                    dz = -r;
                } else if (k < 4 * r) {
                    dx = r;
                    dz = -r + (k - 2 * r);
                } else if (k < 6 * r) {
                    dx = r - (k - 4 * r);
                    dz = r;
                } else {
                    dx = -r;
                    dz = r - (k - 6 * r);
                }
            }
            int x = passX + dx, z = passZ + dz;
            checks++;
            step++;
            if (Math.abs(x - px) > half || Math.abs(z - pz) > half) continue;
            int tx = x & MASK, tz = z & MASK, i = tx + tz * SIZE, key = key(x, z);
            if (owner[i] != key) {
                owner[i] = key;
                state[i] = EMPTY;
                painter.paint(tx, tz, 0);
            }
            int age = tick - stamp[i];
            boolean due = switch (state[i]) {
                case EMPTY -> true;
                case MISSING -> age >= RETRY_MISSING;
                // a column's own few ticks of delay keep a whole ring from coming due at once
                default -> age >= refreshAge(Math.max(Math.abs(x - px), Math.abs(z - pz))) + ((i * 0x9E3779B1) >>> 27);
            };
            if (!due) continue;
            if ((samples & 7) == 7 && System.nanoTime() - start > maxNanos) {
                step--;
                break;
            }
            int color = sampler.sample(x, z);
            samples++;
            stamp[i] = tick;
            if (color == NO_CHUNK) {
                state[i] = MISSING;
            } else {
                state[i] = DONE;
                painter.paint(tx, tz, color);
            }
        }
        lastChecks = checks;
        lastSamples = samples;
        lastNanos = System.nanoTime() - start;
    }
}
