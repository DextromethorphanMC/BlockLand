package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.anim.Ease;
import com.blockland.anim.Ticker;
import com.blockland.client.craft.WeaponLook;
import com.blockland.client.decal.Decals;
import com.blockland.client.impact.Impacts;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.LightSources;
import com.blockland.client.render.ModelCache;
import com.blockland.client.scope.Optics;
import com.blockland.client.scope.ScopeOptics;
import com.blockland.client.scope.SightPose;
import com.blockland.client.viewmodel.Holster;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.craft.CraftData;
import com.blockland.combat.HitShapes;
import com.blockland.effect.ActionLocks;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunMath;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.blockland.movement.Slides;
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
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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

import static com.blockland.client.ui.Tr.tr;

/**
 * Client side of guns: input (fire / aim / reload / inspect / fire mode), client-side prediction (ammo, spread,
 * tracers, impacts) so shooting feels instant, camera recoil and ADS zoom, and the hooks that put the
 * {@link Viewmodel} on screen instead of the vanilla hand. Dual guns (asset spec §26) fire their pair alternately and
 * a volley with the use key; tube magazines (§25) reload a shell at a time and firing interrupts them. Switching away
 * hands the gun on screen to the {@link Holster} (it drops out of view while the next weapon is drawn), so two
 * viewmodels take turns. The server stays authoritative for hits and ammo. While the player spectates someone through
 * their eyes, {@link WatchedGun} shows that player's gun instead (the local path is idle: a spectator holds nothing).
 */
public final class ClientGuns {
    public static final KeyMapping RELOAD = new KeyMapping("key.blockland.reload", InputConstants.KEY_R, "key.categories.blockland");
    public static final KeyMapping FIRE_MODE = new KeyMapping("key.blockland.fire_mode", InputConstants.KEY_B, "key.categories.blockland");
    /** Firing or aiming out of a sprint waits until the gun has come down from the sprint carry. */
    private static final float SPRINT_READY = 0.35f;

    /** Server ammo wins again once we have not predicted anything for this long. */
    private static final long RECONCILE_NANOS = 450_000_000L;
    /** The gun on screen, and the other instance (the one dropping out of view after a switch, or idle). */
    private static Viewmodel viewmodel = new Viewmodel();
    private static Viewmodel spare = new Viewmodel();
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
    /** Earliest time the next shot may go: one fire interval after a shot, a dual gun's volley costs more. */
    private static long nextShotAt;
    /** Dual guns: the next single shot comes from the left gun. */
    private static boolean nextLeft;
    /** The last real shot of the held gun (not a dry click), for the bolt cycle. */
    private static long lastBoltShot;
    private static long lastPrediction;
    private static float bloom;
    private static long bloomAt;
    private static boolean reloading;
    private static long reloadEnd;
    private static long reloadStart;
    private static long drawEnd;
    private static boolean pendingShot;
    private static boolean pendingFromSprint;
    /** Rounds the buffered press fires (2 = a dual gun's volley). */
    private static int pendingShots = 1;
    /** Selected fire mode per gun id (select-fire guns); the gun's default otherwise. */
    private static final Map<String, GunData.FireMode> fireModes = new HashMap<>();
    private static long pendingSince;

    /** Shell-by-shell reloads (asset spec §25): where the sequence is, when that part ends, how it started. */
    private enum ShellPhase { NONE, START, SHELL, END }
    private static ShellPhase shellPhase = ShellPhase.NONE;
    private static long phaseEnd;
    private static boolean shellStartedEmpty;
    /** Rounds the reserve can still give this reload, and the rounds in the gun when it started (HUD progress). */
    private static int shellsLeft, shellStartAmmo, shellTarget;

    // camera
    private static float recoilPitch, recoilYaw;
    private static long lastFrame;
    /** What the server was last told about the sights (C2SAim): up or down, and for which slot. */
    private static boolean sentAiming;
    private static int sentAimSlot = -1;

    // F3 numbers: CPU time of the viewmodel (pose + queue + submit), smoothed
    private static double renderMillis;

    // effects that need this frame's viewmodel matrices (per gun: 0 = right / the only one, 1 = a dual gun's left)
    private static final int[] pendingShells = new int[2];
    private record PendingTracer(Vec3 end, int gun) {
    }
    private static final List<PendingTracer> pendingTracers = new ArrayList<>();
    private static final Map<Integer, GunParticles.Laser> lasers = new HashMap<>();

    private ClientGuns() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> {
            event.register(RELOAD);
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
        DualHold.init();
        WatchedGun.init();
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
                event.setFOV(Optics.fov(event.getFOV(), stats.sight(), (float) Ease.SINE_IN_OUT.apply(Mth.clamp(a, 0, 1)))
                        * viewmodel.cameraFov());
            }
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!holdingGun() || !modelReady || Minecraft.getInstance().options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) return;
            // the weapon animation's camera track (reload dips, inspect roll) plus firing shake
            event.setPitch(event.getPitch() + viewmodel.cameraPitch());
            event.setYaw(event.getYaw() + viewmodel.cameraYaw());
            event.setRoll(event.getRoll() + viewmodel.cameraRoll());
            viewmodel.moveCamera(event.getCamera());
            // a zoomed scope's breathing sway moves the view itself (the overlay is the scope)
            event.setPitch(event.getPitch() + Optics.cameraSwayPitch());
            event.setYaw(event.getYaw() + Optics.cameraSwayYaw());
        });
        // the watched gun's clip camera track (reload dips, the shot's kick) on a spectator's view through its eyes
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!WatchedGun.active()) return;
            event.setPitch(event.getPitch() + WatchedGun.cameraPitch());
            event.setYaw(event.getYaw() + WatchedGun.cameraYaw());
            event.setRoll(event.getRoll() + WatchedGun.cameraRoll());
            WatchedGun.moveCamera(event.getCamera());
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
                // a special skin may colour the kill marks and answer a kill on the weapon (asset spec §38)
                CraftData.SkinFx skinFx = stats == null || !holdingGun() ? null : stats.skinFx();
                boolean kill = hit.kind() == GunPackets.HitKind.KILL || hit.kind() == GunPackets.HitKind.HEAD_KILL;
                GunHud.hit(hit.kind(), skinFx != null && skinFx.hasHitColor() ? skinFx.hitRgb() : -1);
                if (kill && modelReady) viewmodel.killed();
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

            @Override
            public void onAim(GunPackets.S2CAim aim) {
                Optics.remoteAim(aim.playerId(), aim.aiming());
                WatchedGun.aim(aim.playerId(), aim.aiming());
            }
        });
        // the optics show other players' raised scopes (glints): which sight a player holds comes from their gun's build
        Optics.setSightOf(player -> GunContent.statsOf(player.getMainHandItem()).map(GunStats::sight).orElse(null));
    }

    // ---- state queried by the HUD, the craft screen and the settings ------------------------------------

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

    /** 0..1 progress of the current reload (a shell reload: the rounds it can load). */
    public static float reloadProgress() {
        if (!reloading) return 0;
        if (shellPhase != ShellPhase.NONE) {
            if (shellPhase == ShellPhase.END || shellTarget <= shellStartAmmo) return 1;
            return Mth.clamp((ammo - shellStartAmmo) / (float) (shellTarget - shellStartAmmo), 0, 1);
        }
        if (reloadEnd <= reloadStart) return 0;
        return Mth.clamp((System.nanoTime() - reloadStart) / (float) (reloadEnd - reloadStart), 0, 1);
    }

    /** A shell-by-shell reload is running (firing would interrupt it once a round is in). */
    public static boolean isShellReloading() {
        return reloading && shellPhase != ShellPhase.NONE;
    }

    /** The held gun is a dual pair (spec §26). */
    public static boolean isDual() {
        return stats != null && stats.gun().isDual();
    }

    /**
     * Rounds in each gun of a dual pair, {left, right}: the pair fires alternately from the right, so the right gun
     * holds the odd round.
     */
    public static int[] dualRounds() {
        int right = nextLeft ? ammo / 2 : (ammo + 1) / 2;
        return new int[]{ammo - right, right};
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
            GunHud.flashHint(tr("blockland.gun.hud.only_mode", stats.gun().displayName().getString(), modeName(modes.get(0))));
            return;
        }
        GunData.FireMode next = modes.get((modes.indexOf(fireMode()) + 1) % modes.size());
        fireModes.put(stats.gun().id, next);
        GunHud.flashHint(tr("blockland.gun.hud.fire_mode", modeName(next)));
        playUi("gun.hammer", 1.25f, 0.6f);
    }

    private static String modeName(GunData.FireMode mode) {
        return tr("blockland.gun.fire_mode." + mode.name().toLowerCase(java.util.Locale.ROOT));
    }

    public static float aim() {
        return Mth.clamp(viewmodel.ads.get(), 0, 1);
    }

    /** The local player is aiming down the sights of a held gun (more than halfway up). */
    public static boolean isAiming() {
        return stats != null && holdingGun() && aim() > 0.5f;
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
        WatchedGun.tick();
        syncEquipped(player);
        while (RELOAD.consumeClick()) if (stats != null && mc.screen == null) startReload(player);
        if (WeaponInput.inspectPressed() && stats != null && mc.screen == null) inspect();
        while (FIRE_MODE.consumeClick()) if (stats != null && mc.screen == null) cycleFireMode();
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
            holster(player);
            slot = selected;
            gunId = item.gunId();
            stats = resolved;
            state = current;
            ammo = current.ammo();
            reloading = false;
            shellPhase = ShellPhase.NONE;
            pendingShot = false;
            lastBoltShot = 0;
            nextLeft = false;
            nextShotAt = 0;
            modelReady = viewmodel.equip(resolved.gun(), WeaponLook.modelOf(resolved.gun(), resolved.build()));
            viewmodel.setStats(resolved);
            viewmodel.setNextLeft(false);
            viewmodel.setEventSink(ClientGuns::clipEvent);
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
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) holster(player);
        slot = -1;
        gunId = "";
        stats = null;
        state = null;
        reloading = false;
        shellPhase = ShellPhase.NONE;
        modelReady = false;
        lastBoltShot = 0;
        viewmodel.ads.snap(0);
    }

    /**
     * The gun on screen drops out of view (spec §31.4) while whatever comes next is drawn: it goes to the
     * {@link Holster} with its last ammo count, its sights coming down and its sounds off, and the other viewmodel
     * instance takes over.
     */
    private static void holster(LocalPlayer player) {
        if (!modelReady || !viewmodel.isReady() || stats == null) return;
        Viewmodel out = viewmodel;
        int rounds = ammo;
        out.setEventSink(null);
        out.hideOpticMaterials(java.util.Set.of());
        out.ads.toIfChanged(0, 0.12, Ease.SINE_IN_OUT);
        Holster.start(out, new Holster.Outgoing() {
            @Override
            public void update(float dt) {
                out.update(dt, player, false);
            }

            @Override
            public void draw(Matrix4f base, int blockLight, int skyLight) {
                out.draw(base, rounds, player, blockLight, skyLight);
                out.submit();
            }
        });
        viewmodel = spare;
        spare = out;
        Holster.release(viewmodel);
        modelReady = false;
    }

    /** Every frame: animation clock, automatic fire, aiming, reload completion, camera recoil. */
    private static void frame() {
        Ticker.global().tick();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        WatchedGun.frame(dt);
        if (player == null) {
            sentAiming = false;
            sentAimSlot = -1;
        }
        if (player == null || stats == null) {
            recoilPitch = recoilYaw = 0;
            Optics.update(null, 0, dt);
            if (player != null) syncAim(false);
            return;
        }
        boolean free = mc.screen == null && mc.getOverlay() == null;
        if (modelReady && holdingGun()) ViewmodelRenderer.suppressViewBobbing(player);
        // aim (a bolt-action gun takes the sight down while it works the bolt)
        boolean wantAds = free && stats.gun().canAim() && mc.options.keyUse.isDown() && !reloading && now >= drawEnd
                && !player.isSprinting()
                && !ActionLocks.isLocked(player)
                && viewmodel.sprintAmount() < SPRINT_READY
                && !cyclingBolt(now);
        viewmodel.ads.toIfChanged(wantAds ? 1 : 0, stats.adsTime(), Ease.SINE_IN_OUT);
        syncAim(wantAds && holdingGun());
        // dual guns have no sights: the use key fires a volley instead
        Optics.update(holdingGun() && stats.gun().canAim() ? stats.sight() : null, aim(), dt);
        if (wantAds && viewmodel.action() == Viewmodel.Action.INSPECT) viewmodel.stopAction();
        // trigger: held for automatic weapons, a buffered click for semi-automatic ones
        if (free && fireMode() == GunData.FireMode.AUTO && mc.options.keyAttack.isDown()) tryFire(player, false, 1);
        if (pendingShot) {
            long window = pendingFromSprint ? 600_000_000L : 180_000_000L;
            if (now - pendingSince > window) pendingShot = false;
            else if (now >= nextShotAt && viewmodel.sprintAmount() < SPRINT_READY) {
                pendingShot = false;
                tryFire(player, false, pendingShots);
            }
        }
        if (reloading && shellPhase != ShellPhase.NONE) advanceShells(player);
        else if (reloading && now >= reloadEnd) finishReload(player);
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
        tryFire(mc.player, true, 1);
    }

    /**
     * Aiming out of a sprint stops the sprint once (the movement input keeps it stopped while aiming); a dual gun fires
     * a volley instead (spec §26).
     */
    private static void aimPressed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !holdingGun()) return;
        if (stats != null && stats.gun().dual != null) {
            if (stats.gun().dual.volley) tryFire(mc.player, true, 2);
            return;
        }
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    /** @param shots 2 = a dual gun's volley (both guns at once), else 1 */
    private static void tryFire(LocalPlayer player, boolean click, int shots) {
        if (stats == null || slot < 0) return;
        if (ActionLocks.isLocked(player)) {
            pendingShot = false;
            if (click) GunHud.flashHint(tr("blockland.gun.hud.slimed"));
            return;
        }
        long now = System.nanoTime();
        if (now < drawEnd) return;
        if (reloading) {
            // a tube magazine's reload stops for a shot once a round is in (the server stops its own the same way)
            if (shellPhase == ShellPhase.NONE || ammo <= 0) return;
            reloading = false;
            shellPhase = ShellPhase.NONE;
            viewmodel.stopAction();
        }
        if (player.isSprinting()) player.setSprinting(false);
        if (viewmodel.action() == Viewmodel.Action.INSPECT) viewmodel.stopAction();
        boolean lowering = viewmodel.sprintAmount() >= SPRINT_READY;
        if (lowering || now < nextShotAt) {
            // still coming out of the sprint carry, or faster than the fire rate: buffer the press
            if (click) {
                pendingShot = true;
                pendingShots = shots;
                pendingSince = now;
                pendingFromSprint = lowering;
            }
            return;
        }
        if (ammo <= 0) {
            playUi(stats.gun().sounds.dry, 1.0f, 0.8f);
            viewmodel.dryFired();
            lastShot = now;
            nextShotAt = now + (long) (stats.gun().fireInterval() * 1e9);
            if (reserve() > 0 || player.isCreative()) startReload(player);
            return;
        }
        fire(player, now, shots);
    }

    /**
     * Tells the server when the sights go up or come down: it times aiming (a shot only gets the aimed spread once the
     * sights could be up) and shows the players around a raised scope's glint.
     */
    private static void syncAim(boolean aiming) {
        if (aiming == sentAiming && (!aiming || slot == sentAimSlot)) return;
        sentAiming = aiming;
        sentAimSlot = slot;
        BLNetwork.sendToServer(new GunPackets.C2SAim(slot, aiming));
    }

    /** A bolt-action gun is between shots with its sight down (asset spec §23). */
    private static boolean cyclingBolt(long now) {
        return stats != null && stats.gun().boltAction && lastBoltShot != 0
                && BoltCycle.sightDown((now - lastBoltShot) / 1e9f, stats.gun().fireInterval(), stats.adsTime());
    }

    /**
     * One press of the trigger: a single shot, or a dual gun's volley ({@code shots} 2: both guns, two rounds, wider).
     * The pair fires its single shots alternately, from the right first.
     */
    private static void fire(LocalPlayer player, long now, int shots) {
        GunData.Gun gun = stats.gun();
        GunData.Dual dual = gun.dual;
        shots = dual != null && dual.volley ? Math.max(1, Math.min(Math.min(2, shots), ammo)) : 1;
        boolean volley = shots == 2;
        int hand = 0;
        if (dual != null && !volley) {
            hand = nextLeft ? 1 : 0;
            nextLeft = !nextLeft;
        }
        lastShot = now;
        nextShotAt = now + (long) (gun.fireInterval() * (volley ? Math.max(1, dual.volleyCost) : 1) * 1e9);
        lastBoltShot = now;
        lastPrediction = now;
        ammo -= shots;
        viewmodel.setNextLeft(nextLeft);
        float aim = aim();
        float spread = stats.spread(aim, player.getDeltaMovement().horizontalDistance(), !player.onGround(), bloom(now))
                * (volley ? dual.volleySpread : 1);
        for (int i = 0; i < shots; i++) addBloom(now);
        long seed = random.nextLong();
        // aimed, the shot follows the sight line as the player saw it: the reticle is the point of impact, whatever the
        // sway, the kick or the camera's motion (the server accepts it within its usual tolerance of the look direction)
        Vector3f sightLine = Optics.shotDirection();
        float yaw = sightLine != null ? ScopeOptics.yaw(sightLine) : player.getYRot();
        float pitch = sightLine != null ? ScopeOptics.pitch(sightLine) : player.getXRot();
        BLNetwork.sendToServer(new GunPackets.C2SFire(slot, yaw, pitch, aim, seed, shots, hand));

        // a fire clip longer than the fire interval plays faster, so its slide / bolt cycle is home before the next shot
        String clip = fireClip(gun, volley, hand);
        float length = viewmodel.clipLength(clip), interval = gun.fireInterval();
        viewmodel.play(Viewmodel.Action.FIRE, clip, length > interval && interval > 0 ? interval * 0.98f : 0);
        viewmodel.fired(stats.kick() * (volley ? 1.35f : 1), aim, volley ? 3 : 1 << hand);
        float r = (1 + (gun.recoil.ads - 1) * aim) * stats.recoilScale() * (volley ? 1.5f : 1);
        recoilPitch += gun.recoil.vertical * r * (0.85f + random.nextFloat() * 0.3f);
        recoilYaw += (random.nextFloat() - 0.5f) * 2 * gun.recoil.horizontal * r;
        for (int i = 0; i < shots; i++) {
            playUi(stats.fireSound(), 0.96f + random.nextFloat() * 0.08f - i * 0.04f, stats.suppressed() ? 0.7f : 1.0f);
        }

        // predicted tracer and impact, from this client's own trace (the server re-traces with the same seed: shot k's
        // pellets are numbered after the earlier shots'); shots leave from the camera, which is low while sliding
        Vec3 eye = Slides.eyePosition(player);
        muzzleLight(eye.add(player.getViewVector(1).scale(0.8)).add(0, -0.15, 0), stats.skinFx(), stats.suppressed(), volley ? 1.4f : 1f);
        int pellets = Math.max(1, stats.ammo().pellets);
        for (int shot = 0; shot < shots; shot++) {
            int from = volley ? shot : hand;
            for (int pellet = 0; pellet < pellets; pellet++) {
                Vec3 dir = GunMath.spreadDirection(yaw, pitch, spread, seed, shot * pellets + pellet);
                Vec3 end = localTrace(player, eye, dir, stats.range());
                pendingTracers.add(new PendingTracer(end, from));
            }
            // a bolt or pump gun keeps the case until its bolt / pump comes back (clipEvent)
            if (gun.ejectEvent.isEmpty()) pendingShells[from]++;
        }
    }

    /**
     * The fire clip: a dual gun's volley, or the firing gun's shot — its last-round clip when that gun ran dry (with
     * one round left in the pair, the gun that just fired is the empty one).
     */
    private static String fireClip(GunData.Gun gun, boolean volley, int hand) {
        if (gun.dual != null) {
            boolean dry = ammo <= (volley ? 0 : 1);
            String clip = volley ? gun.dual.fireBoth
                    : hand == 1 ? (dry ? gun.dual.fireLastLeft : gun.dual.fireLeft)
                    : dry ? gun.anims.fireLast : gun.anims.fire;
            return viewmodel.hasClip(clip) ? clip : gun.anims.fire;
        }
        return ammo == 0 ? gun.anims.fireLast : gun.anims.fire;
    }

    /**
     * A viewmodel clip event: its sound, and the case of a gun that ejects on an event — only while firing or
     * reloading, the inspect's press-check opens the bolt without throwing anything out.
     */
    private static void clipEvent(String name) {
        playUi("gun." + name, 1.0f, 0.55f);
        Viewmodel.Action action = viewmodel.action();
        if (stats != null && name.equals(stats.gun().ejectEvent)
                && (action == Viewmodel.Action.FIRE || action == Viewmodel.Action.RELOAD)) {
            pendingShells[0]++;
        }
    }

    /** Client-side trace for effects only; spawns the impact ({@link Impacts}: debris, dust, sparks, blood, splashes). */
    private static Vec3 localTrace(LocalPlayer player, Vec3 from, Vec3 dir, double range) {
        ClientLevel level = (ClientLevel) player.level();
        Vec3 to = from.add(dir.scale(range));
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        // the same hit boxes as the server (a sliding player is low, with the head behind the hips)
        Vec3 point = null;
        Entity struck = null;
        double nearest = from.distanceToSqr(end);
        for (Entity candidate : level.getEntities(player, new AABB(from, end).inflate(1),
                e -> e.isPickable() && !e.isSpectator() && !player.isAlliedTo(e))) {
            HitShapes.Hit hit = HitShapes.clip(candidate, from, end, Math.max(0.05, candidate.getPickRadius()));
            if (hit != null && from.distanceToSqr(hit.point()) < nearest) {
                point = hit.point();
                struck = candidate;
                nearest = from.distanceToSqr(point);
            }
        }
        String ammo = stats == null ? null : stats.ammo().id;
        Vec3 stop = point != null ? point : end;
        Impacts.fluidOnPath(level, from, stop, player, ammo);
        if (point != null) {
            Impacts.entity(level, point, struck, dir, ammo);
            return point;
        }
        if (block.getType() != HitResult.Type.MISS) {
            Impacts.block(level, block.getLocation(), block.getDirection(), block.getBlockPos(), dir, ammo);
            if (stats != null) Decals.onBlock(stats.ammo().decal, block.getLocation(), block.getDirection(), block.getBlockPos(), null);
        }
        return end;
    }

    private static void startReload(LocalPlayer player) {
        if (stats == null || reloading || slot < 0) return;
        long now = System.nanoTime();
        if (now < drawEnd || ammo >= stats.capacity()) return;
        if (reserve() <= 0 && !player.isCreative()) {
            GunHud.flashHint(tr("blockland.gun.hud.no_ammo", tr("item.blockland." + stats.ammo().item)));
            return;
        }
        boolean empty = ammo == 0;
        reloading = true;
        reloadStart = now;
        pendingShot = false;
        GunData.Reload reload = stats.gun().reload;
        if (reload.shellMode()) {
            // spec §25: get ready, then a shell at a time (each counts when its clip ends), then finish
            shellPhase = ShellPhase.START;
            shellStartedEmpty = empty;
            shellsLeft = player.isCreative() ? Integer.MAX_VALUE : reserve();
            shellStartAmmo = ammo;
            shellTarget = (int) Math.min(stats.capacity(), (long) ammo + shellsLeft);
            phaseEnd = now + (long) (reload.start * 1e9);
            reloadEnd = phaseEnd;
            viewmodel.play(Viewmodel.Action.RELOAD, stats.gun().anims.reloadStart, reload.start);
        } else {
            float seconds = stats.reloadTime(empty);
            reloadEnd = now + (long) (seconds * 1e9);
            viewmodel.play(Viewmodel.Action.RELOAD, empty ? stats.gun().anims.reloadEmpty : stats.gun().anims.reload, seconds);
        }
        BLNetwork.sendToServer(new GunPackets.C2SReload(slot));
    }

    private static void finishReload(LocalPlayer player) {
        reloading = false;
        if (stats == null) return;
        int needed = stats.capacity() - ammo;
        ammo += player.isCreative() ? needed : Math.min(needed, reserve());
        lastPrediction = System.nanoTime();
        nextLeft = false;
        viewmodel.setNextLeft(false);
    }

    /** Steps a shell reload along its parts (several in one frame after a hitch, each from where the last ended). */
    private static void advanceShells(LocalPlayer player) {
        long now = System.nanoTime();
        while (reloading && stats != null && now >= phaseEnd) {
            switch (shellPhase) {
                case START -> nextShell();
                case SHELL -> {
                    ammo++;
                    shellsLeft--;
                    lastPrediction = now;
                    nextShell();
                }
                default -> {
                    reloading = false;
                    shellPhase = ShellPhase.NONE;
                }
            }
        }
    }

    /** After the start or a round: the next round while there is room and ammo, else the end clip. */
    private static void nextShell() {
        GunData.Reload reload = stats.gun().reload;
        GunData.Anims anims = stats.gun().anims;
        if (ammo < stats.capacity() && shellsLeft > 0) {
            shellPhase = ShellPhase.SHELL;
            phaseEnd += (long) (reload.shell * 1e9);
            viewmodel.play(Viewmodel.Action.RELOAD, anims.reloadShell, reload.shell);
        } else {
            shellPhase = ShellPhase.END;
            boolean chamber = shellStartedEmpty && viewmodel.hasClip(anims.reloadEndEmpty);
            float seconds = shellStartedEmpty ? reload.endEmpty : reload.end;
            phaseEnd += (long) (seconds * 1e9);
            viewmodel.play(Viewmodel.Action.RELOAD, chamber ? anims.reloadEndEmpty : anims.reloadEnd, seconds);
        }
        reloadEnd = phaseEnd;
    }

    /** Inspecting stops a sprint; the movement input keeps sprint off until the inspect ends. */
    private static void inspect() {
        if (reloading || stats == null || System.nanoTime() < drawEnd || aim() > 0.05f) return;
        if (viewmodel.action() == Viewmodel.Action.FIRE || viewmodel.action() == Viewmodel.Action.INSPECT) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.isSprinting()) player.setSprinting(false);
        viewmodel.inspect(stats.gun().anims.inspect);
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
        if (WatchedGun.renderHand(event)) return true;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || stats == null || state == null || !modelReady || !viewmodel.isReady() || !holdingGun()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return true; // both hands are on the gun
        long start = System.nanoTime();
        // pure view space: the hand pose stack would add Minecraft's own bobbing and arm sway on top of ours
        Matrix4f base = new Matrix4f();
        int light = event.getPackedLight();
        CraftData.Attachment sight = stats.sight();
        // the optics draw some of the optic's surfaces themselves, so the viewmodel leaves those out: a reflex sight's
        // glass and reticle (reticle at infinity), a magnified scope's ocular glass (the eyepiece disc); dual guns have none
        viewmodel.hideOpticMaterials(stats.gun().canAim() ? Optics.drawnMaterials(sight) : java.util.Set.of());
        viewmodel.draw(base, ammo, player, LightTexture.block(light), LightTexture.sky(light));
        SightPose pose = viewmodel.sightPose(base);
        if (Optics.hidesViewmodel()) {
            // classic scope zoom: the overlay is the scope
            viewmodel.discard();
        } else {
            // behind the weapon (the world around a raised scope, out of focus), the weapon, then the optics in its glass
            Optics.renderBehind(pose);
            viewmodel.submit();
        }
        renderMillis = renderMillis * 0.95 + (System.nanoTime() - start) / 1e6 * 0.05;
        // a magnified scope's own picture in its eyepiece, a reflex sight's reticle in its glass; and the sight line shots follow
        Optics.renderFront(pose, LightTexture.block(light), LightTexture.sky(light));
        spawnPendingEffects(mc, player, base);
        return true;
    }

    /**
     * Shells and tracers start at this frame's viewmodel ejection port / muzzle (of the gun that fired, for a dual pair),
     * converted to world space; a special skin colours the tracers (spec §30).
     */
    private static void spawnPendingEffects(Minecraft mc, LocalPlayer player, Matrix4f base) {
        if (pendingShells[0] == 0 && pendingShells[1] == 0 && pendingTracers.isEmpty()) return;
        ClientLevel level = mc.level;
        if (level == null) return;
        var camera = mc.gameRenderer.getMainCamera();
        Matrix4f tmp = new Matrix4f();
        Vec3[] muzzles = new Vec3[2];
        for (int gun = 0; gun < 2; gun++) {
            Matrix4f at = viewmodel.nodeMatrix("muzzle", gun, base, tmp);
            muzzles[gun] = gun == 1 && at == null ? muzzles[0] : ViewmodelRenderer.viewToWorld(camera, at, player.getEyePosition());
        }
        CraftData.SkinFx skinFx = stats == null ? null : stats.skinFx();
        // (no muzzle smoke here: in first person the muzzle is ~0.3 blocks from the eye and smoke would fill the screen)
        for (PendingTracer tracer : pendingTracers) mc.particleEngine.add(tracer(level, muzzles[tracer.gun], tracer.end, skinFx));
        pendingTracers.clear();
        GltfModel.Primitive shell = null;
        for (int gun = 0; gun < 2; gun++) {
            if (pendingShells[gun] == 0) continue;
            Matrix4f eject = viewmodel.nodeMatrix("ejection", gun, base, tmp);
            if (eject == null && gun == 1) eject = viewmodel.nodeMatrix("ejection", 0, base, tmp);
            Vec3 at = ViewmodelRenderer.viewToWorld(camera, eject, muzzles[gun]);
            Vector3f out = eject == null ? new Vector3f(1, 0, 0) : eject.transformDirection(new Vector3f(1, 0.6f, 0.15f)).normalize();
            Vector3f world = camera.rotation().transform(out, new Vector3f());
            if (shell == null) shell = shellMesh();
            for (int i = 0; i < pendingShells[gun]; i++) {
                Vec3 velocity = new Vec3(world.x, world.y, world.z).scale(0.14 + random.nextDouble() * 0.05)
                        .add(player.getDeltaMovement()).add(0, 0.06, 0);
                mc.particleEngine.add(new GunParticles.Shell(level, at, velocity, shell));
            }
            pendingShells[gun] = 0;
        }
    }

    /**
     * The light a shot throws around (rendering spec §5): warm and very short, in a special skin's flash colour, dim
     * through a suppressor. It lights the shooter's own gun too.
     */
    private static void muzzleLight(Vec3 at, @Nullable CraftData.SkinFx skinFx, boolean suppressed, float strength) {
        int rgb = skinFx != null && skinFx.hasFlashColor() ? skinFx.flashRgb() : GunParticles.DEFAULT_FLASH;
        LightSources.flash(at.x, at.y, at.z, rgb, (suppressed ? 0.35f : 2.2f) * strength, suppressed ? 3.5f : 7f, 0.07f);
    }

    /** A tracer, in a special skin's colour and width when the gun wears one. */
    private static GunParticles.Tracer tracer(ClientLevel level, Vec3 from, Vec3 to, @Nullable CraftData.SkinFx skinFx) {
        if (skinFx == null) return new GunParticles.Tracer(level, from, to);
        int rgb = skinFx.hasTracerColor() ? skinFx.tracerRgb() : GunParticles.DEFAULT_TRACER;
        return new GunParticles.Tracer(level, from, to, rgb, Math.max(0.2f, skinFx.tracerWidth));
    }

    /** The spent case: the {@code fx_shell} of the model on screen (a special skin's own model brings its own). */
    @Nullable
    private static GltfModel.Primitive shellMesh() {
        if (stats == null) return null;
        GpuModel model = viewmodel.model();
        if (model == null) model = ModelCache.get(BlockLand.id(stats.gun().model));
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
        // the player a spectator sees through: their gun on screen fires it (flash, case and tracer from its muzzle)
        boolean watched = WatchedGun.shot(shot, end);
        if (shooter instanceof Player player && !watched) {
            // the shot came from this gun of a dual pair; a special skin colours the flash and the tracer (spec §30)
            Vec3 muzzle = GunParticles.thirdPersonMuzzle(player, 1, shot.hand() == 1);
            CraftData.SkinFx skinFx = GunContent.statsOf(player.getMainHandItem()).map(GunStats::skinFx).orElse(null);
            muzzleLight(muzzle, skinFx, shot.suppressed(), 1f);
            if (!shot.suppressed()) {
                mc.particleEngine.add(skinFx == null ? new GunParticles.Flash(level, muzzle)
                        : new GunParticles.Flash(level, muzzle, skinFx.hasFlashColor() ? skinFx.flashRgb() : GunParticles.DEFAULT_FLASH,
                        Math.max(0.2f, skinFx.flashScale)));
            }
            mc.particleEngine.add(tracer(level, muzzle, end, skinFx));
        }
        Vec3 dir = shooter == null ? new Vec3(0, -1, 0) : end.subtract(shooter.getEyePosition()).normalize();
        String ammo = shooter instanceof Player player ? GunContent.statsOf(player.getMainHandItem()).map(s -> s.ammo().id).orElse(null) : null;
        if (shooter != null) Impacts.fluidOnPath(level, shooter.getEyePosition(), end, shooter, ammo);
        if (!shot.hitBlock()) {
            // a round that stopped short of any block struck someone: the shot does not say whom, the spot does
            Entity struck = Impacts.entityAt(level, end, shooter);
            if (struck != null) Impacts.entity(level, end, struck, dir, ammo);
            return;
        }
        if (shot.face() < 0 || shot.face() >= Direction.values().length) {
            BlockPos pos = BlockPos.containing(end.add(dir.scale(0.01)));
            Impacts.block(level, end, Direction.getApproximateNearest(-dir.x, -dir.y, -dir.z), pos, dir, ammo);
            return;
        }
        Direction face = Direction.values()[shot.face()];
        // the struck block is just behind the face
        BlockPos pos = BlockPos.containing(end.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01));
        Impacts.block(level, end, face, pos, dir, ammo);
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
