package com.blockland.client.bomb.fx;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * One bomb explosion, staged over about twelve seconds (sizes scale with the blast radius, 16 blocks = 1):
 * <ul>
 * <li>0–0.25 s: a blinding flash; the fireball — some thirty puffs of gas thrown out from the charge — swells in a
 * tenth of a second, white-yellow at its core, while sparks and floor debris fly and a dust ring races over the
 * ground;</li>
 * <li>0.25–1.5 s: the gas cools through orange and deep red to black smoke as it rises; the fire lights its own smoke
 * from inside;</li>
 * <li>1.5–12 s: the smoke rolls up into a mushroom — a column and a cap whose puffs turn over in a torus — drifts with
 * a breath of wind, greys and thins out; the dust cloud settles; long-lived embers float in the updraft.</li>
 * </ul>
 * The puffs move analytically with time (smooth at any frame rate); the sparks are simulated per tick and bounce off the
 * blocks. Nothing here touches the world.
 */
final class BombBlast {
    /** Seconds until everything is gone. */
    private static final float LIFE = 13;
    private static final float GRAVITY = 0.045f;

    private final Vec3 at;
    /** Top of the floor under the charge, or NaN in the air. */
    private final double floorY;
    private final float k;
    private final List<Gas> gas = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final float windX, windZ;
    private int ticks;

    /** A puff: fireball gas (turning into the mushroom's cap or column) or floor dust. */
    private static final class Gas {
        boolean dust;
        boolean cap;
        /** Burst: direction and reach of the first throw. */
        float dx, dy, dz, reach;
        /** Mushroom: angle round the axis, place in the column / phase round the cap's torus. */
        float angle, column, phase;
        float size, seed, cool, life, delay;
    }

    private static final class Spark {
        final Vector3f pos = new Vector3f(), prev = new Vector3f(), vel = new Vector3f();
        float age, life;
        boolean ember;
        int bounces;
        float flicker;
    }

    BombBlast(ClientLevel level, Vec3 at, double floorY, float radius) {
        this.at = at;
        this.floorY = floorY;
        this.k = Mth.clamp(radius / 16f, 0.35f, 2.5f);
        RandomSource random = level.random;
        float wind = random.nextFloat() * Mth.TWO_PI;
        windX = Mth.cos(wind) * 0.35f;
        windZ = Mth.sin(wind) * 0.35f;

        int fire = Math.round(30 * Math.min(1.4f, 0.6f + 0.4f * k));
        for (int i = 0; i < fire; i++) {
            Gas g = new Gas();
            // thrown out in a hemisphere, more upward than sideways (the floor reflects the blast)
            float a = random.nextFloat() * Mth.TWO_PI;
            float y = 0.12f + random.nextFloat() * 0.88f;
            float h = Mth.sqrt(1 - y * y);
            g.dx = Mth.cos(a) * h;
            g.dy = y;
            g.dz = Mth.sin(a) * h;
            g.reach = (1.6f + random.nextFloat() * 3.6f) * k;
            g.cap = random.nextFloat() < 0.62f;
            g.angle = a + (random.nextFloat() - 0.5f) * 0.6f;
            g.column = 0.1f + random.nextFloat() * 0.85f;
            g.phase = random.nextFloat() * Mth.TWO_PI;
            g.size = (2.0f + random.nextFloat() * 1.7f) * k;
            g.seed = random.nextFloat();
            g.cool = 0.32f + random.nextFloat() * 0.5f;
            g.life = 9 + random.nextFloat() * 3.5f;
            g.delay = random.nextFloat() * 0.06f;
            gas.add(g);
        }
        if (!Double.isNaN(floorY)) {
            int dust = Math.round(18 * Math.min(1.4f, 0.6f + 0.4f * k));
            for (int i = 0; i < dust; i++) {
                Gas g = new Gas();
                g.dust = true;
                float a = (i + random.nextFloat() * 0.8f) / dust * Mth.TWO_PI;
                g.dx = Mth.cos(a);
                g.dz = Mth.sin(a);
                g.reach = (6 + random.nextFloat() * 6) * k;
                g.size = (1.6f + random.nextFloat() * 1.5f) * k;
                g.seed = random.nextFloat();
                g.life = 5.5f + random.nextFloat() * 3.5f;
                g.delay = 0.02f + random.nextFloat() * 0.08f;
                gas.add(g);
            }
        }
        int sparkCount = Math.round(150 * Math.min(1.5f, k));
        for (int i = 0; i < sparkCount; i++) {
            Spark s = new Spark();
            float a = random.nextFloat() * Mth.TWO_PI;
            float y = -0.05f + random.nextFloat() * 1.05f;
            float h = Mth.sqrt(Math.max(0, 1 - y * y));
            float speed = (0.55f + random.nextFloat() * 1.35f) * Mth.sqrt(k);
            s.vel.set(Mth.cos(a) * h * speed, y * speed + 0.15f, Mth.sin(a) * h * speed);
            s.pos.set((float) at.x, (float) at.y + 0.3f, (float) at.z);
            s.prev.set(s.pos);
            s.life = 0.6f + random.nextFloat() * 1.8f;
            s.flicker = random.nextFloat() * 10;
            sparks.add(s);
        }
        int embers = Math.round(55 * Math.min(1.5f, k));
        for (int i = 0; i < embers; i++) {
            Spark s = new Spark();
            s.ember = true;
            float a = random.nextFloat() * Mth.TWO_PI;
            float speed = (0.08f + random.nextFloat() * 0.3f) * k;
            s.vel.set(Mth.cos(a) * speed, 0.1f + random.nextFloat() * 0.35f, Mth.sin(a) * speed);
            s.pos.set((float) at.x + (random.nextFloat() - 0.5f) * 2 * k, (float) at.y + 0.5f + random.nextFloat() * 2 * k,
                    (float) at.z + (random.nextFloat() - 0.5f) * 2 * k);
            s.prev.set(s.pos);
            s.life = 2.5f + random.nextFloat() * 4.5f;
            s.flicker = random.nextFloat() * 10;
            sparks.add(s);
        }
    }

    /** Client tick; false when it is over. */
    boolean tick(ClientLevel level) {
        ticks++;
        float t = ticks / 20f;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        sparks.removeIf(s -> {
            s.age += 0.05f;
            if (s.age >= s.life) return true;
            s.prev.set(s.pos);
            if (s.ember) {
                // carried up by the hot column, swirling, slowing
                float lift = Math.max(0, 1 - t / 5f) * 0.012f;
                s.vel.mul(0.93f);
                s.vel.add((float) Math.sin(s.flicker + t * 3) * 0.006f, lift - 0.003f, (float) Math.cos(s.flicker * 1.3f + t * 2.6f) * 0.006f);
            } else {
                s.vel.mul(0.975f);
                s.vel.y -= GRAVITY;
            }
            s.pos.add(s.vel);
            cell.set(s.pos.x, s.pos.y, s.pos.z);
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                // into a block: bounce off the floor, glance off a wall; spent after a couple of bounces
                cell.set(s.pos.x, s.prev.y, s.pos.z);
                boolean wall = !level.getBlockState(cell).getCollisionShape(level, cell).isEmpty();
                if (wall) {
                    s.pos.x = s.prev.x;
                    s.pos.z = s.prev.z;
                    s.vel.x *= -0.3f;
                    s.vel.z *= -0.3f;
                } else {
                    s.pos.y = s.prev.y;
                    s.vel.y *= -0.32f;
                    s.vel.x *= 0.55f;
                    s.vel.z *= 0.55f;
                }
                if (++s.bounces > 2 || s.ember) s.life = Math.min(s.life, s.age + 0.25f);
            }
            return false;
        });
        return t < LIFE;
    }

    /** Size of this blast against the default one (radius 16 = 1). */
    float scale() {
        return k;
    }

    /** Seconds since it went off (smooth between ticks). */
    float time(float partialTick) {
        return (ticks + partialTick) / 20f;
    }

    /** Adds this frame's puffs, sparks and glows; returns the fire's strength now (for its light on the smoke). */
    float render(BombFxRenderer.Frame frame, ClientLevel level, Vec3 cam, float partialTick, Vector3f fireSum) {
        float t = time(partialTick);
        float cx = (float) (at.x - cam.x), cy = (float) (at.y - cam.y), cz = (float) (at.z - cam.z);
        BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
        // a puff poking into a wall keeps the light of the open air round the charge
        lightPos.set(at.x, at.y + 0.5, at.z);
        int openLight = LevelRenderer.getLightColor(level, lightPos);
        float fire = 0;

        for (Gas g : gas) {
            float s = t - g.delay;
            if (s <= 0) continue;
            float x, y, z, size, heat, density, soot;
            if (g.dust) {
                // shoved out along the floor by the shock, then billowing slowly, low
                float out = g.reach * (1 - (float) Math.exp(-s / 0.5f));
                size = g.size * (0.35f + 0.65f * (1 - (float) Math.exp(-s / 0.25f))) * (1 + 0.12f * s);
                x = g.dx * out + windX * s * 0.5f;
                z = g.dz * out + windZ * s * 0.5f;
                y = (float) (floorY - at.y) + size * 0.42f + 0.12f * s;
                heat = 0;
                density = 0.6f * (1 - smooth(g.life * 0.45f, g.life, s));
                soot = 0.12f + 0.12f * g.seed;
            } else {
                // the burst: thrown out fast, braked by the air
                float throwOut = 1 - (float) Math.exp(-s / 0.11f);
                float bx = g.dx * g.reach * throwOut, by = g.dy * g.reach * throwOut * 0.8f + 0.9f * s * s, bz = g.dz * g.reach * throwOut;
                // the mushroom: a column and a cap rising and spreading, the cap's puffs rolling over in a torus
                float rise = 13.5f * k * (1 - (float) Math.exp(-s / 2.6f));
                float mx, my, mz;
                if (g.cap) {
                    float ring = (1.6f + 3.4f * (1 - (float) Math.exp(-s / 2.8f))) * k;
                    float tube = (1.1f + 0.7f * Math.min(1, s / 5)) * k;
                    float roll = g.phase + 0.8f * s;
                    float spread = ring + tube * Mth.cos(roll);
                    mx = Mth.cos(g.angle) * spread;
                    mz = Mth.sin(g.angle) * spread;
                    my = rise + tube * Mth.sin(roll) * 0.8f;
                } else {
                    float stem = (0.9f + 0.25f * Math.min(4, s)) * k;
                    mx = Mth.cos(g.angle) * stem;
                    mz = Mth.sin(g.angle) * stem;
                    my = rise * g.column;
                }
                float w = smooth(0.3f, 2.2f, s);
                float drift = s * s * 0.035f;
                x = Mth.lerp(w, bx, mx) + windX * drift;
                y = Mth.lerp(w, by, my);
                z = Mth.lerp(w, bz, mz) + windZ * drift;
                size = g.size * (0.22f + 0.78f * (1 - (float) Math.exp(-s / 0.09f))) * (1 + 0.15f * s);
                // white hot, cooling fast; the core burns a little longer
                heat = Mth.clamp((float) Math.exp(-s / g.cool) * 1.15f, 0, 1);
                density = 0.95f * (1 - smooth(g.life * 0.5f, g.life, s)) / (1 + 0.05f * s);
                soot = Mth.lerp(smooth(1.5f, 9f, s), 0.92f, 0.5f);
                fire += heat;
                fireSum.add((cx + x) * heat, (cy + y) * heat, (cz + z) * heat);
            }
            float px = cx + x, py = cy + y, pz = cz + z;
            lightPos.set(at.x + x, at.y + y, at.z + z);
            frame.puff(px, py, pz, size, heat, density, g.seed, soot, brighter(LevelRenderer.getLightColor(level, lightPos), openLight));
        }

        // the shock: a dust ring racing out over the floor
        if (!Double.isNaN(floorY) && t < 1.2f) {
            float r = 17 * k * (1 - (float) Math.exp(-t / 0.2f)) + 0.5f;
            float opacity = 0.75f * (1 - t / 1.2f);
            lightPos.set(at.x, floorY + 0.5, at.z);
            frame.ring(cx, (float) (floorY - cam.y) + 0.08f, cz, r, opacity, 0.45f + 0.35f * t / 1.2f, 0.37f, 0.2f,
                    LevelRenderer.getLightColor(level, lightPos));
        }

        // the flash: a blinding white point and a wide warm glow, gone in a quarter of a second
        if (t < 0.3f) {
            float a = t < 0.04f ? 1 : 1 - (t - 0.04f) / 0.26f;
            float grow = 0.6f + 0.4f * Math.min(1, t / 0.04f);
            frame.disc(cx, cy + 1.2f * k, cz, 13 * k * grow, 0xFFD9A8, 2 * a);
            frame.disc(cx, cy + 1.0f * k, cz, 4.5f * k * grow, 0xFFFFFF, 2 * a);
        }
        // the fireball's own glow in the air around it while it burns
        if (fire > 0.05f && t < 2) {
            float strength = Math.min(1, fire / Math.max(1, gas.size() * 0.5f));
            frame.disc(cx, cy + 2.2f * k + t * 1.5f * k, cz, 8 * k, 0xFF8A3C, 0.9f * strength);
        }

        Vector3f tail = new Vector3f(), head = new Vector3f();
        for (Spark s : sparks) {
            float life = s.age / s.life;
            head.set(s.prev).lerp(s.pos, partialTick).sub((float) cam.x, (float) cam.y, (float) cam.z);
            float temperature = 1 - life;
            int rgb = sparkColor(temperature);
            if (s.ember) {
                float flicker = 0.65f + 0.35f * Mth.sin(s.flicker + t * 17) * Mth.sin(s.flicker * 0.7f + t * 9.3f);
                float fade = Math.min(1, (s.life - s.age) / 0.8f) * Math.min(1, s.age / 0.2f);
                tail.set(s.vel).mul(-1.2f).add(head);
                frame.spark(tail, head, 0.035f * k, rgb, (0.5f + 0.6f * temperature) * flicker * fade);
            } else {
                // motion streak: about a tick of travel
                tail.set(s.vel).mul(-1.3f).add(head);
                float fade = Math.min(1, (s.life - s.age) / 0.25f);
                frame.spark(tail, head, 0.05f * Mth.sqrt(k), rgb, (0.6f + 1.2f * temperature * temperature) * fade);
            }
        }
        return fire;
    }

    /** White-yellow when fresh, through orange to a dull red as it cools. */
    private static int sparkColor(float temperature) {
        float t = Mth.clamp(temperature, 0, 1);
        float r = 1, g, b;
        if (t > 0.6f) {
            float u = (t - 0.6f) / 0.4f;
            g = Mth.lerp(u, 0.62f, 0.93f);
            b = Mth.lerp(u, 0.18f, 0.62f);
        } else {
            float u = t / 0.6f;
            r = Mth.lerp(u, 0.75f, 1);
            g = Mth.lerp(u, 0.16f, 0.62f);
            b = Mth.lerp(u, 0.03f, 0.18f);
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    /** The brighter of two packed lights, per channel. */
    private static int brighter(int a, int b) {
        return Math.max(a & 0xFFFF, b & 0xFFFF) | Math.max(a >>> 16, b >>> 16) << 16;
    }

    private static float smooth(float from, float to, float x) {
        float u = Mth.clamp((x - from) / (to - from), 0, 1);
        return u * u * (3 - 2 * u);
    }
}
