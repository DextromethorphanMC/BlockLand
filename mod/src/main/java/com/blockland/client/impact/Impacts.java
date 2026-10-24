package com.blockland.client.impact;

import com.blockland.client.render.LightSources;
import com.blockland.client.render.WorldFx;
import com.blockland.client.settings.ClientSettings;
import com.blockland.common.Sounds;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

/**
 * Bullet impacts (弹着效果): what a round throws out of whatever it strikes — debris of the block itself flying on real
 * ballistic paths and bouncing, powder hanging in the air, sparks, blood, splashes. Client-only and purely visual; the
 * weapon modules only call one of {@link #block}, {@link #entity}, {@link #fluid} (or {@link #fluidOnPath}) with the hit
 * and their ammo id, and everything else — the material (data: {@code assets/blockland/impacts.json}), the round's
 * profile, the hit angle, rain, snow, water, wind, the quality setting and the distance — is decided here (rendering
 * spec §12).
 */
public final class Impacts {
    private static final ResourceLocation PLAIN = ResourceLocation.withDefaultNamespace("block/white_concrete");
    /** Beyond this many blocks nothing is spawned at all. */
    private static final double MAX_DISTANCE = 160;

    private static final ImpactParticles pool = new ImpactParticles();
    private static final RandomSource random = RandomSource.create();
    @Nullable
    private static ClientLevel level;
    private static float windX, windZ;
    /** Impact sounds this tick: a shotgun's pellets or a burst land together, a few sounds carry them. */
    private static int soundsThisTick;
    private static final int MAX_SOUNDS_PER_TICK = 3;

    private Impacts() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) ImpactCatalog::reload));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
        // in the air: over the bullet holes and graffiti on the faces behind
        WorldFx.register(WorldFx.Layer.VOLUME, Impacts::render);
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && !pool.isEmpty()) {
                event.getText().add("[BlockLand] impact pieces: " + pool.live());
            }
            return false;
        });
    }

    /** The player's impact settings. */
    public static ImpactSettings settings() {
        ImpactSettings settings = ClientSettings.get().impacts;
        return settings == null ? new ImpactSettings() : settings;
    }

    // ---- what weapons call --------------------------------------------------------------------------

    /**
     * A round struck a block.
     *
     * @param at   the hit point on the block's face
     * @param face the face it struck
     * @param pos  the block
     * @param dir  the round's direction (unit)
     * @param ammo the ammo id ({@code guns.json} ammo, e.g. {@code rifle}); null = the default profile
     */
    public static void block(ClientLevel world, Vec3 at, Direction face, BlockPos pos, Vec3 dir, @Nullable String ammo) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return;
        float scale = scale(at);
        if (scale <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        ImpactData.Material material = ImpactCatalog.block(state);
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        TextureAtlasSprite sprite = mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
        int tint = mc.getBlockColors().getColor(state, world, pos, 0);
        tint = tint == -1 ? 0xFFFFFF : tint & 0xFFFFFF;
        int materialRgb = ImpactCatalog.rgb(material.color);
        // powder is paler than the solid block it came from
        int dust = material.blockDustColor
                ? ImpactSpawner.mix(ImpactSpawner.mix(state.getMapColor(world, pos).col, materialRgb, 0.3f), 0xFFFFFF, 0.2f)
                : materialRgb;
        BlockPos outside = BlockPos.containing(at.add(normal.scale(0.1)));
        int light = LevelRenderer.getLightColor(world, outside);
        ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, normal, dir, material, ImpactCatalog.ammo(ammo), sprite, tint, dust, light);
        spawn(world, hit, env(world, at, normal, outside), scale);
        sound(material, at, state.getSoundType());
    }

    /** A round struck an entity at {@code at} (on its hit shape). */
    public static void entity(ClientLevel world, Vec3 at, Entity target, Vec3 dir, @Nullable String ammo) {
        float scale = scale(at);
        if (scale <= 0) return;
        ImpactData.Material material = ImpactCatalog.entity(target);
        // out of the body towards the shooter, tilted by where on the body it hit
        Vec3 fromCentre = at.subtract(target.getBoundingBox().getCenter());
        Vec3 normal = dir.scale(-1).add(fromCentre.normalize().scale(0.5)).normalize();
        int rgb = ImpactCatalog.rgb(material.color);
        int light = LevelRenderer.getLightColor(world, BlockPos.containing(at));
        ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, normal, dir, material, ImpactCatalog.ammo(ammo), null, rgb, rgb, light);
        spawn(world, hit, env(world, at, normal, BlockPos.containing(at)), scale);
        sound(material, at, null);
    }

    /** A round went into a fluid's surface at {@code at} (it carries on below; this is the splash). */
    public static void fluid(ClientLevel world, Vec3 at, FluidState fluid, BlockPos pos, Vec3 dir, @Nullable String ammo) {
        ImpactData.Material material = ImpactCatalog.fluid(fluid);
        if (material == null) return;
        float scale = scale(at);
        if (scale <= 0) return;
        int rgb = fluid.is(FluidTags.WATER) ? BiomeColors.getAverageWaterColor(world, pos) & 0xFFFFFF : ImpactCatalog.rgb(material.color);
        int light = LevelRenderer.getLightColor(world, pos.above());
        ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, new Vec3(0, 1, 0), dir, material, ImpactCatalog.ammo(ammo), null, rgb, rgb, light);
        if (!ensureLevel(world)) return;
        ImpactSettings settings = settings();
        ImpactSpawner.splash(pool, hit, scale, settings.qualityLevel().budget, settings.dust, plainSprite(material), random);
        flash(material, at);
        sound(material, at, null);
    }

    /**
     * Splashes where the line from {@code from} to {@code to} first enters a fluid's surface (a round flying into water
     * on its way to whatever it hits). Call it with the whole shot; nothing happens when no fluid lies on the way.
     */
    public static void fluidOnPath(ClientLevel world, Vec3 from, Vec3 to, @Nullable Entity shooter, @Nullable String ammo) {
        // a shot fired from under water starts inside it: no surface to splash
        if (!world.getFluidState(BlockPos.containing(from)).isEmpty()) return;
        BlockHitResult hit = world.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
                shooter == null ? CollisionContext.empty() : CollisionContext.of(shooter)));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        // the clip stopped at a solid block (no fluid on the way) or in the fluid's surface
        FluidState fluid = world.getFluidState(hit.getBlockPos());
        if (fluid.isEmpty()) return;
        fluid(world, hit.getLocation(), fluid, hit.getBlockPos(), to.subtract(from).normalize(), ammo);
    }

    /** The entity a round that ended at {@code end} struck, if one is there (for shots reported without the target). */
    @Nullable
    public static Entity entityAt(ClientLevel world, Vec3 end, @Nullable Entity shooter) {
        Entity best = null;
        double nearest = 0.36;
        for (Entity e : world.getEntities(shooter, new AABB(end, end).inflate(0.6), e -> e.isPickable() && !e.isSpectator())) {
            double d = e.getBoundingBox().distanceToSqr(end);
            if (d < nearest) {
                nearest = d;
                best = e;
            }
        }
        return best;
    }

    // ---- inside -------------------------------------------------------------------------------------

    private static void spawn(ClientLevel world, ImpactSpawner.Hit hit, ImpactSpawner.Env env, float scale) {
        if (!ensureLevel(world)) return;
        ImpactSettings settings = settings();
        ImpactSpawner.spawn(pool, hit, env, scale, settings.qualityLevel().budget, settings.dust, plainSprite(hit.material()), random);
        flash(hit.material(), hit.at().add(hit.normal().scale(0.15)));
    }

    /** Quality × distance: full up to the quality's detail distance, a few pieces at twice it, none past 160 blocks. */
    private static float scale(Vec3 at) {
        if (!ImpactCatalog.hasData()) return 0;
        ImpactSettings.Quality quality = settings().qualityLevel();
        if (quality == ImpactSettings.Quality.OFF) return 0;
        Minecraft mc = Minecraft.getInstance();
        double distance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(at);
        if (distance > MAX_DISTANCE) return 0;
        float near = quality.detail;
        float lod = distance <= near ? 1 : (float) Mth.clampedLerp(1, 0.15, (distance - near) / near);
        return quality.count * lod;
    }

    private static ImpactSpawner.Env env(ClientLevel world, Vec3 at, Vec3 normal, BlockPos outside) {
        if (!settings().environment) return ImpactSpawner.Env.NONE;
        boolean underwater = world.getFluidState(outside).is(FluidTags.WATER);
        boolean wet = !underwater && world.isRainingAt(outside);
        boolean snowy = false;
        if (!underwater && world.isRaining() && world.canSeeSky(outside)) {
            Biome biome = world.getBiome(outside).value();
            snowy = biome.getPrecipitationAt(outside, world.getSeaLevel()) == Biome.Precipitation.SNOW;
        }
        return new ImpactSpawner.Env(wet, snowy, underwater);
    }

    private static void flash(ImpactData.Material material, Vec3 at) {
        if (material.flash > 0) LightSources.flash(at.x, at.y, at.z, ImpactCatalog.rgb(material.flashColor), 1.4f, 3.5f, material.flash);
    }

    private static void sound(ImpactData.Material material, Vec3 at, @Nullable SoundType blockSound) {
        if (material.sound == null || material.sound.isEmpty() || material.soundVolume <= 0) return;
        if (soundsThisTick >= MAX_SOUNDS_PER_TICK) return;
        soundsThisTick++;
        SoundEvent event;
        float volume = material.soundVolume, pitch = 0.85f + random.nextFloat() * 0.3f;
        if ("block".equals(material.sound)) {
            if (blockSound == null) return;
            event = blockSound.getHitSound();
            volume *= blockSound.getVolume();
            pitch *= blockSound.getPitch();
        } else {
            ResourceLocation id = ResourceLocation.tryParse(material.sound);
            if (id == null) return;
            event = Sounds.byId(id);
        }
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(event, SoundSource.BLOCKS, volume, pitch, random, at.x, at.y, at.z));
    }

    /** The material's sprite for untextured pieces (drops, blood, bubbles), white concrete when it names none. */
    private static TextureAtlasSprite plainSprite(ImpactData.Material material) {
        ResourceLocation id = material.sprite == null ? null : ResourceLocation.tryParse(material.sprite);
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(id == null ? PLAIN : id);
    }

    /** Keeps the pool to one level and the world effects pass alive there; false without a level. */
    private static boolean ensureLevel(ClientLevel world) {
        if (world != level) {
            pool.clear();
            level = world;
        }
        WorldFx.wake(world);
        return true;
    }

    private static void tick() {
        soundsThisTick = 0;
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null || world != level) {
            if (!pool.isEmpty()) clear();
            return;
        }
        if (pool.isEmpty()) return;
        wind(world);
        pool.tick(world, windX, windZ, settings().secondCollisions, settings().dust);
        if (pool.isEmpty()) ImpactRenderer.release();
    }

    /** A wind that turns and gusts slowly (blocks per tick), stronger in rain and thunder. */
    private static void wind(ClientLevel world) {
        ImpactData.Environment e = ImpactCatalog.data().environment;
        if (!settings().environment) {
            windX = windZ = 0;
            return;
        }
        double t = world.getGameTime();
        double angle = t * 0.0004 + Math.sin(t * 0.0013) * 1.3;
        float gust = 0.6f + 0.4f * (float) Math.sin(t * 0.011 + Math.sin(t * 0.0031) * 2);
        float weather = world.isThundering() ? e.thunderWind : world.isRaining() ? e.rainWind : 1;
        float speed = e.wind * weather * gust / 20;
        windX = (float) Math.cos(angle) * speed;
        windZ = (float) Math.sin(angle) * speed;
    }

    private static void clear() {
        pool.clear();
        ImpactCatalog.clearCaches();
        level = null;
        ImpactRenderer.release();
    }

    /** Draws the pool from inside the particle pass (world matrices, the depth of the solid world). */
    private static void render(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
        ClientLevel world = level;
        if (world == null || pool.isEmpty()) return;
        float time = (float) ((world.getGameTime() % 24000L) + partialTick) / 20f;
        ImpactRenderer.draw(pool, camera, pose.last().pose(), partialTick, time);
    }
}
