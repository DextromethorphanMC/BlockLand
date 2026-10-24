package com.blockland.client.melee;

import com.blockland.client.viewmodel.ViewSubject;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.melee.MeleeContent;
import com.blockland.melee.MeleeData;
import com.blockland.network.packet.ViewmodelPackets;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.event.RenderHandEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * The knife of the player a spectator watches through their eyes ({@link ViewSubject}): its own
 * {@link KnifeViewmodel}, equipped from their held knife with its build, moved by their movement, swinging when the server
 * reports their attacks (the light combo step or the heavy blow) and jolting when the blade connects (the hit every
 * tracker gets). Nothing the spectator presses reaches it.
 */
final class WatchedKnife {
    private static final KnifeViewmodel viewmodel = new KnifeViewmodel();

    @Nullable
    private static AbstractClientPlayer player;
    private static String knifeId = "";
    private static int slot = -1;
    @Nullable
    private static MeleeData.Knife knife;
    private static boolean ready;

    private WatchedKnife() {
    }

    static void init() {
        ViewSubject.onAction((watched, kind, a, b) -> {
            if (kind != ViewmodelPackets.Kind.MELEE || watched != player || !active()) return;
            boolean heavy = a == 1;
            MeleeData.Attack attack = knife.attack(heavy);
            viewmodel.play(heavy ? KnifeViewmodel.Action.HEAVY : KnifeViewmodel.Action.LIGHT, attack.swing(b).clip, attack.duration);
        });
    }

    static boolean active() {
        return player != null && ready && knife != null && ViewSubject.watched() == player;
    }

    /** Client tick: who is watched and what knife (and build) they hold. */
    static void tick() {
        AbstractClientPlayer watched = ViewSubject.watched();
        MeleeData.Knife held = watched == null ? null : MeleeContent.knifeOf(watched.getMainHandItem()).orElse(null);
        if (held == null) {
            player = null;
            knifeId = "";
            knife = null;
            ready = false;
            return;
        }
        int selected = watched.getInventory().getSelectedSlot();
        if (watched != player || !held.id.equals(knifeId) || selected != slot) {
            player = watched;
            knifeId = held.id;
            slot = selected;
            knife = held;
            ready = viewmodel.equip(held);
            viewmodel.setEventSink(name -> ClientKnives.playWatched("blockland:knife." + name));
        }
        viewmodel.setBuild(MeleeContent.buildOf(watched.getMainHandItem()));
    }

    static void frame(float dt) {
        if (!active()) return;
        ViewmodelRenderer.suppressViewBobbing(player);
        viewmodel.update(dt, player, player.isSprinting());
    }

    /** The watched player's blade connected (the hit every tracker gets): the knife on screen jolts with it. */
    static void impact(int attackerId, boolean heavy, int combo, boolean block) {
        if (!active() || player.getId() != attackerId) return;
        MeleeData.Attack attack = knife.attack(heavy);
        float strength = block ? (heavy ? 1.9f : 1.35f) : (heavy ? 1.5f : 1f);
        viewmodel.impact(strength, attack.punch, attack.swing(combo).slashAngle);
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
