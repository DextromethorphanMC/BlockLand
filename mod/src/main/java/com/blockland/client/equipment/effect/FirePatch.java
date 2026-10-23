package com.blockland.client.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.equipment.effect.FireSpread;
import com.blockland.equipment.effect.Params;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A molotov's fire on the client: the same cells as the server ({@link FireSpread}), catching one after another from
 * the impact, each with two or three licking flames (an 8-frame flipbook on upright quads, drawn at full brightness)
 * over an additive glow on the floor, rising smoke and embers, and a crackling loop. Timed by the server's active
 * ticks; when its entity goes early (smoke put it out) the flames shrink away in a puff of steam.
 */
final class FirePatch implements FxWorld.Fx {
    private static final float CATCH = 0.25f, DIE = 0.8f;

    private final int entityId;
    private final Vec3 impact;
    private final float radius, duration, spread;
    private final int maxFall;
    private List<FireSpread.Cell> cells = List.of();
    private boolean built;
    private float age;
    private int unseenTicks;
    private float outAge = -1;
    private Loop loop;

    FirePatch(int entityId, Vec3 impact, JsonObject params) {
        this.entityId = entityId;
        this.impact = impact;
        this.radius = Params.f(params, "radius", 3.6f);
        this.duration = Params.f(params, "duration", 7);
        this.spread = Math.max(0.05f, Params.f(params, "spreadTime", 0.6f));
        this.maxFall = Params.i(params, "maxFall", 6);
    }

    @Override
    public int entityId() {
        return outAge < 0 ? entityId : -1;
    }

    void keep(int activeTicks) {
        unseenTicks = 0;
        float server = activeTicks / 20f;
        if (Math.abs(server - age) > 0.25f) age = server;
    }

    @Override
    public boolean tick(ClientLevel level) {
        if (!built) {
            built = true;
            cells = FireSpread.cells(level, impact, radius, maxFall);
            if (!cells.isEmpty()) {
                FireSpread.Cell c = cells.get(0);
                loop = new Loop(this, c.centreX(), c.floorY() + 0.3, c.centreZ());
                Minecraft.getInstance().getSoundManager().play(loop);
            }
        }
        age += 0.05f;
        if (outAge < 0 && ++unseenTicks > 3) {
            outAge = 0;
            // put out early: steam over every burning cell
            if (age < duration - 0.3f) {
                for (FireSpread.Cell c : cells) {
                    if (scale(c, age) > 0.2f) level.addParticle(ParticleTypes.CLOUD, c.centreX(), c.floorY() + 0.3, c.centreZ(),
                            0, 0.06 + level.random.nextFloat() * 0.05, 0);
                }
            }
        }
        if (outAge >= 0) outAge += 0.05f;
        RandomSource random = level.random;
        for (FireSpread.Cell c : cells) {
            float s = scale(c, age);
            if (s < 0.3f) continue;
            double x = c.x() + 0.2 + random.nextFloat() * 0.6, z = c.z() + 0.2 + random.nextFloat() * 0.6;
            if (random.nextFloat() < 0.07f * s) level.addParticle(ParticleTypes.LARGE_SMOKE, x, c.floorY() + 0.9, z, 0, 0.04, 0);
            if (random.nextFloat() < 0.05f * s) level.addParticle(ParticleTypes.FLAME, x, c.floorY() + 0.2, z, 0, 0.02, 0);
            if (random.nextFloat() < 0.012f * s) level.addParticle(ParticleTypes.LAVA, x, c.floorY() + 0.1, z, 0, 0, 0);
        }
        return strength() > 0.01f && !cells.isEmpty();
    }

    /** 1 while burning, shrinking to 0 at the end (or when put out). */
    float strength() {
        float s = Mth.clamp((duration - age) / DIE, 0, 1);
        if (outAge >= 0) s *= Mth.clamp(1 - outAge / 0.6f, 0, 1);
        return s;
    }

    /** How big the cell's flames are now: catching after its delay, then the patch's strength. */
    private float scale(FireSpread.Cell cell, float t) {
        float since = t - cell.delay() * spread;
        if (since <= 0) return 0;
        return Math.min(1, since / CATCH) * strength();
    }

    @Override
    public void render(FxDraw draw) {
        float t = age + draw.partialTick * 0.05f;
        for (FireSpread.Cell cell : cells) {
            float s = scale(cell, t);
            if (s <= 0.01f) continue;
            int seed = cell.x() * 73856093 ^ cell.y() * 19349663 ^ cell.z() * 83492791;
            // glow on the floor
            int glow = Math.round(110 * s);
            draw.flat(FxRenderTypes.glow(FxRenderTypes.GLOW), cell.centreX(), cell.floorY() + 0.02, cell.centreZ(), 1.05f, seed * 0.1f,
                    glow << 24 | 0xFF8A30, FxDraw.FULL_BRIGHT);
            int flames = 2 + (seed & 1);
            for (int i = 0; i < flames; i++) {
                int h = seed * (i + 7) * 0x9E3779B1;
                float ox = ((h >>> 8) & 255) / 255f, oz = ((h >>> 16) & 255) / 255f, k = ((h >>> 24) & 255) / 255f;
                float flicker = 0.85f + 0.15f * (float) Math.sin(t * (9 + k * 5) + k * 40) + 0.08f * (float) Math.sin(t * 23 + i);
                float height = (0.85f + k * 0.7f) * s * flicker, width = (0.6f + k * 0.35f) * (0.6f + 0.4f * s);
                int frame = (int) (t * (13 + k * 4) + k * 8) & 7;
                float u0 = frame / 8f;
                int alpha = Math.round(235 * Math.min(1, s * 1.4f));
                draw.upright(FxRenderTypes.soft(FxRenderTypes.FLAME), cell.x() + 0.15 + ox * 0.7, cell.floorY() - 0.05, cell.z() + 0.15 + oz * 0.7,
                        width, height, u0, 0, u0 + 1 / 8f, 1, alpha << 24 | 0xFFFFFF, FxDraw.FULL_BRIGHT);
            }
        }
    }

    /** The crackling loop, following the patch's strength. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private final FirePatch patch;

        Loop(FirePatch patch, double x, double y, double z) {
            super(Sounds.byId(BlockLand.id("equipment.fire_loop")), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.patch = patch;
            this.looping = true;
            this.delay = 0;
            this.x = x;
            this.y = y;
            this.z = z;
            this.volume = 0.01f;
        }

        @Override
        public void tick() {
            float s = patch.strength();
            if (s <= 0.01f || patch.cells.isEmpty() || !FxWorld.all().contains(patch)) {
                stop();
                return;
            }
            volume = 1.1f * s;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
