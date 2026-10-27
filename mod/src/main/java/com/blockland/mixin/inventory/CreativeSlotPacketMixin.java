package com.blockland.mixin.inventory;

import com.blockland.backpack.BackpackMenus;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creative players set slots by number, and vanilla takes only the inventory menu's own 1..45: the backpack slot and its
 * storage (UI spec §2) are set through {@link BackpackMenus#creativeSet}, on the server thread.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CreativeSlotPacketMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void blockland$creativeBackpack(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (BackpackMenus.creativeSet(player, packet.slotNum(), packet.itemStack())) ci.cancel();
    }
}
