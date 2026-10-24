package com.blockland.client.bomb.fx;

import com.blockland.client.decal.Decals;
import com.blockland.client.render.LightSources;
import com.blockland.client.render.WorldFx;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The bomb's explosion on the client: a purely visual effect (the server already dealt the damage; no block is ever
 * broken). Entry point of the bomb FX module; the rest of the client bomb module only calls {@link #play}.
 * {@link BombBlast} stages one explosion, {@link BombFxRenderer} draws them with the explosion shaders in the particle
 * pass (rendering spec §10), {@link BombShake} moves the camera; around them vanilla block particles throw the floor's
 * debris, {@link LightSources} lights the surroundings and {@link Decals} leaves the scorched floor.
 */
public final class BombBlastFx {
    private static final List<BombBlast> blasts = new ArrayList<>();
    @Nullable
    private static ClientLevel level;

    private BombBlastFx() {
    }

    /** Registers what the effect needs (render hooks, reload listeners). Called once from the client bomb module. */
    public static void init(BusGroup modBus) {
        BombShake.init();
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
        // in the air: over the scorch marks and bullet holes on the faces behind
        WorldFx.register(WorldFx.Layer.VOLUME, BombBlastFx::render);
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && !blasts.isEmpty()) {
                event.getText().add("[BlockLand] bomb explosions: " + blasts.size());
            }
            return false;
        });
    }

    /**
     * The bomb went off at {@code at} (the bomb's position on the floor).
     *
     * @param radius the blast radius in blocks (bomb.json {@code blast.radius}): how big the fireball and the shock look
     * @param shake  camera shake multiplier (bomb.json {@code blast.shake})
     */
    public static void play(ClientLevel world, Vec3 at, float radius, float shake) {
        if (world != level) clear();
        level = world;
        float k = Mth.clamp(radius / 16f, 0.35f, 2.5f);
        BlockPos floor = floorBelow(world, at);
        double floorY = floor == null ? Double.NaN : floor.getY() + 1;
        Vec3 centre = new Vec3(at.x, Double.isNaN(floorY) ? at.y : Math.max(at.y, floorY), at.z);
        blasts.add(new BombBlast(world, centre, floorY, radius));
        WorldFx.wake(world);

        RandomSource random = world.random;
        // the floor torn up: chunks of its block flung out and up
        BlockState ground = floor == null ? null : world.getBlockState(floor);
        if (ground != null && !ground.isAir()) {
            BlockParticleOption debris = new BlockParticleOption(ParticleTypes.BLOCK, ground);
            for (int i = 0; i < 110; i++) {
                double a = random.nextDouble() * Math.PI * 2, speed = (0.3 + random.nextDouble() * 1.1) * k;
                world.addParticle(debris, centre.x + (random.nextDouble() - 0.5) * 1.5, centre.y + 0.2, centre.z + (random.nextDouble() - 0.5) * 1.5,
                        Math.cos(a) * speed, 0.35 + random.nextDouble() * 0.9 * k, Math.sin(a) * speed);
            }
        }
        // a blinding white flash, then the fireball's orange light that lingers while it burns
        LightSources.flash(centre.x, centre.y + 1.5 * k, centre.z, 0xFFE2B8, 18f, 30f * k, 0.35f);
        LightSources.flash(centre.x, centre.y + 2.5 * k, centre.z, 0xFF7A2E, 8f, 24f * k, 2.4f);
        // the floor burnt black over a wide patch
        if (floor != null && at.y - floorY < 2) {
            scorch(world, centre, floor);
            for (int i = 0; i < 6; i++) {
                double a = i / 6.0 * Math.PI * 2 + random.nextDouble() * 0.5, d = (1.4 + random.nextDouble() * 1.2) * k;
                Vec3 p = new Vec3(centre.x + Math.cos(a) * d, centre.y + 0.5, centre.z + Math.sin(a) * d);
                BlockPos below = floorBelow(world, p);
                if (below != null) scorch(world, p, below);
            }
        }
        BombShake.blast(centre, 1.6f * shake / 3f + 0.4f, radius * 5);
    }

    private static void scorch(ClientLevel world, Vec3 near, BlockPos floor) {
        Decals.onBlock("scorch", new Vec3(near.x, floor.getY() + 1, near.z), Direction.UP, floor, null);
    }

    /** The first block with a collision shape within three blocks below, or null. */
    @Nullable
    private static BlockPos floorBelow(ClientLevel world, Vec3 at) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(at.x, at.y + 0.5, at.z).mutable();
        for (int i = 0; i < 4; i++) {
            pos.move(Direction.DOWN);
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) return pos.immutable();
        }
        return null;
    }

    private static void tick() {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null || world != level) {
            if (!blasts.isEmpty()) clear();
            return;
        }
        blasts.removeIf(blast -> !blast.tick(world));
        if (blasts.isEmpty()) BombFxRenderer.release();
    }

    private static void clear() {
        blasts.clear();
        level = null;
        BombShake.clear();
        BombFxRenderer.release();
    }

    /** Draws every explosion from inside the particle pass (world matrices, the depth of the solid world). */
    private static void render(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
        ClientLevel world = level;
        if (world == null || blasts.isEmpty()) return;
        BombFxRenderer.Frame frame = new BombFxRenderer.Frame(camera, partialTick);
        Vec3 cam = camera.getPosition();
        Vector3f fireSum = new Vector3f();
        float fire = 0, scale = 0;
        for (BombBlast blast : blasts) {
            fire += blast.render(frame, world, cam, partialTick, fireSum);
            frame.time = Math.max(frame.time, blast.time(partialTick));
            scale = Math.max(scale, blast.scale());
        }
        if (fire > 1e-3f) {
            // the hot gas as one light inside the cloud: where its heat is, as bright as there is heat (linear)
            frame.fireAt.set(fireSum).div(fire);
            float strength = Math.min(2.5f, fire / 12f);
            frame.fireColor.set(1.0f, 0.42f, 0.12f).mul(strength);
            frame.fireReach = 14 * scale;
        }
        BombFxRenderer.draw(frame, pose.last().pose());
    }
}
