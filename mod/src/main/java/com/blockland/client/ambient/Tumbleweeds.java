package com.blockland.client.ambient;

import com.blockland.ambient.AmbientContent;
import com.blockland.ambient.TumbleweedEntity;
import com.blockland.client.perf.FrameTimings;
import com.blockland.client.render.WindField;
import com.blockland.client.settings.ClientSettings;
import com.blockland.network.BLNetwork;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Tumbleweeds (风滚草, rendering spec §29): client-only {@link TumbleweedEntity}s rolling with the wind. Where — the
 * player standing in a desert-like biome of the data (on open sandy ground: the data's ground blocks with nothing above),
 * or inside the box of a match whose map asks for them ({@link AmbientZone}, from the server; any open ground there).
 * How many — the data's count for each case, times the 画质 tab's density. They come upwind of the player and out of
 * sight (or far off), grow in, roll past, and shrink away once they are far, out of their area or old — only where the
 * player is not looking. Moving them is {@link TumbleweedPhysics}, drawing them {@link TumbleweedRenderer}.
 */
public final class Tumbleweeds {
    /** Spawning and despawning run this often (ticks). */
    private static final int PERIOD = 10;
    private static final float[] WIND = new float[3];
    private static final List<TumbleweedEntity> live = new ArrayList<>();
    private static final RandomSource random = RandomSource.create();
    @Nullable
    private static TumbleweedData data;
    @Nullable
    private static ClientLevel level;
    /** Client-only entities take ids the server never hands out. */
    private static int nextId = -0x40000000;
    private static int ticks;
    /** Development only ({@link #devPlace}): placed still ones stand where they were put and the spawner rests. */
    private static boolean frozen;

    private Tumbleweeds() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event ->
                event.registerEntityRenderer(AmbientContent.TUMBLEWEED.get(), TumbleweedRenderer::new));
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> data = TumbleweedData.load(manager)));
        TumbleweedEntity.ticker = TumbleweedPhysics::tick;
        BLNetwork.setAmbientClientListener(AmbientZone::received);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            live.clear();
            frozen = false;
            AmbientZone.clear();
            level = null;
        });
    }

    @Nullable
    static TumbleweedData data() {
        return data;
    }

    static boolean frozen() {
        return frozen;
    }

    /**
     * Development only (the benchmark scene, {@code DevAutomation}'s {@code tumbleweeds}): replaces every tumbleweed with
     * {@code count} still ones round the player — on rings 5 to 17 blocks out, evenly all round, or ({@code front})
     * within 50° either side of where the player looks — the same places, sizes, models and turns every time. While any
     * stand there nothing moves them and the spawner rests; 0 takes them away and lets it go on. Returns how many stand.
     */
    public static int devPlace(int count, boolean front) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        LocalPlayer player = mc.player;
        live.forEach(Entity::discard);
        live.clear();
        frozen = count > 0;
        if (world == null || player == null || data == null || count <= 0) return 0;
        RandomSource seeded = RandomSource.create(0x7577L);
        double look = Math.toRadians(player.getYRot() + 90);
        for (int i = 0; i < count; i++) {
            double angle = front ? look + Math.toRadians(-50 + 100 * (i + 0.5) / count) : Mth.TWO_PI * i / count;
            double distance = 5 + (i % 4) * 4 + seeded.nextDouble();
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance), z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            int top = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            TumbleweedEntity w = new TumbleweedEntity(AmbientContent.TUMBLEWEED.get(), world);
            w.setId(nextId--);
            w.setRadius(Mth.lerp(seeded.nextFloat(), data.size[0], data.size[Math.min(1, data.size.length - 1)]));
            w.variant = i % TumbleweedRenderer.VARIANTS;
            w.rotation.rotateAxis(seeded.nextFloat() * Mth.TWO_PI, new Vector3f(seeded.nextFloat() - 0.5f, seeded.nextFloat() - 0.5f,
                    seeded.nextFloat() - 0.5f).normalize());
            w.rotationO.set(w.rotation);
            w.fade = w.fadeO = 1;
            w.life = Integer.MAX_VALUE;
            w.setPos(x + 0.5, top, z + 0.5);
            w.setOldPosAndRot();
            world.addEntity(w);
            live.add(w);
        }
        return live.size();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        LocalPlayer player = mc.player;
        if (world != level) {
            // the old level's tumbleweeds went with it
            live.clear();
            level = world;
        }
        if (world == null || player == null || data == null || ++ticks % PERIOD != 0) return;
        try (FrameTimings.Span ignored = FrameTimings.span("bl/ambient/tumbleweeds")) {
            update(mc, world, player, data);
        }
    }

    private static void update(Minecraft mc, ClientLevel world, LocalPlayer player, TumbleweedData d) {
        live.removeIf(Entity::isRemoved);
        if (frozen) return;
        AmbientSettings settings = ClientSettings.get().ambient;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        AABB zone = AmbientZone.tumbleweeds();
        boolean inZone = zone != null && zone.inflate(16).contains(player.position());
        boolean desert = !inZone && zone == null && desertAt(world, player.blockPosition(), d);
        int target = !settings.tumbleweeds ? 0 : Math.round((inZone ? d.matchCount : desert ? d.maxCount : 0) * settings.tumbleweedDensity);
        int staying = 0;
        for (TumbleweedEntity w : live) {
            if (w.leaving) continue;
            if (!settings.tumbleweeds) {
                w.leaving = true;
                continue;
            }
            double distance = w.position().distanceTo(eye);
            boolean seen = distance < 48 && inView(mc, camera, w.position());
            boolean outside = inZone ? !zone.inflate(2).contains(w.position()) : !desertAt(world, w.blockPosition(), d);
            if (distance > d.despawnDistance || !seen && (outside || w.age > w.life || staying >= target)) {
                w.leaving = true;
            } else {
                staying++;
            }
        }
        if (staying < target) spawn(mc, world, player, camera, inZone ? zone : null, d);
    }

    /** One tumbleweed upwind of the player, out of sight or far off, on open ground of its area (a few tries). */
    private static void spawn(Minecraft mc, ClientLevel world, LocalPlayer player, Camera camera, @Nullable AABB zone, TumbleweedData d) {
        WindField.sample(player.getX(), player.getZ(), WIND);
        double upwind = Math.atan2(-WIND[1], -WIND[0]);
        Vec3 eye = camera.getPosition();
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = upwind + (random.nextDouble() - 0.5) * 2.4;
            double distance = Mth.lerp(random.nextDouble(), d.spawnRadius[0], d.spawnRadius[Math.min(1, d.spawnRadius.length - 1)]);
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance), z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            if (zone != null && (x + 0.5 < zone.minX || x + 0.5 > zone.maxX || z + 0.5 < zone.minZ || z + 0.5 > zone.maxZ)) continue;
            if (!world.hasChunk(x >> 4, z >> 4)) continue;
            int top = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos at = new BlockPos(x, top, z), below = at.below();
            if (zone != null && (top < zone.minY || top > zone.maxY)) continue;
            if (!world.getFluidState(below).isEmpty() || !world.getFluidState(at).isEmpty()) continue;
            BlockState ground = world.getBlockState(below);
            boolean open = zone != null ? ground.isFaceSturdy(world, below, Direction.UP) : d.ground(ground) && desertAt(world, at, d);
            if (!open || !world.getBlockState(at).getCollisionShape(world, at).isEmpty()) continue;
            Vec3 p = Vec3.atBottomCenterOf(at);
            if (p.distanceTo(eye) < 40 && inView(mc, camera, p)) continue;
            TumbleweedEntity w = new TumbleweedEntity(AmbientContent.TUMBLEWEED.get(), world);
            w.setId(nextId--);
            w.setRadius(Mth.lerp(random.nextFloat(), d.size[0], d.size[Math.min(1, d.size.length - 1)]));
            w.variant = random.nextInt(TumbleweedRenderer.VARIANTS);
            w.rotation.rotateAxis(random.nextFloat() * Mth.TWO_PI, new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f,
                    random.nextFloat() - 0.5f).normalize());
            w.rotationO.set(w.rotation);
            w.life = Mth.nextInt(random, d.lifetime[0], Math.max(d.lifetime[0], d.lifetime[Math.min(1, d.lifetime.length - 1)]));
            w.setPos(p.x, p.y + 0.02, p.z);
            w.setOldPosAndRot();
            // already on its way with the wind
            w.setDeltaMovement(WIND[0] * 0.12, 0, WIND[1] * 0.12);
            world.addEntity(w);
            live.add(w);
            return;
        }
    }

    /** The data's desert-like biomes, under an open sky (a dimension with daylight). */
    private static boolean desertAt(ClientLevel world, BlockPos pos, TumbleweedData d) {
        if (!world.dimensionType().hasSkyLight()) return false;
        return world.getBiome(pos).unwrapKey().map(key -> d.biomeIds.contains(key.location())).orElse(false);
    }

    /** Whether a point is roughly inside the view (a little wider than the screen, so nothing pops in at its edges). */
    private static boolean inView(Minecraft mc, Camera camera, Vec3 point) {
        Vec3 to = point.subtract(camera.getPosition());
        double length = to.length();
        if (length < 1e-3) return true;
        Vector3f look = camera.getLookVector();
        double cos = (to.x * look.x() + to.y * look.y() + to.z * look.z()) / length;
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        double halfH = Math.atan(Math.tan(Math.toRadians(mc.options.fov().get()) / 2) * aspect);
        return cos > Math.cos(Math.min(Math.PI, halfH + Math.toRadians(12)));
    }
}
