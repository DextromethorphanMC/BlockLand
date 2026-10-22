package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.anim.Ease;
import com.blockland.anim.Ticker;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.GpuModel;
import com.blockland.client.decal.Decals;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.render.ModelCache;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunMath;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.blockland.network.BLNetwork;
import com.blockland.network.GunPacketListeners;
import com.blockland.network.packet.GunPackets;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Client side of guns: input (fire / aim / reload / inspect / gunsmith), client-side prediction (ammo, spread,
 * tracers, impacts) so shooting feels instant, camera recoil and ADS zoom, and the hooks that put the
 * {@link Viewmodel} on screen instead of the vanilla hand. The server stays authoritative for hits and ammo.
 */
public final class ClientGuns {
    public static final KeyMapping RELOAD = new KeyMapping("key.blockland.reload", InputConstants.KEY_R, "key.categories.blockland");
    public static final KeyMapping GUNSMITH = new KeyMapping("key.blockland.gunsmith", InputConstants.KEY_G, "key.categories.blockland");
    public static final KeyMapping FIRE_MODE = new KeyMapping("key.blockland.fire_mode", InputConstants.KEY_B, "key.categories.blockland");
    /** Firing or aiming out of a sprint waits until the gun has come down from the sprint carry. */
    private static final float SPRINT_READY = 0.35f;

    /** Server ammo wins again once we have not predicted anything for this long. */
    private static final long RECONCILE_NANOS = 450_000_000L;
    private static final Viewmodel viewmodel = new Viewmodel();
    private static final Random random = new Random();

    // equipped gun
    private static int slot = -1;
    private static String gunId = "";
    private static boolean modelReady;
    @Nullable
    private static GunStats stats;
    @Nullable
    private static GunState state;

    // prediction
    private static int ammo;
    private static long lastShot;
    private static long lastPrediction;
    private static float bloom;
    private static long bloomAt;
    private static boolean reloading;
    private static long reloadEnd;
    private static long reloadStart;
    private static long drawEnd;
    private static boolean pendingShot;
    private static boolean pendingFromSprint;
    /** Selected fire mode per gun id (select-fire guns); the gun's default otherwise. */
    private static final Map<String, GunData.FireMode> fireModes = new HashMap<>();
    private static long pendingSince;

    // camera
    private static float recoilPitch, recoilYaw, shake;
    private static long lastFrame;

    // F3 numbers: CPU time of the viewmodel (pose + queue + submit), smoothed
    private static double renderMillis;

    // effects that need this frame's viewmodel matrices
    private static int pendingShells;
    private static final List<Vec3> pendingTracers = new ArrayList<>();
    private static final Map<Integer, GunParticles.Laser> lasers = new HashMap<>();

    private ClientGuns() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> {
            event.register(RELOAD);
            event.register(GUNSMITH);
            event.register(FIRE_MODE);
        });
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    FxMeshes.clear();
                    gunId = "";
                }));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> {
            ForgeLayeredDraw draw = event.getLayeredDraw();
            draw.addConditionTo(ForgeLayeredDraw.PRE_SLEEP_STACK, ForgeLayeredDraw.CROSSHAIR, () -> !holdingGun());
            draw.addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("gun_hud"), ForgeLayeredDraw.CROSSHAIR, GunHud::render);
        });
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> clientTick());
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> frame());
        RenderHandEvent.BUS.addListener(ClientGuns::renderHand);
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(ClientGuns::interaction);
        // the trigger is read from real press edges, so mining a block cannot turn a semi-automatic gun automatic
        WeaponInput.onAttackPressed(ClientGuns::triggerPressed);
        WeaponInput.onUsePressed(ClientGuns::aimPressed);
        // no sprinting while aiming, shooting or inspecting (like modern shooters): drop the sprint key from the movement input
        MovementInputUpdateEvent.BUS.addListener(event -> {
            if (!holdingGun()) return;
            Minecraft mc = Minecraft.getInstance();
            boolean busy = aim() > 0.05f || mc.options.keyAttack.isDown() && mc.screen == null
                    || System.nanoTime() - lastShot < 300_000_000L
                    || viewmodel.action() == Viewmodel.Action.INSPECT;
            if (!busy) return;
            var keys = event.getInput().keyPresses;
            if (keys.sprint()) {
                event.getInput().keyPresses = new net.minecraft.world.entity.player.Input(keys.forward(), keys.backward(), keys.left(),
                        keys.right(), keys.jump(), keys.shift(), false);
            }
            if (event.getEntity().isSprinting()) event.getEntity().setSprinting(false);
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            if (event.usedConfiguredFov() && stats != null && holdingGun()) {
                float a = viewmodel.ads.get();
                event.setFOV(event.getFOV() / (1 + (stats.sight().zoom - 1) * (float) Ease.SINE_IN_OUT.apply(Mth.clamp(a, 0, 1))));
            }
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!holdingGun() || !modelReady || Minecraft.getInstance().options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) return;
            // the weapon animation's camera track (reload dips, inspect roll) plus firing shake
            event.setPitch(event.getPitch() + viewmodel.cameraPitch());
            event.setYaw(event.getYaw() + viewmodel.cameraYaw());
            event.setRoll(event.getRoll() + viewmodel.cameraRoll());
            if (shake <= 0.001f) return;
            double t = Ticker.global().time();
            event.setRoll(event.getRoll() + (float) Math.sin(t * 61) * shake * 0.6f);
            event.setPitch(event.getPitch() + (float) Math.sin(t * 47 + 1.3) * shake * 0.25f);
        });
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && holdingGun() && modelReady) {
                event.getText().add(String.format("[BlockLand] viewmodel: %s, %.3f ms CPU", viewmodel.stats(), renderMillis));
            }
            return false;
        });
        BLNetwork.setGunClientListener(new GunPacketListeners.Client() {
            @Override
            public void onHit(GunPackets.S2CHit hit) {
                GunHud.hit(hit.kind());
                String sound = switch (hit.kind()) {
                    case BODY -> "gun.hitmarker";
                    case HEAD -> "gun.headshot";
                    case KILL, HEAD_KILL -> "gun.kill";
                };
                playUi(sound, 1.0f, 0.9f);
            }

            @Override
            public void onShot(GunPackets.S2CShot shot) {
                remoteShot(shot);
            }
        });
    }

    // ---- state queried by the HUD and the gunsmith ----------------------------------------------------

    public static boolean holdingGun() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof GunItem && !mc.player.isSpectator();
    }

    @Nullable
    public static GunStats stats() {
        return stats;
    }

    public static int ammo() {
        return ammo;
    }

    public static boolean isReloading() {
        return reloading;
    }

    /** 0..1 progress of the current reload. */
    public static float reloadProgress() {
        if (!reloading || reloadEnd <= reloadStart) return 0;
        return Mth.clamp((System.nanoTime() - reloadStart) / (float) (reloadEnd - reloadStart), 0, 1);
    }

    /** The fire mode currently selected for the held gun. */
    public static GunData.FireMode fireMode() {
        if (stats == null) return GunData.FireMode.SEMI;
        GunData.FireMode selected = fireModes.get(stats.gun().id);
        return selected != null && stats.gun().modes().contains(selected) ? selected : stats.gun().mode();
    }

    /** Select fire (B): cycles through the modes the held gun supports. */
    private static void cycleFireMode() {
        if (stats == null) return;
        var modes = stats.gun().modes();
        if (modes.size() < 2) {
            GunHud.flashHint(stats.gun().name + " 仅支持" + modes.get(0).label);
            return;
        }
        GunData.FireMode next = modes.get((modes.indexOf(fireMode()) + 1) % modes.size());
        fireModes.put(stats.gun().id, next);
        GunHud.flashHint("射击模式：" + next.label);
        playUi("gun.hammer", 1.25f, 0.6f);
    }

    public static float aim() {
        return Mth.clamp(viewmodel.ads.get(), 0, 1);
    }

    /** Current cone half-angle, for the crosshair. */
    public static float currentSpread() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (stats == null || player == null) return 0;
        return stats.spread(aim(), player.getDeltaMovement().horizontalDistance(), !player.onGround(), bloom(System.nanoTime()));
    }

    /** Rounds of this gun's ammo in the inventory. */
    public static int reserve() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || stats == null) return 0;
        Item item = GunContent.ammoItem(stats.ammo().id);
        if (item == null) return 0;
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) if (stack.is(item)) count += stack.getCount();
        return count;
    }

    // ---- ticking ------------------------------------------------------------------------------------

    private static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        syncEquipped(player);
        while (RELOAD.consumeClick()) if (stats != null && mc.screen == null) startReload(player);
        if (WeaponInput.inspectPressed() && stats != null && mc.screen == null) inspect();
        while (FIRE_MODE.consumeClick()) if (stats != null && mc.screen == null) cycleFireMode();
        while (GUNSMITH.consumeClick()) {
            if (stats != null && mc.screen == null) mc.setScreen(new GunsmithScreen(player.getInventory().getSelectedSlot()));
        }
        GunLoadouts.applySaved(player);
        updateLasers(mc);
    }

    /** Picks up gun switches and reconciles predicted ammo with the server's. */
    private static void syncEquipped(LocalPlayer player) {
        ItemStack stack = player.getMainHandItem();
        int selected = player.getInventory().getSelectedSlot();
        if (!(stack.getItem() instanceof GunItem item) || player.isSpectator()) {
            if (!gunId.isEmpty()) unequip();
            return;
        }
        GunState current = GunContent.stateOf(stack);
        GunStats resolved = current == null ? null : GunStats.of(item.gunId(), current).orElse(null);
        if (resolved == null) return;
        long now = System.nanoTime();
        if (selected != slot || !item.gunId().equals(gunId)) {
            slot = selected;
            gunId = item.gunId();
            stats = resolved;
            state = current;
            ammo = current.ammo();
            reloading = false;
            pendingShot = false;
            modelReady = viewmodel.equip(resolved.gun());
            viewmodel.setStats(resolved);
            viewmodel.setEventSink(name -> playUi("gun." + name, 1.0f, 0.55f));
            drawEnd = now + (long) (resolved.gun().drawTime * 1e9);
            playUi("gun.equip", 1.0f, 0.6f);
            return;
        }
        stats = resolved;
        state = current;
        viewmodel.setStats(resolved);
        if (!reloading && now - lastPrediction > RECONCILE_NANOS) ammo = current.ammo();
    }

    private static void unequip() {
        slot = -1;
        gunId = "";
        stats = null;
        state = null;
        reloading = false;
        modelReady = false;
        viewmodel.ads.snap(0);
    }

    /** Every frame: animation clock, automatic fire, aiming, reload completion, camera recoil. */
    private static void frame() {
        Ticker.global().tick();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (player == null || stats == null) {
            recoilPitch = recoilYaw = 0;
            return;
        }
        boolean free = mc.screen == null && mc.getOverlay() == null;
        if (modelReady && holdingGun()) ViewmodelRenderer.suppressViewBobbing(player);
        // aim
        boolean wantAds = free && mc.options.keyUse.isDown() && !reloading && now >= drawEnd && !player.isSprinting()
                && viewmodel.sprintAmount() < SPRINT_READY;
        viewmodel.ads.toIfChanged(wantAds ? 1 : 0, stats.adsTime(), Ease.SINE_IN_OUT);
        if (wantAds && viewmodel.action() == Viewmodel.Action.INSPECT) viewmodel.stopAction();
        // trigger: held for automatic weapons, a buffered click for semi-automatic ones
        if (free && fireMode() == GunData.FireMode.AUTO && mc.options.keyAttack.isDown()) tryFire(player, false);
        if (pendingShot) {
            long window = pendingFromSprint ? 600_000_000L : 180_000_000L;
            if (now - pendingSince > window) pendingShot = false;
            else if (now - lastShot >= stats.gun().fireInterval() * 1e9 && viewmodel.sprintAmount() < SPRINT_READY) {
                pendingShot = false;
                tryFire(player, false);
            }
        }
        if (reloading && now >= reloadEnd) finishReload(player);
        // camera recoil: most of the kick lands within ~60 ms
        float apply = 1 - (float) Math.exp(-dt * 30);
        float dp = recoilPitch * apply, dy = recoilYaw * apply;
        recoilPitch -= dp;
        recoilYaw -= dy;
        if (dp != 0 || dy != 0) {
            float pitch = Mth.clamp(player.getXRot() - dp, -90, 90);
            player.xRotO += pitch - player.getXRot();
            player.setXRot(pitch);
            player.setYRot(player.getYRot() + dy);
            player.yRotO += dy;
        }
        shake *= (float) Math.exp(-dt * 14);
        viewmodel.update(dt, player, player.isSprinting());
    }

    // ---- input ----------------------------------------------------------------------------------------

    /** Attack and use never reach vanilla while a gun is held (no mining, no hitting, no block interaction). */
    private static boolean interaction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !holdingGun() || event.isPickBlock()) return false;
        event.setSwingHand(false);
        return true;
    }

    /** The trigger was pressed (one press = one shot for semi-automatic fire). */
    private static void triggerPressed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || stats == null || !holdingGun()) return;
        tryFire(mc.player, true);
    }

    /** Aiming out of a sprint stops the sprint once (the movement input keeps it stopped while aiming). */
    private static void aimPressed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null && holdingGun() && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    private static void tryFire(LocalPlayer player, boolean click) {
        if (stats == null || slot < 0) return;
        long now = System.nanoTime();
        if (now < drawEnd || reloading) return;
        if (player.isSprinting()) player.setSprinting(false);
        if (viewmodel.action() == Viewmodel.Action.INSPECT) viewmodel.stopAction();
        boolean lowering = viewmodel.sprintAmount() >= SPRINT_READY;
        if (lowering || now - lastShot < stats.gun().fireInterval() * 1e9) {
            // still coming out of the sprint carry, or faster than the fire rate: buffer the press
            if (click) {
                pendingShot = true;
                pendingSince = now;
                pendingFromSprint = lowering;
            }
            return;
        }
        if (ammo <= 0) {
            playUi(stats.gun().sounds.dry, 1.0f, 0.8f);
            lastShot = now;
            if (reserve() > 0 || player.isCreative()) startReload(player);
            return;
        }
        fire(player, now);
    }

    private static void fire(LocalPlayer player, long now) {
        lastShot = now;
        lastPrediction = now;
        ammo--;
        float aim = aim();
        float spread = stats.spread(aim, player.getDeltaMovement().horizontalDistance(), !player.onGround(), bloom(now));
        addBloom(now);
        long seed = random.nextLong();
        float yaw = player.getYRot(), pitch = player.getXRot();
        BLNetwork.sendToServer(new GunPackets.C2SFire(slot, yaw, pitch, aim, seed));

        GunData.Gun gun = stats.gun();
        viewmodel.play(Viewmodel.Action.FIRE, ammo == 0 ? gun.anims.fireLast : gun.anims.fire, 0);
        viewmodel.fired(gun.recoil.kick, aim > 0.5f);
        float r = 1 + (gun.recoil.ads - 1) * aim;
        recoilPitch += gun.recoil.vertical * r * (0.85f + random.nextFloat() * 0.3f);
        recoilYaw += (random.nextFloat() - 0.5f) * 2 * gun.recoil.horizontal * r;
        shake = Math.min(1.5f, shake + 0.55f * r);
        playUi(gun.sounds.fire, 0.96f + random.nextFloat() * 0.08f, 1.0f);

        // predicted tracer and impact, from this client's own trace (the server re-traces with the same seed)
        Vec3 eye = player.getEyePosition();
        for (int pellet = 0; pellet < Math.max(1, stats.ammo().pellets); pellet++) {
            Vec3 dir = GunMath.spreadDirection(yaw, pitch, spread, seed, pellet);
            Vec3 end = localTrace(player, eye, dir, gun.range);
            pendingTracers.add(end);
        }
        pendingShells++;
    }

    /** Client-side trace for effects only; spawns impact particles. */
    private static Vec3 localTrace(LocalPlayer player, Vec3 from, Vec3 dir, double range) {
        ClientLevel level = (ClientLevel) player.level();
        Vec3 to = from.add(dir.scale(range));
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(level, player, from, end, new AABB(from, end).inflate(1),
                e -> e.isPickable() && !e.isSpectator() && !player.isAlliedTo(e), 0.05f);
        if (entity != null) {
            Vec3 point = entity.getEntity().getBoundingBox().inflate(0.05).clip(from, end).orElse(entity.getLocation());
            for (int i = 0; i < 4; i++) {
                level.addParticle(ParticleTypes.CRIT, point.x, point.y, point.z, (random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 0.3, (random.nextDouble() - 0.5) * 0.4);
            }
            return point;
        }
        if (block.getType() != HitResult.Type.MISS) {
            impact(level, block.getLocation(), block.getBlockPos(), dir);
            if (stats != null) Decals.onBlock(stats.ammo().decal, block.getLocation(), block.getDirection(), block.getBlockPos(), null);
        }
        return end;
    }

    private static void impact(ClientLevel level, Vec3 at, BlockPos pos, Vec3 dir) {
        BlockState blockState = level.getBlockState(pos);
        Vec3 back = dir.scale(-0.08);
        for (int i = 0; i < 6; i++) {
            if (!blockState.isAir()) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, blockState), at.x + back.x, at.y + back.y, at.z + back.z,
                        (random.nextDouble() - 0.5) * 0.3 - dir.x * 0.1, random.nextDouble() * 0.25, (random.nextDouble() - 0.5) * 0.3 - dir.z * 0.1);
            }
        }
        level.addParticle(ParticleTypes.SMOKE, at.x + back.x, at.y + back.y, at.z + back.z, 0, 0.02, 0);
    }

    private static void startReload(LocalPlayer player) {
        if (stats == null || reloading || slot < 0) return;
        long now = System.nanoTime();
        if (now < drawEnd || ammo >= stats.capacity()) return;
        if (reserve() <= 0 && !player.isCreative()) {
            GunHud.flashHint("没有" + stats.ammo().name);
            return;
        }
        boolean empty = ammo == 0;
        float seconds = stats.reloadTime(empty);
        reloading = true;
        reloadStart = now;
        reloadEnd = now + (long) (seconds * 1e9);
        pendingShot = false;
        viewmodel.play(Viewmodel.Action.RELOAD, empty ? stats.gun().anims.reloadEmpty : stats.gun().anims.reload, seconds);
        BLNetwork.sendToServer(new GunPackets.C2SReload(slot));
    }

    private static void finishReload(LocalPlayer player) {
        reloading = false;
        if (stats == null) return;
        int needed = stats.capacity() - ammo;
        ammo += player.isCreative() ? needed : Math.min(needed, reserve());
        lastPrediction = System.nanoTime();
    }

    /** Inspecting stops a sprint; the movement input keeps sprint off until the inspect ends. */
    private static void inspect() {
        if (reloading || stats == null || System.nanoTime() < drawEnd || aim() > 0.05f) return;
        if (viewmodel.action() == Viewmodel.Action.FIRE || viewmodel.action() == Viewmodel.Action.INSPECT) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.isSprinting()) player.setSprinting(false);
        viewmodel.play(Viewmodel.Action.INSPECT, stats.gun().anims.inspect, 0);
    }

    private static float bloom(long now) {
        if (stats == null) return 0;
        return Math.max(0, bloom - stats.gun().spread.recovery * (now - bloomAt) / 1e9f);
    }

    private static void addBloom(long now) {
        bloom = Math.min(stats.gun().spread.max, bloom(now) + stats.gun().spread.perShot);
        bloomAt = now;
    }

    // ---- rendering ------------------------------------------------------------------------------------

    /** Replaces the vanilla first-person hand with the viewmodel while a gun is held. */
    private static boolean renderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || stats == null || state == null || !modelReady || !holdingGun()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return true; // both hands are on the gun
        long start = System.nanoTime();
        // pure view space: the hand pose stack would add Minecraft's own bobbing and arm sway on top of ours
        Matrix4f base = new Matrix4f();
        int light = event.getPackedLight();
        viewmodel.draw(base, state, ammo, player, LightTexture.block(light), LightTexture.sky(light));
        viewmodel.submit();
        renderMillis = renderMillis * 0.95 + (System.nanoTime() - start) / 1e6 * 0.05;
        spawnPendingEffects(mc, player, base);
        return true;
    }

    /** Shells and tracers start at this frame's viewmodel ejection port / muzzle, converted to world space. */
    private static void spawnPendingEffects(Minecraft mc, LocalPlayer player, Matrix4f base) {
        if (pendingShells == 0 && pendingTracers.isEmpty()) return;
        ClientLevel level = mc.level;
        if (level == null) return;
        var camera = mc.gameRenderer.getMainCamera();
        Matrix4f tmp = new Matrix4f();
        Vec3 muzzle = ViewmodelRenderer.viewToWorld(camera, viewmodel.nodeMatrix("muzzle", base, tmp), player.getEyePosition());
        // (no muzzle smoke here: in first person the muzzle is ~0.3 blocks from the eye and smoke would fill the screen)
        for (Vec3 end : pendingTracers) mc.particleEngine.add(new GunParticles.Tracer(level, muzzle, end));
        pendingTracers.clear();
        if (pendingShells > 0) {
            Matrix4f eject = viewmodel.nodeMatrix("ejection", base, tmp);
            Vec3 at = ViewmodelRenderer.viewToWorld(camera, eject, muzzle);
            Vector3f out = eject == null ? new Vector3f(1, 0, 0) : eject.transformDirection(new Vector3f(1, 0.6f, 0.15f)).normalize();
            Vector3f world = camera.rotation().transform(out, new Vector3f());
            GltfModel.Primitive shell = shellMesh();
            for (int i = 0; i < pendingShells; i++) {
                Vec3 velocity = new Vec3(world.x, world.y, world.z).scale(0.14 + random.nextDouble() * 0.05)
                        .add(player.getDeltaMovement()).add(0, 0.06, 0);
                mc.particleEngine.add(new GunParticles.Shell(level, at, velocity, shell));
            }
            pendingShells = 0;
        }
    }

    @Nullable
    private static GltfModel.Primitive shellMesh() {
        if (stats == null) return null;
        GpuModel model = ModelCache.get(BlockLand.id(stats.gun().model));
        if (model == null) return null;
        int node = model.source.node("fx_shell");
        if (node < 0 || model.source.nodes.get(node).mesh < 0) return null;
        List<GltfModel.Primitive> primitives = model.source.meshes.get(model.source.nodes.get(node).mesh).primitives;
        return primitives.isEmpty() ? null : primitives.get(0);
    }

    // ---- other players --------------------------------------------------------------------------------

    private static void remoteShot(GunPackets.S2CShot shot) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Entity shooter = level.getEntity(shot.shooterId());
        Vec3 end = new Vec3(shot.endX(), shot.endY(), shot.endZ());
        if (shooter instanceof Player player) {
            Vec3 muzzle = GunParticles.thirdPersonMuzzle(player, 1);
            mc.particleEngine.add(new GunParticles.Flash(level, muzzle));
            mc.particleEngine.add(new GunParticles.Tracer(level, muzzle, end));
        }
        if (!shot.hitBlock()) return;
        Vec3 dir = shooter == null ? new Vec3(0, -1, 0) : end.subtract(shooter.getEyePosition()).normalize();
        if (shot.face() < 0 || shot.face() >= Direction.values().length) {
            impact(level, end, BlockPos.containing(end.add(dir.scale(0.01))), dir);
            return;
        }
        Direction face = Direction.values()[shot.face()];
        // the struck block is just behind the face
        BlockPos pos = BlockPos.containing(end.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01));
        impact(level, end, pos, dir);
        String decal = shooter instanceof Player player ? GunContent.statsOf(player.getMainHandItem()).map(s -> s.ammo().decal).orElse("bullet_hole") : "bullet_hole";
        Decals.onBlock(decal, end, face, pos, null);
    }

    /** Keeps one laser effect alive per player holding a gun with a laser. */
    private static void updateLasers(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) return;
        lasers.values().removeIf(p -> !p.isAlive());
        for (Player player : level.players()) {
            if (lasers.containsKey(player.getId()) || player.isSpectator()) continue;
            if (GunParticles.Laser.laserOf(player).isPresent()) {
                GunParticles.Laser laser = new GunParticles.Laser(level, player);
                lasers.put(player.getId(), laser);
                mc.particleEngine.add(laser);
            }
        }
    }

    static void playUi(String sound, float pitch, float volume) {
        SoundEvent event = GunContent.sound(sound);
        if (event != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }
}
