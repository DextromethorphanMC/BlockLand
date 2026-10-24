package com.blockland.client.fluid;

import com.blockland.client.impact.Impacts;
import com.blockland.client.render.WaterRipples;
import com.blockland.client.render.Wetness;
import com.blockland.client.settings.ClientSettings;
import com.blockland.movement.Slides;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * Water disturbed by what happens in it (rendering spec §15), client-side and purely visual: entities wading and
 * swimming leave rings with a bow wave along their heading and throw spray when they run; falling in splashes by the
 * speed of the fall and its direction; a strike that reaches the water (a swing aimed into it) splashes along the
 * attacker's yaw and pitch; a block placed into water drops in with a splash. Rings go to {@link WaterRipples} (drawn
 * by the lighting pack's water), splashes to the {@link Impacts} facade. Entities in water are soaked ({@link Wetness}:
 * their weapons look wet) and, out of it, drip — fast at first, slower as they dry — while rain slows their drying.
 * Client thread only.
 */
public final class WaterInteractions {
    /** Entities farther than this from the camera disturb no water anyone would see. */
    private static final double RANGE = 48;
    /** Drips are drawn from this near. */
    private static final double DRIP_RANGE = 20;

    private static final Int2ObjectOpenHashMap<State> states = new Int2ObjectOpenHashMap<>();
    private static ClientLevel level;
    private static long tick;

    private static final class State {
        boolean inWater;
        long lastRing, lastSpray, seen;
        int swing;
        /** Fractional drips owed (drips come at a rate, often less than one a tick). */
        float drips;
        /** Distance walked since the last foot came down on wet ground. */
        float stride;
    }

    private WaterInteractions() {
    }

    public static void init() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            states.clear();
            level = null;
            WaterRipples.clear();
            Wetness.clear();
        });
        PlayerInteractEvent.RightClickBlock.BUS.addListener(WaterInteractions::onPlace);
    }

    private static boolean enabled() {
        return ClientSettings.get().graphics.waterRipples;
    }

    private static boolean soaking() {
        return ClientSettings.get().graphics.soaking;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        if (world != level) {
            states.clear();
            Wetness.clear();
            level = world;
        }
        if (world == null || mc.isPaused()) return;
        boolean ripples = enabled(), soaking = soaking();
        if (!soaking) Wetness.clear();
        if (!ripples && !soaking) return;
        tick++;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        for (Entity entity : world.entitiesForRendering()) {
            if (entity.isSpectator() || entity.distanceToSqr(camera) > RANGE * RANGE) continue;
            State state = states.computeIfAbsent(entity.getId(), id -> new State());
            state.seen = tick;
            if (ripples) disturb(world, entity, state);
            if (soaking) soak(world, entity, state, camera);
        }
        if (tick % 100 == 0) {
            states.values().removeIf(s -> tick - s.seen > 100);
            Wetness.prune(world.getGameTime());
        }
    }

    /**
     * Soaking (rendering spec §15): in water an entity is soaked through (under water) or to the depth it wades; out of
     * it, it drips from its body, faster the wetter it is, and the rain slows its drying. Living things and players only.
     */
    private static void soak(ClientLevel world, Entity entity, State state, Vec3 camera) {
        if (!(entity instanceof LivingEntity)) return;
        AABB box = entity.getBoundingBox();
        long time = world.getGameTime();
        if (entity.isInWater()) {
            // how far up the body the water stands (vanilla's own measure over the box; an edge in a stream is shallow)
            float depth = entity.isUnderWater() ? 1
                    : (float) Mth.clamp(entity.getFluidHeight(FluidTags.WATER) / Math.max(0.2, box.getYsize()), 0, 1);
            Wetness.soak(entity, depth * depth, time, false);
            return;
        }
        float wet = Wetness.of(entity);
        // rain on a wet body slows its drying (looked at twice a second)
        if (wet > 0 && (tick + entity.getId()) % 10 == 0) {
            Wetness.soak(entity, 0, time, world.isRainingAt(BlockPos.containing(box.getCenter().x, box.maxY + 0.2, box.getCenter().z)));
        }
        // dripping: seen only from near by, and not off the body the camera is in
        if (wet < 0.08f || entity.distanceToSqr(camera) > DRIP_RANGE * DRIP_RANGE) return;
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.getCameraEntity() && !mc.gameRenderer.getMainCamera().isDetached()) return;
        // water streams off at first, then only drips now and then (wet ** 4: 2.5 a tick fresh out, one a second at 0.4)
        float w2 = wet * wet;
        state.drips += w2 * w2 * 2.5f * Mth.clamp(entity.getBbWidth() / 0.6f, 0.5f, 2.5f);
        Vec3 motion = new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo);
        while (state.drips >= 1) {
            state.drips -= 1;
            // off the body: mostly the lower half and the edges, where water runs down to
            double u = world.random.nextDouble(), h = world.random.nextDouble();
            double y = box.minY + box.getYsize() * (0.15 + 0.7 * h * h);
            double angle = u * Math.PI * 2, r = box.getXsize() * 0.5;
            Vec3 at = new Vec3(box.getCenter().x + Math.cos(angle) * r, y, box.getCenter().z + Math.sin(angle) * r);
            Impacts.drip(world, at, motion);
        }
    }

    private static void disturb(ClientLevel world, Entity entity, State state) {
        AABB box = entity.getBoundingBox();
        double surface = surface(world, entity.getX(), entity.getZ(), box.minY - 0.2, box.maxY);
        boolean wading = !Double.isNaN(surface);
        Vec3 motion = new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo);
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        float size = Mth.clamp(entity.getBbWidth(), 0.25f, 2.5f);
        if (wading && !state.inWater && motion.y < -0.15) {
            // falling in: the faster and heavier, the bigger
            float energy = (float) Mth.clamp(-motion.y * 2.2 * size, 0.3, 2.0);
            Vec3 at = new Vec3(entity.getX(), surface, entity.getZ());
            Impacts.disturb(world, at, fluidAt(world, at), BlockPos.containing(at.x, surface - 0.05, at.z), motion.normalize(), energy);
            ring(world, at, energy * 1.2f, motion);
            state.lastRing = state.lastSpray = tick;
        } else if (wading && horizontal > 0.02) {
            // wading and swimming: rings with a bow wave along the heading, more often the faster; spray when running
            int interval = Math.max(2, (int) (6 - horizontal * 18));
            Vec3 at = new Vec3(entity.getX() + motion.x * 2, surface, entity.getZ() + motion.z * 2);
            if (tick - state.lastRing >= interval) {
                ring(world, at, (float) Mth.clamp(horizontal * 5 * size, 0.15, 1.3), motion);
                state.lastRing = tick;
            }
            if (horizontal > 0.16 && tick - state.lastSpray >= 7) {
                Vec3 dir = new Vec3(motion.x, Math.max(0.15, motion.y), motion.z).normalize();
                Impacts.disturb(world, at, fluidAt(world, at), BlockPos.containing(at.x, surface - 0.05, at.z), dir,
                        (float) Mth.clamp(horizontal * 2.5 * size, 0.2, 0.9));
                state.lastSpray = tick;
            }
        }
        state.inWater = wading;
        if (!wading) puddles(world, entity, state, horizontal, motion);
        // a strike reaching into the water: along the attacker's yaw and pitch
        if (entity instanceof LivingEntity living) {
            int swing = living.swingTime;
            if (living.swinging && swing == 1 && state.swing != 1) strike(world, living);
            state.swing = swing;
        }
    }

    /**
     * Running on rain-soaked ground: every stride a foot comes down and kicks up a few drops of the water standing on it
     * (rain falling there, rain having fallen a while, a solid top under the feet; near the camera only).
     */
    private static void puddles(ClientLevel world, Entity entity, State state, double horizontal, Vec3 motion) {
        if (!entity.onGround() || horizontal < 0.08 || world.getRainLevel(1) < 0.3f || !(entity instanceof LivingEntity)) {
            state.stride = 0;
            return;
        }
        state.stride += (float) horizontal;
        // a stride is about a block running, a little less walking
        if (state.stride < 0.9f) return;
        state.stride = 0;
        Minecraft mc = Minecraft.getInstance();
        if (entity.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition()) > DRIP_RANGE * DRIP_RANGE) return;
        BlockPos feet = entity.blockPosition();
        if (!world.isRainingAt(feet.above()) || !world.getBlockState(feet.below()).isFaceSturdy(world, feet.below(), Direction.UP)) return;
        Impacts.kick(world, new Vec3(entity.getX(), entity.getBoundingBox().minY, entity.getZ()), motion,
                (float) Mth.clamp((horizontal - 0.08) / 0.2, 0, 1));
    }

    private static void strike(ClientLevel world, LivingEntity attacker) {
        // the swing starts at the attacker's eyes (low while it slides)
        Vec3 eye = Slides.eyePosition(attacker);
        Vec3 look = attacker.getLookAngle();
        // along the reach, the first point where the swing meets a water surface
        for (double d = 0.4; d <= 2.6; d += 0.2) {
            Vec3 p = eye.add(look.scale(d));
            double surface = surface(world, p.x, p.z, p.y - 0.15, p.y + 0.15);
            if (Double.isNaN(surface)) continue;
            Vec3 at = new Vec3(p.x, surface, p.z);
            Impacts.disturb(world, at, fluidAt(world, at), BlockPos.containing(at.x, surface - 0.05, at.z), look, 0.35f);
            ring(world, at, 0.6f, look);
            return;
        }
    }

    /** A block placed into water drops in with a splash. */
    private static void onPlace(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide() || !(event.getLevel() instanceof ClientLevel world) || !enabled()) return;
        if (!(event.getItemStack().getItem() instanceof BlockItem)) return;
        BlockPos target = event.getPos().relative(event.getFace() == null ? Direction.UP : event.getFace());
        FluidState fluid = world.getFluidState(target);
        if (!fluid.is(FluidTags.WATER)) return;
        double surface = surface(world, target.getX() + 0.5, target.getZ() + 0.5, target.getY(), target.getY() + 1.5);
        if (Double.isNaN(surface)) return;
        Vec3 at = new Vec3(target.getX() + 0.5, surface, target.getZ() + 0.5);
        Vec3 dir = event.getEntity().getLookAngle().add(0, -1, 0).normalize();
        Impacts.disturb(world, at, fluid, target, dir, 1.4f);
        ring(world, at, 1.6f, dir);
    }

    private static void ring(ClientLevel world, Vec3 at, float strength, Vec3 heading) {
        double h = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        float hx = h > 1e-4 ? (float) (heading.x / h) : 0, hz = h > 1e-4 ? (float) (heading.z / h) : 0;
        WaterRipples.add(world, at.x, at.y, at.z, strength, hx, hz);
    }

    /**
     * The height of an open water surface in the column at (x, z) between {@code fromY} and {@code toY}, or NaN: the top
     * of the highest water there whose block above holds no water.
     */
    private static double surface(ClientLevel world, double x, double z, double fromY, double toY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = Mth.floor(toY); y >= Mth.floor(fromY); y--) {
            pos.set(Mth.floor(x), y, Mth.floor(z));
            FluidState fluid = world.getFluidState(pos);
            if (!fluid.is(FluidTags.WATER)) continue;
            if (world.getFluidState(pos.above()).is(FluidTags.WATER)) return Double.NaN;
            double top = y + fluid.getHeight(world, pos);
            return top >= fromY && top <= toY + 0.5 ? top : Double.NaN;
        }
        return Double.NaN;
    }

    private static FluidState fluidAt(ClientLevel world, Vec3 at) {
        return world.getFluidState(BlockPos.containing(at.x, at.y - 0.05, at.z));
    }
}
