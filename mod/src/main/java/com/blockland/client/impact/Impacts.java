package com.blockland.client.impact;

import com.blockland.client.perf.FrameTimings;
import com.blockland.client.render.LightSources;
import com.blockland.client.render.WeaponBlood;
import com.blockland.client.render.WaterRipples;
import com.blockland.client.render.WorldFx;
import com.blockland.client.settings.ClientSettings;
import com.blockland.combat.Ballistics;
import com.blockland.combat.HitShapes;
import com.blockland.common.Sounds;
import com.blockland.movement.action.BodyActions;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

/**
 * Bullet impacts (弹着效果): what a round or a blade throws out of whatever it strikes — debris of the block itself flying
 * on real ballistic paths and bouncing, sinking or floating in water, powder hanging in the air, sparks, blood that
 * sticks where it lands, splashes, the bubbles of a round under water. Client-only and purely visual; the weapon modules
 * only call one of {@link #block}, {@link #entity}, {@link #fluid}, {@link #path} (a round's way through fluids,
 * {@link Ballistics}), {@link #slash} / {@link #slashEntity} (blades) with the hit and their ammo or attack profile id
 * ({@link #disturb} for fluids stirred by anything else: wading, falling, swinging, blocks dropped in; {@link #drip} for
 * water falling off something soaked), and everything else — the material (data: {@code assets/blockland/impacts.json}),
 * the round's profile and the energy it has left, the hit angle and the side of the body part struck, the target's
 * motion, rain, snow, water and its current, wind, the quality setting and the distance — is decided here (rendering
 * spec §12).
 */
public final class Impacts {
    private static final ResourceLocation PLAIN = ResourceLocation.withDefaultNamespace("block/white_concrete");
    /** Beyond this many blocks nothing is spawned at all. */
    private static final double MAX_DISTANCE = 160;

    private static final ImpactParticles pool = new ImpactParticles();
    /** Wounds that go on bleeding a moment after the hit. */
    private static final Wounds wounds = new Wounds();
    private static final RandomSource random = RandomSource.create();
    @Nullable
    private static ClientLevel level;
    private static float windX, windZ;
    /** Impact sounds this tick: a shotgun's pellets or a burst land together, a few sounds carry them. */
    private static int soundsThisTick;
    private static final int MAX_SOUNDS_PER_TICK = 3;

    /**
     * What stains a body that flying blood strikes (rendering spec §19): wired up by the client (the decal module's body
     * decals), so this module does not depend on it. Returns whether the stain went on (else the drop flies on).
     */
    @FunctionalInterface
    public interface BodyStains {
        /**
         * @param at    where the drop struck the body's box
         * @param dir   the way it was flying (unit)
         * @param decal the blood's body decal type ({@code impacts.json} {@code bodyDecal})
         * @param size  the drop's half size (blocks)
         */
        boolean stain(Entity body, Vec3 at, Vec3 dir, String decal, float size);
    }

    /** Wires up what stains bodies struck by flying blood; null = nothing does. */
    public static void setBodyStains(@Nullable BodyStains stains) {
        pool.bodySink = stains;
    }

    private Impacts() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) ImpactCatalog::reload));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
        HurtConfirm.init();
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
        block(world, at, face, pos, dir, ammo, 1);
    }

    /** {@link #block} for a round that arrives with only {@code energy} (0..1) of its own left (slowed by water). */
    public static void block(ClientLevel world, Vec3 at, Direction face, BlockPos pos, Vec3 dir, @Nullable String ammo, float energy) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || energy <= 0.02f) return;
        float scale = scale(at);
        if (scale <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        ImpactData.Material material = ImpactCatalog.block(state);
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        TextureAtlasSprite sprite = mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
        int tint = mc.getBlockColors().getColor(state, world, pos, 0);
        tint = tint == -1 ? 0xFFFFFF : tint & 0xFFFFFF;
        BlockPos outside = BlockPos.containing(at.add(normal.scale(0.1)));
        int light = LevelRenderer.getLightColor(world, outside);
        ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, normal, dir, material, weakened(ImpactCatalog.ammo(ammo), energy), sprite, tint,
                dustColor(world, pos, state, material), light);
        spawn(world, hit, env(world, at, normal, outside), scale);
        sound(material, at, state.getSoundType());
    }

    /**
     * A round struck an entity at {@code at} (on its hit shape). Living targets bleed (their material's {@code blood}):
     * back-spatter towards the shooter out of the struck side of the body part, the exit spray along the round from where
     * it leaves that part (a head hit: the head), its fine aerosol, mist, all moving with the target; in water a plume,
     * at the surface a pinkish splash. Other entities throw what their material throws, off the side that was struck.
     */
    public static void entity(ClientLevel world, Vec3 at, Entity target, Vec3 dir, @Nullable String ammo) {
        entity(world, at, target, dir, ammo, 1);
    }

    /**
     * Whether a hit on {@code target} would bleed (its material has {@code blood}): such a hit's effects wait for the
     * server's word that the target was really hurt ({@link HurtConfirm}), the others play at once.
     */
    public static boolean bleeds(Entity target) {
        return ImpactCatalog.entity(target).blood != null;
    }

    /** {@link #entity} for a round that arrives with only {@code energy} (0..1) of its own left (slowed by water). */
    public static void entity(ClientLevel world, Vec3 at, Entity target, Vec3 dir, @Nullable String ammo, float energy) {
        float scale = scale(at);
        if (scale <= 0 || energy <= 0.02f) return;
        ImpactData.Material material = ImpactCatalog.entity(target);
        boolean head = isHead(target, at);
        HitShapes.Box part = HitShapes.part(target, at, head);
        Vec3 side = part.faceNormal(at);
        int rgb = ImpactCatalog.rgb(material.color);
        int light = LevelRenderer.getLightColor(world, BlockPos.containing(at));
        ImpactData.Ammo profile = weakened(ImpactCatalog.ammo(ammo), energy);
        ImpactSpawner.Env env = env(world, at, side, BlockPos.containing(at));
        if (material.blood != null) {
            // out of the wound back towards the shooter, tilted by the side of the part it went into
            Vec3 normal = dir.scale(-0.7).add(side.scale(0.6)).normalize();
            ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, normal, dir, material, profile, null, rgb, rgb, light);
            ImpactSettings settings = settings();
            // the body the camera is in throws no blood of its own on this screen (the spray and mist would cover the view
            // as it is hit): only the others see it
            if (settings.blood > 0 && !ownBody(target) && ensureLevel(world)) {
                double surface = surfaceOver(world, at);
                ImpactSpawner.Bleed bleed = new ImpactSpawner.Bleed(motion(target), head, settings.blood, settings.bloodStains);
                pool.source(target.getId());
                ImpactSpawner.blood(pool, hit, part.exit(at, dir), env, bleed, surface, scale, settings.qualityLevel().budget,
                        settings.dust, settings.bloodInWater, plainSprite(material), random);
                pool.source(-1);
                splashBack(world, at, dir, null, material.blood, profile.energy * settings.blood);
                // and it goes on bleeding a moment, from where it went in
                wounds.open(target, at, material.blood, rgb, settings.blood * (head ? material.blood.headMultiplier : 1) * profile.energy);
                // shot standing in water, the wound just above it: the water splashes, tinged with blood
                if (!env.underwater() && !Double.isNaN(surface) && at.y - surface < 0.9 && target.isInWater()) {
                    BlockPos waterPos = BlockPos.containing(at.x, surface - 0.05, at.z);
                    FluidState water = world.getFluidState(waterPos);
                    int tinted = ImpactSpawner.mix(BiomeColors.getAverageWaterColor(world, waterPos) & 0xFFFFFF,
                            ImpactCatalog.rgb(material.blood.plumeColor), material.blood.splashTint);
                    stir(world, new Vec3(at.x, surface, at.z), water, waterPos, dir, 0.5f * profile.energy, tinted);
                }
            }
            sound(material, at, null);
            return;
        }
        spawn(world, new ImpactSpawner.Hit(at, side, dir, material, profile, null, rgb, rgb, light), env, scale);
        sound(material, at, null);
    }

    /** A round went into a fluid's surface at {@code at} (it carries on below; this is the splash). */
    public static void fluid(ClientLevel world, Vec3 at, FluidState fluid, BlockPos pos, Vec3 dir, @Nullable String ammo) {
        fluid(world, at, fluid, pos, dir, ammo, 1);
    }

    private static void fluid(ClientLevel world, Vec3 at, FluidState fluid, BlockPos pos, Vec3 dir, @Nullable String ammo, float energy) {
        ImpactData.Material material = ImpactCatalog.fluid(fluid);
        if (material == null) return;
        float scale = scale(at);
        if (scale <= 0) return;
        int rgb = fluid.is(FluidTags.WATER) ? BiomeColors.getAverageWaterColor(world, pos) & 0xFFFFFF : ImpactCatalog.rgb(material.color);
        int light = LevelRenderer.getLightColor(world, pos.above());
        ImpactData.Ammo profile = weakened(ImpactCatalog.ammo(ammo), energy);
        ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, new Vec3(0, 1, 0), dir, material, profile, null, rgb, rgb, light);
        if (!ensureLevel(world)) return;
        ImpactSettings settings = settings();
        ImpactSpawner.splash(pool, hit, scale, settings.qualityLevel().budget, settings.dust, plainSprite(material), random);
        if (fluid.is(FluidTags.WATER)) {
            // the water's own surface takes the rings (drawn by the water shader), along the round's way
            Vec3 flat = new Vec3(dir.x, 0, dir.z);
            double len = flat.length();
            WaterRipples.add(world, at.x, at.y, at.z, Mth.clamp(0.4f + 0.5f * profile.energy, 0.2f, 2),
                    len > 1e-4 ? (float) (flat.x / len) : 0, len > 1e-4 ? (float) (flat.z / len) : 0);
        }
        flash(material, at);
        sound(material, at, null);
    }

    /**
     * A round's way through fluids ({@link Ballistics}; rendering spec §12, rounds in fluids), up to {@code stop} blocks
     * along it (where it struck something, or the path's end): a splash where it goes into water from the air (a skip
     * off the surface when it meets it at a grazing angle) or spatter where it goes into lava, the trail of bubbles its
     * cavity leaves under water, a spout where it leaves water upwards, and a spent round's last bubbles and its slug
     * sinking. Nothing when the path meets no fluid.
     */
    public static void path(ClientLevel world, Ballistics.Path path, double stop, @Nullable String ammo) {
        if (path.spans() == 0 || !ImpactCatalog.hasData()) return;
        ImpactSettings settings = settings();
        boolean fluids = settings.fluidPhysics;
        for (int i = 0; i < path.spans(); i++) {
            double a = path.spanStart(i);
            boolean skipHere = path.skipped && Math.abs(a - path.length) < 1e-6;
            if (a >= stop - 1e-6 && !skipHere) break;
            Vec3 entry = path.at(a);
            BlockPos pos = BlockPos.containing(entry.add(path.dir.scale(0.02)));
            FluidState fluid = world.getFluidState(pos);
            if (fluid.isEmpty()) continue;
            float energy = path.energyAt(a);
            if (skipHere) {
                skip(world, entry, fluid, pos, path.dir, ammo);
                return;
            }
            if (a > 1e-6) {
                if (path.spanFromSurface(i)) fluid(world, entry, fluid, pos, path.dir, ammo, energy);
                // into the side of falling water: it is torn, not splashed
                else disturb(world, entry, fluid, pos, path.dir, 0.3f * energy);
            }
            double b = Math.min(path.spanEnd(i), stop);
            if (path.spanLava(i) || !fluids) continue;
            float scale = scale(entry);
            if (scale > 0 && ensureLevel(world)) {
                ImpactSpawner.trail(pool, entry, path.at(b), energy, path.energyAt(b), scale, settings.qualityLevel().budget,
                        LevelRenderer.getLightColor(world, pos), plainSprite(ImpactCatalog.material("water")), random);
            }
            // out of the water upwards into the air: a spout where it breaks the surface
            if (b < stop - 1e-6 && path.dir.y > 0.15) {
                Vec3 out = path.at(b);
                BlockPos outPos = BlockPos.containing(out.subtract(path.dir.scale(0.02)));
                FluidState left = world.getFluidState(outPos);
                if (left.is(FluidTags.WATER)) disturb(world, out, left, outPos, path.dir, 0.6f * path.energyAt(b));
            }
        }
        if (path.spent && stop >= path.length - 1e-6) spent(world, path.end(), path.dir);
    }

    /**
     * A round a fluid stopped at {@code at}, as the server tells it (other players' shots, where this client's own trace
     * of the path may come out a hair different): a skip off the water there, or a spent round's last bubbles and slug.
     */
    public static void stopped(ClientLevel world, Vec3 at, Vec3 dir, @Nullable String ammo, boolean skipped) {
        if (!skipped) {
            spent(world, at, dir);
            return;
        }
        // the skip point lies on the surface: the water is just under it
        BlockPos pos = BlockPos.containing(at.x, at.y - 0.02, at.z);
        FluidState fluid = world.getFluidState(pos);
        if (!fluid.isEmpty()) skip(world, at, fluid, pos, dir, ammo);
    }

    /** A spent round under water: its last bubbles and the slug sinking (fluid physics). */
    private static void spent(ClientLevel world, Vec3 at, Vec3 dir) {
        ImpactSettings settings = settings();
        if (!settings.fluidPhysics || !ImpactCatalog.hasData() || !world.getFluidState(BlockPos.containing(at)).is(FluidTags.WATER)) return;
        float scale = scale(at);
        if (scale <= 0 || !ensureLevel(world)) return;
        ImpactSpawner.spent(pool, at, dir, scale, settings.qualityLevel().budget, LevelRenderer.getLightColor(world, BlockPos.containing(at)),
                plainSprite(ImpactCatalog.material("water")), random);
    }

    /** A round skipped off a water surface: a low spray thrown forward along its way, rings, and a slap. */
    private static void skip(ClientLevel world, Vec3 at, FluidState fluid, BlockPos pos, Vec3 dir, @Nullable String ammo) {
        Vec3 away = new Vec3(dir.x, Math.abs(dir.y) * 0.6 + 0.08, dir.z).normalize();
        int rgb = fluid.is(FluidTags.WATER) ? BiomeColors.getAverageWaterColor(world, pos) & 0xFFFFFF : -1;
        stir(world, at, fluid, pos, away, Mth.clamp(0.8f * ImpactCatalog.ammo(ammo).energy, 0.3f, 1.4f), rgb);
        ImpactData.Material material = ImpactCatalog.fluid(fluid);
        if (material != null) sound(material, at, null);
    }

    /**
     * A blade struck a block (rendering spec §12, blades): chips and dust thrown along the cut, sparks off metal and stone.
     *
     * @param motion  the blade's motion at the hit (the cut's direction, or the thrust's)
     * @param profile the attack's impact profile ({@code impacts.json} ammo, e.g. {@code slash}, {@code stab})
     */
    public static void slash(ClientLevel world, Vec3 at, Direction face, BlockPos pos, Vec3 motion, @Nullable String profile) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return;
        float scale = scale(at);
        if (scale <= 0 || !ensureLevel(world)) return;
        Minecraft mc = Minecraft.getInstance();
        ImpactData.Material material = ImpactCatalog.block(state);
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        TextureAtlasSprite sprite = mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
        int tint = mc.getBlockColors().getColor(state, world, pos, 0);
        tint = tint == -1 ? 0xFFFFFF : tint & 0xFFFFFF;
        BlockPos outside = BlockPos.containing(at.add(normal.scale(0.1)));
        ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, normal, motion, material, ImpactCatalog.ammo(profile), sprite, tint,
                dustColor(world, pos, state, material), LevelRenderer.getLightColor(world, outside));
        ImpactSettings settings = settings();
        ImpactSpawner.slash(pool, hit, env(world, at, normal, outside), scale, settings.qualityLevel().budget, settings.dust,
                plainSprite(material), random);
        if (material.flash > 0 && material.sparks.count > 0) flash(material, at.add(normal.scale(0.1)));
    }

    /**
     * A blade cut into an entity: blood flung off along the cut (a stab: back out along the blade), a little mist, moving
     * with the target; a plume under water. Bloodless targets throw their material's pieces off the struck side.
     *
     * @param from    where the attack came from (the attacker's eye)
     * @param motion  the blade's motion through the wound (unit)
     * @param profile the attack's impact profile ({@code impacts.json} ammo)
     */
    public static void slashEntity(ClientLevel world, Vec3 at, Entity target, Vec3 from, Vec3 motion, @Nullable String profile) {
        float scale = scale(at);
        if (scale <= 0 || !ensureLevel(world)) return;
        ImpactData.Material material = ImpactCatalog.entity(target);
        boolean head = isHead(target, at);
        HitShapes.Box part = HitShapes.part(target, at, head);
        Vec3 side = part.faceNormal(at);
        Vec3 towards = at.subtract(from);
        if (towards.lengthSqr() > 1e-6 && side.dot(towards) > 0) side = side.scale(-1);
        int rgb = ImpactCatalog.rgb(material.color);
        int light = LevelRenderer.getLightColor(world, BlockPos.containing(at));
        ImpactData.Ammo blade = ImpactCatalog.ammo(profile);
        ImpactSpawner.Env env = env(world, at, side, BlockPos.containing(at));
        ImpactSettings settings = settings();
        if (material.blood != null) {
            if (settings.blood <= 0 || ownBody(target)) return;
            ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, side, motion, material, blade, null, rgb, rgb, light);
            pool.source(target.getId());
            ImpactSpawner.cut(pool, hit, motion, env, new ImpactSpawner.Bleed(motion(target), head, settings.blood, settings.bloodStains),
                    surfaceOver(world, at), scale, settings.qualityLevel().budget, settings.dust, settings.bloodInWater, plainSprite(material),
                    random);
            pool.source(-1);
            splashBack(world, at, motion, from, material.blood, blade.energy * settings.blood);
            wounds.open(target, at, material.blood, rgb, settings.blood * (head ? material.blood.headMultiplier : 1) * blade.energy);
            return;
        }
        ImpactSpawner.slash(pool, new ImpactSpawner.Hit(at, side, motion, material, blade, null, rgb, rgb, light), env, scale,
                settings.qualityLevel().budget, settings.dust, plainSprite(material), random);
    }

    /**
     * A drop of water falling off something soaked (an entity or a weapon come out of water): it falls, splashes if it
     * lands in water and is gone on dry ground.
     *
     * @param motion the motion it leaves with (blocks per tick; the body's own)
     */
    public static void drip(ClientLevel world, Vec3 at, Vec3 motion) {
        float scale = scale(at);
        if (scale <= 0 || !ensureLevel(world)) return;
        BlockPos pos = BlockPos.containing(at);
        int rgb = BiomeColors.getAverageWaterColor(world, pos) & 0xFFFFFF;
        ImpactSpawner.drip(pool, at, motion, ImpactSpawner.mix(rgb, 0xFFFFFF, 0.35f), LevelRenderer.getLightColor(world, pos),
                settings().qualityLevel().budget, random);
    }

    /**
     * A foot coming down on rain-soaked ground: a few small drops kicked up from it (the water standing on the surface).
     *
     * @param motion the body's motion (blocks per tick)
     * @param energy 0..1: a walk … a sprint
     */
    public static void kick(ClientLevel world, Vec3 at, Vec3 motion, float energy) {
        float scale = scale(at);
        if (scale <= 0 || !ensureLevel(world)) return;
        BlockPos pos = BlockPos.containing(at);
        int rgb = ImpactSpawner.mix(BiomeColors.getAverageWaterColor(world, pos) & 0xFFFFFF, 0xFFFFFF, 0.5f);
        ImpactSpawner.kick(pool, at, motion, Mth.clamp(energy, 0, 1) * Math.min(1, scale), rgb, LevelRenderer.getLightColor(world, pos.above()),
                settings().qualityLevel().budget, random);
    }

    /**
     * A fluid stirred without a round: an entity wading or swimming, jumping or falling in, swinging at it, a block
     * dropped in. A splash shaped by the motion (drops thrown along {@code dir}, a crown all round for a fall), rings on
     * the surface and a few bubbles; no sound (vanilla's splash sounds play for these already).
     *
     * @param at     on the fluid's surface
     * @param dir    the disturber's motion or facing (unit; yaw and pitch)
     * @param energy 0..2: 0.2 a hand swing, 0.5 walking through, 1 a jump or fall, 2 a heavy fall or a block dropped in
     */
    public static void disturb(ClientLevel world, Vec3 at, FluidState fluid, BlockPos pos, Vec3 dir, float energy) {
        if (fluid.isEmpty() || energy <= 0) return;
        int rgb = fluid.is(FluidTags.WATER) ? BiomeColors.getAverageWaterColor(world, pos) & 0xFFFFFF : -1;
        stir(world, at, fluid, pos, dir, energy, rgb);
    }

    /** {@link #disturb} with the splash's colour ({@code -1}: the fluid material's). */
    private static void stir(ClientLevel world, Vec3 at, FluidState fluid, BlockPos pos, Vec3 dir, float energy, int rgb) {
        ImpactData.Material material = ImpactCatalog.fluid(fluid);
        if (material == null) return;
        float e = Mth.clamp(energy, 0, 2);
        if (fluid.is(FluidTags.WATER)) {
            Vec3 flat = new Vec3(dir.x, 0, dir.z);
            double len = flat.length();
            WaterRipples.add(world, at.x, at.y, at.z, Math.max(0.2f, e), len > 1e-4 ? (float) (flat.x / len) : 0,
                    len > 1e-4 ? (float) (flat.z / len) : 0);
        }
        float scale = scale(at);
        if (scale <= 0 || !ensureLevel(world)) return;
        int tint = rgb == -1 ? ImpactCatalog.rgb(material.color) : rgb;
        int light = LevelRenderer.getLightColor(world, pos.above());
        ImpactSpawner.Hit hit = new ImpactSpawner.Hit(at, new Vec3(0, 1, 0), dir, material, ImpactCatalog.ammo(null), null, tint, tint, light);
        ImpactSpawner.disturb(pool, hit, e, scale, settings().qualityLevel().budget, plainSprite(material), random);
    }

    /** The height of the water surface over a point in water (or just above it), or NaN when no water is there. */
    private static double surfaceOver(ClientLevel world, Vec3 at) {
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos pos = BlockPos.containing(at.x, at.y + dy, at.z);
            FluidState fluid = world.getFluidState(pos);
            if (!fluid.is(FluidTags.WATER)) continue;
            // up through the water column to its top
            BlockPos.MutableBlockPos top = pos.mutable();
            while (world.getFluidState(top.above()).is(FluidTags.WATER) && top.getY() < pos.getY() + 32) top.move(0, 1, 0);
            return top.getY() + world.getFluidState(top).getHeight(world, top);
        }
        return Double.NaN;
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

    /** Whether a hit at {@code at} is on the entity's head (its head box while it slides, else the head band). */
    private static boolean isHead(Entity target, Vec3 at) {
        if (BodyActions.isActing(target)) {
            for (HitShapes.Box box : HitShapes.of(target, 0.03)) if (box.head() && box.contains(at)) return true;
            return false;
        }
        return HitShapes.inHeadBand(target, at.y);
    }

    /** The entity's motion over the last tick (blocks per tick, at most a running sprint's): its blood moves with it. */
    private static Vec3 motion(Entity target) {
        Vec3 v = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
        double length = v.length();
        return length > 0.6 ? v.scale(0.6 / length) : v;
    }

    /**
     * A round's profile with only {@code energy} (0..1) of it left: slower pieces, fewer of them, less powder (a round
     * slowed by water strikes softly).
     */
    private static ImpactData.Ammo weakened(ImpactData.Ammo ammo, float energy) {
        if (energy >= 0.999f) return ammo;
        float e = Mth.clamp(energy, 0, 1);
        ImpactData.Ammo weak = new ImpactData.Ammo();
        weak.energy = ammo.energy * (0.3f + 0.7f * e);
        weak.count = ammo.count * (0.25f + 0.75f * e);
        weak.size = ammo.size;
        weak.dust = ammo.dust * e;
        weak.sparks = ammo.sparks * e;
        weak.blade = ammo.blade;
        weak.thrust = ammo.thrust;
        return weak;
    }

    /** The powder's colour: paler than the solid block it came from (its map colour), or the material's own. */
    private static int dustColor(ClientLevel world, BlockPos pos, BlockState state, ImpactData.Material material) {
        int materialRgb = ImpactCatalog.rgb(material.color);
        return material.blockDustColor
                ? ImpactSpawner.mix(ImpactSpawner.mix(state.getMapColor(world, pos).col, materialRgb, 0.3f), 0xFFFFFF, 0.2f)
                : materialRgb;
    }

    private static void spawn(ClientLevel world, ImpactSpawner.Hit hit, ImpactSpawner.Env env, float scale) {
        if (!ensureLevel(world)) return;
        ImpactSettings settings = settings();
        ImpactSpawner.spawn(pool, hit, env, scale, settings.qualityLevel().budget, settings.dust, plainSprite(hit.material()), random);
        // no sparks under water, so no flash of them either
        if (!env.underwater()) flash(hit.material(), hit.at().add(hit.normal().scale(0.15)));
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
        boolean fluids = settings().fluidPhysics;
        // under water is not weather: blood, dust and sparks there are what they are with the switch off too
        boolean underwater = world.getFluidState(outside).is(FluidTags.WATER);
        if (!settings().environment) return new ImpactSpawner.Env(false, false, underwater, fluids);
        boolean wet = !underwater && world.isRainingAt(outside);
        boolean snowy = false;
        if (!underwater && world.isRaining() && world.canSeeSky(outside)) {
            Biome biome = world.getBiome(outside).value();
            snowy = biome.getPrecipitationAt(outside, world.getSeaLevel()) == Biome.Precipitation.SNOW;
        }
        return new ImpactSpawner.Env(wet, snowy, underwater, fluids);
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
        // under water a strike is a dull, low knock (the water around it or the listener's ear in it)
        Minecraft mc = Minecraft.getInstance();
        boolean muffled = settings().fluidPhysics && mc.level != null && (mc.level.getFluidState(BlockPos.containing(at)).is(FluidTags.WATER)
                || mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER);
        if (muffled) {
            volume *= 0.55f;
            pitch *= 0.6f;
        }
        mc.getSoundManager().play(new SimpleSoundInstance(event, SoundSource.BLOCKS, volume, pitch, random, at.x, at.y, at.z));
    }

    /** The body the camera is in (the player, or whoever is watched through their eyes): it throws no blood on this screen. */
    private static boolean ownBody(Entity target) {
        return target == Minecraft.getInstance().getCameraEntity();
    }

    /** The material's sprite for untextured pieces (drops, blood, bubbles), white concrete when it names none. */
    private static TextureAtlasSprite plainSprite(ImpactData.Material material) {
        ResourceLocation id = material.sprite == null ? null : ResourceLocation.tryParse(material.sprite);
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(id == null ? PLAIN : id);
    }

    /**
     * Blood splashed back onto the weapon of whoever struck the body (rendering spec §19, the weapon): the player whose eye
     * the attack came from ({@code from}, a blade) or who looks down the round's line at the wound within the blood's
     * {@code weaponRange}; a blade's hit leaves its {@code weaponBlade}, a round's less the further off it was fired.
     */
    private static void splashBack(ClientLevel world, Vec3 at, Vec3 dir, @Nullable Vec3 from, @Nullable ImpactData.Blood b, float energy) {
        if (b == null || b.weaponRange <= 0 || energy <= 0) return;
        for (Player player : world.players()) {
            if (player.isSpectator()) continue;
            Vec3 eye = player.getEyePosition();
            double distance = eye.distanceTo(at);
            if (distance > b.weaponRange || distance < 1e-3) continue;
            boolean struck = from != null ? eye.distanceTo(from) < 0.75 : at.subtract(eye).scale(1 / distance).dot(dir) > 0.9;
            if (!struck) continue;
            float amount = from != null ? b.weaponBlade : b.weaponRound * (float) (1 - distance / b.weaponRange);
            WeaponBlood.splash(player, eye.subtract(at), amount * Math.min(1.5f, energy), world.getGameTime(),
                    Math.round(b.weaponLife * 20), Math.round(b.weaponDry * 20));
            return;
        }
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
        if (world != null && world.getGameTime() % 100 == 0) WeaponBlood.prune(world.getGameTime());
        if (world == null || world != level) {
            if (!pool.isEmpty() || !wounds.isEmpty()) clear();
            return;
        }
        ImpactSettings settings = settings();
        if (!wounds.isEmpty() && !Minecraft.getInstance().isPaused()) {
            wounds.tick(world, pool, settings.qualityLevel().budget, settings.bloodStains, settings.bloodInWater,
                    plainSprite(ImpactCatalog.material(ImpactCatalog.data().fallbackEntity)), random);
            if (!pool.isEmpty()) WorldFx.wake(world);
        }
        if (pool.isEmpty()) return;
        wind(world);
        pool.tick(world, windX, windZ, settings);
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
        wounds.clear();
        WeaponBlood.clear();
        ImpactCatalog.clearCaches();
        level = null;
        ImpactRenderer.release();
    }

    /** Draws the pool from inside the particle pass (world matrices, the depth of the solid world). */
    private static void render(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick) {
        ClientLevel world = level;
        if (world == null || pool.isEmpty()) return;
        float time = (float) ((world.getGameTime() % 24000L) + partialTick) / 20f;
        try (FrameTimings.Span ignored = FrameTimings.span("bl/impact/render")) {
            ImpactRenderer.draw(pool, camera, pose.last().pose(), partialTick, time);
        }
    }
}
