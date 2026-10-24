package com.blockland.client.impact;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Turns one hit into pieces: the material says what is thrown out, the round scales it, the angle shapes it — a hit
 * square on bursts out round the surface normal, a grazing one streaks along the surface towards the ricochet — and the
 * surroundings change it (wet ground, snow, under water). Counts are rounded at random so small multipliers still show
 * something on average.
 */
final class ImpactSpawner {
    static final byte CHIP = 0, FLAKE = 1, SPLINTER = 2, SHARD = 3, GRAIN = 4, DROPLET = 5, LEAF = 6, BUBBLE = 7;

    /** Where a hit happens and how it is seen. */
    record Hit(Vec3 at, Vec3 normal, Vec3 dir, ImpactData.Material material, ImpactData.Ammo ammo,
               @Nullable TextureAtlasSprite sprite, int tint, int dustRgb, int light) {
    }

    /** The surroundings at the hit. */
    record Env(boolean wet, boolean snowy, boolean underwater) {
        static final Env NONE = new Env(false, false, false);
    }

    private ImpactSpawner() {
    }

    /**
     * @param scale  multiplier of every count (quality × distance)
     * @param plain  the sprite for untextured pieces (droplets, blood, bubbles)
     */
    static void spawn(ImpactParticles pool, Hit hit, Env env, float scale, int budget, boolean dustOn,
                      @Nullable TextureAtlasSprite plain, RandomSource random) {
        ImpactData.Material m = hit.material();
        ImpactData.Environment e = ImpactCatalog.data().environment;
        Vec3 n = hit.normal(), dir = hit.dir();
        double facing = Mth.clamp(-dir.dot(n), 0, 1);
        double grazing = 1 - facing;
        Vec3 reflect = dir.subtract(n.scale(2 * dir.dot(n)));
        // square hits burst out round the normal, glancing ones fan out along the ricochet
        Vec3 base = n.scale(1 - grazing * 0.85).add(reflect.scale(grazing * 0.85)).normalize();
        Vec3 origin = hit.at().add(n.scale(0.02));
        float energy = hit.ammo().energy;

        // ---- the powder: its colour and how much of it the hit makes (from the material thrown out, not the drawn count)
        ImpactData.Dust dust = m.dust;
        int dustRgb = hit.dustRgb();
        float mass = (dust.count + dust.perDebris * m.debris.count * hit.ammo().count) * dust.friability * hit.ammo().dust
                * (0.6f + 0.4f * energy);
        if (env.wet()) {
            mass *= e.wetDust;
            dustRgb = darken(dustRgb, e.wetDarken);
        }
        if (env.snowy()) {
            mass *= e.snowDust;
            dustRgb = mix(dustRgb, 0xF2F5F8, 0.5f);
        }
        boolean powder = dustOn && !env.underwater() && mass > 0.02f;

        // ---- debris
        ImpactData.Debris debris = m.debris;
        int chunks = count(debris.count * hit.ammo().count * scale, random);
        boolean textured = m.blockTexture && hit.sprite() != null;
        TextureAtlasSprite sprite = textured ? hit.sprite() : plain;
        int tint = textured ? hit.tint() : ImpactCatalog.rgb(m.color);
        if (env.wet() && !env.underwater()) tint = darken(tint, e.wetDarken);
        for (int i = 0; i < chunks; i++) {
            ImpactParticles.Debris d = pool.debris(budget);
            Vec3 v = cone(base, n, debris.spread * (1 - 0.4 * grazing), random)
                    .scale(range(debris.speed, random) * energy * (0.55 + 0.45 * facing) / 20);
            place(d, origin, v);
            d.size = range(debris.size, random) * hit.ammo().size;
            d.shape = shape(debris.shapes, random);
            d.gravity = debris.gravity;
            d.drag = debris.drag;
            d.restitution = debris.restitution;
            d.friction = debris.friction;
            d.wind = debris.wind;
            d.collide = true;
            spin(d, debris.spin, random);
            d.life = Math.max(2, Math.round(range(debris.life, random) * 20));
            uv(d, sprite, random);
            d.rgb = vary(tint, random);
            d.light = hit.light();
            if (powder) {
                // powdery chunks smoke as they fly and raise a little poof where they land
                if (dust.trail > 0 && random.nextFloat() < dust.trail) d.trail = 0.05f * dust.friability * hit.ammo().dust;
                d.landing = dust.landing * dust.friability;
                d.dustRgb = dustRgb;
            }
        }

        // ---- the dust cloud, or bubbles under water
        if (env.underwater()) {
            int bubbles = count(e.bubbles * scale * (chunks > 0 || dust.count > 0 ? 1 : 0.3f), random);
            for (int i = 0; i < bubbles; i++) {
                ImpactParticles.Debris d = pool.debris(budget);
                place(d, origin, cone(n, n, 60, random).scale(0.03 + random.nextFloat() * 0.05));
                d.size = 0.015f + random.nextFloat() * 0.03f;
                d.shape = BUBBLE;
                d.bubble = true;
                d.collide = false;
                d.life = 30 + random.nextInt(50);
                uv(d, plain, random);
                d.rgb = 0xDDEEFF;
                d.light = hit.light();
                spin(d, 0, random);
            }
        } else if (powder) {
            cloud(pool, origin, n, base, facing, dust, mass, scale, dustRgb, hit.light(), random);
        }

        // ---- wet ground: a few muddy drops thrown up with the debris
        if (env.wet() && !env.underwater() && hit.sprite() != null) {
            int drops = count(e.wetSplash * scale, random);
            for (int i = 0; i < drops; i++) {
                ImpactParticles.Debris d = pool.debris(budget);
                place(d, origin, cone(n, n, 40, random).scale((1.5 + random.nextFloat() * 2) / 20));
                droplet(d, plain, darken(hit.dustRgb(), 0.45f), hit.light(), random);
            }
        }

        // ---- sparks
        ImpactData.Sparks sparks = m.sparks;
        int streaks = count(sparks.count * hit.ammo().count * scale * (float) (0.6 + 0.6 * grazing), random);
        int sparkRgb = ImpactCatalog.rgb(sparks.color);
        for (int i = 0; i < streaks; i++) {
            ImpactParticles.Spark s = pool.spark(budget);
            Vec3 burst = cone(base, n, sparks.spread, random);
            Vec3 along = cone(reflect, n, 18, random);
            Vec3 v = burst.scale(1 - sparks.ricochet).add(along.scale(sparks.ricochet)).normalize()
                    .scale(range(sparks.speed, random) * energy / 20);
            s.x = s.px = origin.x;
            s.y = s.py = origin.y;
            s.z = s.pz = origin.z;
            s.vx = (float) v.x;
            s.vy = (float) v.y;
            s.vz = (float) v.z;
            s.gravity = sparks.gravity;
            s.width = 0.008f + random.nextFloat() * 0.01f;
            s.life = Math.max(2, Math.round(range(sparks.life, random) * 20));
            s.bounces = sparks.bounce ? 1 : 0;
            s.rgb = vary(sparkRgb, random);
        }
    }

    /**
     * One hit's dust cloud. Its mass sets everything: the radius grows with ∛mass (the volume with the mass), the core's
     * density and how long it hangs grow with it too, so a few chips make a faint wisp and a heavy round into concrete a
     * thick, lingering cloud. Quality and distance only change how many lobes draw it.
     */
    private static void cloud(ImpactParticles pool, Vec3 origin, Vec3 n, Vec3 base, double facing, ImpactData.Dust dust,
                              float mass, float scale, int rgb, int light, RandomSource random) {
        ImpactParticles.Cloud c = pool.cloud();
        float k = (float) Math.cbrt(mass);
        Vec3 v = cone(base, n, 30, random).scale(range(dust.speed, random) * (0.4 + 0.6 * facing) / 20);
        c.x = c.px = origin.x + n.x * 0.06;
        c.y = c.py = origin.y + n.y * 0.06;
        c.z = c.pz = origin.z + n.z * 0.06;
        c.vx = (float) v.x;
        c.vy = (float) v.y;
        c.vz = (float) v.z;
        c.nx = (float) n.x;
        c.ny = (float) n.y;
        c.nz = (float) n.z;
        Vec3 axis = v.lengthSqr() > 1e-10 ? v.normalize() : n;
        c.ax = (float) axis.x;
        c.ay = (float) axis.y;
        c.az = (float) axis.z;
        // a square, fast throw makes a long jet; a glancing one stays a low smear along the surface
        c.plume = Mth.clamp(0.35f + 0.55f * (float) facing * range(dust.speed, random) / 1.6f, 0.3f, 1.3f);
        float[] size = dust.size == null || dust.size.length < 2 ? new float[]{0.16f, 0.55f} : dust.size;
        c.rBurst = size[0] * (0.55f + 0.45f * k);
        c.rEnd = size[1] * k;
        c.density = dust.opacity * 7 * Mth.clamp(k, 0.35f, 1.8f);
        c.rise = dust.rise / 20;
        c.settle = dust.settle / 20;
        c.wind = dust.wind;
        c.seed = random.nextFloat() * 97;
        c.rgb = rgb;
        c.light = light;
        c.life = Math.max(8, Math.round(range(dust.life, random) * 20 * (0.7f + 0.3f * Math.min(1.6f, k))));
        c.lobes = (byte) Mth.clamp(Math.round((1 + 2.2f * k) * Math.min(1, 0.4f + 0.6f * scale)), 1, 8);
    }

    /**
     * A round into a fluid's surface (rendering spec §12, fluids). Water: a splash event the renderer shapes from its
     * age (crown sheet, column, rings, white water), drops thrown from the crown and the top of the column that fall back
     * and splash again, bubbles under the surface; lava: a glowing crater, molten spatter that cools and crusts where it
     * lands, embers and smoke.
     */
    static void splash(ImpactParticles pool, Hit hit, float scale, int budget, boolean dustOn, @Nullable TextureAtlasSprite plain,
                       RandomSource random) {
        ImpactData.Splash splash = hit.material().splash;
        if (splash == null) {
            spawn(pool, hit, Env.NONE, scale, budget, dustOn, plain, random);
            return;
        }
        boolean lava = "lava".equals(splash.kind);
        Vec3 at = hit.at();
        // the round's energy, a heavy one making the bigger splash
        float energy = hit.ammo().energy * (0.75f + 0.25f * hit.ammo().size);
        ImpactParticles.Splash s = pool.splash();
        s.x = at.x;
        s.y = at.y;
        s.z = at.z;
        s.kind = lava ? ImpactParticles.LAVA : ImpactParticles.WATER;
        s.energy = energy;
        s.foam = lava ? 0 : Mth.clamp((energy - 0.9f) * 1.1f, 0, 1) * splash.foam;
        s.sheet = splash.sheet;
        s.column = splash.column;
        s.ripple = splash.ripple;
        s.seed = random.nextFloat() * 97;
        s.rgb = hit.tint();
        s.light = hit.light();
        s.life = Math.max(10, Math.round(splash.rippleLife * 20 * (0.8f + 0.2f * Math.min(2, energy))));

        int drops = count(splash.drops * hit.ammo().count * scale * Math.min(2, energy), random);
        for (int i = 0; i < drops; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double up = Math.toRadians((lava ? 40 : 50) + random.nextDouble() * 28);
            Vec3 v = new Vec3(Math.cos(a) * Math.cos(up), Math.sin(up), Math.sin(a) * Math.cos(up))
                    .scale(splash.dropSpeed * energy * (0.55 + 0.55 * random.nextDouble()) / 20);
            ImpactParticles.Debris d = pool.debris(budget);
            // from the rim of the crown, not its centre
            place(d, at.add(Math.cos(a) * 0.06 * energy, 0.02, Math.sin(a) * 0.06 * energy), v);
            fluidDrop(d, lava, hit.tint(), hit.light(), hit.ammo().size, random);
        }
        if (!lava && energy > 0.6f && splash.column > 0) {
            // the top of the jet breaks off into a drop or two, thrown as high as the column rises
            double height = splash.column * Math.pow(energy, 1.5);
            int top = 1 + (energy > 1.3f ? 1 : 0) + (random.nextFloat() < 0.4f ? 1 : 0);
            for (int i = 0; i < top; i++) {
                double speed = Math.sqrt(2 * ImpactParticles.GRAVITY * height) * (0.85 + 0.25 * random.nextDouble());
                ImpactParticles.Debris d = pool.debris(budget);
                place(d, at.add(0, 0.05, 0), cone(new Vec3(0, 1, 0), new Vec3(0, 1, 0), 6, random).scale(speed));
                fluidDrop(d, false, hit.tint(), hit.light(), hit.ammo().size * 1.4f, random);
            }
        }
        if (!lava && s.foam > 0) {
            // air dragged down by a heavy round comes back up as bubbles
            int bubbles = count(6 * s.foam * scale, random);
            for (int i = 0; i < bubbles; i++) {
                ImpactParticles.Debris d = pool.debris(budget);
                place(d, at.add((random.nextFloat() - 0.5) * 0.3, -0.25 - random.nextFloat() * 0.4, (random.nextFloat() - 0.5) * 0.3),
                        new Vec3(0, 0.02, 0));
                d.size = 0.015f + random.nextFloat() * 0.03f;
                d.shape = BUBBLE;
                d.bubble = true;
                d.collide = false;
                d.life = 20 + random.nextInt(30);
                uv(d, plain, random);
                d.rgb = 0xDDEEFF;
                d.light = hit.light();
                spin(d, 0, random);
            }
        }
        // and whatever else the fluid's material throws (lava: embers, smoke)
        ImpactData.Material m = hit.material();
        if (m.sparks.count > 0 || m.dust.count > 0) {
            ImpactData.Material rest = new ImpactData.Material();
            rest.sparks = m.sparks;
            rest.dust = m.dust;
            rest.debris = new ImpactData.Debris();
            spawn(pool, new Hit(at, new Vec3(0, 1, 0), hit.dir(), rest, hit.ammo(), null, hit.tint(), hit.dustRgb(), hit.light()),
                    Env.NONE, scale, budget, dustOn, plain, random);
        }
    }

    /** A drop of water (falls back and splashes again) or of molten lava (glows, lands, cools to a crust). */
    private static void fluidDrop(ImpactParticles.Debris d, boolean lava, int rgb, int light, float ammoSize, RandomSource random) {
        d.fluid = lava ? ImpactParticles.LAVA : ImpactParticles.WATER;
        d.shape = DROPLET;
        d.size = (lava ? 0.02f + random.nextFloat() * 0.025f : 0.012f + random.nextFloat() * 0.02f) * ammoSize;
        d.gravity = lava ? 1.15f : 1;
        d.drag = lava ? 0.03f : 0.012f;
        d.restitution = 0;
        d.friction = lava ? 0.15f : 0;
        d.wind = lava ? 0 : 0.03f;
        d.collide = true;
        d.life = lava ? 70 + random.nextInt(50) : 40 + random.nextInt(14);
        d.u0 = d.v0 = d.u1 = d.v1 = 0;
        d.rgb = rgb;
        d.light = light;
        spin(d, 0, random);
    }

    // ---- pieces -------------------------------------------------------------------------------------

    private static void droplet(ImpactParticles.Debris d, @Nullable TextureAtlasSprite plain, int rgb, int light, RandomSource random) {
        d.size = 0.018f + random.nextFloat() * 0.022f;
        d.shape = DROPLET;
        d.gravity = 1;
        d.drag = 0.01f;
        d.restitution = 0;
        d.friction = 0;
        d.wind = 0.05f;
        d.collide = true;
        d.life = 18 + random.nextInt(14);
        uv(d, plain, random);
        d.rgb = vary(rgb, random);
        d.light = light;
        spin(d, 0, random);
    }

    private static void place(ImpactParticles.Debris d, Vec3 at, Vec3 v) {
        d.x = d.px = at.x;
        d.y = d.py = at.y;
        d.z = d.pz = at.z;
        d.vx = (float) v.x;
        d.vy = (float) v.y;
        d.vz = (float) v.z;
    }

    private static void spin(ImpactParticles.Debris d, float degreesPerSecond, RandomSource random) {
        float max = (float) Math.toRadians(degreesPerSecond) / 20;
        d.a = d.pa = random.nextFloat() * Mth.TWO_PI;
        d.b = d.pb = random.nextFloat() * Mth.TWO_PI;
        d.c = d.pc = random.nextFloat() * Mth.TWO_PI;
        d.va = (random.nextFloat() * 2 - 1) * max;
        d.vb = (random.nextFloat() * 2 - 1) * max;
        d.vc = (random.nextFloat() * 2 - 1) * max;
    }

    /** A random quarter of the sprite, like vanilla's block particles (the debris looks like the block it came from). */
    private static void uv(ImpactParticles.Debris d, @Nullable TextureAtlasSprite sprite, RandomSource random) {
        if (sprite == null) {
            d.u0 = d.v0 = d.u1 = d.v1 = 0;
            return;
        }
        float u = random.nextFloat() * 0.75f, v = random.nextFloat() * 0.75f;
        d.u0 = sprite.getU(u);
        d.v0 = sprite.getV(v);
        d.u1 = sprite.getU(u + 0.25f);
        d.v1 = sprite.getV(v + 0.25f);
    }

    private static byte shape(List<String> shapes, RandomSource random) {
        if (shapes == null || shapes.isEmpty()) return CHIP;
        return switch (shapes.get(random.nextInt(shapes.size()))) {
            case ImpactData.FLAKE -> FLAKE;
            case ImpactData.SPLINTER -> SPLINTER;
            case ImpactData.SHARD -> SHARD;
            case ImpactData.GRAIN -> GRAIN;
            case ImpactData.DROPLET -> DROPLET;
            case ImpactData.LEAF -> LEAF;
            default -> CHIP;
        };
    }

    /** A direction within {@code halfAngle} degrees of {@code axis}, kept out of the surface with normal {@code n}. */
    static Vec3 cone(Vec3 axis, Vec3 n, double halfAngle, RandomSource random) {
        double cosMax = Math.cos(Math.toRadians(Math.min(89, halfAngle)));
        double cos = 1 - random.nextDouble() * (1 - cosMax), sin = Math.sqrt(Math.max(0, 1 - cos * cos));
        double phi = random.nextDouble() * Math.PI * 2;
        Vec3 t = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0).cross(axis).normalize() : new Vec3(1, 0, 0).cross(axis).normalize();
        Vec3 b = axis.cross(t);
        Vec3 v = axis.scale(cos).add(t.scale(Math.cos(phi) * sin)).add(b.scale(Math.sin(phi) * sin));
        double into = v.dot(n);
        // never into the surface it came off: mirrored back out, just above it
        if (into < 0.05) v = v.add(n.scale(0.05 - into * 2)).normalize();
        return v;
    }

    private static float range(float[] range, RandomSource random) {
        if (range == null || range.length == 0) return 1;
        if (range.length == 1) return range[0];
        return range[0] + (range[1] - range[0]) * random.nextFloat();
    }

    /** Rounds {@code amount} at random: 2.3 gives 2 or 3 (3 three times in ten). */
    private static int count(float amount, RandomSource random) {
        if (amount <= 0) return 0;
        int whole = (int) amount;
        return whole + (random.nextFloat() < amount - whole ? 1 : 0);
    }

    /** A little colour variation per piece, so a pile of chips is not one flat tone. */
    private static int vary(int rgb, RandomSource random) {
        float k = 0.82f + random.nextFloat() * 0.3f;
        return scale(rgb, k);
    }

    static int darken(int rgb, float k) {
        return scale(rgb, k);
    }

    private static int scale(int rgb, float k) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((rgb & 0xFF) * k));
        return r << 16 | g << 8 | b;
    }

    static int mix(int a, int b, float t) {
        int r = Math.round(Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF));
        int g = Math.round(Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF));
        int bl = Math.round(Mth.lerp(t, a & 0xFF, b & 0xFF));
        return r << 16 | g << 8 | bl;
    }

    static int multiply(int a, int b) {
        int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return r << 16 | g << 8 | bl;
    }
}
