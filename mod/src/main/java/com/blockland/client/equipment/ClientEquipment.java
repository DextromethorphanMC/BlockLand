package com.blockland.client.equipment;

import com.blockland.BlockLand;
import com.blockland.anim.Ticker;
import com.blockland.client.equipment.effect.ClientEquipmentEffects;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.viewmodel.Holster;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.effect.ActionLocks;
import com.blockland.entity.BLEntities;
import com.blockland.equipment.EquipmentContent;
import com.blockland.equipment.EquipmentData;
import com.blockland.equipment.EquipmentItem;
import com.blockland.equipment.ThrownEquipment;
import com.blockland.network.BLNetwork;
import com.blockland.network.EquipmentPacketListeners;
import com.blockland.network.packet.EquipmentPackets;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Client side of equipment: input (grenades — hold the left button to pull the pin and cook, let go to throw; the right
 * button the same with an underhand lob; throwing knives — left click; injectors — either button), the hand's timing
 * mirrored from equipment.json (pull, hold, the throw clip's release point, recovery), the {@link EquipmentViewmodel}
 * on screen with a {@link TrajectoryArc} while a grenade is held ready, the thrower's hit marker and the renderer and
 * client hooks of the thrown entity. Switching away hands the item on screen to the {@link Holster}. The server stays
 * authoritative for what is thrown, when, and what it hits.
 */
public final class ClientEquipment {
    enum State { IDLE, PULLING, HOLDING, THROWING, USING }

    private static EquipmentViewmodel viewmodel = new EquipmentViewmodel();
    private static EquipmentViewmodel spare = new EquipmentViewmodel();
    private static final Random random = new Random();

    // equipped item
    private static int slot = -1;
    private static String itemId = "";
    @Nullable
    private static EquipmentData.Equipment item;
    private static boolean modelReady;

    // the hand
    private static State state = State.IDLE;
    private static boolean lob;
    private static long drawEnd;
    private static long readyAt;
    private static long stateStart;
    /** Grenades: the pin is out from here. */
    private static long pinAt;
    /** The item leaves the hand (or the injection takes hold) at this time; 0 = not yet scheduled. */
    private static long releaseAt;
    private static boolean releaseQueued;
    private static boolean releasedFx;
    @Nullable
    private static TrajectoryArc arc;

    private static long lastFrame;
    private static double renderMillis;

    private ClientEquipment() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> itemId = ""));
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event ->
                event.registerEntityRenderer(BLEntities.THROWN_EQUIPMENT.get(), ThrownEquipmentRenderer::new));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("equipment_hud"), ForgeLayeredDraw.CROSSHAIR, EquipmentHud::render));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> clientTick());
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> frame());
        RenderHandEvent.BUS.addListener(ClientEquipment::renderHand);
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(ClientEquipment::interaction);
        WeaponInput.onAttackPressed(() -> pressed(false));
        WeaponInput.onUsePressed(() -> pressed(true));
        // no sprinting with a grenade in the throwing hand
        MovementInputUpdateEvent.BUS.addListener(event -> {
            if (!holdingEquipment() || state == State.IDLE) return;
            Input keys = event.getInput().keyPresses;
            if (keys.sprint()) {
                event.getInput().keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), false);
            }
            if (event.getEntity().isSprinting()) event.getEntity().setSprinting(false);
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!holdingEquipment() || !modelReady || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON) return;
            event.setPitch(event.getPitch() + viewmodel.cameraPitch());
            event.setYaw(event.getYaw() + viewmodel.cameraYaw());
            event.setRoll(event.getRoll() + viewmodel.cameraRoll());
            viewmodel.camera().moveCamera(event.getCamera());
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            if (event.usedConfiguredFov() && holdingEquipment() && modelReady) event.setFOV(event.getFOV() * viewmodel.camera().fovScale());
        });
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && holdingEquipment() && modelReady) {
                event.getText().add(String.format("[BlockLand] equipment viewmodel: %s, %.3f ms CPU", viewmodel.stats(), renderMillis));
            }
            return false;
        });
        BLNetwork.setEquipmentClientListener(new EquipmentPacketListeners.Client() {
            @Override
            public void onHit(EquipmentPackets.S2CHit hit) {
                HitMarker.show(hit.head(), hit.kill());
                playUi(hit.kill() ? "blockland:gun.kill" : hit.head() ? "blockland:gun.headshot" : "blockland:gun.hitmarker", 1, 0.9f);
            }
        });
        ThrownEquipment.setClientHooks(new ThrownEquipment.ClientHooks() {
            @Override
            public void detonated(ThrownEquipment thrown, EquipmentData.Equipment equipment) {
                ClientEquipmentEffects.detonated(thrown, equipment.kind, equipment.effect);
            }

            @Override
            public void tick(ThrownEquipment thrown, EquipmentData.Equipment equipment, int activeTicks) {
                if (activeTicks >= 0) ClientEquipmentEffects.tickActive(thrown, equipment.kind, equipment.effect, activeTicks);
                else inFlight(thrown, equipment);
            }
        });
    }

    // ---- state queried by the HUD ---------------------------------------------------------------------

    public static boolean holdingEquipment() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof EquipmentItem && !mc.player.isSpectator();
    }

    @Nullable
    static EquipmentData.Equipment item() {
        return item;
    }

    static State state() {
        return state;
    }

    /** Seconds left on a cooking fuse (frag, flash held with the pin out), or -1 when nothing is cooking. */
    static float cookLeft() {
        if (item == null || !item.cookable || item.fuse <= 0) return -1;
        if (state != State.HOLDING && state != State.PULLING) return -1;
        long now = System.nanoTime();
        if (now < pinAt) return item.fuse;
        return Math.max(0, item.fuse - (now - pinAt) / 1e9f);
    }

    /** 0..1 how far the hand is through its recovery or draw (1 = ready). */
    static float readiness() {
        long end = Math.max(readyAt, drawEnd), now = System.nanoTime();
        if (item == null || now >= end) return 1;
        long from = Math.max(stateStart, end - (long) 2e9);
        return Math.max(0, (now - from) / (float) Math.max(1, end - from));
    }

    /** How many of the held item the player carries (all stacks). */
    static int carried() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || item == null) return 0;
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.getItem() instanceof EquipmentItem e && e.equipmentId().equals(itemId)) count += stack.getCount();
        }
        return count;
    }

    // ---- ticking ------------------------------------------------------------------------------------

    private static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        syncEquipped(player);
        if (item != null) viewmodel.setBuild(EquipmentContent.buildOf(player.getMainHandItem()));
        if (WeaponInput.inspectPressed() && item != null && mc.screen == null) inspect(player);
    }

    private static void syncEquipped(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        EquipmentData.Equipment held = player.isSpectator() ? null : EquipmentContent.equipmentOf(player.getMainHandItem()).orElse(null);
        if (held == null) {
            if (!itemId.isEmpty()) unequip();
            return;
        }
        if (selected == slot && held.id.equals(itemId)) return;
        if (!itemId.isEmpty()) putAway();
        holster(player);
        slot = selected;
        itemId = held.id;
        item = held;
        state = State.IDLE;
        long now = System.nanoTime();
        modelReady = viewmodel.equip(held);
        viewmodel.setEventSink(ClientEquipment::clipEvent);
        drawEnd = now + (long) (held.drawTime * 1e9);
        stateStart = now;
        playUi("blockland:equipment.equip", 1.0f, 0.6f);
    }

    private static void unequip() {
        putAway();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) holster(player);
        slot = -1;
        itemId = "";
        item = null;
        modelReady = false;
        state = State.IDLE;
    }

    /** Leaving the item mid-way: the server drops a live grenade at the feet, anything else is just put away. */
    private static void putAway() {
        boolean thrownAlready = state == State.THROWING && releasedFx;
        if (state != State.IDLE && !thrownAlready && slot >= 0) {
            BLNetwork.sendToServer(new EquipmentPackets.C2SAction(slot, EquipmentPackets.Action.CANCEL, lob));
        }
        state = State.IDLE;
    }

    private static void holster(LocalPlayer player) {
        if (!modelReady || !viewmodel.isReady()) return;
        EquipmentViewmodel out = viewmodel;
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

    /** Every frame: the hand's timing, the button coming up, the animation clock. */
    private static void frame() {
        Ticker.global().tick();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (player == null || item == null || !holdingEquipment()) return;
        if (modelReady) ViewmodelRenderer.suppressViewBobbing(player);
        EquipmentData.Equipment it = item;
        switch (state) {
            case PULLING -> {
                if (!button().isDown()) releaseQueued = true;
                if (now >= pinAt) {
                    state = State.HOLDING;
                    if (!modelReady) cue(EquipmentData.MOLOTOV.equals(it.kind) ? "light" : "pin");
                    viewmodel.play(EquipmentViewmodel.Action.HOLD, it.anims.hold, 0);
                }
            }
            case HOLDING -> {
                // the hold clip loops while the button stays down
                if (viewmodel.action() == null) viewmodel.play(EquipmentViewmodel.Action.HOLD, it.anims.hold, 0);
                if (it.cookable && it.fuse > 0 && now >= pinAt + (long) (it.fuse * 1e9)) {
                    // held too long: it went off in the hand (the server's doing); the hand is empty again
                    state = State.IDLE;
                    viewmodel.stopAction();
                    readyAt = now + (long) (it.cooldown * 1e9);
                    stateStart = now;
                } else if (releaseQueued || !button().isDown() || mc.screen != null) {
                    release(now);
                }
            }
            case THROWING -> {
                if (!releasedFx && now >= releaseAt) {
                    releasedFx = true;
                    if (!modelReady) cue("release");
                    viewmodel.released(lob, it.handling.weight);
                    player.swing(InteractionHand.MAIN_HAND);
                }
                if (now >= releaseAt + (long) (it.throwing.recover * 1e9)) state = State.IDLE;
            }
            case USING -> {
                if (!releasedFx && now >= releaseAt) {
                    releasedFx = true;
                    if (!modelReady) cue("inject");
                    viewmodel.injected();
                }
                if (now >= stateStart + (long) (it.useTime * 1e9)) state = State.IDLE;
            }
            case IDLE -> { }
        }
        viewmodel.update(dt, player, player.isSprinting());
    }

    /** The button the readied item listens to: the right one for a lob. */
    private static KeyMapping button() {
        Minecraft mc = Minecraft.getInstance();
        return lob ? mc.options.keyUse : mc.options.keyAttack;
    }

    // ---- input ----------------------------------------------------------------------------------------

    /** Attack and use never reach vanilla while equipment is held. */
    private static boolean interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (Minecraft.getInstance().player == null || !holdingEquipment() || event.isPickBlock()) return false;
        event.setSwingHand(false);
        return true;
    }

    private static void pressed(boolean right) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || item == null || !holdingEquipment() || slot < 0) return;
        long now = System.nanoTime();
        if (state != State.IDLE || now < drawEnd || now < readyAt) return;
        if (ActionLocks.isLocked(player)) {
            EquipmentHud.flash(net.minecraft.network.chat.Component.translatable("blockland.effect.slimed").getString());
            return;
        }
        EquipmentData.Equipment it = item;
        if (player.isSprinting()) player.setSprinting(false);
        lob = right && it.held() && it.throwing.lob != null;
        stateStart = now;
        releaseQueued = false;
        releasedFx = false;
        if (it.held()) {
            // a grenade: the pin comes out (the rag is lit), then it is held until the button comes up
            state = State.PULLING;
            pinAt = now + (long) (it.pullTime * 1e9);
            viewmodel.play(EquipmentViewmodel.Action.PULL, it.anims.pull, it.pullTime);
            showArc(player, it);
        } else if (it.thrown()) {
            state = State.THROWING;
            releaseAt = now + (long) (it.throwing.releaseAt * 1e9);
            readyAt = releaseAt + (long) ((it.throwing.recover + it.cooldown) * 1e9);
            viewmodel.play(EquipmentViewmodel.Action.THROW, it.anims.throwClip, 0);
        } else {
            state = State.USING;
            releaseAt = now + (long) (it.injectAt * 1e9);
            readyAt = now + (long) ((it.useTime + it.cooldown) * 1e9);
            viewmodel.play(EquipmentViewmodel.Action.USE, it.anims.use, it.useTime);
            if (!modelReady) cue("cap");
        }
        BLNetwork.sendToServer(new EquipmentPackets.C2SAction(slot, EquipmentPackets.Action.PRESS, lob));
    }

    /** The button came up: the throw clip plays and the item leaves the hand at its release point. */
    private static void release(long now) {
        if (item == null) return;
        EquipmentData.Equipment it = item;
        state = State.THROWING;
        releasedFx = false;
        releaseAt = Math.max(now, pinAt) + (long) (it.throwing.releaseAt * 1e9);
        readyAt = releaseAt + (long) ((it.throwing.recover + it.cooldown) * 1e9);
        stateStart = now;
        String clip = lob && viewmodel.hasClip(it.anims.lob) ? it.anims.lob : it.anims.throwClip;
        viewmodel.play(EquipmentViewmodel.Action.THROW, clip, 0);
        BLNetwork.sendToServer(new EquipmentPackets.C2SAction(slot, EquipmentPackets.Action.RELEASE, lob));
    }

    private static void inspect(LocalPlayer player) {
        if (item == null || System.nanoTime() < drawEnd || state != State.IDLE || viewmodel.action() != null) return;
        if (player.isSprinting()) player.setSprinting(false);
        viewmodel.play(EquipmentViewmodel.Action.INSPECT, item.anims.inspect, 0);
    }

    private static void showArc(LocalPlayer player, EquipmentData.Equipment it) {
        if (arc != null && arc.isAlive()) arc.remove();
        Minecraft mc = Minecraft.getInstance();
        if (!(player.level() instanceof ClientLevel level)) return;
        String held = it.id;
        arc = new TrajectoryArc(level, player, it, lob, () -> holdingEquipment() && held.equals(itemId)
                && (state == State.PULLING || state == State.HOLDING));
        mc.particleEngine.add(arc);
    }

    // ---- sounds and effects ---------------------------------------------------------------------------

    /** A clip event of the held item: its sound for the thrower (others hear the server's). */
    private static void clipEvent(String name) {
        String sound = switch (name) {
            case "pin" -> "equipment.pin";
            case "spoon" -> "equipment.spoon";
            case "light" -> "equipment.molotov_light";
            case "release" -> item != null && EquipmentData.KNIFE.equals(item.kind) ? "equipment.knife_throw"
                    : lob ? "equipment.lob" : "equipment.throw";
            case "cap" -> "equipment.adrenaline_cap";
            case "inject" -> "equipment.adrenaline_inject";
            default -> "";
        };
        if (!sound.isEmpty()) playUi(BlockLand.MODID + ":" + sound, 0.97f + random.nextFloat() * 0.06f, 0.7f);
    }

    /** Without a model (no clip events) the moments of the hand still sound. */
    private static void cue(String event) {
        clipEvent(event);
        if ("release".equals(event) && item != null && item.cookable) clipEvent("spoon");
    }

    /** Something thrown, still flying or lying around: a lit molotov's rag burns and smokes. */
    private static void inFlight(ThrownEquipment thrown, EquipmentData.Equipment equipment) {
        if (!equipment.shatters() || !(thrown.level() instanceof ClientLevel level)) return;
        Vec3 p = thrown.position();
        if (random.nextFloat() < 0.8f) level.addParticle(ParticleTypes.SMALL_FLAME, p.x, p.y + 0.18, p.z, 0, 0.01, 0);
        if (random.nextFloat() < 0.3f) level.addParticle(ParticleTypes.SMOKE, p.x, p.y + 0.22, p.z, 0, 0.02, 0);
    }

    // ---- rendering ------------------------------------------------------------------------------------

    /** Replaces the vanilla first-person hands with the equipment viewmodel. */
    private static boolean renderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || item == null || !modelReady || !holdingEquipment()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return true;
        long start = System.nanoTime();
        int light = event.getPackedLight();
        viewmodel.draw(new Matrix4f(), player, LightTexture.block(light), LightTexture.sky(light));
        viewmodel.submit();
        renderMillis = renderMillis * 0.95 + (System.nanoTime() - start) / 1e6 * 0.05;
        return true;
    }

    private static void playUi(String sound, float pitch, float volume) {
        SoundEvent event = EquipmentContent.sound(sound);
        if (event != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }
}
