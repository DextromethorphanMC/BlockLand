package com.blockland.client.impact;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Turns one hit into pieces: the material says what is thrown out, the round scales it, the angle shapes it — a hit
 * square on bursts out round the surface normal with a share blown straight back along the hole towards the shooter, a
 * grazing one streaks along the surface towards the ricochet and fans out downrange (the round's direction along the
 * surface: the shooter's yaw and pitch decide where the spray goes) — and the surroundings change it (wet ground: less
 * dust, duller bounces, quenched sparks; snow; under water the debris leaves slowly, spreads little and sinks or floats,
 * no sparks, bubbles take the dust's place). Living targets bleed instead (blood: back-spatter, the exit spray and its
 * fine aerosol, mist; plumes in water), moving with the target. A blade throws along its cut (chips, sparks, cast-off
 * blood) instead of out of a crater. Fluids can be disturbed without a round (entities wading, falling, swinging, blocks
 * dropped in), and a round under water leaves a trail of bubbles and, spent, sinks. Counts are rounded at random so small
 * multipliers still show something on average.
 */
final class ImpactSpawner {
    static final byte CHIP = 0, FLAKE = 1, SPLINTER = 2, SHARD = 3, GRAIN = 4, DROPLET = 5, LEAF = 6, BUBBLE = 7;

    /** Where a hit happens and how it is seen. */
    record Hit(Vec3 at, Vec3 normal, Vec3 dir, ImpactData.Material material, ImpactData.Ammo ammo,
               @Nullable TextureAtlasSprite sprite, int tint, int dustRgb, int light) {
    }

    /** The surroundings at the hit (and whether fluid physics are on). */
    record Env(boolean wet, boolean snowy, boolean underwater, boolean fluids) {
        static final Env NONE = new Env(false, false, false, true);
    }

    /**
     * How blood leaves a wound: the target's own motion (blocks per tick, every drop and the mist move with it), a head
     * hit (more of everything), the blood setting, and how long stains stay (ticks, 0 = none).
     */
    record Bleed(Vec3 inherit, boolean head, float amount, float stains) {
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
        // the round's path along the surface: an oblique hit throws its ejecta that way (a butterfly-shaped spray), a
        // square one evenly round the hole
        Vec3 path = dir.subtract(n.scale(dir.dot(n)));
        Vec3 downrange = path.lengthSqr() > 1e-6 ? path.normalize() : Vec3.ZERO;
        Vec3 back = dir.scale(-1);
        Vec3 origin = hit.at().add(n.scale(0.02));
        float energy = hit.ammo().energy;
        // water is a thousand times denser than air: the chunks leave slowly and spread little
        double water = env.underwater() ? 0.3 : 1;

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
        // wet material clumps: it bounces less and sheds no powder in flight
        float bounce = env.wet() && !env.underwater() ? e.wetBounce : 1;
        for (int i = 0; i < chunks; i++) {
            ImpactParticles.Debris d = pool.debris(budget);
            // a share of a square hit is blown straight back out of the hole, along the round's path
            boolean blown = random.nextFloat() < 0.22f * facing;
            Vec3 axis = blown ? back : base;
            double spread = debris.spread * (1 - 0.4 * grazing) * (blown ? 0.4 : 1) * (env.underwater() ? 0.6 : 1);
            Vec3 v = spray(axis, n, downrange, spread, grazing, random)
                    .scale(range(debris.speed, random) * energy * (0.55 + 0.45 * facing) * water * (blown ? 1.2 : 1) / 20);
            place(d, origin, v);
            chunk(d, debris, hit.ammo().size, bounce, random);
            uv(d, sprite, random);
            d.rgb = vary(tint, random);
            d.light = hit.light();
            if (powder) {
                // powdery chunks smoke as they fly and raise a little poof where they land
                if (dust.trail > 0 && !env.wet() && random.nextFloat() < dust.trail) d.trail = 0.05f * dust.friability * hit.ammo().dust;
                d.landing = dust.landing * dust.friability;
                d.dustRgb = dustRgb;
            }
        }

        // ---- the dust cloud, or bubbles under water
        if (env.underwater()) {
            // the round's cavity and the crushed material let out air: more of it the harder the round
            int bubbles = count(e.bubbles * scale * (0.6f + 0.6f * energy) * (chunks > 0 || dust.count > 0 ? 1 : 0.3f), random);
            for (int i = 0; i < bubbles; i++) {
                bubble(pool.debris(budget), origin, cone(n, n, 60, random).scale(0.03 + random.nextFloat() * 0.05),
                        0.012f + random.nextFloat() * 0.03f, 30 + random.nextInt(50), hit.light(), plain, random);
            }
        } else if (powder) {
            cloud(pool, origin, n, base, facing, dust, mass, scale, dustRgb, hit.light(), random);
        }

        // ---- wet ground: a few muddy drops thrown up with the debris
        if (env.wet() && !env.underwater() && hit.sprite() != null) {
            int drops = count(e.wetSplash * scale * Math.min(1.5f, energy), random);
            for (int i = 0; i < drops; i++) {
                ImpactParticles.Debris d = pool.debris(budget);
                place(d, origin, cone(n, n, 40, random).scale((1.5 + random.nextFloat() * 2) * energy / 20));
                droplet(d, plain, darken(hit.dustRgb(), 0.45f), hit.light(), random);
            }
        }

        // ---- sparks: none under water (the metal hisses: a few bubbles), fewer and shorter-lived on a wet surface
        ImpactData.Sparks sparks = m.sparks;
        float quench = env.wet() ? e.rainSparks : 1;
        int streaks = count(sparks.count * hit.ammo().count * hit.ammo().sparks * scale * (float) (0.6 + 0.6 * grazing) * quench, random);
        if (env.underwater()) {
            for (int i = 0; i < Math.min(streaks, 6); i++) {
                bubble(pool.debris(budget), origin, cone(base, n, 50, random).scale(0.04 + random.nextFloat() * 0.04),
                        0.008f + random.nextFloat() * 0.012f, 20 + random.nextInt(25), hit.light(), plain, random);
            }
            return;
        }
        int sparkRgb = ImpactCatalog.rgb(sparks.color);
        for (int i = 0; i < streaks; i++) {
            ImpactParticles.Spark s = pool.spark(budget);
            Vec3 burst = cone(base, n, sparks.spread, random);
            Vec3 along = cone(reflect, n, 18, random);
            Vec3 v = burst.scale(1 - sparks.ricochet).add(along.scale(sparks.ricochet)).normalize()
                    .scale(range(sparks.speed, random) * energy / 20);
            spark(s, origin, v, sparks, quench, sparkRgb, random);
        }
    }

    /**
     * A blade against a block (rendering spec §12, blades): no crater, a scrape — the chips leave along the cut, low over
     * the surface (a thrust throws them back out of the hole instead), sparks fly off along it from metal and stone,
     * a little dust. {@code hit.dir} is the blade's motion (the cut, or the thrust's direction).
     */
    static void slash(ImpactParticles pool, Hit hit, Env env, float scale, int budget, boolean dustOn,
                      @Nullable TextureAtlasSprite plain, RandomSource random) {
        ImpactData.Material m = hit.material();
        ImpactData.Ammo blade = hit.ammo();
        ImpactData.Environment e = ImpactCatalog.data().environment;
        Vec3 n = hit.normal();
        Vec3 motion = hit.dir();
        Vec3 along = motion.subtract(n.scale(motion.dot(n)));
        // the cut along the face; a thrust (or a cut square into the face) throws back out of the gouge
        Vec3 axis = blade.thrust || along.lengthSqr() < 0.04 ? n : along.normalize().scale(0.8).add(n.scale(0.55)).normalize();
        Vec3 origin = hit.at().add(n.scale(0.02));
        double water = env.underwater() ? 0.35 : 1;
        float bounce = env.wet() && !env.underwater() ? e.wetBounce : 1;

        ImpactData.Debris debris = m.debris;
        int chunks = count(debris.count * blade.count * scale, random);
        boolean textured = m.blockTexture && hit.sprite() != null;
        TextureAtlasSprite sprite = textured ? hit.sprite() : plain;
        int tint = textured ? hit.tint() : ImpactCatalog.rgb(m.color);
        if (env.wet() && !env.underwater()) tint = darken(tint, e.wetDarken);
        for (int i = 0; i < chunks; i++) {
            ImpactParticles.Debris d = pool.debris(budget);
            // spread along the gouge, not from one point
            Vec3 start = blade.thrust || along.lengthSqr() < 0.04 ? origin
                    : origin.add(along.normalize().scale((random.nextFloat() - 0.7f) * 0.18f));
            Vec3 v = cone(axis, n, debris.spread * 0.7, random).scale(range(debris.speed, random) * blade.energy * water / 20);
            place(d, start, v);
            chunk(d, debris, blade.size, bounce, random);
            uv(d, sprite, random);
            d.rgb = vary(tint, random);
            d.light = hit.light();
        }
        ImpactData.Dust dust = m.dust;
        float mass = (dust.count + dust.perDebris * debris.count * blade.count) * dust.friability * blade.dust * (env.wet() ? e.wetDust : 1);
        if (dustOn && !env.underwater() && mass > 0.02f) {
            cloud(pool, origin, n, axis, 0.5, dust, mass, scale, hit.dustRgb(), hit.light(), random);
        }
        if (env.underwater()) return;
        // steel on stone or metal: a few sparks raked off along the cut
        ImpactData.Sparks sparks = m.sparks;
        float quench = env.wet() ? e.rainSparks : 1;
        int streaks = count(sparks.count * blade.sparks * scale * quench, random);
        int sparkRgb = ImpactCatalog.rgb(sparks.color);
        for (int i = 0; i < streaks; i++) {
            Vec3 v = cone(axis, n, 30, random).scale(range(sparks.speed, random) * Math.max(0.4f, blade.energy) / 20);
            spark(pool.spark(budget), origin, v, sparks, quench, sparkRgb, random);
        }
    }

    /**
     * A living target's blood (rendering spec §12, blood). In the air: drops thrown back out of the entry towards the
     * shooter ({@code hit.normal}: the struck side of the body part, tilted towards the shooter), a larger, faster spray
     * out of the exit along the round's direction, the fine aerosol — many tiny, fast drops braked hard by the air, the
     * red burst of the first tenth of a second — a mist at both (the exit's the bigger, drawn out along the round), all
     * moving with the target; drops that land stick as stains (ImpactParticles). A head hit throws more. Drops that fall
     * into water cloud out there. In water: no drops and no mist, a plume at each wound that drifts, swirls, sinks a little
     * and spreads for several seconds, colouring the water around it.
     *
     * @param exit    where the round leaves the target (the far side of the part it struck, along the path)
     * @param surface the water's surface over the target (world y), or NaN
     */
    static void blood(ImpactParticles pool, Hit hit, Vec3 exit, Env env, Bleed bleed, double surface, float scale, int budget,
                      boolean dustOn, boolean bloodInWater, @Nullable TextureAtlasSprite plain, RandomSource random) {
        ImpactData.Blood b = hit.material().blood;
        Vec3 dir = hit.dir(), at = hit.at();
        float energy = hit.ammo().energy;
        float amount = bleed.amount() * (bleed.head() ? b.headMultiplier : 1);
        int plumeRgb = ImpactCatalog.rgb(b.plumeColor);
        if (env.underwater()) {
            if (!bloodInWater) return;
            double top = Double.isNaN(surface) ? Math.max(at.y, exit.y) + 2 : surface;
            plume(pool, at, dir.scale(-1), top, b.plume * amount * 0.5f * energy, b, plumeRgb, hit.light(), random);
            plume(pool, exit, dir, top, b.plume * amount * energy, b, plumeRgb, hit.light(), random);
            // a few bubbles out of the wound channel
            int bubbles = count(3 * scale * energy, random);
            for (int i = 0; i < bubbles; i++) {
                ImpactParticles.Debris d = pool.debris(budget);
                bubble(d, exit, cone(dir, dir, 50, random).scale(0.02 + random.nextFloat() * 0.03),
                        0.01f + random.nextFloat() * 0.02f, 25 + random.nextInt(30), hit.light(), plain, random);
                // tinged by the blood they come through
                d.rgb = 0xE8D8D8;
            }
            return;
        }
        int rgb = hit.tint();
        Vec3 carry = bleed.inherit();
        float size = 0.7f + 0.3f * Math.min(1.5f, bleed.amount());
        // back-spatter: a spray out of the entry, back along the round towards the shooter
        int backDrops = count(b.backDrops * hit.ammo().count * scale * amount, random);
        Vec3 backAxis = hit.normal();
        for (int i = 0; i < backDrops; i++) {
            ImpactParticles.Debris d = pool.debris(budget);
            Vec3 way = random.nextFloat() < b.radial ? crown(dir.scale(-1), random) : cone(backAxis, backAxis, b.backSpread, random);
            place(d, at.add(backAxis.scale(0.03)), way.scale(range(b.backSpeed, random) * Math.sqrt(energy) / 20).add(carry));
            bloodDrop(d, b, rgb, plumeRgb, hit.light(), range(b.dropSize, random) * size * hit.ammo().size, bleed.stains(), plain, random);
        }
        // the exit: bigger and faster, along the round
        int exitDrops = count(b.exitDrops * hit.ammo().count * energy * scale * amount, random);
        for (int i = 0; i < exitDrops; i++) {
            ImpactParticles.Debris d = pool.debris(budget);
            Vec3 way = random.nextFloat() < b.radial * 0.6f ? crown(dir, random) : cone(dir, dir, b.exitSpread, random);
            place(d, exit.add(dir.scale(0.03)), way.scale(range(b.exitSpeed, random) * energy / 20).add(carry));
            bloodDrop(d, b, rgb, plumeRgb, hit.light(), range(b.dropSize, random) * size * hit.ammo().size * 1.15f, bleed.stains(), plain, random);
        }
        // the fine aerosol: a fast, narrow burst of tiny drops (two thirds out of the exit), braked hard by the air
        int fine = count(b.fineDrops * hit.ammo().count * energy * scale * amount, random);
        for (int i = 0; i < fine; i++) {
            boolean out = random.nextFloat() < 0.67f;
            Vec3 axis = out ? dir : backAxis;
            ImpactParticles.Debris d = pool.debris(budget);
            Vec3 way = random.nextFloat() < b.radial ? crown(out ? dir : dir.scale(-1), random)
                    : cone(axis, axis, out ? b.exitSpread : b.backSpread * 0.8, random);
            place(d, (out ? exit : at).add(axis.scale(0.03)), way.scale(range(b.fineSpeed, random) * (out ? energy : Math.sqrt(energy)) / 20).add(carry));
            bloodDrop(d, b, rgb, plumeRgb, hit.light(), range(b.fineSize, random) * size, bleed.stains() * 0.5f, plain, random);
            // the finest drops lose their speed within a few ticks
            d.drag = 0.11f + random.nextFloat() * 0.06f;
            d.wind = 0.25f;
        }
        if (dustOn) {
            int mistRgb = ImpactCatalog.rgb(b.mistColor);
            float headMist = bleed.head() ? 1.25f : 1;
            mist(pool, at.add(backAxis.scale(0.05)), backAxis, carry, b.backMist * amount * energy, 0.7f * headMist, b, mistRgb, hit.light(), scale, random);
            mist(pool, exit.add(dir.scale(0.05)), dir, carry, b.exitMist * amount * energy, 1.2f * Math.min(1.6f, energy) * headMist, b, mistRgb,
                    hit.light(), scale, random);
        }
    }

    /**
     * A blade through a living target: no exit wound — blood flung off the blade along the cut (cast-off, in a short line
     * along the wound), a little thrown back out of a stab, a small mist, all moving with the target; under water a plume.
     *
     * @param cut the blade's motion through the wound (unit)
     */
    static void cut(ImpactParticles pool, Hit hit, Vec3 cut, Env env, Bleed bleed, double surface, float scale, int budget,
                    boolean dustOn, boolean bloodInWater, @Nullable TextureAtlasSprite plain, RandomSource random) {
        ImpactData.Blood b = hit.material().blood;
        ImpactData.Ammo blade = hit.ammo();
        Vec3 at = hit.at();
        float amount = bleed.amount() * (bleed.head() ? b.headMultiplier : 1);
        int plumeRgb = ImpactCatalog.rgb(b.plumeColor);
        if (env.underwater()) {
            if (!bloodInWater) return;
            plume(pool, at, cut, Double.isNaN(surface) ? at.y + 2 : surface, b.plume * amount * 0.6f * blade.energy, b, plumeRgb,
                    hit.light(), random);
            return;
        }
        Vec3 carry = bleed.inherit();
        Vec3 out = hit.normal();
        // cast-off leaves along the cut and a little out of the body; a stab's blood comes back out along the blade
        Vec3 axis = blade.thrust ? out : cut.scale(0.85).add(out.scale(0.5)).normalize();
        int drops = count(b.castOff * blade.count * scale * amount, random);
        for (int i = 0; i < drops; i++) {
            ImpactParticles.Debris d = pool.debris(budget);
            // strung out along the wound, the later drops further along the swing
            Vec3 start = blade.thrust ? at : at.add(cut.scale((random.nextFloat() - 0.35f) * 0.22f));
            Vec3 way = blade.thrust && random.nextFloat() < b.radial ? crown(out.scale(-1), random) : cone(axis, axis, blade.thrust ? 35 : 26, random);
            place(d, start.add(out.scale(0.03)), way.scale(range(b.castSpeed, random) * blade.energy / 20).add(carry));
            bloodDrop(d, b, hit.tint(), plumeRgb, hit.light(), range(b.dropSize, random) * blade.size, bleed.stains(), plain, random);
        }
        if (dustOn) {
            mist(pool, at.add(out.scale(0.05)), axis, carry, b.backMist * 0.6f * amount * blade.energy, 0.6f, b, ImpactCatalog.rgb(b.mistColor),
                    hit.light(), scale, random);
        }
    }

    /** One drop of blood: flies, sticks where it lands as a stain (ImpactParticles.stick); into water it clouds out. */
    private static void bloodDrop(ImpactParticles.Debris d, ImpactData.Blood b, int rgb, int plumeRgb, int light, float size, float stains,
                                  @Nullable TextureAtlasSprite plain, RandomSource random) {
        d.size = size;
        d.shape = DROPLET;
        d.fluid = ImpactParticles.BLOOD;
        d.blood = b;
        d.gravity = 1;
        // the air brakes the smaller drops harder
        d.drag = 0.02f + 0.03f * (0.03f - Math.min(0.03f, d.size)) / 0.03f;
        d.restitution = 0;
        d.friction = 0;
        d.wind = 0.06f;
        d.collide = true;
        d.density = 1.06f;
        // in flight at most a few seconds; once landed the stain lives on
        d.life = 100;
        d.stain = Math.round(b.splatLife * 20 * stains * (0.8f + 0.4f * random.nextFloat()));
        d.runs = b.runs;
        d.washRate = b.rainWash;
        d.dry = Math.max(1, Math.round(b.dryTime * 20));
        uv(d, plain, random);
        d.rgb = vary(rgb, random);
        d.light = light;
        d.dustRgb = plumeRgb;
        spin(d, 0, random);
    }

    /**
     * The fine blood mist at a wound: an aerosol cloud thrown out along {@code axis} (and carried with the target),
     * drawn out along it right after the hit, hanging in the air briefly and sinking a little.
     */
    private static void mist(ImpactParticles pool, Vec3 at, Vec3 axis, Vec3 carry, float mass, float throwSpeed, ImpactData.Blood b, int rgb,
                             int light, float scale, RandomSource random) {
        if (mass <= 0.02f) return;
        ImpactParticles.Cloud c = pool.cloud();
        float k = (float) Math.cbrt(mass);
        Vec3 v = cone(axis, axis, 18, random).scale(throwSpeed * (0.8 + 0.4 * random.nextDouble()) / 20).add(carry.scale(0.8));
        c.x = c.px = at.x;
        c.y = c.py = at.y;
        c.z = c.pz = at.z;
        c.vx = (float) v.x;
        c.vy = (float) v.y;
        c.vz = (float) v.z;
        // not off a surface: the lobes spread evenly round the centre
        c.nx = c.ny = c.nz = 0;
        c.ax = (float) axis.x;
        c.ay = (float) axis.y;
        c.az = (float) axis.z;
        c.plume = Mth.clamp(0.5f + 0.5f * throwSpeed, 0.3f, 1.4f);
        float[] size = b.mistSize == null || b.mistSize.length < 2 ? new float[]{0.22f, 0.9f} : b.mistSize;
        c.rBurst = size[0] * (0.55f + 0.45f * k);
        c.rEnd = size[1] * k;
        c.density = b.mistOpacity * 7 * Mth.clamp(k, 0.35f, 1.8f);
        c.rise = 0;
        c.settle = 0.08f / 20;
        c.wind = 0.4f;
        c.seed = random.nextFloat() * 97;
        c.rgb = rgb;
        c.light = light;
        c.life = Math.max(8, Math.round(range(b.mistLife, random) * 20 * (0.7f + 0.3f * Math.min(1.6f, k))));
        c.lobes = (byte) Mth.clamp(Math.round((1 + 2.2f * k) * Math.min(1, 0.4f + 0.6f * scale)), 1, 8);
    }

    /** A plume of blood in water at a wound: pushed a little along {@code axis}, then left to the water (ImpactParticles). */
    private static void plume(ImpactParticles pool, Vec3 at, Vec3 axis, double surface, float mass, ImpactData.Blood b, int rgb,
                              int light, RandomSource random) {
        if (mass <= 0.02f) return;
        plumeInto(pool.cloud(), at, axis, surface, mass, b, rgb, light, random);
    }

    private static void plumeInto(ImpactParticles.Cloud c, Vec3 at, Vec3 axis, double surface, float mass, ImpactData.Blood b, int rgb,
                                  int light, RandomSource random) {
        float k = (float) Math.cbrt(Math.max(0.02f, mass));
        c.x = c.px = at.x;
        c.y = c.py = Math.min(at.y, surface - 0.12);
        c.z = c.pz = at.z;
        Vec3 v = axis.scale(0.6 / 20);
        c.vx = (float) v.x;
        c.vy = (float) v.y;
        c.vz = (float) v.z;
        c.nx = c.ny = c.nz = 0;
        c.ax = (float) axis.x;
        c.ay = (float) axis.y;
        c.az = (float) axis.z;
        c.plume = 0.5f;
        float[] size = b.plumeSize == null || b.plumeSize.length < 2 ? new float[]{0.15f, 1.6f} : b.plumeSize;
        c.rBurst = size[0] * (0.55f + 0.45f * k);
        c.rEnd = size[1] * k;
        c.density = b.plumeOpacity * 5 * Mth.clamp(k, 0.4f, 1.8f);
        c.rise = 0;
        c.settle = 0.03f / 20;
        c.wind = 0;
        c.seed = random.nextFloat() * 97;
        c.rgb = rgb;
        c.light = light;
        c.life = Math.max(40, Math.round(range(b.plumeLife, random) * 20));
        c.lobes = (byte) Mth.clamp(Math.round(1 + 2.5f * k), 1, 8);
        c.water = true;
        c.surface = surface;
    }

    /**
     * A fluid disturbed without a round (rendering spec §12, fluids): an entity wading or swimming through it, jumping or
     * falling in, swinging at it, a block dropped in. A splash event like a round's, without the column unless the
     * energy is high (a heavy fall), its drops thrown along the disturber's motion or facing — a wading body pushes a bow
     * spray ahead, a fall throws a crown all round — and a few bubbles under the surface.
     *
     * @param energy 0..2: 0.2 a hand swing, 0.5 walking through, 1 a jump or fall, 2 a heavy fall or a block dropped in
     */
    static void disturb(ImpactParticles pool, Hit hit, float energy, float scale, int budget, @Nullable TextureAtlasSprite plain,
                        RandomSource random) {
        ImpactData.Splash splash = hit.material().splash;
        if (splash == null || energy <= 0) return;
        boolean lava = "lava".equals(splash.kind);
        Vec3 at = hit.at(), dir = hit.dir();
        Vec3 flat = new Vec3(dir.x, 0, dir.z);
        double horizontal = flat.length();
        flat = horizontal > 1e-4 ? flat.scale(1 / horizontal) : Vec3.ZERO;
        // wading spray, a case falling in: only into a free slot (never pushes out a round's splash); a fall takes one
        ImpactParticles.Splash s = energy < 1 ? pool.smallSplash() : pool.splash();
        if (s == null) return;
        s.x = at.x;
        s.y = at.y;
        s.z = at.z;
        s.kind = lava ? ImpactParticles.LAVA : ImpactParticles.WATER;
        // a body moves more water than a round, slower: a wider, lower crown
        s.energy = 0.2f + 0.45f * energy;
        s.secondary = energy < 1.2f;
        s.foam = lava ? 0 : Mth.clamp((energy - 1) * 0.6f, 0, 1) * splash.foam;
        s.sheet = splash.sheet * (0.6f + 0.3f * Math.min(2, energy));
        s.column = energy >= 1.2f ? splash.column * 0.5f : 0;
        s.ripple = splash.ripple * 0.8f;
        s.seed = random.nextFloat() * 97;
        s.rgb = hit.tint();
        s.light = hit.light();
        s.life = Math.max(10, Math.round(splash.rippleLife * 20 * (0.8f + 0.4f * Math.min(2, energy))));

        // drops: along the motion (a bow spray) the more horizontal it is, all round and upwards the more vertical
        Vec3 up = new Vec3(0, 1, 0);
        int drops = count(splash.drops * 0.6f * energy * scale, random);
        for (int i = 0; i < drops; i++) {
            Vec3 axis;
            double spread;
            if (horizontal > 0.35 && random.nextFloat() < horizontal) {
                axis = flat.scale(0.85).add(up.scale(0.75)).normalize();
                spread = 32;
            } else {
                double a = random.nextDouble() * Math.PI * 2;
                axis = new Vec3(Math.cos(a) * 0.55, 0.85, Math.sin(a) * 0.55).normalize();
                spread = 18;
            }
            Vec3 v = cone(axis, up, spread, random).scale(splash.dropSpeed * (0.35 + 0.4 * energy) * (0.55 + 0.6 * random.nextDouble()) / 20);
            ImpactParticles.Debris d = pool.debris(budget);
            place(d, at.add(v.x * 2, 0.03, v.z * 2), v);
            fluidDrop(d, lava, hit.tint(), hit.light(), 0.8f + 0.3f * Math.min(2, energy), random);
        }
        if (!lava) {
            // air pulled under by the body comes back up
            int bubbles = count(4 * energy * scale, random);
            for (int i = 0; i < bubbles; i++) {
                bubble(pool.debris(budget), at.add((random.nextFloat() - 0.5) * 0.5, -0.2 - random.nextFloat() * 0.5, (random.nextFloat() - 0.5) * 0.5),
                        new Vec3(0, 0.02, 0), 0.012f + random.nextFloat() * 0.025f, 20 + random.nextInt(30), hit.light(), plain, random);
            }
        }
    }

    /**
     * A round's way through water (rendering spec §12, rounds in fluids): the air its cavity drags in comes off it as a
     * trail of fine bubbles — dense where it went in, thinning as it slows ({@code energy} at each end of the stretch).
     */
    static void trail(ImpactParticles pool, Vec3 from, Vec3 to, float energyFrom, float energyTo, float scale, int budget, int light,
                      @Nullable TextureAtlasSprite plain, RandomSource random) {
        double length = from.distanceTo(to);
        if (length < 0.05) return;
        Vec3 dir = to.subtract(from).scale(1 / length);
        float perBlock = ImpactCatalog.data().environment.trailBubbles;
        int bubbles = Math.min(60, count((float) (perBlock * length * (energyFrom + energyTo) * 0.5f * scale), random));
        for (int i = 0; i < bubbles; i++) {
            // more of them near the entry, where the round was fastest
            float t = random.nextFloat() * random.nextFloat() * 0.6f + random.nextFloat() * 0.4f;
            Vec3 p = from.add(dir.scale(length * t)).add((random.nextFloat() - 0.5) * 0.03, (random.nextFloat() - 0.5) * 0.03,
                    (random.nextFloat() - 0.5) * 0.03);
            float e = Mth.lerp(t, energyFrom, energyTo);
            bubble(pool.debris(budget), p, cone(dir, dir, 70, random).scale((0.005 + 0.02 * random.nextFloat()) * e),
                    0.005f + random.nextFloat() * 0.011f * (0.5f + e), 25 + random.nextInt(35), light, plain, random);
        }
    }

    /**
     * A round spent in water: a last puff of bubbles where it stopped and the slug itself, sinking slowly, tumbling,
     * coming to rest on the bottom.
     */
    static void spent(ImpactParticles pool, Vec3 at, Vec3 dir, float scale, int budget, int light, @Nullable TextureAtlasSprite plain,
                      RandomSource random) {
        int bubbles = count(4 * scale, random);
        for (int i = 0; i < bubbles; i++) {
            bubble(pool.debris(budget), at, cone(dir, dir, 80, random).scale(0.01 + 0.02 * random.nextFloat()),
                    0.008f + random.nextFloat() * 0.012f, 20 + random.nextInt(30), light, plain, random);
        }
        ImpactParticles.Debris d = pool.debris(budget);
        place(d, at, dir.scale(0.04));
        d.size = 0.012f;
        d.shape = CHIP;
        d.gravity = 1;
        d.drag = 0.2f;
        d.restitution = 0.05f;
        d.friction = 0.3f;
        d.wind = 0;
        d.collide = true;
        d.density = 9;
        d.life = 80 + random.nextInt(40);
        uv(d, plain, random);
        d.rgb = 0x9a7a4a;
        d.light = light;
        spin(d, 400, random);
    }

    /** A drop falling from a wound that goes on bleeding (Wounds): it leaves with the body's motion, falls and stains. */
    static void bleed(ImpactParticles pool, Vec3 at, Vec3 motion, ImpactData.Blood b, int rgb, int light, float size, float stains,
                      int budget, @Nullable TextureAtlasSprite plain, RandomSource random) {
        ImpactParticles.Debris d = pool.debris(budget);
        place(d, at, motion.scale(0.8).add((random.nextFloat() - 0.5) * 0.01, -0.01, (random.nextFloat() - 0.5) * 0.01));
        bloodDrop(d, b, rgb, ImpactCatalog.rgb(b.plumeColor), light, range(b.dropSize, random) * size * 0.8f, stains, plain, random);
        // nothing runs from these: they fall straight down
        d.runs = 0;
    }

    /** A wound under water seeping: a small plume of blood drifting off it. */
    static void seep(ImpactParticles pool, Vec3 at, ImpactData.Blood b, int light, RandomSource random) {
        ImpactParticles.Cloud c = pool.smallCloud();
        if (c == null) return;
        double surface = at.y + 2;
        plumeInto(c, at, cone(new Vec3(0, -1, 0), new Vec3(0, -1, 0), 80, random), surface, b.plume * 0.15f, b,
                ImpactCatalog.rgb(b.plumeColor), light, random);
        c.small = true;
    }

    /**
     * A foot coming down on rain-soaked ground: a few small drops kicked up and back from it, against the step's motion
     * (blocks per tick); {@code energy} 0..1 by how fast it ran.
     */
    static void kick(ImpactParticles pool, Vec3 at, Vec3 motion, float energy, int rgb, int light, int budget, RandomSource random) {
        Vec3 flat = new Vec3(motion.x, 0, motion.z);
        Vec3 back = flat.lengthSqr() > 1e-6 ? flat.normalize().scale(-0.5) : Vec3.ZERO;
        Vec3 axis = back.add(0, 1, 0).normalize();
        int drops = count(2 + 4 * energy, random);
        for (int i = 0; i < drops; i++) {
            ImpactParticles.Debris d = pool.debris(budget);
            place(d, at.add((random.nextFloat() - 0.5) * 0.25, 0.03, (random.nextFloat() - 0.5) * 0.25),
                    cone(axis, new Vec3(0, 1, 0), 40, random).scale((0.8 + 1.4 * random.nextFloat()) * (0.5 + 0.5 * energy) / 20).add(flat.scale(0.3)));
            fluidDrop(d, false, rgb, light, 0.35f + 0.25f * random.nextFloat(), random);
            d.life = 14 + random.nextInt(8);
        }
    }

    /** A drop of water falling off something soaked, leaving with part of its motion (blocks per tick). */
    static void drip(ImpactParticles pool, Vec3 at, Vec3 motion, int rgb, int light, int budget, RandomSource random) {
        ImpactParticles.Debris d = pool.debris(budget);
        place(d, at, motion.scale(0.6).add((random.nextFloat() - 0.5) * 0.004, -0.004, (random.nextFloat() - 0.5) * 0.004));
        fluidDrop(d, false, rgb, light, 0.45f + random.nextFloat() * 0.3f, random);
        d.life = 30 + random.nextInt(12);
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

        // a slanting round throws its splash forward, along its way over the surface
        Vec3 flat = new Vec3(hit.dir().x, 0, hit.dir().z);
        double slant = Math.min(1, flat.length() * 1.2);
        flat = flat.lengthSqr() > 1e-6 ? flat.normalize() : Vec3.ZERO;
        int drops = count(splash.drops * hit.ammo().count * scale * Math.min(2, energy), random);
        for (int i = 0; i < drops; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double up = Math.toRadians((lava ? 40 : 50) + random.nextDouble() * 28);
            Vec3 v = new Vec3(Math.cos(a) * Math.cos(up), Math.sin(up), Math.sin(a) * Math.cos(up))
                    .add(flat.scale(0.45 * slant)).normalize()
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
                bubble(pool.debris(budget), at.add((random.nextFloat() - 0.5) * 0.3, -0.25 - random.nextFloat() * 0.4, (random.nextFloat() - 0.5) * 0.3),
                        new Vec3(0, 0.02, 0), 0.015f + random.nextFloat() * 0.03f, 20 + random.nextInt(30), hit.light(), plain, random);
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
        d.density = lava ? 2.8f : 1;
        d.life = lava ? 70 + random.nextInt(50) : 40 + random.nextInt(14);
        d.u0 = d.v0 = d.u1 = d.v1 = 0;
        d.rgb = rgb;
        d.light = light;
        spin(d, 0, random);
    }

    // ---- pieces -------------------------------------------------------------------------------------

    /** A chunk of the struck material: its shape, size and how it flies, bounces and settles (in air and water). */
    private static void chunk(ImpactParticles.Debris d, ImpactData.Debris debris, float sizeScale, float bounce, RandomSource random) {
        d.size = range(debris.size, random) * sizeScale;
        d.shape = shape(debris.shapes, random);
        d.gravity = debris.gravity;
        d.drag = debris.drag;
        d.restitution = debris.restitution * bounce;
        d.friction = debris.friction;
        d.wind = debris.wind;
        d.density = debris.density;
        d.collide = true;
        spin(d, debris.spin, random);
        d.life = Math.max(2, Math.round(range(debris.life, random) * 20));
    }

    /** A bubble of air in water (rises at its size's speed, wobbles, pops at the surface: ImpactParticles). */
    private static void bubble(ImpactParticles.Debris d, Vec3 at, Vec3 v, float size, int life, int light,
                               @Nullable TextureAtlasSprite plain, RandomSource random) {
        place(d, at, v);
        d.size = size;
        d.shape = BUBBLE;
        d.bubble = true;
        d.collide = false;
        d.density = 0;
        d.life = life;
        uv(d, plain, random);
        d.rgb = 0xDDEEFF;
        d.light = light;
        spin(d, 0, random);
    }

    private static void spark(ImpactParticles.Spark s, Vec3 origin, Vec3 v, ImpactData.Sparks sparks, float quench, int rgb, RandomSource random) {
        s.x = s.px = origin.x;
        s.y = s.py = origin.y;
        s.z = s.pz = origin.z;
        s.vx = (float) v.x;
        s.vy = (float) v.y;
        s.vz = (float) v.z;
        s.gravity = sparks.gravity;
        s.width = 0.008f + random.nextFloat() * 0.01f;
        s.life = Math.max(2, Math.round(range(sparks.life, random) * 20 * quench));
        s.bounces = sparks.bounce ? 1 : 0;
        s.rgb = vary(rgb, random);
    }

    private static void droplet(ImpactParticles.Debris d, @Nullable TextureAtlasSprite plain, int rgb, int light, RandomSource random) {
        d.size = 0.018f + random.nextFloat() * 0.022f;
        d.shape = DROPLET;
        d.gravity = 1;
        d.drag = 0.01f;
        d.restitution = 0;
        d.friction = 0;
        d.wind = 0.05f;
        d.collide = true;
        d.density = 1.4f;
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

    /**
     * A direction of the spray: within {@code halfAngle} of {@code axis}, then drawn out downrange (along the round's path
     * over the surface) the more glancing the hit — ejecta of an oblique hit fan out that way — kept out of the surface.
     */
    private static Vec3 spray(Vec3 axis, Vec3 n, Vec3 downrange, double halfAngle, double grazing, RandomSource random) {
        Vec3 v = cone(axis, n, halfAngle, random);
        if (downrange == Vec3.ZERO || grazing < 0.05) return v;
        double along = v.dot(downrange);
        v = v.add(downrange.scale(Math.max(along, 0) * 1.2 * grazing + 0.15 * grazing)).normalize();
        double into = v.dot(n);
        return into < 0.05 ? v.add(n.scale(0.05 - into * 2)).normalize() : v;
    }

    /**
     * A direction out sideways round {@code axis}: 60° to 95° off it, any way round — up, down, left, right — the crown a
     * wound throws round the round's line.
     */
    static Vec3 crown(Vec3 axis, RandomSource random) {
        double off = Math.toRadians(60 + random.nextDouble() * 35);
        double phi = random.nextDouble() * Math.PI * 2;
        Vec3 t = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0).cross(axis).normalize() : new Vec3(1, 0, 0).cross(axis).normalize();
        Vec3 b = axis.cross(t);
        return axis.scale(Math.cos(off)).add(t.scale(Math.cos(phi) * Math.sin(off))).add(b.scale(Math.sin(phi) * Math.sin(off)));
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

    static float range(float[] range, RandomSource random) {
        if (range == null || range.length == 0) return 1;
        if (range.length == 1) return range[0];
        return range[0] + (range[1] - range[0]) * random.nextFloat();
    }

    /** Rounds {@code amount} at random: 2.3 gives 2 or 3 (3 three times in ten). */
    static int count(float amount, RandomSource random) {
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
