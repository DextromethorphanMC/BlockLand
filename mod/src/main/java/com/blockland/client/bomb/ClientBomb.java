package com.blockland.client.bomb;

import com.blockland.BlockLand;
import com.blockland.anim.Ticker;
import com.blockland.bomb.BombCatalog;
import com.blockland.bomb.BombContent;
import com.blockland.bomb.BombData;
import com.blockland.bomb.BombItem;
import com.blockland.bomb.PlantedBomb;
import com.blockland.client.bomb.fx.BombBlastFx;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.render.LightSources;
import com.blockland.client.viewmodel.Holster;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.network.BLNetwork;
import com.blockland.network.BombPacketListeners;
import com.blockland.network.packet.BombPackets;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Client side of 枪战爆破: reports the buttons (attack held with the bomb in hand = plant, the defuse key = defuse), shows
 * the bomb in first person, remembers what the server says about the match for the HUD, makes the planted bomb blink
 * and beep from its synced fuse, and plays the explosion. The server decides everything; nothing here is trusted.
 */
public final class ClientBomb {
    public static final KeyMapping DEFUSE = new KeyMapping("key.blockland.defuse", InputConstants.KEY_X, "key.categories.blockland");

    private static BombPackets.S2CState state = BombPackets.S2CState.NONE;
    /** Client tick count when {@link #state} arrived (the HUD counts on from it). */
    private static int stateTick;
    private static int ticks;
    private static boolean plantHeld;
    private static boolean defuseHeld;

    private static BombViewmodel viewmodel = new BombViewmodel();
    private static BombViewmodel spare = new BombViewmodel();
    private static int slot = -1;
    private static boolean equipped;
    private static boolean modelReady;
    private static long lastFrame;

    /** Per planted bomb: the seconds-left mark of its next beep, and when it last beeped (nanoTime). */
    private static final Map<Integer, float[]> beeps = new HashMap<>();
    private static final Map<Integer, Long> lastBeep = new HashMap<>();
    private static final RandomSource random = RandomSource.create();

    private ClientBomb() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(DEFUSE));
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event ->
                event.registerEntityRenderer(BombContent.PLANTED_BOMB.get(), PlantedBombRenderer::new));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("bomb_hud"), ForgeLayeredDraw.CROSSHAIR, BombHud::render));
        BombBlastFx.init(modBus);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> clientTick());
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> frame());
        RenderHandEvent.BUS.addListener(ClientBomb::renderHand);
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(ClientBomb::interaction);
        WeaponInput.onAttackPressed(ClientBomb::attackPressed);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> reset());
        // no sprinting while kneeling at the bomb
        MovementInputUpdateEvent.BUS.addListener(event -> {
            if (state.action() == BombPackets.Action.NONE) return;
            Input keys = event.getInput().keyPresses;
            if (keys.sprint()) {
                event.getInput().keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), false);
            }
            if (event.getEntity().isSprinting()) event.getEntity().setSprinting(false);
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!holdingBomb() || !modelReady || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON) return;
            event.setPitch(event.getPitch() + viewmodel.cameraPitch());
            event.setYaw(event.getYaw() + viewmodel.cameraYaw());
            event.setRoll(event.getRoll() + viewmodel.cameraRoll());
            viewmodel.camera().moveCamera(event.getCamera());
        });
        // a spectator seeing through the eyes of a player who holds the bomb
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!WatchedBomb.active()) return;
            event.setPitch(event.getPitch() + WatchedBomb.cameraPitch());
            event.setYaw(event.getYaw() + WatchedBomb.cameraYaw());
            event.setRoll(event.getRoll() + WatchedBomb.cameraRoll());
            WatchedBomb.moveCamera(event.getCamera());
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            if (event.usedConfiguredFov() && holdingBomb() && modelReady) event.setFOV(event.getFOV() * viewmodel.camera().fovScale());
        });
        PlantedBomb.setClientHooks(ClientBomb::tickPlanted);
        BLNetwork.setBombClientListener(new BombPacketListeners.Client() {
            @Override
            public void onState(BombPackets.S2CState received) {
                BombPackets.Action before = state.action();
                state = received;
                stateTick = ticks;
                if (received.action() == BombPackets.Action.PLANTING && before != BombPackets.Action.PLANTING && modelReady) {
                    BombData data = BombCatalog.get();
                    viewmodel.play(BombViewmodel.Action.PLANT, data.anims.plant, received.actionTicks() / 20f);
                } else if (received.action() != BombPackets.Action.PLANTING && viewmodel.action() == BombViewmodel.Action.PLANT) {
                    viewmodel.stopAction();
                }
            }

            @Override
            public void onDetonate(BombPackets.S2CDetonate detonate) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null) return;
                Vec3 at = new Vec3(detonate.x(), detonate.y(), detonate.z());
                BombBlastFx.play(mc.level, at, detonate.radius(), detonate.shake());
                // far away the close blast is out of earshot: a distant boom instead
                if (mc.player != null && mc.player.position().distanceTo(at) > 40) {
                    mc.getSoundManager().play(new SimpleSoundInstance(BombContent.sound("bomb.explode_far"), SoundSource.PLAYERS, 6f, 1f,
                            random, at.x, at.y, at.z));
                }
            }
        });
    }

    private static void reset() {
        state = BombPackets.S2CState.NONE;
        plantHeld = defuseHeld = false;
        beeps.clear();
        lastBeep.clear();
    }

    // ---- read by the HUD ------------------------------------------------------------------------------

    static BombPackets.S2CState state() {
        return state;
    }

    /** Ticks since the state arrived, with the frame's partial tick (the HUD counts down from the packet). */
    static float ticksSinceState(float partialTick) {
        return ticks - stateTick + partialTick;
    }

    static boolean holdingBomb() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof BombItem && !mc.player.isSpectator();
    }

    /** The planted bomb in the client world, if it is in range. */
    @Nullable
    static PlantedBomb plantedBomb() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        PlantedBomb nearest = null;
        double best = Double.MAX_VALUE;
        for (PlantedBomb bomb : mc.level.getEntitiesOfClass(PlantedBomb.class, mc.player.getBoundingBox().inflate(160))) {
            double d = bomb.distanceToSqr(mc.player);
            if (bomb.state() == PlantedBomb.State.TICKING && d < best) {
                best = d;
                nearest = bomb;
            }
        }
        return nearest;
    }

    /** Whether the local player stands at the bomb close enough and looks at it (a hint only; the server decides). */
    static boolean atBomb(PlantedBomb bomb) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        BombData data = BombCatalog.get();
        Vec3 to = bomb.position().add(0, 0.1, 0).subtract(player.getEyePosition());
        double distance = to.length();
        if (distance > data.defuseRange) return false;
        return distance < 0.6 || player.getLookAngle().dot(to.scale(1 / distance)) >= Math.cos(Math.toRadians(data.defuseAngle));
    }

    /** The site the local player stands in, or null. */
    @Nullable
    static BombPackets.Site siteHere() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        for (BombPackets.Site site : state.sites()) {
            if (site.contains(player.getX(), player.getY() + 0.1, player.getZ())) return site;
        }
        return null;
    }

    /** 0..1 brightness of the planted bomb's light (on just after a beep). */
    static float led(PlantedBomb bomb) {
        if (bomb.state() != PlantedBomb.State.TICKING) return 0;
        if (bomb.secondsLeft(0) <= BombCatalog.get().beep.finalSeconds) return 1;
        Long at = lastBeep.get(bomb.getId());
        if (at == null) return 0;
        float since = (System.nanoTime() - at) / 1e9f;
        return Math.max(0, 1 - since / 0.14f);
    }

    // ---- input ----------------------------------------------------------------------------------------

    private static void attackPressed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !holdingBomb() || plantHeld) return;
        plantHeld = true;
        BLNetwork.sendToServer(new BombPackets.C2SPlant(true));
    }

    /** Attack and use never reach vanilla while the bomb is in hand (no mining, no swinging). */
    private static boolean interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (Minecraft.getInstance().player == null || !holdingBomb() || event.isPickBlock()) return false;
        event.setSwingHand(false);
        return true;
    }

    private static void clientTick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        WatchedBomb.tick();
        syncEquipped(player);
        if (plantHeld && (!mc.options.keyAttack.isDown() || !holdingBomb() || mc.screen != null)) {
            plantHeld = false;
            BLNetwork.sendToServer(new BombPackets.C2SPlant(false));
        }
        boolean defuse = DEFUSE.isDown() && mc.screen == null && !player.isSpectator()
                && state.side() == BombPackets.Side.DEFENDER && state.phase() == BombPackets.Phase.PLANTED;
        while (DEFUSE.consumeClick()) {
            // presses are read through isDown(); the queue only has to stay empty
        }
        if (defuse != defuseHeld) {
            defuseHeld = defuse;
            BLNetwork.sendToServer(new BombPackets.C2SDefuse(defuse));
        }
        if (WeaponInput.inspectPressed() && equipped && modelReady && mc.screen == null && viewmodel.action() == null) {
            viewmodel.play(BombViewmodel.Action.INSPECT, BombCatalog.get().anims.inspect, 0);
        }
    }

    // ---- the bomb in the hand -------------------------------------------------------------------------

    private static void syncEquipped(LocalPlayer player) {
        boolean holding = holdingBomb();
        int selected = player.getInventory().getSelectedSlot();
        if (!holding) {
            if (equipped) unequip(player);
            return;
        }
        if (equipped && selected == slot) return;
        if (equipped) holster(player);
        equipped = true;
        slot = selected;
        modelReady = viewmodel.equip(BombCatalog.get());
        viewmodel.setEventSink(ClientBomb::clipEvent);
        playUi("bomb.draw", 1, 0.6f);
    }

    private static void unequip(LocalPlayer player) {
        holster(player);
        equipped = false;
        slot = -1;
        modelReady = false;
    }

    /** Hands the bomb on screen to the holster so it is lowered out of view while the next item comes up. */
    private static void holster(LocalPlayer player) {
        if (!modelReady || !viewmodel.isReady()) return;
        BombViewmodel out = viewmodel;
        out.setEventSink(name -> {
        });
        Holster.start(out, new Holster.Outgoing() {
            @Override
            public void update(float dt) {
                out.update(dt, player, false);
            }

            @Override
            public void draw(Matrix4f base, int blockLight, int skyLight) {
                out.draw(base, player, blockLight, skyLight);
                out.submit();
            }
        });
        viewmodel = spare;
        spare = out;
        Holster.release(viewmodel);
        modelReady = false;
    }

    private static void frame() {
        Ticker.global().tick();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        WatchedBomb.frame(dt);
        if (player == null || !equipped || !holdingBomb()) return;
        if (modelReady) ViewmodelRenderer.suppressViewBobbing(player);
        viewmodel.update(dt, player, player.isSprinting());
    }

    /** Replaces the vanilla first-person hands with the bomb viewmodel. */
    private static boolean renderHand(RenderHandEvent event) {
        if (WatchedBomb.renderHand(event)) return true;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !equipped || !modelReady || !holdingBomb()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return true;
        int light = event.getPackedLight();
        viewmodel.draw(new Matrix4f(), player, LightTexture.block(light), LightTexture.sky(light));
        viewmodel.submit();
        return true;
    }

    /** A clip event of the bomb in hand: its sound for the holder (others hear the server's). */
    private static void clipEvent(String name) {
        switch (name) {
            case "key" -> playUi("bomb.key", 0.95f + random.nextFloat() * 0.1f, 0.5f);
            case "draw" -> playUi("bomb.draw", 1, 0.5f);
            default -> {
            }
        }
    }

    // ---- the planted bomb -----------------------------------------------------------------------------

    /** Every client tick for each planted bomb in range: the beep gets faster as the fuse runs down, then a solid tone. */
    private static void tickPlanted(PlantedBomb bomb) {
        if (!(bomb.level() instanceof ClientLevel)) return;
        int id = bomb.getId();
        if (bomb.state() != PlantedBomb.State.TICKING) {
            beeps.remove(id);
            lastBeep.remove(id);
            return;
        }
        BombData.Beep beep = BombCatalog.get().beep;
        float left = bomb.secondsLeft(0), fuse = bomb.fuseTicks() / 20f;
        float[] next = beeps.computeIfAbsent(id, k -> new float[]{left, 0});
        if (left <= beep.finalSeconds) {
            if (next[1] == 0) {
                next[1] = 1;
                playAt(bomb, "bomb.beep_final", 1.6f, 1);
            }
            return;
        }
        if (left > next[0]) return;
        // the interval shrinks with the square of the time left: slow for most of the fuse, frantic at the end
        float k = Math.max(0, Math.min(1, left / Math.max(1, fuse)));
        float interval = beep.fastInterval + (beep.slowInterval - beep.fastInterval) * k * k;
        next[0] = left - interval;
        lastBeep.put(id, System.nanoTime());
        playAt(bomb, "bomb.beep", 1.4f, 1 + (1 - k) * 0.08f);
        Vec3 p = bomb.position();
        LightSources.flash(p.x, p.y + 0.2, p.z, 0xFF2A1A, 1.2f, 3.5f, 0.14f);
    }

    private static void playAt(PlantedBomb bomb, String sound, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(BombContent.sound(sound), SoundSource.PLAYERS, volume, pitch,
                random, bomb.getX(), bomb.getY(), bomb.getZ()));
    }

    private static void playUi(String sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(BombContent.sound(sound), pitch, volume));
    }
}
