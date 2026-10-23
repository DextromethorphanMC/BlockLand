package com.blockland.client.equipment.effect;

import com.blockland.equipment.effect.Params;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Random;

/**
 * A smoke grenade's cloud: dense, softly lit puffs that burst out of the canister, roll out to fill an upright
 * ellipsoid on the ground ({@code radius} × {@code height}), churn slowly in place and thin out over the last
 * {@code fade} seconds. Thick enough to hide whoever is in or behind it; inside it the screen greys out
 * ({@link #density}). Timed by the server's active ticks, so every client sees the same cloud; when its entity goes
 * early, it thins out on its own.
 */
final class SmokeCloud implements FxWorld.Fx {
    private static final float PUFF_ALPHA = 0.66f;
    /** Seconds a puff takes from the canister to its place in the cloud. */
    private static final float ROLL_OUT = 1.3f;
    /** Seconds the cloud takes to thin out when its entity went away early. */
    private static final float ORPHAN_FADE = 1.8f;

    private final int entityId;
    private final Vec3 origin;
    private final float radius, height, duration, grow, fade;
    private Puff[] puffs = new Puff[0];
    private boolean built;
    /** Seconds since the pop (client ticks, re-synced to the server's active ticks). */
    private float age;
    private int unseenTicks;
    private float orphanAge = -1;

    private record Puff(float x, float y, float z, float size, int sprite, float spin, float phase, float shade, float start) {
    }

    SmokeCloud(int entityId, Vec3 origin, JsonObject params) {
        this.entityId = entityId;
        this.origin = origin;
        this.radius = Params.f(params, "radius", 4.8f);
        this.height = Params.f(params, "height", 3.4f);
        this.duration = Params.f(params, "duration", 18);
        this.grow = Math.max(0.2f, Params.f(params, "grow", 1.6f));
        this.fade = Math.max(0.2f, Params.f(params, "fade", 3));
    }

    /** Lays the puffs out; each stops short of the walls between it and the canister (the smoke fills the room it is in). */
    private void build(ClientLevel level) {
        built = true;
        Random random = new Random(entityId * 31L + 7);
        int count = Mth.clamp(Math.round(radius * radius * height * 0.95f), 24, 110);
        puffs = new Puff[count];
        float scale = radius / 4.8f;
        for (int i = 0; i < count; i++) {
            // denser low down: a pile that thins towards its top
            float u = random.nextFloat(), v = (float) Math.pow(random.nextFloat(), 1.4);
            double angle = random.nextDouble() * Math.PI * 2;
            float y = v * height * 0.82f;
            float reach = (float) Math.sqrt(u) * radius * (1 - 0.45f * (y / height)) * 0.82f;
            Vec3 from = origin.add(0, 0.4, 0), to = origin.add(Math.cos(angle) * reach, y + 0.35f, Math.sin(angle) * reach);
            BlockHitResult wall = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (wall.getType() != HitResult.Type.MISS) {
                Vec3 dir = to.subtract(from);
                double keep = Math.max(0, wall.getLocation().distanceTo(from) - 0.6) / Math.max(1e-3, dir.length());
                to = from.add(dir.scale(keep));
            }
            puffs[i] = new Puff((float) (to.x - origin.x), (float) (to.y - origin.y), (float) (to.z - origin.z),
                    (1.25f + random.nextFloat() * 0.9f) * scale, random.nextInt(4), (random.nextFloat() - 0.5f) * 0.25f,
                    random.nextFloat() * 100, 0.74f + random.nextFloat() * 0.16f, grow * 0.75f * i / count);
        }
    }

    @Override
    public int entityId() {
        return orphanAge < 0 ? entityId : -1;
    }

    /** The server's clock for the cloud (active ticks of its entity). */
    void keep(int activeTicks) {
        unseenTicks = 0;
        float server = activeTicks / 20f;
        if (Math.abs(server - age) > 0.25f) age = server;
    }

    @Override
    public boolean tick(ClientLevel level) {
        if (!built) build(level);
        age += 0.05f;
        if (orphanAge < 0 && ++unseenTicks > 3) orphanAge = 0;
        if (orphanAge >= 0) orphanAge += 0.05f;
        // the canister keeps hissing out a thin stream while the cloud grows
        if (age < grow + 1.5f && level.random.nextFloat() < 0.6f) {
            level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, origin.x, origin.y + 0.15, origin.z,
                    (level.random.nextFloat() - 0.5f) * 0.04, 0.05, (level.random.nextFloat() - 0.5f) * 0.04);
        }
        return alpha() > 0.003f && age < duration + 1;
    }

    /** Overall opacity: whole until the last {@code fade} seconds (or until orphaned). */
    private float alpha() {
        float a = Mth.clamp((duration - age) / fade, 0, 1);
        if (orphanAge >= 0) a *= Mth.clamp(1 - orphanAge / ORPHAN_FADE, 0, 1);
        return a;
    }

    /** 0 outside … 1 deep inside: how much the cloud hides the view of a camera at {@code p}. */
    float density(Vec3 p) {
        double dx = (p.x - origin.x) / radius, dz = (p.z - origin.z) / radius;
        double dy = (p.y - (origin.y + height * 0.4)) / (height * 0.62);
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float filled = Mth.clamp((age - grow * 0.4f) / grow, 0, 1);
        return (float) Mth.clamp((1.02 - d) / 0.4, 0, 1) * filled * alpha();
    }

    @Override
    public void render(FxDraw draw) {
        float a = alpha();
        if (a <= 0.003f) return;
        float t = age + draw.partialTick * 0.05f;
        float thinning = 1 - a;
        int around = draw.light(origin.x, origin.y + 0.6, origin.z);
        for (Puff puff : puffs) {
            float since = t - puff.start;
            if (since <= 0) continue;
            float out = ease(Math.min(1, since / ROLL_OUT));
            // a slow churn: each puff wanders a little around its place, the whole cloud creeps up as it thins
            float wx = (float) Math.sin(t * 0.21 + puff.phase) * 0.35f, wz = (float) Math.cos(t * 0.17 + puff.phase * 1.3) * 0.35f;
            float wy = (float) Math.sin(t * 0.13 + puff.phase * 0.7) * 0.18f + thinning * 0.9f;
            double x = origin.x + puff.x * out + wx * out, y = origin.y + 0.2 + (puff.y - 0.2) * out + wy, z = origin.z + puff.z * out + wz * out;
            float size = puff.size * (0.25f + 0.75f * out) * (1 + thinning * 0.35f);
            float alpha = PUFF_ALPHA * a * Math.min(1, since / 0.25f);
            // puffs right at the eye would be one flat grey sheet: the inside fog takes over there
            double near = Math.sqrt(draw.distanceSqr(x, y, z));
            alpha *= (float) Mth.clamp((near - size * 0.35) / (size * 0.9), 0, 1);
            if (alpha <= 0.004f) continue;
            int grey = Math.round(255 * puff.shade);
            int argb = Math.round(alpha * 255) << 24 | grey << 16 | grey << 8 | Math.min(255, grey + 6);
            float u0 = (puff.sprite & 1) * 0.5f, v0 = (puff.sprite >> 1) * 0.5f;
            draw.billboard(FxRenderTypes.soft(FxRenderTypes.SMOKE), x, y, z, size, puff.phase + t * puff.spin,
                    u0, v0, u0 + 0.5f, v0 + 0.5f, argb, FxDraw.brighter(around, draw.light(x, y, z)));
        }
    }

    private static float ease(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }
}
