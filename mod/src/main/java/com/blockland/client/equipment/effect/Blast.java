package com.blockland.client.equipment.effect;

import com.blockland.client.decal.Decals;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A grenade going off. A frag: a white-hot flash, a cluster of fireballs that swell in a tenth of a second, turn
 * orange and dark and roll up as smoke, a dust ring racing out over the ground, debris of the floor block, sparks, and
 * a scorch mark. A flashbang: a blinding point of light with a white flare and a puff of smoke. Lives on its own
 * after the entity is gone.
 */
final class Blast implements FxWorld.Fx {
    private static final int BALLS = 9;

    private final boolean frag;
    private final Vec3 at;
    /** Floor under the blast (y), or NaN when it went off in the air. */
    private final double floorY;
    private final float[] ballX = new float[BALLS], ballY = new float[BALLS], ballZ = new float[BALLS], ballSize = new float[BALLS];
    private final int[] ballSprite = new int[BALLS];
    private final float spin;
    private float age;

    private Blast(boolean frag, Vec3 at, double floorY, RandomSource random) {
        this.frag = frag;
        this.at = at;
        this.floorY = floorY;
        for (int i = 0; i < BALLS; i++) {
            ballX[i] = (random.nextFloat() - 0.5f) * 1.6f;
            ballY[i] = random.nextFloat() * 1.3f;
            ballZ[i] = (random.nextFloat() - 0.5f) * 1.6f;
            ballSize[i] = 1.3f + random.nextFloat() * 1.1f;
            ballSprite[i] = random.nextInt(4);
        }
        spin = random.nextFloat() * 6.28f;
    }

    static void frag(ClientLevel level, Vec3 at) {
        RandomSource random = level.random;
        BlockPos floor = floorBelow(level, at);
        double floorY = floor == null ? Double.NaN : floor.getY() + 1;
        FxWorld.add(new Blast(true, at, floorY, random));
        BlockState ground = floor == null ? null : level.getBlockState(floor);
        for (int i = 0; i < 48; i++) {
            double vx = (random.nextFloat() - 0.5) * 1.1, vy = 0.25 + random.nextFloat() * 0.7, vz = (random.nextFloat() - 0.5) * 1.1;
            if (ground != null && !ground.isAir()) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.2, at.z, vx, vy, vz);
            }
            if (i % 3 == 0) level.addParticle(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, vx * 0.7, vy * 0.5, vz * 0.7);
        }
        for (int i = 0; i < 4; i++) {
            level.addParticle(ParticleTypes.EXPLOSION, at.x + (random.nextFloat() - 0.5) * 2, at.y + random.nextFloat(),
                    at.z + (random.nextFloat() - 0.5) * 2, 0, 0, 0);
        }
        for (int i = 0; i < 16; i++) {
            level.addParticle(ParticleTypes.LARGE_SMOKE, at.x + (random.nextFloat() - 0.5) * 2.5, at.y + random.nextFloat() * 1.5,
                    at.z + (random.nextFloat() - 0.5) * 2.5, (random.nextFloat() - 0.5) * 0.15, 0.05 + random.nextFloat() * 0.1,
                    (random.nextFloat() - 0.5) * 0.15);
        }
        if (floor != null && at.y - floorY < 1.6) Decals.onBlock("scorch", new Vec3(at.x, floorY, at.z), Direction.UP, floor, null);
        BlastShake.blast(at, 1.0f, 26);
    }

    static void flash(ClientLevel level, Vec3 at) {
        RandomSource random = level.random;
        FxWorld.add(new Blast(false, at, Double.NaN, random));
        for (int i = 0; i < 20; i++) {
            level.addParticle(ParticleTypes.FIREWORK, at.x, at.y + 0.1, at.z, (random.nextFloat() - 0.5) * 0.5,
                    random.nextFloat() * 0.35, (random.nextFloat() - 0.5) * 0.5);
        }
        for (int i = 0; i < 5; i++) {
            level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y + 0.1, at.z, (random.nextFloat() - 0.5) * 0.03, 0.04, (random.nextFloat() - 0.5) * 0.03);
        }
        BlastShake.blast(at, 0.3f, 14);
    }

    private static BlockPos floorBelow(ClientLevel level, Vec3 at) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(at).mutable();
        for (int i = 0; i < 3; i++) {
            pos.move(Direction.DOWN);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return pos.immutable();
        }
        return null;
    }

    @Override
    public boolean tick(ClientLevel level) {
        age += 0.05f;
        return age < (frag ? 3.2f : 0.6f);
    }

    @Override
    public void render(FxDraw draw) {
        float t = age + draw.partialTick * 0.05f;
        if (frag) renderFrag(draw, t);
        else renderFlash(draw, t);
    }

    private void renderFrag(FxDraw draw, float t) {
        // the flash: a huge additive glow for a few frames
        if (t < 0.3f) {
            float a = t < 0.05f ? 1 : 1 - (t - 0.05f) / 0.25f;
            draw.billboard(FxRenderTypes.glow(FxRenderTypes.GLOW), at.x, at.y + 0.6, at.z, 7.5f * (0.6f + 0.4f * Math.min(1, t / 0.05f)), 0,
                    0, 0, 1, 1, Math.round(255 * a) << 24 | 0xFFE2B0, FxDraw.FULL_BRIGHT);
        }
        // fireballs: swell, cool from white-yellow through orange to dark smoke, rise
        for (int i = 0; i < BALLS; i++) {
            float delay = i * 0.012f, s = t - delay;
            if (s <= 0) continue;
            float swell = 1 - (float) Math.pow(1 - Math.min(1, s / 0.16f), 3);
            float heat = Mth.clamp(1 - s / 0.45f, 0, 1);
            float smoke = Mth.clamp((s - 0.25f) / 0.6f, 0, 1);
            float alpha = Mth.clamp(1 - (s - 0.6f) / 2.4f, 0, 1);
            if (alpha <= 0.01f) continue;
            float size = ballSize[i] * (0.3f + 0.7f * swell) * (1 + smoke * 0.9f);
            double x = at.x + ballX[i] * (0.5 + swell * 0.8), y = at.y + 0.4 + ballY[i] * (0.4 + swell * 0.8) + smoke * smoke * 1.6, z = at.z + ballZ[i] * (0.5 + swell * 0.8);
            int r, g, b;
            if (heat > 0) {
                r = 255;
                g = Math.round(110 + 140 * heat);
                b = Math.round(40 + 180 * heat * heat);
            } else {
                int grey = Math.round(Mth.lerp(smoke, 150, 55));
                r = Math.round(Mth.lerp(smoke, 210, grey));
                g = Math.round(Mth.lerp(smoke, 90, grey));
                b = Math.round(Mth.lerp(smoke, 40, grey));
            }
            int light = heat > 0.05f ? FxDraw.FULL_BRIGHT : draw.light(x, y, z);
            int u = ballSprite[i];
            if (smoke > 0.5f) {
                // turned to smoke: drawn with the smoke puffs
                float u0 = (u & 1) * 0.5f, v0 = (u >> 1) * 0.5f;
                draw.billboard(FxRenderTypes.soft(FxRenderTypes.SMOKE), x, y, z, size, spin + i + t * 0.2f, u0, v0, u0 + 0.5f, v0 + 0.5f,
                        Math.round(alpha * 200) << 24 | r << 16 | g << 8 | b, light);
            } else {
                float u0 = (u & 1) * 0.5f, v0 = (u >> 1) * 0.5f;
                draw.billboard(FxRenderTypes.soft(FxRenderTypes.FIREBALL), x, y, z, size, spin + i * 1.7f + t * 0.6f, u0, v0, u0 + 0.5f, v0 + 0.5f,
                        Math.round(alpha * 255) << 24 | r << 16 | g << 8 | b, light);
            }
        }
        // dust ring racing over the floor
        if (!Double.isNaN(floorY) && t < 0.9f) {
            float k = t / 0.9f;
            float size = 0.6f + 7.5f * (1 - (1 - k) * (1 - k));
            int a = Math.round(170 * (1 - k));
            draw.flat(FxRenderTypes.soft(FxRenderTypes.RING), at.x, floorY + 0.06, at.z, size, spin, a << 24 | 0xC8C0B4, draw.light(at.x, floorY + 0.5, at.z));
        }
    }

    private void renderFlash(FxDraw draw, float t) {
        float a = t < 0.04f ? 1 : Mth.clamp(1 - (t - 0.04f) / 0.4f, 0, 1);
        float size = 2 + 9 * Math.min(1, t / 0.05f);
        draw.billboard(FxRenderTypes.glow(FxRenderTypes.GLOW), at.x, at.y + 0.2, at.z, size, 0, 0, 0, 1, 1,
                Math.round(255 * a) << 24 | 0xFFFFFF, FxDraw.FULL_BRIGHT);
        draw.billboard(FxRenderTypes.glow(FxRenderTypes.GLOW), at.x, at.y + 0.2, at.z, size * 0.35f, 0, 0, 0, 1, 1,
                Math.round(255 * a) << 24 | 0xFFFFFF, FxDraw.FULL_BRIGHT);
    }
}
