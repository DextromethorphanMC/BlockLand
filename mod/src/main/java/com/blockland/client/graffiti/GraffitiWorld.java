package com.blockland.client.graffiti;

import com.blockland.client.render.LightSources;
import com.blockland.client.render.WorldFx;
import com.blockland.common.Sounds;
import com.blockland.graffiti.GraffitiCatalog;
import com.blockland.graffiti.GraffitiData;
import com.blockland.network.packet.GraffitiPackets;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The graffiti up in the client's world, as the server reported them: laid out on their surfaces, timed from when they
 * were sprayed (they build up, then stay) and from when they were taken down (they thin out), with the light, the hum
 * and the sounds of the special ones. Drawn by {@link GraffitiRenderer} from inside the particle pass.
 */
final class GraffitiWorld {
    /** Ticks a piece takes to be sprayed on, and to vanish when it goes. */
    static final float REVEAL_TICKS = 12, FADE_TICKS = 12;
    /** Ticks between re-laying pieces on their surfaces (blocks may have changed). */
    private static final int RELAYOUT_TICKS = 20;

    /** One piece in the world. */
    static final class Piece {
        final GraffitiPackets.Placement placement;
        final GraffitiData.Graffiti graffiti;
        @Nullable
        final Direction face;
        final float rx, ry, rz, ux, uy, uz;
        List<GraffitiGeometry.Polygon> polygons = List.of();
        /** Game time it started going away, -1 while it stays. */
        long removedAt = -1;
        /** Floating pieces: the floor under them (the hologram's projector ring), NaN = none in reach. */
        double floorY = Double.NaN;
        List<GraffitiArt.Point> points = List.of();
        /** Neon: how bright it is right now (its light follows the flicker). */
        float lit = 1;

        Piece(GraffitiPackets.Placement placement, GraffitiData.Graffiti graffiti) {
            this.placement = placement;
            this.graffiti = graffiti;
            this.face = placement.floating() ? null
                    : Direction.getApproximateNearest(placement.nx(), placement.ny(), placement.nz());
            ux = placement.ux();
            uy = placement.uy();
            uz = placement.uz();
            // right = up × normal: how a viewer facing the surface sees the picture, unmirrored
            rx = uy * placement.nz() - uz * placement.ny();
            ry = uz * placement.nx() - ux * placement.nz();
            rz = ux * placement.ny() - uy * placement.nx();
        }

        Vec3 centre() {
            return new Vec3(placement.x(), placement.y(), placement.z());
        }

        float reveal(double ticks) {
            return Mth.clamp((float) ((ticks - placement.placedAt()) / REVEAL_TICKS), 0, 1);
        }

        float fade(double ticks) {
            return removedAt < 0 ? 1 : Mth.clamp(1 - (float) ((ticks - removedAt) / FADE_TICKS), 0, 1);
        }

        String effect() {
            return graffiti.effect;
        }
    }

    private static final Map<Integer, Piece> pieces = new LinkedHashMap<>();
    @Nullable
    private static ClientLevel level;
    private static int tickCount;
    private static final RandomSource random = RandomSource.create();

    private GraffitiWorld() {
    }

    static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    GraffitiArt.clear();
                    GraffitiRenderer.forgetTextures();
                }));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
        // on the faces: under the dust, smoke and debris in front of them
        WorldFx.register(WorldFx.Layer.SURFACE, GraffitiWorld::render);
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && !pieces.isEmpty()) {
                event.getText().add("[BlockLand] graffiti: " + pieces.size());
            }
            return false;
        });
    }

    static Collection<Piece> pieces() {
        return Collections.unmodifiableCollection(pieces.values());
    }

    // ---- from the server ------------------------------------------------------------------------------

    static void place(GraffitiPackets.Placement placement) {
        ClientLevel world = Minecraft.getInstance().level;
        GraffitiData.Graffiti graffiti = GraffitiCatalog.get().byId(placement.graffiti());
        if (world == null || graffiti == null) return;
        if (world != level) clear();
        level = world;
        Piece piece = new Piece(placement, graffiti);
        layOut(world, piece);
        if (placement.floating()) {
            BlockHitResult floor = world.clip(new ClipContext(piece.centre(), piece.centre().add(0, -(placement.size() / 2 + 2.5), 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if (floor.getType() == HitResult.Type.BLOCK && floor.getDirection() == Direction.UP) piece.floorY = floor.getLocation().y;
        }
        if (GraffitiData.PARTICLES.equals(graffiti.effect)) piece.points = GraffitiArt.points(graffiti.texture, graffiti.points);
        Piece old = pieces.put(placement.id(), piece);
        if (old != null) old.removedAt = 0;
        attachLight(piece);
        boolean fresh = world.getGameTime() - placement.placedAt() < 40;
        if (GraffitiData.NEON.equals(graffiti.effect)) {
            Minecraft.getInstance().getSoundManager().play(new NeonHum(piece));
        } else if (fresh && !graffiti.sound.isEmpty()) {
            ResourceLocation sound = ResourceLocation.tryParse(graffiti.sound);
            if (sound != null) {
                Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(Sounds.byId(sound), SoundSource.PLAYERS, 0.9f, 1f,
                        random, placement.x(), placement.y(), placement.z()));
            }
        }
        if (fresh) puff(world, piece);
        ensurePass(world);
    }

    static void remove(List<Integer> ids) {
        ClientLevel world = Minecraft.getInstance().level;
        long now = world == null ? 0 : world.getGameTime();
        for (int id : ids) {
            Piece piece = pieces.get(id);
            if (piece != null && piece.removedAt < 0) piece.removedAt = now;
        }
    }

    static void sync(List<GraffitiPackets.Placement> placements) {
        clear();
        for (GraffitiPackets.Placement p : placements) place(p);
    }

    // ---- ticking ------------------------------------------------------------------------------------

    private static void tick() {
        ClientLevel world = Minecraft.getInstance().level;
        if (world != level) {
            clear();
            level = world;
        }
        if (world == null || pieces.isEmpty()) return;
        long now = world.getGameTime();
        boolean relayout = ++tickCount % RELAYOUT_TICKS == 0;
        List<Integer> gone = new ArrayList<>();
        for (Map.Entry<Integer, Piece> entry : pieces.entrySet()) {
            Piece piece = entry.getValue();
            if (piece.removedAt >= 0 && now - piece.removedAt > FADE_TICKS) {
                gone.add(entry.getKey());
                continue;
            }
            if (relayout && piece.face != null) layOut(world, piece);
            if (GraffitiData.NEON.equals(piece.effect())) {
                // the light stutters with the tube (the shader draws the same stutter in the tube's glow)
                float flicker = piece.graffiti.flicker;
                piece.lit = random.nextFloat() < 0.03f * flicker ? 0.35f : 0.9f + 0.1f * Mth.sin(now * 0.23f + entry.getKey());
            }
        }
        for (int id : gone) pieces.remove(id);
        ensurePass(world);
    }

    private static void layOut(ClientLevel world, Piece piece) {
        if (piece.face == null) return;
        GraffitiPackets.Placement p = piece.placement;
        piece.polygons = GraffitiGeometry.layOut(world, p.x(), p.y(), p.z(), piece.face, piece.rx, piece.ry, piece.rz,
                piece.ux, piece.uy, piece.uz, p.size());
    }

    /** The light a glowing piece casts, for as long as it is up (the supplier ends it by returning null). */
    private static void attachLight(Piece piece) {
        GraffitiData.Graffiti g = piece.graffiti;
        if (g.light <= 0) return;
        int rgb = g.rgb();
        float r = (rgb >> 16 & 0xFF) / 255f, gr = (rgb >> 8 & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        Vec3 at = piece.face == null ? piece.centre()
                : piece.centre().add(piece.placement.nx() * 0.35, piece.placement.ny() * 0.35, piece.placement.nz() * 0.35);
        LightSources.attach(() -> {
            ClientLevel world = level;
            if (world == null || pieces.get(piece.placement.id()) != piece) return null;
            double ticks = world.getGameTime();
            float k = piece.reveal(ticks) * piece.fade(ticks) * piece.lit;
            return k <= 0.01f ? new LightSources.Light(at.x, at.y, at.z, r, gr, b, 0, g.lightRadius)
                    : new LightSources.Light(at.x, at.y, at.z, r, gr, b, g.light * k, g.lightRadius);
        });
    }

    /** A short cloud of paint mist where it was sprayed. */
    private static void puff(ClientLevel world, Piece piece) {
        GraffitiData.Graffiti g = piece.graffiti;
        int rgb = GraffitiData.NEON.equals(g.effect) || g.floating ? g.rgb() : 0xD8D8D8;
        Vec3 c = piece.centre();
        double nx = piece.placement.nx(), ny = piece.placement.ny(), nz = piece.placement.nz();
        for (int i = 0; i < 14; i++) {
            double s = piece.placement.size() * 0.5;
            double x = c.x + (random.nextDouble() - 0.5) * s + nx * 0.15, y = c.y + (random.nextDouble() - 0.5) * s + ny * 0.15,
                    z = c.z + (random.nextDouble() - 0.5) * s + nz * 0.15;
            world.addParticle(new DustParticleOptions(rgb, 0.8f + random.nextFloat() * 0.6f), x, y, z,
                    nx * 0.02 + (random.nextDouble() - 0.5) * 0.01, 0.01, nz * 0.02 + (random.nextDouble() - 0.5) * 0.01);
        }
    }

    private static void ensurePass(ClientLevel world) {
        if (pieces.isEmpty()) return;
        WorldFx.wake(world);
    }

    static void clear() {
        pieces.clear();
        level = null;
    }

    /** A neon tube's hum, where it hangs, for as long as it is up. */
    private static final class NeonHum extends AbstractTickableSoundInstance {
        private final Piece piece;

        NeonHum(Piece piece) {
            super(Sounds.byId(ResourceLocation.tryParse(piece.graffiti.sound.isEmpty() ? "blockland:graffiti.neon_hum" : piece.graffiti.sound)),
                    SoundSource.AMBIENT, RandomSource.create());
            this.piece = piece;
            this.looping = true;
            this.delay = 0;
            this.x = piece.placement.x();
            this.y = piece.placement.y();
            this.z = piece.placement.z();
            this.volume = 0.01f;
        }

        @Override
        public void tick() {
            ClientLevel world = level;
            if (world == null || pieces.get(piece.placement.id()) != piece) {
                stop();
                return;
            }
            double ticks = world.getGameTime();
            volume = 0.45f * piece.reveal(ticks) * piece.fade(ticks) * piece.lit;
        }
    }

    /** Draws every piece from inside the particle pass (world matrices, the depth of the solid world). */
    private static void render(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
        ClientLevel world = level;
        if (world == null || pieces.isEmpty()) return;
        GraffitiRenderer.draw(pieces.values(), camera, pose.last().pose(), partialTick, world);
    }
}
