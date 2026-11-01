package com.blockland.client.ambient;

import com.blockland.ambient.TumbleweedEntity;
import com.blockland.client.perf.FrameTimings;
import com.blockland.client.render.WindField;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One tick of a tumbleweed (rendering spec §29): the wind ({@link WindField}: the plants' steady wind, turbulence and
 * gusts) brings it up to its speed — quickly on the ground, slowly in the air; it rolls without slipping, hops now and
 * then and bounces when it lands hard; a wall turns it back a little and it tries to hop over, pressed into a corner by
 * the wind it stays until the wind turns; rolling into a catcher plant (cactus, dead bush …) may hold it until a strong
 * gust tears it free. It grows in when it comes and shrinks away when it goes (water and lava send it away). Only blocks
 * are in its way, through vanilla's collision move.
 */
final class TumbleweedPhysics {
    private static final float[] WIND = new float[3];
    private static final Quaternionf turn = new Quaternionf();
    private static final Vector3f rolling = new Vector3f();

    private TumbleweedPhysics() {
    }

    static void tick(TumbleweedEntity w) {
        try (FrameTimings.Span ignored = FrameTimings.span("bl/ambient/tumbleweed_physics")) {
            step(w);
        }
    }

    private static void step(TumbleweedEntity w) {
        TumbleweedData d = Tumbleweeds.data();
        Level level = w.level();
        if (d == null || !level.hasChunkAt(w.blockPosition())) {
            w.discard();
            return;
        }
        if (Tumbleweeds.frozen()) {
            // development: placed still for a measurement
            w.fade = 1;
            return;
        }
        if (w.leaving) {
            w.fade -= 0.05f;
            if (w.fade <= 0) {
                w.discard();
                return;
            }
        } else {
            w.fade = Math.min(1, w.fade + 0.04f);
        }
        float r = w.radius;
        if (!level.getFluidState(BlockPos.containing(w.getX(), w.getY() + r * 0.5, w.getZ())).isEmpty()) w.leaving = true;

        RandomSource random = w.getRandom();
        WindField.sample(w.getX(), w.getZ(), WIND);
        float wind = Math.max(d.minWind, WIND[2]);
        // lighter (smaller) ones run a little quicker
        double target = Math.min(d.maxSpeed, d.speed * wind) * Mth.clamp(0.45f / r, 0.75f, 1.35f) / 20;
        Vec3 v = w.getDeltaMovement();
        boolean ground = w.onGround();
        double vx = v.x, vy = v.y, vz = v.z;
        if (w.caught > 0) {
            // held by a plant: it shivers in place until the hold runs out or a strong gust tears it free
            vx *= 0.3;
            vz *= 0.3;
            w.caught--;
            if (wind > d.releaseWind && random.nextFloat() < 0.1f) w.caught = 0;
            if (w.caught == 0) vy = Math.max(vy, hop(d, random));
        } else {
            double grip = ground ? d.groundGrip : d.airGrip;
            vx += (WIND[0] * target - vx) * grip;
            vz += (WIND[1] * target - vz) * grip;
            double speed = Math.sqrt(vx * vx + vz * vz);
            if (ground && speed > 0) {
                double friction = Math.min(speed, d.rollingFriction);
                vx -= vx / speed * friction;
                vz -= vz / speed * friction;
                speed -= friction;
            }
            // a rolling tumbleweed bounds now and then, the more the faster it goes
            if (ground && random.nextFloat() < d.hopChance * Mth.clamp(speed / Math.max(1e-3, d.maxSpeed / 20), 0, 1.5)) {
                vy = Math.max(vy, hop(d, random));
            }
            // rolling into a catcher plant may hold it there a while
            if (ground && speed > 0.01) {
                double reach = r + 0.2;
                BlockPos ahead = BlockPos.containing(w.getX() + vx / speed * reach, w.getY() + 0.2, w.getZ() + vz / speed * reach);
                if (d.catcher(level.getBlockState(ahead)) && random.nextFloat() < d.snagChance * 0.2f) {
                    w.caught = Mth.nextInt(random, d.snagTicks[0], Math.max(d.snagTicks[0], d.snagTicks[1]));
                }
            }
        }
        vy = (vy - d.gravity) * 0.98;

        Vec3 before = new Vec3(vx, vy, vz);
        w.setDeltaMovement(before);
        w.move(MoverType.SELF, before);
        Vec3 after = w.getDeltaMovement();
        double nx = after.x, ny = after.y, nz = after.z;
        if (w.horizontalCollision) {
            // a wall turns it back a little; it tries to hop over (pressed into a corner it stays until the wind turns)
            if (Math.abs(nx) < 1e-6 && Math.abs(before.x) > 1e-4) nx = -before.x * 0.25;
            if (Math.abs(nz) < 1e-6 && Math.abs(before.z) > 1e-4) nz = -before.z * 0.25;
            if (w.onGround() && random.nextFloat() < 0.08f) ny = hop(d, random);
        }
        if (w.verticalCollisionBelow && before.y < -0.08) {
            // a hard landing bounces, and knocks its spin about a little
            ny = -before.y * d.bounce;
            w.spin.add((random.nextFloat() - 0.5f) * 0.2f, (random.nextFloat() - 0.5f) * 0.2f, (random.nextFloat() - 0.5f) * 0.2f);
        } else if (w.verticalCollision && !w.verticalCollisionBelow) {
            ny = Math.min(0, ny);
        }
        w.setDeltaMovement(nx, ny, nz);

        // spin: rolling without slipping on the ground (ω = up × v / r), keeping its spin in the air
        if (w.onGround()) {
            float sx = (float) (nz / r), sz = (float) (-nx / r);
            w.spin.lerp(rolling.set(sx, 0, sz), 0.5f);
        } else {
            w.spin.mul(0.985f);
        }
        float angle = w.spin.length();
        if (angle > 1e-5f) {
            turn.identity().rotateAxis(angle, w.spin.x / angle, w.spin.y / angle, w.spin.z / angle);
            w.rotation.premul(turn).normalize();
        }
    }

    private static double hop(TumbleweedData d, RandomSource random) {
        return Mth.lerp(random.nextFloat(), d.hopSpeed[0], d.hopSpeed[Math.min(1, d.hopSpeed.length - 1)]);
    }
}
