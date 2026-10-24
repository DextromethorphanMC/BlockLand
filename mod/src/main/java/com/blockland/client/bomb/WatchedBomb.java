package com.blockland.client.bomb;

import com.blockland.bomb.BombCatalog;
import com.blockland.bomb.BombItem;
import com.blockland.client.viewmodel.ViewSubject;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.event.RenderHandEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * The bomb in the hands of the player a spectator watches through their eyes ({@link ViewSubject}): its own
 * {@link BombViewmodel}, drawn and moved by their movement. Nothing the spectator presses reaches it.
 */
final class WatchedBomb {
    private static final BombViewmodel viewmodel = new BombViewmodel();

    @Nullable
    private static AbstractClientPlayer player;
    private static int slot = -1;
    private static boolean ready;

    private WatchedBomb() {
    }

    static boolean active() {
        return player != null && ready && ViewSubject.watched() == player;
    }

    static void tick() {
        AbstractClientPlayer watched = ViewSubject.watched();
        if (watched == null || !(watched.getMainHandItem().getItem() instanceof BombItem)) {
            player = null;
            ready = false;
            return;
        }
        int selected = watched.getInventory().getSelectedSlot();
        if (watched == player && selected == slot) return;
        player = watched;
        slot = selected;
        ready = viewmodel.equip(BombCatalog.get());
        viewmodel.setEventSink(name -> {
        });
    }

    static void frame(float dt) {
        if (!active()) return;
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
