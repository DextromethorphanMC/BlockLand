package com.blockland.client.armor;

import com.blockland.BlockLand;
import com.blockland.armor.ArmorContent;
import com.blockland.armor.ArmorRules;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.render.RenderClock;
import com.blockland.client.viewmodel.SwimPose;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.network.ArmorPacketListeners;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.ArmorPackets;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix4f;

/**
 * Body armour on this client (asset spec §50): the carrier drawn on every player's body ({@link VestLayer}), the plate
 * held in first person ({@link PlateViewmodel}) and its insertion — a left-click with a plate in hand and a carrier on
 * starts it at once (the animation and the view's dip), the server's armour service checks it and puts the plate in
 * when its time is up, or says why not —, and the armour HUD ({@link ArmorHud}).
 */
public final class ClientArmor {
    private static final PlateViewmodel viewmodel = new PlateViewmodel();
    private static boolean equipped, modelReady;
    private static int slot = -1;
    /** The insertion under way: since when, for how long (nanos; 0 = none). */
    private static long insertStart, insertNanos;
    private static long lastFrame;

    private ClientArmor() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.AddLayers.getBus(modBus).addListener(event -> {
            for (PlayerSkin.Model skin : event.getSkins()) {
                PlayerRenderer renderer = event.getPlayerSkin(skin);
                if (renderer != null) renderer.addLayer(new VestLayer(renderer));
            }
        });
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    VestLayer.clear();
                    equipped = false;
                }));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("armor_hud"), ForgeLayeredDraw.CROSSHAIR, ArmorHud::render));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> clientTick());
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> frame());
        RenderHandEvent.BUS.addListener(ClientArmor::renderHand);
        // no vanilla hit, mining or use with a plate in hand: the left-click inserts it
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(event -> {
            if (Minecraft.getInstance().player == null || !holdingPlate() || event.isPickBlock()) return false;
            event.setSwingHand(false);
            return true;
        });
        WeaponInput.onAttackPressed(ClientArmor::attackPressed);
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!holdingPlate() || !modelReady || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON) return;
            // the clip's camera, and the head bowing to see the plate into the carrier
            event.setPitch(event.getPitch() + viewmodel.cameraPitch() + dip());
            event.setYaw(event.getYaw() + viewmodel.cameraYaw());
            event.setRoll(event.getRoll() + viewmodel.cameraRoll());
            viewmodel.camera().moveCamera(event.getCamera());
        });
        BLNetwork.setArmorClientListener(new ArmorPacketListeners.Client() {
            @Override
            public void onInsert(ArmorPackets.S2CInsert insert) {
                answered(insert);
            }

            @Override
            public void onHit(ArmorPackets.S2CHit hit) {
                ArmorHud.hit(hit.soaked(), hit.broken());
            }
        });
    }

    static boolean holdingPlate() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && !player.isSpectator() && ArmorContent.isPlate(player.getMainHandItem());
    }

    /** Degrees the head bows now to watch the plate go in. */
    private static float dip() {
        return ArmorRules.get().insertDip * Mth.sin((float) Math.PI * insertProgress());
    }

    /** 0..1 through the insertion under way, 0 when there is none. */
    static float insertProgress() {
        if (insertNanos <= 0) return 0;
        return Mth.clamp((RenderClock.nanos() - insertStart) / (float) insertNanos, 0, 1);
    }

    private static void clientTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean holding = player != null && holdingPlate();
        int selected = player == null ? -1 : player.getInventory().getSelectedSlot();
        if (holding && (!equipped || selected != slot)) {
            equipped = true;
            slot = selected;
            insertNanos = 0;
            modelReady = viewmodel.equip();
            viewmodel.setEventSink(name -> playUi("blockland:armor." + name));
        } else if (!holding && equipped) {
            equipped = false;
            modelReady = false;
            insertNanos = 0;
        }
        // an insertion the server never answered (a lost packet) gives up after a while
        if (insertNanos > 0 && RenderClock.nanos() - insertStart > insertNanos * 3 + 2_000_000_000L) insertNanos = 0;
    }

    private static void frame() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        long now = RenderClock.nanos();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (player == null || !equipped || !modelReady) return;
        ViewmodelRenderer.suppressViewBobbing(player);
        viewmodel.update(dt, player, SwimPose.running(player));
    }

    private static void attackPressed() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || !holdingPlate() || insertNanos > 0) return;
        if (viewmodel.action() == PlateViewmodel.Action.DRAW) return;
        ArmorRules rules = ArmorRules.get();
        if (ArmorContent.wornVest(player).isEmpty()) {
            player.displayClientMessage(Component.translatable("blockland.armor.no_vest"), true);
            return;
        }
        if (rules.placeFor(ArmorContent.stateOf(ArmorContent.wornVest(player))) == ArmorRules.NO_ROOM) {
            player.displayClientMessage(Component.translatable("blockland.armor.full"), true);
            return;
        }
        // at once: the server's answer only confirms it (or takes it back)
        startInsert(rules.insertSeconds);
        BLNetwork.sendToServer(new ArmorPackets.C2SInsert(player.getInventory().getSelectedSlot()));
    }

    private static void startInsert(float seconds) {
        insertStart = RenderClock.nanos();
        insertNanos = (long) (Math.max(0.1f, seconds) * 1e9);
        viewmodel.insert(seconds);
    }

    private static void answered(ArmorPackets.S2CInsert insert) {
        switch (insert.result()) {
            case STARTED -> {
                if (insertNanos <= 0) startInsert(insert.seconds());
            }
            case DONE -> {
                insertNanos = 0;
                // the next plate comes up from the pouch (the last one gone, the hand empties and this ends)
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null && player.getMainHandItem().getCount() > 0 && holdingPlate()) viewmodel.draw();
                ArmorHud.inserted();
            }
            case NO_VEST, FULL, CANCELLED -> {
                insertNanos = 0;
                viewmodel.stopAction();
            }
        }
    }

    private static boolean renderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !equipped || !modelReady || !holdingPlate() || !viewmodel.isReady()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return true; // the rig owns both arms
        int light = event.getPackedLight();
        viewmodel.draw(new Matrix4f(), player, LightTexture.block(light), LightTexture.sky(light), dip());
        viewmodel.submit();
        return true;
    }

    private static void playUi(String id) {
        LocalPlayer player = Minecraft.getInstance().player;
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (player == null || location == null) return;
        player.level().playLocalSound(player.getX(), player.getEyeY() - 0.4, player.getZ(),
                com.blockland.common.Sounds.byId(location), SoundSource.PLAYERS, 0.7f, 0.97f + player.getRandom().nextFloat() * 0.06f, false);
    }
}
