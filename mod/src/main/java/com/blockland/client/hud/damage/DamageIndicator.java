package com.blockland.client.hud.damage;

import com.blockland.BlockLand;
import com.blockland.client.hud.layout.HudLayout;
import com.blockland.client.render.RenderClock;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.DamagePackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The HUD's damage direction arrows (UI spec §3.5): the server tells the player where each hurt that really took
 * something off them came from ({@link DamagePackets.S2CDamage}); each attacker (or blast point) gets one arrow round
 * the crosshair that every further hit pops again, shown by the movable element {@link DamageDirectionElement}. Look and
 * timing are data ({@code assets/blockland/hud/damage_indicator.json}).
 */
public final class DamageIndicator {
    private static final List<DamageArrow> arrows = new ArrayList<>();
    private static DamageIndicatorData data = new DamageIndicatorData().resolve();
    private static LocalPlayer seen;

    private DamageIndicator() {
    }

    public static void init(BusGroup modBus) {
        HudLayout.register(new DamageDirectionElement());
        BLNetwork.setDamageClientListener(DamageIndicator::onDamage);
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> data = DamageIndicatorData.load(manager)));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> arrows.clear());
    }

    static DamageIndicatorData data() {
        return data;
    }

    static List<DamageArrow> arrows() {
        return arrows;
    }

    private static void onDamage(DamagePackets.S2CDamage msg) {
        BlockLand.LOGGER.debug("[BlockLand] Damage arrows: hit for {} health, {} plates from ({}, {}, {}), attacker {}",
                msg.health(), msg.plates(), msg.x(), msg.y(), msg.z(), msg.attacker());
        if (Minecraft.getInstance().player == null) return;
        long now = RenderClock.nanos();
        Vec3 at = new Vec3(msg.x(), msg.y(), msg.z());
        DamageArrow arrow = null;
        for (DamageArrow a : arrows) {
            boolean same = msg.attacker() != 0 ? a.attacker == msg.attacker()
                    : a.attacker == 0 && a.at.distanceTo(at) <= data.mergeDistance;
            if (same) {
                arrow = a;
                break;
            }
        }
        if (arrow == null) {
            if (arrows.size() >= Math.max(1, data.maxArrows)) arrows.remove(arrows.stream().min(Comparator.comparingLong(a -> a.last)).orElseThrow());
            arrow = new DamageArrow(msg.attacker(), at);
            arrows.add(arrow);
        }
        arrow.hit(now, at, msg.health(), msg.plates(), data);
    }

    /** Arrows that have faded go; a new player (respawned, another world) or a dead one starts with none. */
    private static void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != seen || player != null && (!player.isAlive() || player.isSpectator())) arrows.clear();
        seen = player;
        if (arrows.isEmpty()) return;
        long now = RenderClock.nanos();
        float gone = data.hold + data.fade;
        arrows.removeIf(a -> a.age(now) > gone);
    }
}
