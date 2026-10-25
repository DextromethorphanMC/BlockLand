package com.blockland.client.impact;

import com.blockland.client.render.BlockFaces;
import com.blockland.client.render.PlantWind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every live piece of every bullet impact, in fixed pools that never allocate while playing (a pool full to its budget
 * hands out its oldest piece again): debris (chunks, drops, molten spatter, bubbles), dust clouds, sparks and fluid
 * splashes. Ticked with the client (20 Hz): ballistic flight with gravity, drag and wind, tumbling, the second collision
 * — a bounce off the block a piece runs into until it settles — dust shed by dusty chunks in flight and raised where they
 * land, and the fluids (rendering spec §12, fluid physics): a chunk falling into water splashes and then sinks at its
 * terminal speed there or comes back up and floats (its density), carried by the current; into lava it burns up; sparks
 * die in water; bubbles rise at their size's speed, wobbling, and pop at the surface. Blood: drops stick where they land
 * — floors, walls, ceilings — as splats drawn out along the way they struck, run down walls, drip from ceilings, dry
 * and wash out in the rain; drops that fall into water spread into a plume, and plumes (clouds in water) drift with
 * the current, swirl and sink slowly under the surface. Positions of the previous tick are kept so the renderer can
 * interpolate between ticks.
 */
final class ImpactParticles {
    /** Vanilla item gravity, blocks per tick². */
    static final float GRAVITY = 0.04f;
    /** Most pieces of each kind the ultra setting allows (the pools are this big; lower settings use part of them). */
    static final int CAPACITY = 5000;
    /** Dust clouds alive at once (each is one entry of the renderer's cloud buffer). */
    static final int CLOUDS = 128;
    static final int SPLASHES = 96;
    /** Fluid kinds of a drop or splash; also what a point is in ({@link #medium}, 0 = air). */
    static final byte WATER = 1, LAVA = 2, BLOOD = 3;
    /** At most this many face rectangles cut a stain. */
    static final int MAX_CLIPS = 12;
    /**
     * What landed blood is (rendering spec §19): a splat (a drop's), a pool (drops run together on a floor), a stain on a
     * plant (on its blades, moving with it).
     */
    static final byte SPLAT = 0, POOL = 1, PLANT = 2;
    /** Bodies a tick's flying blood is tested against, at most. */
    private static final int MAX_BODIES = 24;
    private static final Direction[] DIRECTIONS = Direction.values();

    /** A chunk, flake, splinter, shard, grain, leaf, droplet, bubble or a drop of a fluid. */
    static final class Debris {
        double x, y, z, px, py, pz;
        float vx, vy, vz;
        /** Tumble: three angles (radians) and their speeds per tick. */
        float a, b, c, pa, pb, pc, va, vb, vc;
        float size, gravity, drag, restitution, friction, wind;
        /** Density relative to water (sinks above 1, floats below). */
        float density;
        /** Block atlas rectangle and tint (0xRRGGBB). */
        float u0, v0, u1, v1;
        int rgb, light;
        byte shape;
        /** 0, or {@link #WATER} / {@link #LAVA} / {@link #BLOOD}: a drop of that fluid, drawn shaded like it. */
        byte fluid;
        int age, life;
        boolean alive, resting, bubble, collide;
        /** Dust it sheds in flight (mass per puff, 0 = none) and raises where it lands, and the dust's colour. */
        float trail, landing;
        /** The dust's colour; for blood the colour of its plume in water. */
        int dustRgb;
        /** The medium it was in last tick (0 air, {@link #WATER}, {@link #LAVA}). */
        byte medium;
        /** Floating on a water surface (a light chunk come up). */
        boolean floating;
        /** The current where it is, blocks per tick (refreshed every few ticks). */
        float flowX, flowZ;
        /**
         * A landed drop (blood): the side of the block it lies on ({@link Direction} ordinal of the face's outward normal,
         * -1 = none), the splat's long axis on that face (unit), how far it is drawn out along it, its radius, when it
         * landed, how far it runs down a wall now and at most, ticks until a ceiling splat lets a drop fall, rain on it.
         */
        byte face = -1;
        float sx, sy, sz, stretch, splat, run, runMax;
        /**
         * The parts of its plane that block faces really cover round a landed drop (`BlockFaces.surface`: rectangles
         * s0, t0, s1, t1 relative to it, up to {@link #MAX_CLIPS}), so the splat and its run are cut at the faces' edges;
         * made when it lands, the array kept with the piece.
         */
        float[] clip;
        byte clips;
        int landed, drip;
        boolean washed;
        /**
         * Blood in flight (set by the spawner from its material): ticks its stain stays once it lands (0 = it vanishes
         * there), how readily it runs down a wall (0..1), how fast rain washes it out (× 1 + this), and the rain's
         * leftover ageing.
         */
        int stain;
        float runs, washRate, wash;
        /** Ticks a landed stain stays wet and glossy (it dries matte and darker over them). */
        int dry;
        /** Blood: its material's data (pools, plants, bodies), null for anything else. */
        ImpactData.Blood blood;
        /** Landed blood: {@link #SPLAT}, {@link #POOL} or {@link #PLANT}. */
        byte kind;
        /** A pool: the radius it creeps out to (blocks); how many drops ran into it; the sides it ran over (-s -t +s +t). */
        float grow;
        byte merged, overflowed;
        /**
         * Per rectangle of {@link #clip}: which of its edges (bits s0, t0, s1, t1) are the surface's real edges — not shared
         * with a neighbouring rectangle that carries the surface on, not where the search window cut it.
         */
        byte[] edges;
        /** Wind bits ({@link PlantWind}) of what it lies on — a leaf block, a plant — 0 still. */
        int windBits;
        /**
         * Blood on a plant: the plant's block, the plant quad's horizontal axis (unit, x z), and the block atlas uv under
         * the stain's middle with its change per block along that axis and up.
         */
        long plant;
        float pax, paz, atlasU, atlasV, dudA, dvdA, dudU, dvdU;
        /** The block it last looked at for a plant on its way (each block once). */
        long lastPlant;
        /** The body it came out of (its own blood does not stain it at once), -1 none. */
        int source;
        /** In {@link #stains} (landed blood other drops may run into). */
        boolean listed;
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
        /** A plume in water (blood): no wind, a slow swirl, kept under the water's surface at {@link #surface}. */
        boolean water;
        double surface;
        /** The water's current at a plume (blocks per tick). */
        float flowX, flowZ;

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
    /** Blood in water on (the setting): blood drops falling into water spread into plumes. */
    private boolean bloodInWater = true;
    /** Fluid physics on (the setting): splashing in, sinking and floating, currents, burning up, quenching, popping. */
    private boolean fluids = true;
    /** The budget debris handed out from inside the tick may use (a ceiling splat's drip). */
    private int budget = CAPACITY;
    /** Ticks since the pool was made: the stains' occasional checks run on it (their age jumps in the rain). */
    private int ticks;
    /** Landed blood on faces that drops landing near by may run into (pools). */
    private final List<Debris> stains = new ArrayList<>();
    /** The body the pieces handed out now come out of (set round a hit on a body), -1 none. */
    private int source = -1;
    /** What stains bodies flying blood strikes (the decal module, through Impacts), null = nothing does. */
    Impacts.BodyStains bodySink;
    /** Bodies near this tick's flying blood, and the box the last tick's flying blood was in. */
    private final List<Entity> bodies = new ArrayList<>();
    private double flyMinX, flyMinY, flyMinZ, flyMaxX, flyMaxY, flyMaxZ;
    private boolean flying, flyingNext;

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
        // the oldest gives way, but a stain (blood lying on a face) is passed over a few times: a burst of debris must
        // not wipe the walls clean
        Debris d = null;
        for (int tries = 0; tries < 8 && (d == null || d.alive && d.resting && (d.face >= 0 || d.kind == PLANT)); tries++) {
            if (nextDebris >= Math.min(budget, CAPACITY)) nextDebris = 0;
            d = debris[nextDebris++];
        }
        if (!d.alive) liveDebris++;
        d.alive = true;
        d.resting = false;
        d.bubble = false;
        d.fluid = 0;
        d.trail = 0;
        d.landing = 0;
        d.age = 0;
        d.density = 2.5f;
        d.medium = -1;
        d.floating = false;
        d.flowX = d.flowZ = 0;
        d.face = -1;
        d.stretch = 1;
        d.splat = 0;
        d.run = d.runMax = 0;
        d.landed = 0;
        d.drip = 0;
        d.washed = false;
        d.stain = 0;
        d.runs = 0;
        d.washRate = 0;
        d.wash = 0;
        d.dry = 120;
        d.blood = null;
        d.kind = SPLAT;
        d.grow = 0;
        d.merged = d.overflowed = 0;
        d.windBits = 0;
        d.lastPlant = Long.MIN_VALUE;
        d.source = source;
        return d;
    }

    /** The body the pieces handed out from now on come out of (-1: none); set round a hit on a body. */
    void source(int entityId) {
        source = entityId;
    }

    /** A cloud: the oldest gives way when all are taken. */
    Cloud cloud() {
        if (nextCloud >= CLOUDS) nextCloud = 0;
        Cloud c = clouds[nextCloud++];
        if (!c.alive) liveClouds++;
        c.alive = true;
        c.small = false;
        c.water = false;
        c.flowX = c.flowZ = 0;
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
                c.water = false;
                c.flowX = c.flowZ = 0;
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

    /** A small splash (a chunk falling in, a bubble popping) only where one is free: it never pushes out a round's. */
    Splash smallSplash() {
        for (int i = 0; i < SPLASHES; i++) {
            int k = (nextSplash + i) % SPLASHES;
            if (!splashes[k].alive) {
                Splash s = splashes[k];
                liveSplashes++;
                s.alive = true;
                s.secondary = true;
                s.age = 0;
                return s;
            }
        }
        return null;
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
    void tick(ClientLevel level, float windX, float windZ, ImpactSettings settings) {
        bounce = settings.secondCollisions;
        dustOn = settings.dust;
        bloodInWater = settings.bloodInWater;
        fluids = settings.fluidPhysics;
        budget = settings.qualityLevel().budget;
        ticks++;
        if (ticks % 20 == 0) pruneStains();
        findBodies(level);
        flyingNext = false;
        liveDebris = 0;
        for (Debris d : debris) {
            if (!d.alive) continue;
            tickDebris(level, d, windX, windZ);
            if (d.alive) liveDebris++;
        }
        flying = flyingNext;
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
            if (d.fluid == BLOOD && d.kind == PLANT) {
                tickPlantStain(level, d);
                return;
            }
            if (d.face >= 0) {
                tickStain(level, d);
                return;
            }
            if (d.age % 10 == 0 && !solid(level, d.x, d.y - d.size - 0.02, d.z)) d.resting = false;
            else return;
        }
        ImpactData.Environment env = ImpactCatalog.data().environment;
        byte was = d.medium;
        byte now = medium(level, d.x, d.y, d.z);
        d.medium = now;
        boolean entered = was == 0 && now != 0 && d.age > 1;
        if (d.fluid == BLOOD && now == WATER && d.age > 0) {
            // a drop of blood falling into water does not sink as a drop: it clouds out into the water
            if (bloodInWater) {
                double surface = waterSurface(level, d.x, d.y, d.z);
                plume(d.x, Double.isNaN(surface) ? d.y : Math.min(d.y, surface - 0.08), d.z, Double.isNaN(surface) ? d.y + 1 : surface,
                        0.06f * d.size / 0.02f, d.dustRgb, d.light, d.size * 53 + d.age);
            }
            d.alive = false;
            return;
        }
        if (d.fluid == WATER && now == WATER && d.vy >= 0 && d.age > 1) {
            // a drop of water in water is water
            d.alive = false;
            return;
        }
        if (d.fluid == WATER && d.vy < 0 && d.age > 1) {
            // a drop falling back below the water's surface: it splashes again, smaller
            double surface = waterSurface(level, d.x, d.y, d.z);
            if (!Double.isNaN(surface) && d.y <= surface) {
                splashBack(d, surface);
                d.alive = false;
                return;
            }
        }
        if (now == LAVA && d.age > 2) {
            // molten spatter falling back into the pool is one with it again; anything else burns up in it
            if (d.fluid != LAVA && fluids && !d.bubble) burn(level, d);
            d.alive = false;
            return;
        }
        if (d.bubble) {
            // air rises through water and is gone at the surface
            if (now != WATER) {
                if (fluids) pop(level, d);
                d.alive = false;
                return;
            }
            tickBubble(level, d, env);
        } else if (now == WATER) {
            if (entered && fluids) splashIn(level, d);
            tickInWater(level, d, env, was != WATER);
        } else {
            d.floating = false;
            d.vy -= GRAVITY * d.gravity;
            d.vx = (d.vx + (windX - d.vx) * d.wind * 0.08f) * (1 - d.drag);
            d.vz = (d.vz + (windZ - d.vz) * d.wind * 0.08f) * (1 - d.drag);
            d.vy *= 1 - d.drag;
        }
        d.a += d.va;
        d.b += d.vb;
        d.c += d.vc;
        // a dusty chunk in flight leaves a streak of powder behind it
        if (dustOn && now == 0 && d.trail > 0 && d.age % 3 == 0 && d.vx * d.vx + d.vy * d.vy + d.vz * d.vz > 0.004f) {
            puff(d.x, d.y, d.z, d.trail, 0.05f, 0.14f, 10 + d.age % 7, d.dustRgb, d.light, d.age * 0.37f + d.size);
        }
        if (d.fluid == BLOOD && now == 0 && d.blood != null) {
            // blood in the air: a body in its way, a plant it goes through
            flown(d);
            if (hitBody(d) || onPlant(level, d)) return;
        }
        if (d.floating) {
            d.x += d.vx;
            d.z += d.vz;
            if (solid(level, d.x, d.y, d.z)) {
                d.x = d.px;
                d.z = d.pz;
                d.vx = d.vz = 0;
            }
            return;
        }
        move(level, d);
    }

    /**
     * Under water (rendering spec §12, fluid physics): water is ~800 times denser than air, so a piece reaches its
     * terminal speed within a tick or two — a small one sooner — sinking the faster the denser it is, coming back up when
     * it is lighter than water and floating at the surface; the current carries it all the while. Without fluid physics a
     * piece only falls more slowly.
     */
    private void tickInWater(ClientLevel level, Debris d, ImpactData.Environment env, boolean fresh) {
        if (!fluids) {
            float drag = Math.max(d.drag, env.waterDrag);
            d.vy -= GRAVITY * d.gravity * 0.25f;
            d.vx *= 1 - drag;
            d.vz *= 1 - drag;
            d.vy *= 1 - drag;
            return;
        }
        // the smaller the piece, the more its drag holds it
        float drag = Mth.clamp(0.35f * Mth.sqrt(0.03f / Math.max(0.004f, d.size)), Math.max(d.drag, env.waterDrag), 0.7f);
        // net weight in water; a light piece rises more slowly than a heavy one sinks (it tilts and flutters up)
        float buoyant = 1 - 1 / Math.max(0.05f, d.density);
        if (buoyant < 0) buoyant *= 0.4f;
        if (fresh || d.age % 4 == 0) flow(level, d, env);
        d.vy = (d.vy - GRAVITY * Math.max(0.3f, d.gravity) * buoyant) * (1 - drag);
        // the water takes it along: its speed relaxes to the current's
        d.vx += (d.flowX - d.vx) * drag;
        d.vz += (d.flowZ - d.vz) * drag;
        // the tumble dies down in water
        d.va *= 0.85f;
        d.vb *= 0.85f;
        d.vc *= 0.85f;
        if (buoyant < 0) {
            // lighter than water: up to the surface, then afloat there, bobbing
            double surface = waterSurface(level, d.x, d.y, d.z);
            if (!Double.isNaN(surface) && d.y + d.vy >= surface - d.size * 0.3) {
                d.floating = true;
                // bobbing, but always just under the surface (above it, it would fall out of the water again)
                d.y = surface - Math.max(d.size * 0.3, 0.008) + Mth.sin(d.age * 0.25f + d.size * 300) * 0.006;
                d.vy = 0;
                d.va = d.vb = 0;
                d.vc *= 0.9f;
                d.b = d.pb = 0;
                if (d.age % 8 == 0) d.light = light(level, d.x, d.y + 0.2, d.z);
            }
        }
    }

    /** A bubble: it rises at its size's speed (bigger is faster), wobbling, carried by the current. */
    private void tickBubble(ClientLevel level, Debris d, ImpactData.Environment env) {
        float[] rise = env.bubbleRise == null || env.bubbleRise.length < 2 ? new float[]{0.22f, 0.45f} : env.bubbleRise;
        float k = Mth.clamp((d.size - 0.008f) / 0.035f, 0, 1);
        float target = Mth.lerp(k, rise[0], rise[1]) / 20;
        if (fluids && (d.age % 4 == 0 || d.age < 2)) flow(level, d, env);
        float wobble = fluids ? 0.0035f * (0.5f + k) : 0;
        float phase = d.age * (0.7f + 0.3f * k) + d.size * 500;
        d.vy += (target - d.vy) * 0.2f;
        d.vx = d.vx * 0.85f + (d.flowX * 0.15f) + Mth.sin(phase) * wobble;
        d.vz = d.vz * 0.85f + (d.flowZ * 0.15f) + Mth.cos(phase * 1.3f) * wobble;
    }

    /** The current at a piece in water (vanilla's flow vector, blocks per tick at the data's speed). */
    private void flow(ClientLevel level, Debris d, ImpactData.Environment env) {
        probe.set(d.x, d.y, d.z);
        FluidState fluid = level.getFluidState(probe);
        if (!fluid.is(FluidTags.WATER)) {
            d.flowX = d.flowZ = 0;
            return;
        }
        Vec3 flow = fluid.getFlow(level, probe);
        d.flowX = (float) flow.x * env.current / 20;
        d.flowZ = (float) flow.z * env.current / 20;
    }

    /** A chunk or drop fell into water from the air: a small splash and rings where it went in, if it was big enough. */
    private void splashIn(ClientLevel level, Debris d) {
        float speed = Mth.sqrt(d.vx * d.vx + d.vy * d.vy + d.vz * d.vz);
        double surface = waterSurface(level, d.x, d.y, d.z);
        if (!Double.isNaN(surface) && speed > 0.05f && d.size > 0.01f) {
            Splash s = smallSplash();
            if (s != null) {
                s.x = d.x;
                s.y = surface;
                s.z = d.z;
                s.kind = WATER;
                s.energy = Mth.clamp(speed * 2f * (d.size / 0.03f), 0.08f, 0.4f);
                s.foam = 0;
                s.sheet = 0.2f;
                s.column = 0;
                s.ripple = 0.6f;
                s.seed = (float) (d.x * 5.3 + d.z * 3.7);
                s.rgb = waterRgb(level, d);
                s.light = d.light;
                s.life = 14;
            }
        }
        // the water takes most of its speed at once
        d.vx *= 0.35f;
        d.vy *= 0.35f;
        d.vz *= 0.35f;
    }

    /** A bubble reached the surface: a big one leaves a faint ring where it burst. */
    private void pop(ClientLevel level, Debris d) {
        if (d.size < 0.02f) return;
        double surface = waterSurface(level, d.px, d.py, d.pz);
        if (Double.isNaN(surface)) return;
        Splash s = smallSplash();
        if (s == null) return;
        s.x = d.x;
        s.y = surface;
        s.z = d.z;
        s.kind = WATER;
        s.energy = 0.05f;
        s.foam = 0;
        s.sheet = 0;
        s.column = 0;
        s.ripple = 0.35f;
        s.seed = (float) (d.x * 7.1 + d.z * 2.3);
        s.rgb = waterRgb(level, d);
        s.light = d.light;
        s.life = 10;
    }

    /** A piece that fell into lava: a wisp of smoke and, from wood and leaves, a little flame. */
    private void burn(ClientLevel level, Debris d) {
        double top = d.y + 0.05;
        puff(d.x, top, d.z, 0.08f + d.size * 3, 0.05f, 0.22f, 22, 0x3a3634, LevelRenderer.getLightColor(level, probe.set(d.x, top + 0.3, d.z)),
                d.size * 41 + d.age);
        if (d.density < 1) {
            Spark s = spark(budget);
            s.x = s.px = d.x;
            s.y = s.py = top;
            s.z = s.pz = d.z;
            s.vx = (float) ((d.size * 97 % 1) - 0.5) * 0.02f;
            s.vy = 0.06f;
            s.vz = (float) ((d.size * 61 % 1) - 0.5) * 0.02f;
            s.gravity = -0.2f;
            s.width = 0.012f;
            s.life = 10;
            s.bounces = 0;
            s.rgb = 0xFF8A30;
        }
    }

    /** The biome's water colour at a piece (a drop's own colour when it is one). */
    private int waterRgb(ClientLevel level, Debris d) {
        if (d.fluid == WATER) return d.rgb;
        return BiomeColors.getAverageWaterColor(level, probe.set(d.x, d.y, d.z)) & 0xFFFFFF;
    }

    /** Moves a piece one tick, axis by axis, bouncing off (or dying at, or sticking to) the first block in the way. */
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
            boolean floor = d.vy < 0;
            if (d.fluid == BLOOD && bounce) {
                double plane = plane(level, d.x, ny, d.z, Direction.Axis.Y, floor);
                stick(level, d, floor ? Direction.UP : Direction.DOWN, Double.isNaN(plane) ? (floor ? Math.floor(ny) + 1 : Math.floor(ny + 1) - 1) : plane);
                return;
            }
            if (d.fluid == BLOOD) {
                // no second collisions: blood is gone where it lands
                d.alive = false;
                return;
            }
            hit = true;
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
            if (d.fluid == BLOOD && bounce) {
                boolean positive = d.vx > 0;
                double plane = plane(level, nx, d.y, d.z, Direction.Axis.X, !positive);
                stick(level, d, positive ? Direction.WEST : Direction.EAST, Double.isNaN(plane) ? (positive ? Math.floor(nx) : Math.floor(nx) + 1) : plane);
                return;
            }
            hit = true;
            d.vx = -d.vx * d.restitution;
            d.vz *= d.friction;
        } else {
            d.x = nx;
        }
        if (solid(level, d.x, d.y, nz)) {
            if (d.fluid == BLOOD && bounce) {
                boolean positive = d.vz > 0;
                double plane = plane(level, d.x, d.y, nz, Direction.Axis.Z, !positive);
                stick(level, d, positive ? Direction.NORTH : Direction.SOUTH, Double.isNaN(plane) ? (positive ? Math.floor(nz) : Math.floor(nz) + 1) : plane);
                return;
            }
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

    /**
     * A drop of blood lands on the side {@code face} of a block (its plane at {@code plane}): it stays there as a splat
     * (rendering spec §12, blood). The faster it struck, the wider it spreads; the more obliquely, the longer it is drawn
     * out along the way it was going (an ellipse whose length is about 1 / sin of the angle, its satellites ahead of
     * it). A large splat on a wall runs down it; one on a ceiling may let a drop fall. It lives the stain time from now.
     */
    private void stick(ClientLevel level, Debris d, Direction face, double plane) {
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
        float speed = Mth.sqrt(d.vx * d.vx + d.vy * d.vy + d.vz * d.vz);
        float normal = Math.abs(d.vx * nx + d.vy * ny + d.vz * nz);
        // along the face: the way it was sliding when it struck
        float tx = d.vx - nx * (d.vx * nx + d.vy * ny + d.vz * nz), ty = d.vy - ny * (d.vx * nx + d.vy * ny + d.vz * nz),
                tz = d.vz - nz * (d.vx * nx + d.vy * ny + d.vz * nz);
        float along = Mth.sqrt(tx * tx + ty * ty + tz * tz);
        if (along < 1e-5f) {
            // square on: any way along the face
            float h = d.size * 977 % 1 * Mth.TWO_PI;
            if (ny != 0) {
                tx = Mth.cos(h);
                ty = 0;
                tz = Mth.sin(h);
            } else {
                tx = nz * Mth.cos(h);
                ty = Mth.sin(h);
                tz = -nx * Mth.cos(h);
            }
            along = Mth.sqrt(tx * tx + ty * ty + tz * tz);
        }
        d.sx = tx / along;
        d.sy = ty / along;
        d.sz = tz / along;
        float sin = speed > 1e-5f ? normal / speed : 1;
        d.stretch = Mth.clamp(1 / Math.max(0.3f, sin), 1, 3.2f);
        // a drop spreads wider the harder it struck (1.4 to ~3 times its own radius at 5 blocks a second and more)
        d.splat = d.size * (1.4f + 1.8f * Math.min(1, speed * 4));
        // onto the face as drawn (a snow layer's top above its collision box, a fence post's top under its raised one); over
        // nothing visible it is gone
        int sa = BlockFaces.sAxis(face), ta = BlockFaces.tAxis(face);
        plane = BlockFaces.visiblePlane(level, face, plane, coordinate(d, sa), coordinate(d, ta), 0.6);
        if (Double.isNaN(plane)) {
            d.alive = false;
            return;
        }
        switch (face.getAxis()) {
            case X -> d.x = plane;
            case Y -> d.y = plane;
            case Z -> d.z = plane;
        }
        d.px = d.x;
        d.py = d.y;
        d.pz = d.z;
        d.vx = d.vy = d.vz = 0;
        if (d.stain <= 0) {
            // no stains (the setting): it is gone where it lands
            d.alive = false;
            return;
        }
        // into blood already lying there: it runs together with it (a pool grows)
        if (merge(level, d, face)) {
            d.alive = false;
            return;
        }
        d.resting = true;
        d.face = (byte) face.ordinal();
        d.landed = d.age;
        d.life = d.age + d.stain;
        d.drip = 0;
        float seed = (d.size * 7919 + d.age * 0.618f) % 1;
        if (face.getAxis() != Direction.Axis.Y && d.size > 0.011f && seed < d.runs) {
            // a wall: the larger drops gather and run down it
            d.runMax = wallBelow(level, d, face, d.splat * (3 + 9 * ((seed * 13.7f) % 1)) * Math.min(1.6f, d.size / 0.016f));
        } else if (face == Direction.DOWN && d.size > 0.012f && seed < 0.5f) {
            // a ceiling: a big drop hangs there and lets go a moment later
            d.drip = 12 + Math.round(seed * 50);
        }
        d.washed = rainAt(level, d, face);
        d.light = light(level, d.x + nx * 0.1, d.y + ny * 0.1, d.z + nz * 0.1);
        d.kind = SPLAT;
        d.grow = d.splat;
        d.windBits = leafWind(level, d, face);
        if (!clip(level, d)) {
            d.alive = false;
            return;
        }
        list(d);
    }

    /**
     * Works out which parts of its plane the faces round a stain cover, out to where the splat (also washed wider) and its
     * run can reach (rendering spec §12, blood); false when none hold it any more (the block went).
     */
    private static boolean clip(ClientLevel level, Debris d) {
        Direction face = DIRECTIONS[d.face];
        int a = face.getAxis().ordinal(), sa = BlockFaces.sAxis(face), ta = BlockFaces.tAxis(face);
        // the splat's quad reaches 1.35 × its radius (× 1.2 washed), along its long axis × its stretch; a pool as far as it
        // is creeping out to (the window must not cut it where the surface goes on)
        float radius = Math.max(d.splat, d.grow);
        float along = radius * 1.2f * 1.35f * d.stretch, across = radius * 1.2f * 1.35f;
        float ux = d.sx, uy = d.sy, uz = d.sz;
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
        float vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
        float reachS = Math.abs(component(ux, uy, uz, sa)) * along + Math.abs(component(vx, vy, vz, sa)) * across;
        float reachT = Math.abs(component(ux, uy, uz, ta)) * along + Math.abs(component(vx, vy, vz, ta)) * across;
        // a run goes down the wall (y is s on an x face, t on a z face)
        if (a != 1 && d.runMax > 0) {
            if (sa == 1) reachS = Math.max(reachS, d.runMax + 0.01f);
            else reachT = Math.max(reachT, d.runMax + 0.01f);
        }
        if (d.clip == null) d.clip = new float[MAX_CLIPS * 4];
        if (d.edges == null) d.edges = new byte[MAX_CLIPS];
        double plane = coordinate(d, a);
        d.clips = (byte) BlockFaces.surface(level, face, plane, coordinate(d, sa), coordinate(d, ta), reachS + 0.01f, reachT + 0.01f, d.clip);
        for (int r = 0; r < d.clips; r++) {
            int mask = 0;
            for (int e = 0; e < 4; e++) {
                if (exposed(d.clip, d.clips, r, e, (e & 1) == 0 ? reachS + 0.01f : reachT + 0.01f)) mask |= 1 << e;
            }
            d.edges[r] = (byte) mask;
        }
        return d.clips > 0;
    }

    /**
     * Whether edge {@code e} (s0, t0, s1, t1) of rectangle {@code r} is a real edge of the surface: not where the search
     * window ({@code reach}) cut it, not (for the most part) shared with a neighbouring rectangle that carries the surface
     * on (rendering spec §19: blood stops at real edges only).
     */
    private static boolean exposed(float[] clip, int count, int r, int e, float reach) {
        float v = clip[r * 4 + e];
        if (Math.abs(v) >= reach - 1e-4f) return false;
        boolean sEdge = (e & 1) == 0;
        float lo = clip[r * 4 + (sEdge ? 1 : 0)], hi = clip[r * 4 + (sEdge ? 3 : 2)];
        int opposite = (e + 2) % 4;
        float covered = 0;
        for (int q = 0; q < count; q++) {
            if (q == r || Math.abs(clip[q * 4 + opposite] - v) > 1e-3f) continue;
            float qlo = clip[q * 4 + (sEdge ? 1 : 0)], qhi = clip[q * 4 + (sEdge ? 3 : 2)];
            covered += Math.max(0, Math.min(hi, qhi) - Math.max(lo, qlo));
        }
        return covered < 0.9f * (hi - lo);
    }

    private static double coordinate(Debris d, int axis) {
        return axis == 0 ? d.x : axis == 1 ? d.y : d.z;
    }

    private static float component(float x, float y, float z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    /**
     * How far down the wall a run from the stain can go, at most {@code max}: while there is wall behind it and open air
     * in front (it stops at the top of a doorway, a window, a ledge).
     */
    private float wallBelow(ClientLevel level, Debris d, Direction face, float max) {
        double bx = d.x - face.getStepX() * 0.02, bz = d.z - face.getStepZ() * 0.02;
        double fx = d.x + face.getStepX() * 0.02, fz = d.z + face.getStepZ() * 0.02;
        for (float down = 0.05f; down < max; down += 0.05f) {
            double y = d.y - down;
            if (!solid(level, bx, y, bz) || solid(level, fx, y, fz)) return Math.max(0, down - 0.05f);
        }
        return max;
    }

    /** A splat lying on a face: it runs, drips, dries, washes out, and goes with the surface it lies on. */
    private void tickStain(ClientLevel level, Debris d) {
        Direction face = DIRECTIONS[d.face];
        if (d.run < d.runMax) d.run += (d.runMax - d.run) * (d.washed ? 0.08f : 0.05f) + 0.0005f;
        if (d.drip > 0 && --d.drip == 0) {
            // a hanging drop lets go
            int left = d.life - d.age;
            Debris drop = debris(budget);
            drop.x = drop.px = d.x;
            drop.y = drop.py = d.y - d.size * 1.2;
            drop.z = drop.pz = d.z;
            drop.vx = drop.vz = 0;
            drop.vy = -0.02f;
            drop.size = d.size * 0.8f;
            drop.shape = d.shape;
            drop.fluid = BLOOD;
            drop.gravity = 1;
            drop.drag = 0.012f;
            drop.restitution = 0;
            drop.friction = 0;
            drop.wind = 0.02f;
            drop.collide = true;
            drop.life = 200;
            drop.stain = Math.max(20, left);
            drop.runs = d.runs;
            drop.washRate = d.washRate;
            drop.dry = d.dry;
            drop.u0 = d.u0;
            drop.v0 = d.v0;
            drop.u1 = d.u1;
            drop.v1 = d.v1;
            drop.rgb = d.rgb;
            drop.light = d.light;
            drop.dustRgb = d.dustRgb;
            drop.blood = d.blood;
            drop.density = 1.06f;
            drop.a = drop.b = drop.c = drop.pa = drop.pb = drop.pc = 0;
            drop.va = drop.vb = drop.vc = 0;
            // a full pool may hand out this very stain: then it is the drop now
            if (drop == d) return;
        }
        // a pool creeps out to the size the drops that ran into it make
        if (d.splat < d.grow) {
            float spread = d.blood != null ? d.blood.poolSpread : 1.6f;
            d.splat = Math.min(d.grow, d.splat + (d.grow - d.splat) * 3f / Math.max(1, spread * 20) + 0.0003f);
        }
        int phase = ticks + d.landed;
        if (phase % 10 == 0) {
            // the faces round it changed (a block broken or placed): cut it anew; the block it lay on is gone: so is the stain
            if (!clip(level, d)) {
                d.alive = false;
                return;
            }
            if (d.kind == POOL && face == Direction.UP) overflow(level, d);
            if (phase % 40 == 0) {
                d.washed = rainAt(level, d, face);
                d.light = light(level, d.x + face.getStepX() * 0.1, d.y + face.getStepY() * 0.1, d.z + face.getStepZ() * 0.1);
            }
        }
        // rain washes a stain out faster: it ages × (1 + its wash rate)
        if (d.washed && d.washRate > 0) {
            d.wash += d.washRate;
            int extra = (int) d.wash;
            d.age += extra;
            d.wash -= extra;
        }
    }

    // ---- pools, plants, bodies (rendering spec §19) ----------------------------------------------------------------

    /**
     * A drop landing within reach of blood already lying on the same face runs into it: the stain grows by the drop's
     * area (at most its data's {@code poolMax} in radius), creeping out to it over a moment ({@link #tickStain}); on a floor it
     * rounds into a pool. Its middle shifts a little towards where the drop landed, it is wet again and lives on.
     */
    private boolean merge(ClientLevel level, Debris d, Direction face) {
        ImpactData.Blood b = d.blood;
        if (b == null || b.poolMerge <= 0) return false;
        int a = face.getAxis().ordinal();
        double plane = coordinate(d, a);
        boolean floor = face == Direction.UP;
        Debris best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Debris o : stains) {
            if (o == d || !o.alive || !o.resting || o.fluid != BLOOD || o.kind == PLANT || o.face != face.ordinal()) continue;
            if (Math.abs(coordinate(o, a) - plane) > 0.02) continue;
            double dx = d.x - o.x, dy = d.y - o.y, dz = d.z - o.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // on a wall drops run rather than gather: only right on top of each other
            double reach = Math.max(o.splat, o.grow) * b.poolMerge * (floor ? 1 : 0.5);
            if (distance < reach && distance < bestDistance) {
                best = o;
                bestDistance = distance;
            }
        }
        if (best == null) return false;
        float r0 = Math.max(best.grow, best.splat), add = d.splat * 0.85f;
        float grown = (float) Math.sqrt(r0 * r0 + add * add);
        float most = Math.max(r0, b.poolMax);
        float w = add * add / (r0 * r0 + add * add) * 0.5f;
        best.x += (d.x - best.x) * w;
        best.y += (d.y - best.y) * w;
        best.z += (d.z - best.z) * w;
        best.px = best.x;
        best.py = best.y;
        best.pz = best.z;
        best.grow = Math.min(most, grown);
        if (best.merged < 100) best.merged++;
        if (floor) {
            best.kind = POOL;
            best.stretch = 1 + (best.stretch - 1) * 0.6f;
        }
        // fresh blood on it: wet again, and it stays
        best.landed = best.age;
        best.life = Math.max(best.life, best.age + d.stain);
        if (!clip(level, best)) best.alive = false;
        return true;
    }

    private void list(Debris d) {
        if (d.listed) return;
        d.listed = true;
        stains.add(d);
    }

    /** Drops what no longer lies anywhere from {@link #stains} (dried, recycled into another piece). */
    private void pruneStains() {
        stains.removeIf(d -> {
            boolean gone = !d.alive || !d.resting || d.fluid != BLOOD || d.face < 0;
            if (gone) d.listed = false;
            return gone;
        });
    }

    /** The wind bits of a leaf block the stain lies on (the canopy rustles, the stain with it), 0 otherwise. */
    private int leafWind(ClientLevel level, Debris d, Direction face) {
        probe.set(d.x - face.getStepX() * 0.01, d.y - face.getStepY() * 0.01, d.z - face.getStepZ() * 0.01);
        BlockState state = level.getBlockState(probe);
        if (state.isAir()) return 0;
        int bits = PlantWind.bits(Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state));
        return PlantWind.mode(bits) == PlantWind.LEAVES ? bits : 0;
    }

    /**
     * A pool on a floor grown past its data's {@code overflow} × {@code poolMax}, reaching a real edge of the floor: it runs
     * over it and down the side below (a run), once per side.
     */
    private void overflow(ClientLevel level, Debris d) {
        ImpactData.Blood b = d.blood;
        if (b == null || b.overflow <= 0 || d.grow < b.overflow * b.poolMax || d.edges == null) return;
        float reach = d.splat * 0.92f;
        for (int r = 0; r < d.clips; r++) {
            for (int e = 0; e < 4; e++) {
                if ((d.edges[r] >> e & 1) == 0 || (d.overflowed >> e & 1) != 0) continue;
                float limit = d.clip[r * 4 + e];
                if (Math.abs(limit) > reach) continue;
                boolean sEdge = (e & 1) == 0;
                float lo = d.clip[r * 4 + (sEdge ? 1 : 0)] + 0.03f, hi = d.clip[r * 4 + (sEdge ? 3 : 2)] - 0.03f;
                if (lo > hi) continue;
                float across = Mth.clamp(0, lo, hi);
                d.overflowed |= (byte) (1 << e);
                spill(level, d, sEdge, e >= 2, limit, across);
                return;
            }
        }
    }

    /** A run from a pool's edge down the side of the block below it (an UP face: s is x, t is z). */
    private void spill(ClientLevel level, Debris pool, boolean alongS, boolean positive, float limit, float across) {
        Direction side = alongS ? (positive ? Direction.EAST : Direction.WEST) : (positive ? Direction.SOUTH : Direction.NORTH);
        double x = alongS ? pool.x + limit : pool.x + across, z = alongS ? pool.z + across : pool.z + limit;
        Debris drop = debris(budget);
        if (drop == pool) return;
        drop.x = drop.px = x;
        drop.y = drop.py = pool.y - 0.012;
        drop.z = drop.pz = z;
        drop.vx = drop.vz = 0;
        drop.vy = -0.15f;
        drop.size = 0.018f;
        drop.shape = pool.shape;
        drop.fluid = BLOOD;
        drop.collide = true;
        drop.stain = Math.max(20, pool.life - pool.age);
        // it always runs: the pool keeps feeding it
        drop.runs = 1;
        drop.washRate = pool.washRate;
        drop.dry = pool.dry;
        drop.rgb = pool.rgb;
        drop.light = pool.light;
        drop.dustRgb = pool.dustRgb;
        drop.blood = pool.blood;
        drop.source = -1;
        drop.life = 400;
        stick(level, drop, side, alongS ? x : z);
    }

    /** Grows the box this tick's flying blood is in (the next tick looks for bodies there). */
    private void flown(Debris d) {
        if (!flyingNext) {
            flyMinX = flyMaxX = d.x;
            flyMinY = flyMaxY = d.y;
            flyMinZ = flyMaxZ = d.z;
            flyingNext = true;
            return;
        }
        flyMinX = Math.min(flyMinX, d.x);
        flyMinY = Math.min(flyMinY, d.y);
        flyMinZ = Math.min(flyMinZ, d.z);
        flyMaxX = Math.max(flyMaxX, d.x);
        flyMaxY = Math.max(flyMaxY, d.y);
        flyMaxZ = Math.max(flyMaxZ, d.z);
    }

    /** The living bodies round last tick's flying blood (not the camera's own body in first person). */
    private void findBodies(ClientLevel level) {
        bodies.clear();
        if (!flying || bodySink == null) return;
        Minecraft mc = Minecraft.getInstance();
        Entity self = mc.options.getCameraType().isFirstPerson() ? mc.getCameraEntity() : null;
        AABB box = new AABB(flyMinX, flyMinY, flyMinZ, flyMaxX, flyMaxY, flyMaxZ).inflate(1.5);
        if (box.getSize() > 96) return;
        for (Entity e : level.getEntities((Entity) null, box, e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
            if (e == self) continue;
            bodies.add(e);
            if (bodies.size() >= MAX_BODIES) break;
        }
    }

    /** A drop of blood flying into another body (not its own, at once): it stains it where it strikes, and is gone. */
    private boolean hitBody(Debris d) {
        if (bodies.isEmpty() || d.blood.bodies <= 0 || d.blood.bodyDecal == null || d.blood.bodyDecal.isEmpty()) return false;
        Vec3 from = new Vec3(d.x, d.y, d.z), to = new Vec3(d.x + d.vx, d.y + d.vy, d.z + d.vz);
        for (Entity body : bodies) {
            if (body.getId() == d.source && d.age < 12) continue;
            AABB box = body.getBoundingBox().inflate(0.04);
            Optional<Vec3> at = box.contains(from) ? Optional.of(from) : box.clip(from, to);
            if (at.isEmpty()) continue;
            float chance = (d.size * 7919 + d.age * 0.618f + body.getId() * 0.37f) % 1;
            if (chance > d.blood.bodies) return false;
            Vec3 dir = to.subtract(from);
            if (dir.lengthSqr() < 1e-8) dir = new Vec3(0, -1, 0);
            if (bodySink.stain(body, at.get(), dir.normalize(), d.blood.bodyDecal, d.size)) {
                d.alive = false;
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * A drop of blood going through a plant the wind bends (grass, flowers, crops — no collision box): with its data's
     * {@code plants} chance it sticks to one of the plant's quads where it passes, as a stain on its blades
     * ({@link #PLANT}): the plant's own texture cuts it, and the wind and bodies move it with the plant.
     */
    private boolean onPlant(ClientLevel level, Debris d) {
        if (d.blood.plants <= 0) return false;
        probe.set(d.x, d.y, d.z);
        long key = probe.asLong();
        if (key == d.lastPlant) return false;
        d.lastPlant = key;
        BlockState state = level.getBlockState(probe);
        if (state.isAir() || !state.getFluidState().isEmpty() || !state.getCollisionShape(level, probe).isEmpty()) return false;
        float chance = (d.size * 3571 + d.age * 0.618f + (key & 1023) * 0.0137f) % 1;
        if (chance > d.blood.plants) return false;
        Vec3 offset = state.getOffset(probe);
        float qx = (float) (d.x - probe.getX() - offset.x), qy = (float) (d.y - probe.getY() - offset.y), qz = (float) (d.z - probe.getZ() - offset.z);
        List<BlockModelPart> parts = Minecraft.getInstance().getBlockRenderer().getBlockModel(state).collectParts(RandomSource.create(key));
        BakedQuad best = null;
        float bestDistance = 0.2f;
        for (BlockModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(null)) {
                if (PlantWind.mode(PlantWind.bits(quad.sprite())) != PlantWind.BEND) continue;
                float distance = planeDistance(quad.vertices(), qx, qy, qz);
                if (distance < bestDistance) {
                    best = quad;
                    bestDistance = distance;
                }
            }
        }
        if (best == null || d.stain <= 0) return false;
        int[] v = best.vertices();
        // the quad: corner 0, its edges to corners 1 and 3 (a rectangle), and which of them is the level one
        float x0 = Float.intBitsToFloat(v[0]), y0 = Float.intBitsToFloat(v[1]), z0 = Float.intBitsToFloat(v[2]);
        float u0 = Float.intBitsToFloat(v[4]), w0 = Float.intBitsToFloat(v[5]);
        float ax = Float.intBitsToFloat(v[8]) - x0, ay = Float.intBitsToFloat(v[9]) - y0, az = Float.intBitsToFloat(v[10]) - z0;
        float au = Float.intBitsToFloat(v[12]) - u0, aw = Float.intBitsToFloat(v[13]) - w0;
        float bx = Float.intBitsToFloat(v[24]) - x0, by = Float.intBitsToFloat(v[25]) - y0, bz = Float.intBitsToFloat(v[26]) - z0;
        float bu = Float.intBitsToFloat(v[28]) - u0, bw = Float.intBitsToFloat(v[29]) - w0;
        float la = ax * ax + ay * ay + az * az, lb = bx * bx + by * by + bz * bz;
        if (la < 1e-6f || lb < 1e-6f) return false;
        // the point on the quad (its parameters along the two edges), the uv there
        float px = qx - x0, py = qy - y0, pz = qz - z0;
        float pa = Mth.clamp((px * ax + py * ay + pz * az) / la, 0.04f, 0.96f), pb = Mth.clamp((px * bx + py * by + pz * bz) / lb, 0.04f, 0.96f);
        boolean aLevel = Math.abs(ay) < Math.abs(by);
        float hx = aLevel ? ax : bx, hz = aLevel ? az : bz, hl = (float) Math.sqrt(aLevel ? la : lb);
        float uLen = (float) Math.sqrt(aLevel ? lb : la), upSign = Math.signum(aLevel ? by : ay);
        d.x = probe.getX() + offset.x + x0 + pa * ax + pb * bx;
        d.y = probe.getY() + offset.y + y0 + pa * ay + pb * by;
        d.z = probe.getZ() + offset.z + z0 + pa * az + pb * bz;
        d.px = d.x;
        d.py = d.y;
        d.pz = d.z;
        d.pax = hx / hl;
        d.paz = hz / hl;
        d.atlasU = u0 + pa * au + pb * bu;
        d.atlasV = w0 + pa * aw + pb * bw;
        d.dudA = (aLevel ? au : bu) / hl;
        d.dvdA = (aLevel ? aw : bw) / hl;
        d.dudU = (aLevel ? bu : au) / uLen * upSign;
        d.dvdU = (aLevel ? bw : aw) / uLen * upSign;
        d.plant = key;
        d.windBits = PlantWind.bits(best.sprite());
        d.kind = PLANT;
        d.face = -1;
        d.vx = d.vy = d.vz = 0;
        d.resting = true;
        d.splat = d.size * 1.5f;
        d.grow = d.splat;
        d.stretch = 1;
        d.landed = d.age;
        d.life = d.age + d.stain;
        d.run = d.runMax = 0;
        d.drip = 0;
        d.washed = level.isRaining() && level.isRainingAt(probe.above());
        d.light = light(level, d.x, d.y, d.z);
        return true;
    }

    /** Distance (blocks) from the point to the plane of a quad's vertex data, or MAX when it lies beyond the quad. */
    private static float planeDistance(int[] v, float qx, float qy, float qz) {
        float x0 = Float.intBitsToFloat(v[0]), y0 = Float.intBitsToFloat(v[1]), z0 = Float.intBitsToFloat(v[2]);
        float ax = Float.intBitsToFloat(v[8]) - x0, ay = Float.intBitsToFloat(v[9]) - y0, az = Float.intBitsToFloat(v[10]) - z0;
        float bx = Float.intBitsToFloat(v[24]) - x0, by = Float.intBitsToFloat(v[25]) - y0, bz = Float.intBitsToFloat(v[26]) - z0;
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float la = ax * ax + ay * ay + az * az, lb = bx * bx + by * by + bz * bz;
        if (nl < 1e-6f || la < 1e-6f || lb < 1e-6f) return Float.MAX_VALUE;
        float px = qx - x0, py = qy - y0, pz = qz - z0;
        float a = (px * ax + py * ay + pz * az) / la, b = (px * bx + py * by + pz * bz) / lb;
        if (a < -0.05f || a > 1.05f || b < -0.05f || b > 1.05f) return Float.MAX_VALUE;
        return Math.abs(px * nx + py * ny + pz * nz) / nl;
    }

    /** Blood on a plant: dries, washes out in the rain, and goes with the plant (broken, cut, trampled flat). */
    private void tickPlantStain(ClientLevel level, Debris d) {
        if ((ticks + d.landed) % 20 == 0) {
            probe.set(BlockPos.getX(d.plant), BlockPos.getY(d.plant), BlockPos.getZ(d.plant));
            BlockState state = level.getBlockState(probe);
            if (state.isAir() || !state.getCollisionShape(level, probe).isEmpty()) {
                d.alive = false;
                return;
            }
            d.washed = level.isRaining() && level.isRainingAt(probe.above());
            d.light = light(level, d.x, d.y, d.z);
        }
        if (d.washed && d.washRate > 0) {
            d.wash += d.washRate;
            int extra = (int) d.wash;
            d.age += extra;
            d.wash -= extra;
        }
    }

    /** Whether rain falls on a stain (tops in the open, walls just inside the rain's reach). */
    private boolean rainAt(ClientLevel level, Debris d, Direction face) {
        if (!level.isRaining() || face == Direction.DOWN) return false;
        probe.set(d.x + face.getStepX() * 0.5, d.y + face.getStepY() * 0.5 + 0.05, d.z + face.getStepZ() * 0.5);
        return level.isRainingAt(probe);
    }

    /**
     * The plane of the side of the collision box at {@code (x, y, z)} that a piece moving along {@code axis} meets:
     * the box's {@code max} when {@code fromAbove} (the piece comes from the positive side), else its {@code min}; NaN when
     * no box holds the point.
     */
    private double plane(ClientLevel level, double x, double y, double z, Direction.Axis axis, boolean fromAbove) {
        probe.set(x, y, z);
        BlockState state = level.getBlockState(probe);
        VoxelShape shape = state.getCollisionShape(level, probe);
        double lx = x - probe.getX(), ly = y - probe.getY(), lz = z - probe.getZ();
        for (AABB box : shape.toAabbs()) {
            if (lx >= box.minX && lx <= box.maxX && ly >= box.minY && ly <= box.maxY && lz >= box.minZ && lz <= box.maxZ) {
                return switch (axis) {
                    case X -> probe.getX() + (fromAbove ? box.maxX : box.minX);
                    case Y -> probe.getY() + (fromAbove ? box.maxY : box.minY);
                    case Z -> probe.getZ() + (fromAbove ? box.maxZ : box.minZ);
                };
            }
        }
        return Double.NaN;
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

    /** A small plume of blood in water where a cloud is free (a drop that fell in; never pushes out a hit's plume). */
    private void plume(double x, double y, double z, double surface, float mass, int rgb, int light, float seed) {
        Cloud c = smallCloud();
        if (c == null) return;
        c.x = c.px = x;
        c.y = c.py = y;
        c.z = c.pz = z;
        c.vx = c.vz = 0;
        c.vy = -0.01f;
        c.nx = c.ny = c.nz = 0;
        c.ax = 0;
        c.ay = -1;
        c.az = 0;
        c.plume = 0.3f;
        float k = (float) Math.cbrt(Math.max(0.02f, mass));
        c.rBurst = 0.06f * k;
        c.rEnd = 0.6f * k;
        c.density = 4f * Math.min(1.5f, k);
        c.rise = 0;
        c.settle = 0.02f / 20;
        c.wind = 0;
        c.seed = seed;
        c.rgb = rgb;
        c.light = light;
        c.life = 100 + (int) (seed * 7) % 60;
        c.lobes = 2;
        c.water = true;
        c.surface = surface;
    }

    /** The height of the water surface in the block at the point, or NaN when there is no water. */
    double waterSurface(ClientLevel level, double x, double y, double z) {
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
        // a drop's splash never pushes out a round's
        Splash s = smallSplash();
        if (s == null) return;
        s.x = d.x;
        s.y = surface;
        s.z = d.z;
        s.kind = WATER;
        s.secondary = true;
        s.energy = Mth.clamp(speed * 2.2f * (d.size / 0.03f), 0.12f, 0.45f);
        s.foam = 0;
        // a tiny drop (a drip) only rings the surface
        s.sheet = d.size < 0.012f ? 0 : 0.24f;
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
        if (c.water) {
            tickPlume(level, c);
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

    /**
     * A plume in water: its burst slows within a second (water is a thousand times denser than air), then it drifts in a
     * slow, turning eddy, carried by the current (fluid physics), and sinks a little (blood is a little denser than
     * water), staying under the surface; no wind.
     */
    private void tickPlume(ClientLevel level, Cloud c) {
        if (fluids && c.age % 5 == 1) {
            probe.set(c.x, c.y, c.z);
            FluidState fluid = level.getFluidState(probe);
            if (fluid.is(FluidTags.WATER)) {
                Vec3 flow = fluid.getFlow(level, probe);
                float current = ImpactCatalog.data().environment.current;
                c.flowX = (float) flow.x * current / 20;
                c.flowZ = (float) flow.z * current / 20;
            } else {
                c.flowX = c.flowZ = 0;
            }
        }
        float eddy = c.seed + c.age * 0.04f;
        c.vx = (c.vx + (Mth.sin(eddy) * 0.004f + c.flowX - c.vx) * 0.05f) * 0.88f;
        c.vz = (c.vz + (Mth.cos(eddy * 1.3f) * 0.004f + c.flowZ - c.vz) * 0.05f) * 0.88f;
        c.vy = (c.vy + (-c.settle - c.vy) * 0.05f) * 0.88f;
        // the current keeps it going where the eddy alone would stop it
        double nx = c.x + c.vx + c.flowX * 0.6, ny = c.y + c.vy, nz = c.z + c.vz + c.flowZ * 0.6;
        if (!solid(level, nx, c.y, c.z)) c.x = nx;
        else c.vx = 0;
        if (!solid(level, c.x, ny, c.z)) c.y = Math.min(ny, c.surface - 0.1);
        else c.vy = 0;
        if (!solid(level, c.x, c.y, nz)) c.z = nz;
        else c.vz = 0;
        c.light = ease(c.light, light(level, c.x, c.y, c.z), 0.1f);
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
        // a spark meeting water is quenched at once
        if (fluids && medium(level, nx, ny, nz) == WATER) {
            s.alive = false;
            return;
        }
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

    /** What the point is in: 0 air, {@link #WATER} or {@link #LAVA} — below the fluid's own height in its block. */
    byte medium(ClientLevel level, double x, double y, double z) {
        probe.set(x, y, z);
        FluidState fluid = level.getFluidState(probe);
        if (fluid.isEmpty()) return 0;
        if (y - probe.getY() > fluid.getHeight(level, probe)) return 0;
        return fluid.is(FluidTags.WATER) ? WATER : fluid.is(FluidTags.LAVA) ? LAVA : 0;
    }

    boolean inWater(ClientLevel level, double x, double y, double z) {
        return medium(level, x, y, z) == WATER;
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
            Direction side = i == 0 ? null : DIRECTIONS[i - 1];
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
