package com.blockland.mixin.anticheat;

import com.blockland.server.anticheat.net.NetHooks;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * The anti-cheat's acknowledgements and the velocities it follows (anti-cheat spec §5): pongs to its pings (vanilla
 * ignores pongs) and what the server sends a player in the world. Forwards only, for game listeners only.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {
    @Inject(method = "handlePong", at = @At("HEAD"))
    private void blockland$pong(ServerboundPongPacket packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && game.player != null) NetHooks.pong(game.player, packet.getId());
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
    private void blockland$send(Packet<?> packet, @Nullable ChannelFutureListener listener, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && game.player != null) NetHooks.sending(game.player, packet);
    }
}
