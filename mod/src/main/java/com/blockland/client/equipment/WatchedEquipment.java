package com.blockland.client.equipment;

import com.blockland.client.viewmodel.ViewSubject;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.equipment.EquipmentContent;
import com.blockland.equipment.EquipmentData;
import com.blockland.network.packet.ViewmodelPackets;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.event.RenderHandEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * The equipment of the player a spectator watches through their eyes ({@link ViewSubject}): its own
 * {@link EquipmentViewmodel}, equipped from their held grenade / molotov / knife / injector with its build, moved by their
 * movement, and playing the hand the server reports — the pin pulled and the grenade held cocked, the throw or the lob,
 * a knife thrown, the injector used, a cancel. Nothing the spectator presses reaches it.
 */
final class WatchedEquipment {
    private static final EquipmentViewmodel viewmodel = new EquipmentViewmodel();

    @Nullable
    private static AbstractClientPlayer player;
    private static String itemId = "";
    private static int slot = -1;
    @Nullable
    private static EquipmentData.Equipment item;
    private static boolean ready;
    /** The cocked hold starts here after the pull (0 = none); the release / injection lands at {@link #releaseAt}. */
    private static long holdAt;
    private static long releaseAt;
    private static boolean lob;
    private static boolean injecting;

    private WatchedEquipment() {
    }

    static void init() {
        ViewSubject.onAction((watched, kind, a, b) -> {
            if (kind == ViewmodelPackets.Kind.EQUIPMENT && watched == player && active()) action(a, b == 1);
        });
    }

    static boolean active() {
        return player != null && ready && item != null && ViewSubject.watched() == player;
    }

    static void tick() {
        AbstractClientPlayer watched = ViewSubject.watched();
        EquipmentData.Equipment held = watched == null ? null : EquipmentContent.equipmentOf(watched.getMainHandItem()).orElse(null);
        if (held == null) {
            player = null;
            itemId = "";
            item = null;
            ready = false;
            holdAt = releaseAt = 0;
            return;
        }
        int selected = watched.getInventory().getSelectedSlot();
        if (watched != player || !held.id.equals(itemId) || selected != slot) {
            player = watched;
            itemId = held.id;
            slot = selected;
            item = held;
            holdAt = releaseAt = 0;
            ready = viewmodel.equip(held);
            viewmodel.setEventSink(name -> {
            });
        }
        viewmodel.setBuild(EquipmentContent.buildOf(watched.getMainHandItem()));
    }

    /** {@code a}: 0 pressed, 1 released, 2 put away (see the packet). */
    private static void action(int a, boolean underhand) {
        EquipmentData.Equipment it = item;
        long now = System.nanoTime();
        switch (a) {
            case 0 -> {
                lob = underhand;
                if (it.held()) {
                    viewmodel.play(EquipmentViewmodel.Action.PULL, it.anims.pull, it.pullTime);
                    holdAt = now + (long) (it.pullTime * 1e9);
                    releaseAt = 0;
                } else if (it.thrown()) {
                    viewmodel.play(EquipmentViewmodel.Action.THROW, it.anims.throwClip, 0);
                    releaseAt = now + (long) (it.throwing.releaseAt * 1e9);
                    injecting = false;
                } else {
                    viewmodel.play(EquipmentViewmodel.Action.USE, it.anims.use, it.useTime);
                    releaseAt = now + (long) (it.injectAt * 1e9);
                    injecting = true;
                }
            }
            case 1 -> {
                holdAt = 0;
                String clip = lob && viewmodel.hasClip(it.anims.lob) ? it.anims.lob : it.anims.throwClip;
                viewmodel.play(EquipmentViewmodel.Action.THROW, clip, 0);
                releaseAt = now + (long) (it.throwing.releaseAt * 1e9);
                injecting = false;
            }
            default -> {
                holdAt = releaseAt = 0;
                viewmodel.stopAction();
            }
        }
    }

    static void frame(float dt) {
        if (!active()) return;
        long now = System.nanoTime();
        EquipmentData.Equipment it = item;
        if (holdAt != 0 && now >= holdAt && viewmodel.action() != EquipmentViewmodel.Action.THROW) {
            // cocked: the hold clip loops until the release comes
            if (viewmodel.action() == null || viewmodel.action() == EquipmentViewmodel.Action.PULL) {
                viewmodel.play(EquipmentViewmodel.Action.HOLD, it.anims.hold, 0);
            }
        }
        if (releaseAt != 0 && now >= releaseAt) {
            releaseAt = 0;
            if (injecting) viewmodel.injected();
            else viewmodel.released(lob, it.handling.weight);
        }
        ViewmodelRenderer.suppressViewBobbing(player);
        viewmodel.update(dt, player, player.isSprinting());
    }

    static float cameraPitch() {
        return active() ? viewmodel.cameraPitch() : 0;
    }

    static float cameraYaw() {
        return active() ? viewmodel.cameraYaw() : 0;
    }

    static float cameraRoll() {
        return active() ? viewmodel.cameraRoll() : 0;
    }

    static void moveCamera(Camera camera) {
        if (active()) viewmodel.camera().moveCamera(camera);
    }

    static boolean renderHand(RenderHandEvent event) {
        if (!active()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return true;
        int light = event.getPackedLight();
        viewmodel.draw(new Matrix4f(), player, LightTexture.block(light), LightTexture.sky(light));
        viewmodel.submit();
        return true;
    }
}
