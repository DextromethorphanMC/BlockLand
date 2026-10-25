package com.blockland.client.supply;

import com.blockland.client.gun.ClientGuns;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.viewmodel.WeaponOverlays;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.SupplyPackets;
import com.blockland.supply.SupplyContent;
import com.blockland.supply.SupplyRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

/**
 * Ammo crates on this client (asset spec §46): the crate's renderer, the use of a crate with a gun in hand (a held gun
 * keeps vanilla's block interaction to itself, so the use goes to the server's supply service as a packet), and the
 * first-person resupply when the server answers. Rules: {@link SupplyRules}; motion: {@link ResupplyMotion}.
 */
public final class ClientSupply {
    private static final ResupplyOverlay overlay = new ResupplyOverlay();
    /** No new request this soon after the last one (the server's cooldown decides; this only spares packets). */
    private static final long REQUEST_NANOS = 400_000_000L;
    private static long lastRequest;

    private ClientSupply() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event ->
                event.registerBlockEntityRenderer(SupplyContent.AMMO_CRATE_ENTITY.get(), AmmoCrateRenderer::new));
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> ResupplyMotion.reset()));
        WeaponOverlays.register(overlay);
        WeaponInput.onUsePressed(ClientSupply::usePressed);
        BLNetwork.setSupplyClientListener(msg -> overlay.start(msg.slot(), msg.reload(), msg.pos()));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> overlay.tick());
    }

    /** The ammo crate under the crosshair within reach while a gun is held, or null. */
    @Nullable
    static BlockPos crateInReach() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !ClientGuns.holdingGun()) return null;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        if (!mc.level.getBlockState(pos).is(SupplyContent.AMMO_CRATE.get())) return null;
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= SupplyRules.get().reach ? pos : null;
    }

    private static void usePressed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || overlay.active() || ClientGuns.isReloading()) return;
        BlockPos pos = crateInReach();
        long now = System.nanoTime();
        if (pos == null || now - lastRequest < REQUEST_NANOS) return;
        lastRequest = now;
        BLNetwork.sendToServer(new SupplyPackets.C2SUse(pos, mc.player.getInventory().getSelectedSlot()));
    }
}
