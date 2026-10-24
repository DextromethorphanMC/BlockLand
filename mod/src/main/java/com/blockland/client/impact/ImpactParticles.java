package com.blockland.client.impact;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Every live piece of every bullet impact, in fixed pools that never allocate while playing (a pool full to its budget
 * hands out its oldest piece again): debris (chunks, drops, molten spatter, bubbles), dust clouds, sparks and fluid
 * splashes. Ticked with the client (20 Hz): ballistic flight with gravity, drag and wind, tumbling, the second collision
 * — a bounce off the block a piece runs into until it settles — dust shed by dusty chunks in flight and raised where they
 * land, and water drops falling back into water splashing again. Positions of the previous tick are kept so the renderer
 * can interpolate between ticks.
 */
final class ImpactParticles {
    /** Vanilla item gravity, blocks per tick². */
    static final float GRAVITY = 0.04f;
    /** Most pieces of each kind the ultra setting allows (the pools are this big; lower settings use part of them). */
    static final int CAPACITY = 5000;
    /** Dust clouds alive at once (each is one entry of the renderer's cloud buffer). */
    static final int CLOUDS = 128;
    static final int SPLASHES = 96;
    /** Fluid kinds of a drop or splash. */
    static final byte WATER = 1, LAVA = 2;

    /** A chunk, flake, splinter, shard, grain, leaf, droplet, bubble or a drop of a fluid. */
    static final class Debris {
        double x, y, z, px, py, pz;
        float vx, vy, vz;
        /** Tumble: three angles (radians) and their speeds per tick. */
        float a, b, c, pa, pb, pc, va, vb, vc;
        float size, gravity, drag, restitution, friction, wind;
        /** Block atlas rectangle and tint (0xRRGGBB). */
        float u0, v0, u1, v1;
        int rgb, light;
        byte shape;
        /** 0, or {@link #WATER} / {@link #LAVA}: a drop of that fluid, drawn shaded like it. */
        byte fluid;
        int age, life;
        boolean alive, resting, bubble, collide;
        /** Dust it sheds in flight (mass per puff, 0 = none) and raises where it lands, and the dust's colour. */
        float trail, landing;
        int dustRgb;
    }

    /**
     * One hit's dust: a cloud with a shared density field (its mass spread over a Gaussian of radius {@link #radius}),
     * drawn as a few overlapping lobes that each integrate the same field (rendering spec §12).
     */
    static final class Cloud {
        double x, y, z, px, py, pz;
        float vx, vy, vz;
        /** The surface it came off: the cloud spreads out along it as it ages. */
        float nx, ny, nz;
        /** The way it was thrown out of the hole (unit), and how far drawn out along it right after the hit (0 = round). */
        float ax, ay = 1, az, plume;
        float rBurst, rEnd, density, rise, settle, wind, seed;
        int rgb, light;
        int age, life;
        byte lobes;
        boolean alive, small;

        /** Radius at {@code ticks} (with partial tick): a fast burst, then a slow spread. */
        float radius(float ticks) {
            float s = ticks / 20f;
            float burst = rBurst * (1 - (float) Math.exp(-s / 0.07f));
            return burst + (rEnd - rBurst) * (float) Math.sqrt(Math.min(1, ticks / Math.max(1, life)));
        }

        /**
         * Optical density at the centre: densest in the burst right after the hit, diluted as it spreads (less than r⁻³:
         * the powder keeps coming off the settling chunks and the air it mixes into is dusty itself) and settling out
         * all along — most of it is gone by half its life, a thin haze is the last of it.
         */
        float density(float ticks) {
            float t = Mth.clamp(ticks / Math.max(1, life), 0, 1);
            float r = Math.max(radius(ticks), rBurst * 0.35f);
            float dilution = Math.min(1.25f, (float) Math.pow(rBurst / r, 1.8));
            float burst = 1 + 0.5f * (float) Math.exp(-ticks / 20f / 0.1f);
            return density * dilution * burst * (float) Math.pow(1 - t, 1.8) * Mth.clamp(ticks / 1.5f, 0.15f, 1);
        }

        /** How far the cloud is drawn out along its throw (1 = round): a jet out of the hole, rounding off as it slows. */
        float stretch(float ticks) {
            return 1 + plume * (float) Math.exp(-ticks / 20f / 0.2f);
        }

        /** How much lower the cloud hangs below its centre (0 = round): the heavy part of the powder sinks as it ages. */
        float sag(float ticks) {
            float t = Mth.clamp(ticks / Math.max(1, life), 0, 1);
            return t * (0.4f + 80 * settle);
        }
    }

    /** A fluid surface struck: the crown, column, rings and white water are drawn from its age alone. */
    static final class Splash {
        double x, y, z;
        float energy, foam, seed, sheet, column, ripple;
        int rgb, light;
        int age, life;
        byte kind;
        /** A drop falling back (no column, a small crown). */
        boolean secondary;
        boolean alive;
    }

    /** A hot streak or ember. */
    static final class Spark {
        double x, y, z, px, py, pz;
        float vx, vy, vz;
        float width, gravity;
        int rgb;
        int age, life, bounces;
        boolean alive;
    }

    final Debris[] debris = new Debris[CAPACITY];
    final Cloud[] clouds = new Cloud[CLOUDS];
    final Splash[] splashes = new Splash[SPLASHES];
    final Spark[] sparks = new Spark[CAPACITY];
    private int nextDebris, nextCloud, nextSplash, nextSpark;
    private int liveDebris, liveClouds, liveSplashes, liveSparks;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    /** Second collisions on (the setting): pieces bounce, otherwise they vanish where they touch a block. */
    private boolean bounce = true;
    /** Dust on (the setting): chunks shed trails and raise poofs. */
    private boolean dustOn = true;

    ImpactParticles() {
        for (int i = 0; i < CAPACITY; i++) {
            debris[i] = new Debris();
            sparks[i] = new Spark();
        }
        for (int i = 0; i < CLOUDS; i++) clouds[i] = new Cloud();
        for (int i = 0; i < SPLASHES; i++) splashes[i] = new Splash();
    }

    // ---- handing out pieces ---------------------------------------------------------------------------
    // a piece counts as live at once: the pool must not look empty before its first tick, or it is never ticked nor drawn

    Debris debris(int budget) {
        if (nextDebris >= Math.min(budget, CAPACITY)) nextDebris = 0;
        Debris d = debris[nextDebris++];
        if (!d.alive) liveDebris++;
        d.alive = true;
        d.resting = false;
        d.bubble = false;
        d.fluid = 0;
        d.trail = 0;
        d.landing = 0;
        d.age = 0;
        return d;
    }

    /** A cloud: the oldest gives way when all are taken. */
    Cloud cloud() {
        if (nextCloud >= CLOUDS) nextCloud = 0;
        Cloud c = clouds[nextCloud++];
        if (!c.alive) liveClouds++;
        c.alive = true;
        c.small = false;
        c.age = 0;
        return c;
    }

    /** A small cloud (a trail puff, a landing poof) only where one is free: it never pushes out a hit's own cloud. */
    Cloud smallCloud() {
        for (int i = 0; i < CLOUDS; i++) {
            int k = (nextCloud + i) % CLOUDS;
            if (!clouds[k].alive) {
                Cloud c = clouds[k];
                liveClouds++;
                c.alive = true;
                c.small = true;
                c.age = 0;
                return c;
            }
        }
        return null;
    }

    Splash splash() {
        if (nextSplash >= SPLASHES) nextSplash = 0;
        Splash s = splashes[nextSplash++];
        if (!s.alive) liveSplashes++;
        s.alive = true;
        s.secondary = false;
        s.age = 0;
        return s;
    }

    Spark spark(int budget) {
        if (nextSpark >= Math.min(budget, CAPACITY)) nextSpark = 0;
        Spark s = sparks[nextSpark++];
        if (!s.alive) liveSparks++;
        s.alive = true;
        s.age = 0;
        return s;
    }

    boolean isEmpty() {
        return liveDebris == 0 && liveClouds == 0 && liveSplashes == 0 && liveSparks == 0;
    }

    int live() {
        return liveDebris + liveClouds + liveSplashes + liveSparks;
    }

    void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            debris[i].alive = false;
            sparks[i].alive = false;
        }
        for (Cloud c : clouds) c.alive = false;
        for (Splash s : splashes) s.alive = false;
        liveDebris = liveClouds = liveSplashes = liveSparks = 0;
    }

    // ---- ticking ------------------------------------------------------------------------------------

    /** One client tick; {@code windX/windZ} blocks per tick. */
    void tick(ClientLevel level, float windX, float windZ, boolean secondCollisions, boolean dust) {
        bounce = secondCollisions;
        dustOn = dust;
        liveDebris = 0;
        for (Debris d : debris) {
            if (!d.alive) continue;
            tickDebris(level, d, windX, windZ);
            if (d.alive) liveDebris++;
        }
        liveClouds = 0;
        for (Cloud c : clouds) {
            if (!c.alive) continue;
            tickCloud(level, c, windX, windZ);
            if (c.alive) liveClouds++;
        }
        liveSplashes = 0;
        for (Splash s : splashes) {
            if (!s.alive) continue;
            if (++s.age >= s.life) s.alive = false;
            else liveSplashes++;
        }
        liveSparks = 0;
        for (Spark s : sparks) {
            if (!s.alive) continue;
            tickSpark(level, s);
            if (s.alive) liveSparks++;
        }
    }

    private void tickDebris(ClientLevel level, Debris d, float windX, float windZ) {
        d.px = d.x;
        d.py = d.y;
        d.pz = d.z;
        d.pa = d.a;
        d.pb = d.b;
        d.pc = d.c;
        if (++d.age >= d.life) {
            d.alive = false;
            return;
        }
        if (d.resting) {
            if (d.age % 10 == 0 && !solid(level, d.x, d.y - d.size - 0.02, d.z)) d.resting = false;
            else return;
        }
        boolean wet = inWater(level, d.x, d.y, d.z);
        if (d.fluid == WATER && d.vy < 0 && d.age > 1) {
            // a drop falling back below the water's surface: it splashes again, smaller
            double surface = waterSurface(level, d.x, d.y, d.z);
            if (!Double.isNaN(surface) && d.y <= surface) {
                splashBack(d, surface);
                d.alive = false;
                return;
            }
        }
        if (d.fluid == LAVA && d.age > 2 && inLava(level, d.x, d.y, d.z)) {
            // molten spatter falling back into the pool is one with it again
            d.alive = false;
            return;
        }
        if (d.bubble) {
            // air rises through water and is gone at the surface
            if (!wet) {
                d.alive = false;
                return;
            }
            d.vy += (0.035f - d.vy) * 0.2f;
            d.vx *= 0.85f;
            d.vz *= 0.85f;
        } else {
            float drag = wet ? Math.max(d.drag, ImpactCatalog.data().environment.waterDrag) : d.drag;
            d.vy -= GRAVITY * d.gravity * (wet ? 0.25f : 1);
            d.vx = (d.vx + (windX - d.vx) * d.wind * 0.08f) * (1 - drag);
            d.vz = (d.vz + (windZ - d.vz) * d.wind * 0.08f) * (1 - drag);
            d.vy *= 1 - drag;
        }
        d.a += d.va;
        d.b += d.vb;
        d.c += d.vc;
        // a dusty chunk in flight leaves a streak of powder behind it
        if (dustOn && d.trail > 0 && d.age % 3 == 0 && d.vx * d.vx + d.vy * d.vy + d.vz * d.vz > 0.004f) {
            puff(d.x, d.y, d.z, d.trail, 0.05f, 0.14f, 10 + d.age % 7, d.dustRgb, d.light, d.age * 0.37f + d.size);
        }
        move(level, d);
    }

    /** Moves a piece one tick, axis by axis, bouncing off (or dying at) the first block in the way. */
    private void move(ClientLevel level, Debris d) {
        double nx = d.x + d.vx, ny = d.y + d.vy, nz = d.z + d.vz;
        if (!d.collide || d.bubble) {
            d.x = nx;
            d.y = ny;
            d.z = nz;
            return;
        }
        boolean hit = false;
        if (solid(level, d.x, ny, d.z)) {
            hit = true;
            boolean floor = d.vy < 0;
            double top = floor ? top(level, d.x, ny, d.z) : Double.NaN;
            float impact = Math.abs(d.vy);
            if (floor && d.landing > 0 && dustOn && impact > 0.06f) {
                // where a powdery chunk lands it raises a little poof
                puff(d.x, Double.isNaN(top) ? d.y : top + 0.04, d.z, d.landing * Math.min(1.5f, impact * 6), 0.06f, 0.2f,
                        16 + d.age % 9, d.dustRgb, d.light, d.size * 31 + d.age);
                d.landing *= 0.3f;
            }
            if (floor && d.fluid == WATER) {
                // water on dry ground: gone into it
                d.alive = false;
                return;
            }
            d.vy = -d.vy * d.restitution;
            d.vx *= d.friction;
            d.vz *= d.friction;
            d.va *= 0.5f;
            d.vb *= 0.5f;
            d.vc *= 0.5f;
            // too slow to jump again: it lies where it fell, flat side down
            if (floor && Math.abs(d.vy) < 0.04f && d.vx * d.vx + d.vz * d.vz < 0.002f) {
                d.resting = true;
                // on the surface itself (the step that hit it began a little above), thin side down
                if (!Double.isNaN(top)) d.y = d.py = top + d.size * 0.25;
                d.vx = d.vy = d.vz = 0;
                d.b = 0;
                d.c = 0;
                d.pb = d.pc = 0;
                d.light = light(level, d.x, d.y + 0.1, d.z);
                return;
            }
        } else {
            d.y = ny;
        }
        if (solid(level, nx, d.y, d.z)) {
            hit = true;
            d.vx = -d.vx * d.restitution;
            d.vz *= d.friction;
        } else {
            d.x = nx;
        }
        if (solid(level, d.x, d.y, nz)) {
            hit = true;
            d.vz = -d.vz * d.restitution;
            d.vx *= d.friction;
        } else {
            d.z = nz;
        }
        if (hit) {
            if (!bounce) d.alive = false;
            d.light = light(level, d.x, d.y, d.z);
        } else if (d.age % 8 == 0) {
            d.light = light(level, d.x, d.y, d.z);
        }
    }

    /** A small cloud (trail, landing poof) where one is free. */
    private void puff(double x, double y, double z, float mass, float rBurst, float rEnd, int life, int rgb, int light, float seed) {
        Cloud c = smallCloud();
        if (c == null) return;
        c.x = c.px = x;
        c.y = c.py = y;
        c.z = c.pz = z;
        c.vx = c.vy = c.vz = 0;
        c.nx = 0;
        c.ny = 1;
        c.nz = 0;
        c.ax = 0;
        c.ay = 1;
        c.az = 0;
        c.plume = 0;
        float k = (float) Math.cbrt(Math.max(0.02f, mass));
        c.rBurst = rBurst * (0.6f + 0.4f * k);
        c.rEnd = rEnd * (0.6f + 0.4f * k);
        c.density = 1.6f * Math.min(1.5f, k);
        c.rise = 0.006f;
        c.settle = 0.002f;
        c.wind = 0.7f;
        c.seed = seed;
        c.rgb = rgb;
        c.light = light;
        c.life = life;
        c.lobes = 1;
    }

    /** The height of the water surface in the block at the point, or NaN when there is no water. */
    private double waterSurface(ClientLevel level, double x, double y, double z) {
        probe.set(x, y, z);
        FluidState fluid = level.getFluidState(probe);
        if (!fluid.is(FluidTags.WATER)) return Double.NaN;
        return probe.getY() + fluid.getHeight(level, probe);
    }

    /** A water drop went in at {@code surface}: a small secondary splash where it did. */
    private void splashBack(Debris d, double surface) {
        // it came from above the surface (not one already under water)
        if (d.py < surface - 0.05) return;
        float speed = (float) Math.sqrt(d.vx * d.vx + d.vy * d.vy + d.vz * d.vz);
        Splash s = splash();
        s.x = d.x;
        s.y = surface;
        s.z = d.z;
        s.kind = WATER;
        s.secondary = true;
        s.energy = Mth.clamp(speed * 2.2f * (d.size / 0.03f), 0.12f, 0.45f);
        s.foam = 0;
        s.sheet = 0.24f;
        s.column = 0;
        s.ripple = 0.7f;
        s.seed = (float) (d.x * 3.1 + d.z * 7.7);
        s.rgb = d.rgb;
        s.light = d.light;
        s.life = 16;
    }

    private void tickCloud(ClientLevel level, Cloud c, float windX, float windZ) {
        c.px = c.x;
        c.py = c.y;
        c.pz = c.z;
        if (++c.age >= c.life) {
            c.alive = false;
            return;
        }
        // the burst slows in a few ticks; then the light part rises, the heavy part sinks, and the wind carries it
        float t = c.age / (float) c.life;
        c.vx = (c.vx + (windX - c.vx) * c.wind * 0.05f) * 0.82f;
        c.vz = (c.vz + (windZ - c.vz) * c.wind * 0.05f) * 0.82f;
        c.vy = (c.vy + (c.rise * (1 - t) - c.settle * t - c.vy) * 0.06f) * 0.84f;
        double nx = c.x + c.vx, ny = c.y + c.vy, nz = c.z + c.vz;
        // the cloud slides along walls and floors instead of going through them
        if (!solid(level, nx, c.y, c.z)) c.x = nx;
        else c.vx = 0;
        if (!solid(level, c.x, ny, c.z)) c.y = ny;
        else c.vy = 0;
        if (!solid(level, c.x, c.y, nz)) c.z = nz;
        else c.vz = 0;
        // eased towards the light around the cloud every tick: a jump (or a dark sample) would flash the whole cloud
        c.light = ease(c.light, light(level, c.x, c.y, c.z), 0.2f);
    }

    private void tickSpark(ClientLevel level, Spark s) {
        s.px = s.x;
        s.py = s.y;
        s.pz = s.z;
        if (++s.age >= s.life) {
            s.alive = false;
            return;
        }
        s.vy -= GRAVITY * s.gravity;
        s.vx *= 0.96f;
        s.vy *= 0.96f;
        s.vz *= 0.96f;
        double nx = s.x + s.vx, ny = s.y + s.vy, nz = s.z + s.vz;
        if (solid(level, nx, ny, nz)) {
            if (s.bounces <= 0 || !bounce) {
                s.alive = false;
                return;
            }
            s.bounces--;
            // a glancing bounce: off the surface it struck, losing most of its speed
            if (solid(level, s.x, ny, s.z)) s.vy = -s.vy * 0.35f;
            if (solid(level, nx, s.y, s.z)) s.vx = -s.vx * 0.35f;
            if (solid(level, s.x, s.y, nz)) s.vz = -s.vz * 0.35f;
            s.vx *= 0.7f;
            s.vz *= 0.7f;
            return;
        }
        s.x = nx;
        s.y = ny;
        s.z = nz;
    }

    // ---- the world ----------------------------------------------------------------------------------

    /** Whether the point lies inside a block's collision shape. */
    boolean solid(ClientLevel level, double x, double y, double z) {
        probe.set(x, y, z);
        BlockState state = level.getBlockState(probe);
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(level, probe);
        if (shape.isEmpty()) return false;
        double lx = x - probe.getX(), ly = y - probe.getY(), lz = z - probe.getZ();
        List<AABB> boxes = shape.toAabbs();
        for (AABB box : boxes) {
            if (lx >= box.minX && lx <= box.maxX && ly >= box.minY && ly <= box.maxY && lz >= box.minZ && lz <= box.maxZ) return true;
        }
        return false;
    }

    /** The top of the collision box under (x, z) that contains the point, or NaN. */
    private double top(ClientLevel level, double x, double y, double z) {
        probe.set(x, y, z);
        BlockState state = level.getBlockState(probe);
        VoxelShape shape = state.getCollisionShape(level, probe);
        double lx = x - probe.getX(), lz = z - probe.getZ(), best = Double.NaN;
        for (AABB box : shape.toAabbs()) {
            if (lx >= box.minX && lx <= box.maxX && lz >= box.minZ && lz <= box.maxZ && (Double.isNaN(best) || box.maxY > best)) best = box.maxY;
        }
        return Double.isNaN(best) ? best : probe.getY() + best;
    }

    boolean inWater(ClientLevel level, double x, double y, double z) {
        probe.set(x, y, z);
        return level.getFluidState(probe).is(FluidTags.WATER);
    }

    boolean inLava(ClientLevel level, double x, double y, double z) {
        probe.set(x, y, z);
        return level.getFluidState(probe).is(FluidTags.LAVA);
    }

    /**
     * The packed light at a point, the brightest of its block and the six around it per channel: a point on the face it
     * came off (or a lobe drifting into a wall) would otherwise read the inside of a solid block — no light at all — and
     * the dust would turn into a dark, flickering smudge.
     */
    int light(ClientLevel level, double x, double y, double z) {
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        int block = 0, sky = 0;
        for (int i = 0; i < 7; i++) {
            Direction side = i == 0 ? null : Direction.values()[i - 1];
            probe.set(bx, by, bz);
            if (side != null) probe.move(side);
            int packed = LevelRenderer.getLightColor(level, probe);
            block = Math.max(block, packed & 0xFFFF);
            sky = Math.max(sky, packed >>> 16);
        }
        return block | sky << 16;
    }

    /** Moves packed light {@code from} towards {@code to} by {@code k} per channel. */
    static int ease(int from, int to, float k) {
        int block = Math.round(Mth.lerp(k, from & 0xFFFF, to & 0xFFFF));
        int sky = Math.round(Mth.lerp(k, from >>> 16, to >>> 16));
        return block | sky << 16;
    }

    /** 0..1 of a piece's life left, smooth at the end ({@code fadeShare} of the life fades). */
    static float fade(int age, int life, float partialTick, float fadeShare) {
        float t = (age + partialTick) / Math.max(1, life);
        return Mth.clamp((1 - t) / Math.max(0.01f, fadeShare), 0, 1);
    }
}
